#!/usr/bin/env python3
"""Builds the EE variant of Elide's GraalVM distribution: Oracle GraalVM with the fork's code.

The base is the Oracle GraalVM early-access build made from the same upstream sources as the
fork's base (same GraalVM version and JVMCI). Its open-source jars are replaced with the fork's,
taken from the CE distribution built in the same job. The enterprise parts stay: svm-enterprise
(PGO, code compression, ...), the G1 libraries, the enterprise compiler and Truffle modules.

Oracle's build has the Graal compiler inside lib/modules, so the fork's compiler changes cannot
be overlaid as jars. The fork may only change compiler classes used for Truffle compilation; their
classes are put in lib/truffle/builder/elide-compiler-patch.jar, which the truffle-svm macro
(enabled by truffle-runtime) adds to the image builder with --patch-module. Any other change to
code that Oracle's build has inside lib/modules fails the build.

  build-ee.py <version> <platform> <ce-home> <out-dir>

Run from the workspace that holds the graal checkout. Needs ORACLE_EE_REPOSITORY and ORACLE_EE_TAG
(the early-access release to build on). Prints home=<EE home> and archive=<archive>.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

# Elide platform -> Oracle's archive platform name
ASSET_PLATFORMS = {
    "linux-amd64": "linux-x64",
    "linux-aarch64": "linux-aarch64",
    "macos-aarch64": "macos-aarch64",
    "windows-amd64": "windows-x64",
}

# Directories whose jars both distributions ship are taken from the CE distribution. Other jars
# (e.g. lib/jrt-fs.jar, which belongs to the JDK) stay Oracle's.
OVERLAY_DIRS = ("lib/svm", "lib/truffle", "lib/graalvm")

# Sources of modules that Oracle's build has inside lib/modules.
LINKED_SOURCES = (
    "compiler/src/jdk.graal.compiler/",
    "compiler/src/jdk.graal.compiler.management/",
    "sdk/src/org.graalvm.collections/",
    "sdk/src/org.graalvm.nativeimage/",
    "sdk/src/org.graalvm.nativeimage.libgraal/",
    "sdk/src/org.graalvm.word/",
    "truffle/src/com.oracle.truffle.compiler/",
)
# The subset that may change: compiler classes used for Truffle compilation, patched by the
# truffle-svm macro.
PATCHABLE_SOURCES = "compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/truffle/"
PATCH_JAR = "lib/truffle/builder/elide-compiler-patch.jar"
MACRO = "lib/svm/macros/truffle-svm/native-image.properties"


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def run(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.PIPE, text=True).stdout


def download(url, path):
    print(f"downloading {url}")
    with urllib.request.urlopen(url) as response, open(path, "wb") as out:
        shutil.copyfileobj(response, out, 1 << 20)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_release(home):
    values = {}
    with open(os.path.join(home, "release"), encoding="utf-8") as f:
        for line in f:
            key, sep, value = line.rstrip("\n").partition("=")
            if sep:
                values[key] = value.strip('"')
    return values


def find_home(root):
    """The JDK home in an extracted archive: <dir>, or <bundle>/Contents/Home on macOS."""
    (top,) = os.listdir(root)
    top = os.path.join(root, top)
    home = os.path.join(top, "Contents", "Home")
    return top, home if os.path.isdir(home) else top


def extract(archive, dest):
    if archive.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            z.extractall(dest)
    else:
        # The system tar keeps symbolic links and file modes.
        subprocess.run(["tar", "-xzf", archive, "-C", dest], check=True)


def suite_version(graal):
    with open(os.path.join(graal, "sdk", "mx.sdk", "suite.py"), encoding="utf-8") as f:
        match = re.search(r'"version"\s*:\s*"([^"]+)"', f.read())
    return match.group(1)


def jvmci_tag(release):
    match = re.search(r"jvmci-[0-9.]+-b[0-9]+", release.get("JAVA_RUNTIME_VERSION", ""))
    return match.group(0) if match else None


def changed_linked_sources(graal, ee_commit):
    """The fork's changes, relative to the sources of Oracle's build, in modules inside lib/modules."""
    repository = "https://github.com/oracle/graal"
    run("git", "fetch", "--quiet", "--depth=1", repository, ee_commit, cwd=graal)
    names = run("git", "diff", "--name-status", "--no-renames", ee_commit, "HEAD", "--", *LINKED_SOURCES, cwd=graal)
    changes = [line.split("\t", 1) for line in names.splitlines() if line]
    not_patchable = [path for status, path in changes if status == "D" or not path.startswith(PATCHABLE_SOURCES)]
    if not_patchable:
        fail("the fork changes code that Oracle's build has inside lib/modules, which the EE "
             f"distribution cannot carry: {', '.join(not_patchable)}")
    return [path for _, path in changes if path.endswith(".java")]


def write_patch_jar(ce_home, ee_home, sources):
    """Puts the CE build's classes of the given compiler sources into the patch jar."""
    classes_dir = tempfile.mkdtemp()
    jimage = os.path.join(ce_home, "bin", "jimage.exe" if os.name == "nt" else "jimage")
    run(jimage, "extract", "--dir", classes_dir, "--include", "regex:/jdk.graal.compiler/jdk/graal/compiler/truffle/.*",
        os.path.join(ce_home, "lib", "modules"))
    module_dir = os.path.join(classes_dir, "jdk.graal.compiler")
    entries = []
    for source in sources:
        relative = source[len("compiler/src/jdk.graal.compiler/src/"):-len(".java")]
        package_dir, name = os.path.split(relative)
        found = [f for f in os.listdir(os.path.join(module_dir, package_dir))
                 if f == f"{name}.class" or (f.startswith(f"{name}$") and f.endswith(".class"))]
        if f"{name}.class" not in found:
            fail(f"no class for {source} in the CE distribution")
        entries += [f"{package_dir}/{f}" for f in sorted(found)]
    with zipfile.ZipFile(os.path.join(ee_home, PATCH_JAR), "w", zipfile.ZIP_DEFLATED) as jar:
        for entry in entries:
            jar.write(os.path.join(module_dir, entry), entry)
    shutil.rmtree(classes_dir)
    print(f"compiler patch: {len(entries)} classes from {len(sources)} sources")

    macro = os.path.join(ee_home, MACRO)
    with open(macro, encoding="utf-8") as f:
        text = f.read()
    if re.search(r"^JavaArgs\s*=", text, re.M):
        fail(f"{MACRO} already has JavaArgs; merge the --patch-module argument into them")
    with open(macro, "a", encoding="utf-8") as f:
        f.write(f"\nJavaArgs = --patch-module=jdk.graal.compiler=${{.}}/../../../truffle/builder/{os.path.basename(PATCH_JAR)}\n")


