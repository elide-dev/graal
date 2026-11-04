#!/usr/bin/env python3
"""Assemble and inspect the Linux amd64 sanitizer native-library add-on."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile


SVM_LIBRARIES = {"libjvm.a", "libsvm_container.a", "liblibchelper.a", "libzstd.a"}
JARS = ("truffle-api", "truffle-nfi-libffi", "python-resources")
PREFIXES = {"asan": "__asan_", "tsan": "__tsan_", "msan": "__msan_"}


def run(toolchain, tool, *args):
    return subprocess.check_output([str(toolchain / "bin" / tool), *map(str, args)], text=True)


def inspect_library(path, sanitizer, toolchain):
    args = ["--undefined-only"]
    if path.suffix != ".a":
        args.append("--dynamic")
    symbols = run(toolchain, "llvm-nm", *args, path)
    if PREFIXES[sanitizer] not in symbols:
        raise ValueError(f"{path}: missing {sanitizer} instrumentation")
    # The native libraries must retain the release's glibc floor, even though the toolchain's
    # sanitizer runtime can have additional host requirements.
    if path.suffix != ".a":
        versions = run(toolchain, "llvm-readelf", "--version-info", path)
        for version in re.findall(r"GLIBC_(\d+(?:\.\d+)+)", versions):
            if tuple(map(int, version.split("."))) > (2, 35):
                raise ValueError(f"{path}: needs GLIBC_{version}, above 2.35")
        return
    with tempfile.TemporaryDirectory() as temp:
        # Inspect the machine-code half of every member as well as the embedded bitcode.
        subprocess.run([str(toolchain / "bin/llvm-ar"), "x", str(path.resolve())], cwd=temp, check=True)
        objects = list(Path(temp).glob("*.o"))
        if not objects:
            raise ValueError(f"{path}: empty archive")
        for obj in objects:
            sections = run(toolchain, "llvm-readelf", "--sections", obj)
            if ".llvm.lto" not in sections or ".text" not in sections:
                raise ValueError(f"{path}({obj.name}): expected machine code and ThinLTO bitcode")


def assemble(version, sanitizer, toolchain, out, svm_tar, jars):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-elide\.\d+|-dev)", version):
        raise ValueError(f"Invalid artifact version: {version}")
    if sanitizer not in PREFIXES:
        raise ValueError(f"Unsupported sanitizer: {sanitizer}")
    root = out / f"graalvm-natives-{version}-linux-amd64-sanitizer-{sanitizer}"
    if root.exists():
        shutil.rmtree(root)
    clibs = root / "lib/svm/clibraries"
    found = set()
    with tarfile.open(svm_tar) as archive:
        for member in archive.getmembers():
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError(f"Invalid archive path: {member.name}")
            # Exclude musl/default libraries and ship the headers used by native embedders.
            if not member.isfile() or not ("include" in relative.parts or "glibc" in relative.parts):
                continue
            dest = clibs / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, dest.open("wb") as target:
                shutil.copyfileobj(source, target)
            if dest.suffix == ".a":
                inspect_library(dest, sanitizer, toolchain)
                found.add(dest.name)
    if found != SVM_LIBRARIES:
        raise ValueError(f"Expected {SVM_LIBRARIES}, found {found}")
    native_counts = {}
    for artifact, jar in zip(JARS, jars):
        count = 0
        # These are complete replacement jars, with the existing files/sha256 resource metadata
        # produced by mx. They live outside Maven's ordinary platform-neutral coordinates.
        dest = root / "jars" / f"{artifact}.jar"
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(jar, dest)
        with zipfile.ZipFile(dest) as resources, tempfile.TemporaryDirectory() as temp:
            for entry in resources.infolist():
                if entry.is_dir():
                    continue
                if "/linux/" in entry.filename and "/linux/amd64/" not in entry.filename:
                    raise ValueError(f"{artifact}: unexpected platform resource {entry.filename}")
                if "/linux/amd64/" not in entry.filename:
                    continue
                data = resources.read(entry)
                if not data.startswith(b"\x7fELF") and not data.startswith(b"!<arch>\n"):
                    continue
                native = Path(temp) / Path(entry.filename).name
                native.write_bytes(data)
                inspect_library(native, sanitizer, toolchain)
                count += 1
        if not count:
            raise ValueError(f"{artifact}: no native libraries inspected")
        native_counts[artifact] = count
    manifest = {
        "version": version,
        "platform": "linux-amd64",
        "sanitizer": sanitizer,
        "toolchain": json.loads((toolchain / f"share/elide-toolchain/sanitizers/{sanitizer}.addon.json").read_text()),
        "native_libraries": native_counts,
        "files": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in sorted(root.rglob("*")) if p.is_file()},
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(native_counts, sort_keys=True))


if __name__ == "__main__":
    if len(sys.argv) != 9:
        sys.exit("Usage: sanitizer-natives.py VERSION SANITIZER TOOLCHAIN OUT SVM_TAR TRUFFLE_JAR NFI_JAR PYTHON_JAR")
    assemble(sys.argv[1], sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5]), sys.argv[6:])
