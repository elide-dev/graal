#!/usr/bin/env python3
"""Collects and merges the platform-specific native resources of Elide's GraalVM Maven jars.

Truffle internal resources keep each platform's files, and their `files`/`sha256` metadata, under
`<prefix><os>/<arch>/` in a jar. Each platform's build only has its own, so the Maven jars, which
the linux-amd64 build deploys, lack the others. Each platform job collects its subtrees; the
publish job merges all of them into the Maven jars.

  natives.py collect <platform> <out-dir> <artifact>=<jar> ...
  natives.py merge <maven-repo> <version> <natives-dir> ...
"""
import hashlib
import os
import sys
import zipfile

# Maven artifact -> (group path, resource prefix holding <os>/<arch>/ subtrees)
ARTIFACTS = {
    "truffle-api": ("org/graalvm/truffle", "META-INF/resources/engine/libtruffleattach/"),
    "truffle-nfi-libffi": ("org/graalvm/truffle", "META-INF/resources/nfi-native/libnfi/"),
    "python-resources": ("org/graalvm/python", "META-INF/resources/"),
}
PLATFORMS = {
    "linux-amd64": "linux/amd64",
    "linux-aarch64": "linux/aarch64",
    "macos-aarch64": "darwin/aarch64",
    "windows-amd64": "windows/amd64",
}


def subtree(prefix, platform):
    return prefix + PLATFORMS[platform] + "/"


def collect(platform, out, jars):
    for spec in jars:
        artifact, jar = spec.split("=", 1)
        prefix = subtree(ARTIFACTS[artifact][1], platform)
        n = 0
        with zipfile.ZipFile(jar) as z:
            for info in z.infolist():
                if info.filename.startswith(prefix) and not info.is_dir():
                    dest = os.path.join(out, artifact, info.filename)
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(dest, "wb") as f:
                        f.write(z.read(info))
                    n += 1
        if n == 0:
            sys.exit(f"{jar}: no {prefix} entries")
        print(f"{artifact} {platform}: {n} files")


def rehash(path):
    data = open(path, "rb").read()
    for algo in ("md5", "sha1"):
        with open(f"{path}.{algo}", "w") as f:
            f.write(hashlib.new(algo, data).hexdigest())


def merge(repo, version, natives_dirs):
    for artifact, (group, base) in ARTIFACTS.items():
        jar = os.path.join(repo, group, artifact, version, f"{artifact}-{version}.jar")
        # Collected files keep their jar entry names, relative to <natives-dir>/<artifact>.
        add = {}
        for d in natives_dirs:
            root = os.path.join(d, artifact)
            for dirpath, _, files in os.walk(root):
                for name in files:
                    full = os.path.join(dirpath, name)
                    add[os.path.relpath(full, root).replace(os.sep, "/")] = full
        prefixes = {subtree(base, p) for p in PLATFORMS if any(e.startswith(subtree(base, p)) for e in add)}
        tmp = jar + ".tmp"
        with zipfile.ZipFile(jar) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                if not any(info.filename.startswith(p) for p in prefixes):
                    zout.writestr(info, zin.read(info))
            for entry in sorted(add):
                zout.write(add[entry], entry)
        os.replace(tmp, jar)
        rehash(jar)
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
        counts = {p: sum(1 for x in names if x.startswith(subtree(base, p))) for p in PLATFORMS}
        print(f"{artifact}: " + ", ".join(f"{p}={c}" for p, c in counts.items()))
        missing = [p for p, c in counts.items() if c == 0]
        if missing:
            sys.exit(f"{artifact}: no natives for {', '.join(missing)}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "collect" and len(sys.argv) >= 5:
        collect(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif cmd == "merge" and len(sys.argv) >= 5:
        merge(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        sys.exit(__doc__)
