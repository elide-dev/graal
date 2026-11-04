#!/usr/bin/env python3
"""Exercise the packaged ASan Truffle/NFI/GraalPy resources in an ordinary JVM.

Usage: check-sanitizer-resources.py TOOLCHAIN ADDON MX_OUTPUT_ROOT
Requires JAVA_HOME. The output root supplies the matching Java-only dependencies.
"""
import os
from pathlib import Path
import subprocess
import sys


def check(toolchain, addon, build):
    replaced = {"truffle-api.jar", "truffle-nfi-libffi.jar", "graalpython-resources.jar"}
    classpath = sorted((addon / "jars").glob("*.jar"))
    for suite in ("sdk", "truffle", "graalpython", "regex"):
        classpath.extend(p for p in sorted((build / suite / "dists").glob("*.jar")) if p.name not in replaced)
    cc = toolchain / "bin/x86_64-unknown-linux-gnu-asan-clang"
    runtime = Path(subprocess.check_output([str(cc), "--print-runtime-dir"], text=True).strip())
    env = dict(os.environ)
    # In particular, do not let a build-time bootstrap attach library override the packaged one.
    env.pop("JAVA_TOOL_OPTIONS", None)
    env["LD_PRELOAD"] = str(runtime / "libclang_rt.asan.so")
    env["LD_LIBRARY_PATH"] = str(runtime) + (os.pathsep + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    env["ASAN_OPTIONS"] = "detect_leaks=0:handle_segv=0:allow_user_segv_handler=1"
    java = Path(env["JAVA_HOME"]) / "bin/java"
    probe = Path(__file__).with_name("SanitizerNativeResourcesProbe.java")
    subprocess.run([str(java), "--enable-native-access=ALL-UNNAMED", "--class-path",
                    os.pathsep.join(map(str, classpath)), str(probe)], env=env, check=True)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    check(*map(Path, sys.argv[1:]))
