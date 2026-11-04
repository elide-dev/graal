#!/usr/bin/env python3
"""Artifact checks with real instrumented and ordinary objects; requires ELIDE_TOOLCHAIN_HOME."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location("sanitizer_natives", Path(__file__).with_name("sanitizer-natives.py"))
NATIVES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NATIVES)


class SanitizerArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.toolchain = Path(os.environ["ELIDE_TOOLCHAIN_HOME"])
        self.source = self.root / "fixture.c"
        self.source.write_text("void store(int *p, int v) { *p = v; }\n")
        self.obj = self.root / "fixture.o"
        self.shared = self.root / "libfixture.so"
        cc = self.toolchain / "bin/x86_64-unknown-linux-gnu-asan-clang"
        subprocess.run([str(cc), "-c", "-g", "-O1", "-fPIC", "-flto=thin", "-ffat-lto-objects",
                        str(self.source), "-o", str(self.obj)], check=True, capture_output=True)
        subprocess.run([str(cc), "-shared", "-fPIC", "-shared-libsan", str(self.source),
                        "-o", str(self.shared)], check=True, capture_output=True)
        self.svm = self.root / "svm.tar"
        with tarfile.open(self.svm, "w") as archive:
            for name in NATIVES.SVM_LIBRARIES:
                lib = self.root / name
                subprocess.run([str(self.toolchain / "bin/llvm-ar"), "rcs", str(lib), str(self.obj)], check=True)
                archive.add(lib, arcname=f"linux-amd64/glibc/{name}")
        self.jars = []
        for name in NATIVES.JARS:
            jar = self.root / f"{name}.jar"
            with zipfile.ZipFile(jar, "w") as resources:
                resources.writestr("Fixture.class", b"java bytecode retained")
                resources.writestr("META-INF/resources/test/linux/amd64/sha256", b"resource metadata retained")
                resources.write(self.shared, "META-INF/resources/test/linux/amd64/libfixture.so")
            self.jars.append(jar)

    def assemble(self, version="25.5.5-elide.25"):
        NATIVES.assemble(version, "asan", self.toolchain, self.root / "out", self.svm, self.jars)

    def test_preserves_jars_and_records_hashes(self):
        for version in ("25.5.5-elide.25", "25.6.0-dev"):
            with self.subTest(version=version):
                self.assemble(version)
                root = self.root / f"out/graalvm-natives-{version}-linux-amd64-sanitizer-asan"
                manifest = json.loads((root / "manifest.json").read_text())
                self.assertEqual(manifest["version"], version)
                self.assertEqual(manifest["native_libraries"], dict.fromkeys(NATIVES.JARS, 1))
                for name, jar in zip(NATIVES.JARS, self.jars):
                    relative = f"jars/{name}.jar"
                    self.assertEqual((root / relative).read_bytes(), jar.read_bytes())
                    self.assertEqual(manifest["files"][relative], NATIVES.hashlib.sha256(jar.read_bytes()).hexdigest())

    def test_rejects_uninstrumented_resource(self):
        ordinary = self.root / "ordinary.so"
        subprocess.run([str(self.toolchain / "bin/x86_64-unknown-linux-gnu-clang"), "-shared", "-fPIC",
                        str(self.source), "-o", str(ordinary)], check=True)
        with zipfile.ZipFile(self.jars[0], "w") as resources:
            resources.write(ordinary, "META-INF/resources/test/linux/amd64/ordinary.so")
        with self.assertRaisesRegex(ValueError, "missing asan instrumentation"):
            self.assemble()

    def test_rejects_missing_archive(self):
        with tarfile.open(self.svm, "w") as archive:
            archive.add(self.root / "libjvm.a", arcname="linux-amd64/glibc/libjvm.a")
        with self.assertRaisesRegex(ValueError, "Expected"):
            self.assemble()

    def test_rejects_archive_without_bitcode(self):
        subprocess.run([str(self.toolchain / "bin/x86_64-unknown-linux-gnu-asan-clang"), "-c", "-fPIC",
                        str(self.source), "-o", str(self.obj)], check=True)
        lib = self.root / "ordinary.a"
        subprocess.run([str(self.toolchain / "bin/llvm-ar"), "rcs", str(lib), str(self.obj)], check=True)
        with self.assertRaisesRegex(ValueError, "expected machine code and ThinLTO bitcode"):
            NATIVES.inspect_library(lib, "asan", self.toolchain)


if __name__ == "__main__":
    unittest.main()
