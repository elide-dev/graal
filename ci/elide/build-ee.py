#!/usr/bin/env python3
"""Builds the EE variant of Elide's GraalVM distribution: Oracle GraalVM with the fork's code.

The base is the Oracle GraalVM early-access build made from the same upstream sources as the
fork's base (same GraalVM version and JVMCI). Its open-source jars are replaced with the fork's,
taken from the CE distribution built in the same job. The enterprise parts stay: svm-enterprise
(PGO, code compression, ...), the G1 libraries, the enterprise compiler and Truffle modules.

Oracle's build has the Graal compiler inside lib/modules. Its jdk.graal.compiler module is the
open-source build of the same sources (byte for byte); the enterprise compiler is a separate module
(com.oracle.graal.graal_enterprise). The fork's compiler modules (jdk.graal.compiler and
jdk.graal.compiler.management), taken from the CE build, go in lib/jvmci as graal.jar and
graal-management.jar. When lib/jvmci exists, the native-image driver puts those on the image
builder's --upgrade-module-path, so every image build uses the fork's compiler with the enterprise
compiler on top. The JIT of the java launcher (libgraal) stays Oracle's. A change to another module
that Oracle's build has inside lib/modules fails the build if it changes the module's classes: those
cannot be upgraded. Changes to comments or formatting leave the classes byte-identical and pass.

LinkCheck.java then checks that the enterprise code (the jars only Oracle's build has, and the
enterprise modules) still links against the fork's classes, the overlaid jars and the upgraded
modules.

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

# Sources of modules that Oracle's build has inside lib/modules (source directory -> module).
LINKED_SOURCES = {
    "compiler/src/jdk.graal.compiler/": "jdk.graal.compiler",
    "compiler/src/jdk.graal.compiler.management/": "jdk.graal.compiler.management",
    "compiler/src/jdk.graal.compiler.options/": "jdk.graal.compiler.options",
    "sdk/src/org.graalvm.collections/": "org.graalvm.collections",
    "sdk/src/org.graalvm.nativeimage/": "org.graalvm.nativeimage",
    "sdk/src/org.graalvm.nativeimage.libgraal/": "org.graalvm.nativeimage.libgraal",
    "sdk/src/org.graalvm.word/": "org.graalvm.word",
    "truffle/src/com.oracle.truffle.compiler/": "org.graalvm.truffle.compiler",
}
# The subset that may change: modules the native-image driver upgrades from lib/jvmci (source
# directory -> module, jar).
UPGRADEABLE_SOURCES = {
    "compiler/src/jdk.graal.compiler/": ("jdk.graal.compiler", "graal.jar"),
    "compiler/src/jdk.graal.compiler.management/": ("jdk.graal.compiler.management", "graal-management.jar"),
}
UPGRADE_DIR = "lib/jvmci"


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


def changed_linked_modules(graal, ee_commit, ce_home, ee_home):
    """The upgradeable modules (module, jar) whose sources the fork changes, relative to the sources
    of Oracle's build. A module that cannot be upgraded may only have source changes that leave its
    classes as they are (comments, formatting): its classes in the CE build must be byte-identical to
    Oracle's, which the same sources give. Fails otherwise."""
    repository = "https://github.com/oracle/graal"
    run("git", "fetch", "--quiet", "--depth=1", repository, ee_commit, cwd=graal)
    names = run("git", "diff", "--name-only", "--no-renames", ee_commit, "HEAD", "--", *LINKED_SOURCES, cwd=graal)
    modules, fixed = set(), {}
    for path in names.splitlines():
        source = next((s for s in LINKED_SOURCES if path.startswith(s)), None)
        if source in UPGRADEABLE_SOURCES:
            modules.add(UPGRADEABLE_SOURCES[source])
        elif source:
            fixed.setdefault(LINKED_SOURCES[source], []).append(path)
    for module, paths in sorted(fixed.items()):
        differing = differing_classes(ce_home, ee_home, module)
        if differing:
            fail(f"the fork changes {module}, which Oracle's build has inside lib/modules and the EE "
                 f"distribution cannot upgrade: classes {', '.join(differing[:20])} differ (sources: {', '.join(paths)})")
        print(f"{module}: {len(paths)} changed sources, classes identical to Oracle's")
    return sorted(modules)


