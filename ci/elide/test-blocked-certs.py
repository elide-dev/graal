#!/usr/bin/env python3
"""Tests install-blocked-certs.py on made-up Java homes: python3 ci/elide/test-blocked-certs.py.

check-blocked-certs.sh checks what the installed list does in a real distribution.
"""
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
INSTALL = HERE / "install-blocked-certs.py"
JMOD_MAGIC = b"JM\x01\x00"


def fingerprints(text):
    return {line for line in text.splitlines() if line and not line.startswith(("#", "Algorithm="))}


LIST = fingerprints((HERE / "blocked.certs").read_text(encoding="utf-8"))
# Base: the list without the CVE-2026-91403 entries (e.g. the first 10), plus one the list lacks.
BASE_ONLY = "00" * 32
BASE = sorted(LIST)[:10] + [BASE_ONLY]


def blocked_certs(entries, newline="\n", algorithm="SHA-256"):
    return newline.join([f"Algorithm={algorithm}", *sorted(entries)]).encode() + newline.encode()


def jmod(entries):
    out = io.BytesIO()
    out.write(JMOD_MAGIC)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return out.getvalue()


def jmod_entries(path):
    data = path.read_bytes()
    assert data.startswith(JMOD_MAGIC), "lost the jmod header"
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {name: z.read(name) for name in z.namelist()}


class InstallBlockedCertsTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix="blocked certs ")
        self.home = Path(self.work.name) / "Contents" / "Home"
        self.file = self.home / "lib" / "security" / "blocked.certs"
        self.file.parent.mkdir(parents=True)
        self.file.write_bytes(blocked_certs(BASE))
        self.file.chmod(0o644)
        self.jmod = self.home / "jmods" / "java.base.jmod"
        self.jmod.parent.mkdir()
        self.jmod_others = {"classes/module-info.class": b"\xca\xfe\xba\xbe", "bin/keytool": b"#!/bin/sh\n"}
        self.jmod.write_bytes(jmod({**self.jmod_others, "lib/security/blocked.certs": blocked_certs(BASE)}))

    def tearDown(self):
        self.work.cleanup()

    def install(self, check=True):
        return subprocess.run([sys.executable, "-I", str(INSTALL), str(self.home)], check=check,
                              capture_output=True, text=True)

    def test_merges_and_is_idempotent(self):
        self.install()
        expected = blocked_certs(LIST | {BASE_ONLY})
        self.assertEqual(expected, self.file.read_bytes())
        self.assertEqual(0o644, self.file.stat().st_mode & 0o777)
        entries = jmod_entries(self.jmod)
        self.assertEqual(expected, entries.pop("lib/security/blocked.certs"))
        self.assertEqual(self.jmod_others, entries)
        installed, packaged = self.file.read_bytes(), self.jmod.read_bytes()
        result = self.install()
        self.assertIn("added 0 fingerprints", result.stdout)
        self.assertIn("up to date", result.stdout)
        self.assertEqual(installed, self.file.read_bytes())
        self.assertEqual(packaged, self.jmod.read_bytes())

    def test_matches_a_fixed_jdk(self):
        # Installed into a base that has none of the entries the fix adds, the list is the fixed JDK's.
        self.file.write_bytes(blocked_certs(LIST))
        self.jmod.unlink()
        self.install()
        content = (HERE / "blocked.certs").read_bytes()
        stripped = b"".join(line for line in content.splitlines(keepends=True) if not line.startswith(b"#"))
        self.assertEqual(stripped, self.file.read_bytes())

    def test_keeps_line_separators(self):
        self.file.write_bytes(blocked_certs(BASE, newline="\r\n"))
        self.install()
        self.assertEqual(blocked_certs(LIST | {BASE_ONLY}, newline="\r\n"), self.file.read_bytes())

    def test_replaces_hard_links(self):
        other = Path(self.work.name) / "other-jdk-blocked.certs"
        os.link(self.file, other)
        before = other.read_bytes()
        self.install()
        self.assertEqual(before, other.read_bytes())

    def test_without_jmods(self):
        self.jmod.unlink()
        self.assertIn("jmods/java.base.jmod: none", self.install().stdout)

    def test_fails(self):
        cases = {
            "missing file": lambda: self.file.unlink(),
            "other algorithm": lambda: self.file.write_bytes(blocked_certs(BASE, algorithm="SHA-1")),
            "not a fingerprint": lambda: self.file.write_bytes(blocked_certs(BASE + ["not a fingerprint"])),
            "empty": lambda: self.file.write_bytes(b""),
            "jmod without the file": lambda: self.jmod.write_bytes(jmod(self.jmod_others)),
            "not a jmod": lambda: self.jmod.write_bytes(b"PK\x03\x04"),
        }
        for name, break_home in cases.items():
            with self.subTest(name):
                self.tearDown()
                self.setUp()
                break_home()
                result = self.install(check=False)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn("::error::", result.stderr)


if __name__ == "__main__":
    unittest.main()