def overlay_jars(ce_home, ee_home):
    count = 0
    for directory in OVERLAY_DIRS:
        for dirpath, _, files in os.walk(os.path.join(ce_home, directory)):
            for name in files:
                if not name.endswith(".jar"):
                    continue
                relative = os.path.relpath(os.path.join(dirpath, name), ce_home)
                target = os.path.join(ee_home, relative)
                if os.path.exists(target):
                    shutil.copy2(os.path.join(ce_home, relative), target)
                    count += 1
    print(f"replaced {count} jars with the fork's")
    if count == 0:
        fail("no jars to overlay; is the CE home right?")


def package(root, name, platform, out_dir):
    stage = os.path.dirname(root)
    named = os.path.join(stage, name)
    os.rename(root, named)
    os.makedirs(out_dir, exist_ok=True)
    if platform.startswith("windows-"):
        archive = os.path.join(out_dir, f"{name}.zip")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            for dirpath, _, files in os.walk(named):
                for f in files:
                    path = os.path.join(dirpath, f)
                    z.write(path, os.path.relpath(path, stage))
    else:
        archive = os.path.join(out_dir, f"{name}.tar.gz")
        subprocess.run(["tar", "-C", stage, "-czf", archive, name], check=True)
    with open(f"{archive}.sha256", "w", encoding="utf-8") as f:
        f.write(f"{sha256(archive)}  {os.path.basename(archive)}\n")
    return named, archive