def differing_classes(ce_home, ee_home, module):
    """The classes of a module in lib/modules that differ between the CE build and Oracle's."""
    trees = []
    for home in (ce_home, ee_home):
        jimage = os.path.join(home, "bin", "jimage.exe" if os.name == "nt" else "jimage")
        out = tempfile.mkdtemp()
        run(jimage, "extract", "--dir", out, "--include", f"regex:/{module}/.*", os.path.join(home, "lib", "modules"))
        files = {}
        root = os.path.join(out, module)
        for dirpath, _, names in os.walk(root):
            for name in names:
                if name.endswith(".class"):
                    path = os.path.join(dirpath, name)
                    with open(path, "rb") as f:
                        files[os.path.relpath(path, root).replace(os.sep, "/")] = hashlib.sha256(f.read()).digest()
        shutil.rmtree(out)
        trees.append(files)
    ce, ee = trees
    return sorted(name for name in ce.keys() | ee.keys() if ce.get(name) != ee.get(name))


def write_upgrade_jars(ce_home, ee_home, modules):
    """Puts the CE build's copy of each module into lib/jvmci, as a modular jar. Returns the jars."""
    jimage = os.path.join(ce_home, "bin", "jimage.exe" if os.name == "nt" else "jimage")
    os.makedirs(os.path.join(ee_home, UPGRADE_DIR), exist_ok=True)
    jars = []
    for module, jar_name in modules:
        classes_dir = tempfile.mkdtemp()
        run(jimage, "extract", "--dir", classes_dir, "--include", f"regex:/{module}/.*", os.path.join(ce_home, "lib", "modules"))
        module_dir = os.path.join(classes_dir, module)
        if not os.path.isfile(os.path.join(module_dir, "module-info.class")):
            fail(f"no module {module} in the CE distribution")
        jar_path = os.path.join(ee_home, UPGRADE_DIR, jar_name)
        count = 0
        with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as jar:
            for dirpath, _, files in os.walk(module_dir):
                for name in sorted(files):
                    path = os.path.join(dirpath, name)
                    entry = os.path.relpath(path, module_dir).replace(os.sep, "/")
                    if entry == "module-info.java":  # jimage's rendering of the descriptor
                        continue
                    jar.write(path, entry)
                    count += name.endswith(".class")
        shutil.rmtree(classes_dir)
        print(f"upgraded {module}: {UPGRADE_DIR}/{jar_name} ({count} classes)")
        jars.append(jar_path)
    return jars


def link_check(graal, ee_home, fork_jars, enterprise_jars):
    java = os.path.join(ee_home, "bin", "java.exe" if os.name == "nt" else "java")
    script = os.path.join(graal, "ci", "elide", "LinkCheck.java")
    result = subprocess.run([java, script, ee_home, *fork_jars, "--", *enterprise_jars], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end="")
    if result.returncode != 0:
        fail("the enterprise code does not link against the fork's code (see the problems above)")


def overlay_jars(ce_home, ee_home):
    """Replaces Oracle's jars with the CE build's. Returns the replaced jars and the jars only
    Oracle's build has."""
    replaced, enterprise = [], []
    for directory in OVERLAY_DIRS:
        for dirpath, _, files in os.walk(os.path.join(ce_home, directory)):
            for name in files:
                if not name.endswith(".jar"):
                    continue
                relative = os.path.relpath(os.path.join(dirpath, name), ce_home)
                target = os.path.join(ee_home, relative)
                if os.path.exists(target):
                    shutil.copy2(os.path.join(ce_home, relative), target)
                    replaced.append(target)
    print(f"replaced {len(replaced)} jars with the fork's")
    if not replaced:
        fail("no jars to overlay; is the CE home right?")
    for directory in OVERLAY_DIRS:
        for dirpath, _, files in os.walk(os.path.join(ee_home, directory)):
            for name in files:
                path = os.path.join(dirpath, name)
                if name.endswith(".jar") and path not in replaced:
                    enterprise.append(path)
    return replaced, sorted(enterprise)


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

    replaced, enterprise_jars = overlay_jars(ce_home, ee_home)
    upgraded = write_upgrade_jars(ce_home, ee_home, changed_linked_modules(graal, ee_commit.group(1), ce_home, ee_home))
    link_check(graal, ee_home, replaced + upgraded, enterprise_jars)

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
