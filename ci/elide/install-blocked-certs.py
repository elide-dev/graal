#!/usr/bin/env python3
"""Installs the blocked-certificate list with the fix for CVE-2026-91403 into a Java home.

  install-blocked-certs.py <java-home>

The bases of the CE and EE distributions (LabsJDK and Oracle GraalVM EA, both JDK 25.0.4.1.1) trust
the eMudhra intermediate CA certificates that CVE-2026-91403 is about: their lib/security/blocked.certs
predates the fix. ci/elide/blocked.certs is the list of a JDK with the fix. This merges it into the
home's lib/security/blocked.certs, and into the copy in jmods/java.base.jmod (what jlink puts in the
runtimes it makes) if the home has one. Entries the home has that the list lacks are kept. Running it
again changes nothing. Fails if the home has no blocked.certs, or one this does not understand: the
JDK silently trusts every certificate if the file is missing or unreadable.

The image builder reads blocked.certs at build time (Target_sun_security_ssl_TrustStoreManager), so
native images built with the home get the list as well; images built before do not.
"""
import copy
import io
import os
import re
import shutil
import sys
import tempfile
import zipfile

LIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "blocked.certs")
ENTRY = "lib/security/blocked.certs"
JMOD = os.path.join("jmods", "java.base.jmod")
JMOD_ENTRY = ENTRY  # in the jmod's "lib" section, which jlink copies to the runtime's lib
JMOD_MAGIC = b"JM\x01\x00"
ALGORITHM = "SHA-256"


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def parse(text, source):
    """The fingerprints of a blocked.certs file (java.util.Properties: Algorithm=..., then one
    fingerprint per line)."""
    algorithm, fingerprints = None, set()
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if line.startswith("Algorithm="):
            algorithm = line.partition("=")[2]
        elif re.fullmatch(r"[0-9A-F]{64}", line):
            fingerprints.add(line)
        else:
            fail(f"{source}:{number}: not a {ALGORITHM} fingerprint: {line!r}")
    if algorithm != ALGORITHM:
        fail(f"{source}: fingerprint algorithm {algorithm}, expected {ALGORITHM}")
    if not fingerprints:
        fail(f"{source}: no fingerprints")
    return fingerprints


def merged(current, source, blocked):
    """The content of a blocked.certs file with the list merged in, in the JDK's format (sorted, as
    BlockedCertsConverter writes it) and with the file's line separators."""
    ours = parse(current.decode("utf-8"), source)
    kept = ours - blocked
    if kept:
        print(f"{source}: keeping {len(kept)} fingerprints that ci/elide/blocked.certs does not have")
    newline = "\r\n" if b"\r\n" in current else "\n"
    lines = [f"Algorithm={ALGORITHM}", *sorted(ours | blocked)]
    return newline.join(lines).encode("utf-8") + newline.encode("utf-8"), len(blocked - ours)


def replace(path, write):
    """Replaces a file through a temporary file in the same directory, keeping its mode. A new file,
    not the old one rewritten: the old one may be a hard link to another JDK's."""
    fd, temporary = tempfile.mkstemp(prefix=f".{os.path.basename(path)}-", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "wb") as out:
            write(out)
        shutil.copymode(path, temporary)
        os.replace(temporary, path)
    except BaseException:
        os.remove(temporary)
        raise


def install_file(home, blocked):
    path = os.path.join(home, *ENTRY.split("/"))
    if not os.path.isfile(path):
        fail(f"{path} does not exist: is {home} a Java home?")
    with open(path, "rb") as f:
        current = f.read()
    content, added = merged(current, path, blocked)
    if content != current:
        replace(path, lambda out: out.write(content))
    with open(path, "rb") as f:
        missing = blocked - parse(f.read().decode("utf-8"), path)
    if missing:
        fail(f"{path}: {len(missing)} fingerprints missing after the update")
    print(f"{ENTRY}: added {added} fingerprints, {len(content.splitlines()) - 1} in all")


def install_jmod(home, blocked):
    path = os.path.join(home, JMOD)
    if not os.path.isfile(path):
        print(f"{JMOD}: none, nothing to do")
        return
    with open(path, "rb") as f:
        data = f.read()
    if not data.startswith(JMOD_MAGIC):
        fail(f"{path}: not a jmod file")
    # A jmod is a header followed by a zip file whose offsets start after the header.
    with zipfile.ZipFile(io.BytesIO(data)) as jmod:
        if JMOD_ENTRY not in jmod.namelist():
            fail(f"{path}: no {JMOD_ENTRY}")
        content, added = merged(jmod.read(JMOD_ENTRY), f"{path}!{JMOD_ENTRY}", blocked)
        if content == jmod.read(JMOD_ENTRY):
            print(f"{JMOD}: {JMOD_ENTRY} up to date")
            return

        def write(out):
            out.write(JMOD_MAGIC)
            with zipfile.ZipFile(out, "w") as updated:
                for info in jmod.infolist():
                    # writestr updates the entry it is given: give it a copy.
                    updated.writestr(copy.copy(info), content if info.filename == JMOD_ENTRY else jmod.read(info))
                # zipfile gives entries without attributes rw------- ones; the jmod tool writes none.
                for written, info in zip(updated.filelist, jmod.infolist()):
                    written.external_attr = info.external_attr
        replace(path, write)
    with zipfile.ZipFile(path) as jmod:
        if jmod.testzip() is not None or blocked - parse(jmod.read(JMOD_ENTRY).decode("utf-8"), path):
            fail(f"{path}: the update did not take")
    print(f"{JMOD}: added {added} fingerprints to {JMOD_ENTRY}")


def main():
    if len(sys.argv) != 2:
        fail(__doc__)
    home = os.path.abspath(sys.argv[1])
    with open(LIST, "rb") as f:
        blocked = parse(f.read().decode("utf-8"), LIST)
    print(f"CVE-2026-91403: installing {len(blocked)} blocked-certificate fingerprints into {home}")
    install_file(home, blocked)
    install_jmod(home, blocked)


if __name__ == "__main__":
    main()