def main():
    if len(sys.argv) != 5:
        fail(__doc__)
    version, platform, ce_home, out_dir = sys.argv[1:]
    ce_home, out_dir = os.path.abspath(ce_home), os.path.abspath(out_dir)
    graal = os.path.abspath("graal")
    repository, tag = os.environ["ORACLE_EE_REPOSITORY"], os.environ["ORACLE_EE_TAG"]
    m = re.match(r"jdk-(\w+)-(.+)$", tag)
    if not m or platform not in ASSET_PLATFORMS:
        fail(f"cannot name the archive for tag {tag} and platform {platform}")
    extension = "zip" if platform.startswith("windows-") else "tar.gz"
    asset = f"graalvm-jdk-{m.group(1)}-{m.group(2)}_{ASSET_PLATFORMS[platform]}_bin.{extension}"
    base = f"https://github.com/{repository}/releases/download/{tag}"

    work = tempfile.mkdtemp(prefix="graalvm-ee-", dir=os.environ.get("RUNNER_TEMP"))
    archive = os.path.join(work, asset)
    download(f"{base}/{asset}", archive)
    with urllib.request.urlopen(f"{base}/{asset}.sha256") as response:
        expected = response.read().decode().split()[0]
    if sha256(archive) != expected:
        fail(f"{asset}: checksum mismatch")
    extracted = os.path.join(work, "extracted")
    os.makedirs(extracted)
    extract(archive, extracted)
    os.remove(archive)
    root, ee_home = find_home(extracted)

    # Oracle's enterprise code is built against the open-source code of its build, so the fork
    # must be based on the same: same GraalVM version and JVMCI.
    ee_release, ce_release = read_release(ee_home), read_release(ce_home)
    ours = suite_version(graal)
    if not ee_release.get("GRAALVM_VERSION", "").startswith(ours):
        fail(f"{tag} is GraalVM {ee_release.get('GRAALVM_VERSION')}, the fork is {ours}")
    if jvmci_tag(ee_release) != jvmci_tag(ce_release):
        fail(f"{tag} is built on {jvmci_tag(ee_release)}, the fork on {jvmci_tag(ce_release)}")
    ee_commit = re.search(r"\bcompiler:([0-9a-f]{40})", ee_release.get("SOURCE", ""))
    if not ee_commit:
        fail(f"{tag} names no compiler source revision")
    print(f"base: Oracle GraalVM {ee_release['GRAALVM_VERSION']} ({tag}), open source {ee_commit.group(1)}")

    overlay_jars(ce_home, ee_home)
    sources = changed_linked_sources(graal, ee_commit.group(1))
    if sources:
        write_patch_jar(ce_home, ee_home, sources)

    revision = run("git", "rev-parse", "HEAD", cwd=graal).strip()
    release = os.path.join(ee_home, "release")
    with open(release, "rb") as f:
        newline = f.read().endswith(b"\n")
    with open(release, "a", encoding="utf-8") as f:
        # Oracle's release file does not end with a newline.
        f.write(("" if newline else "\n") + f'ELIDE_GRAALVM="{version} graal:{revision} base:{tag}"\n')

    home_in_root = os.path.relpath(ee_home, root)
    root, archive = package(root, f"graalvm-ee-{version}-{platform}", platform, out_dir)
    print(f"home={os.path.normpath(os.path.join(root, home_in_root))}")
    print(f"archive={archive}")


if __name__ == "__main__":
    main()
