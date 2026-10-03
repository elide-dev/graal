#!/usr/bin/env bash
# Smoke-tests the EE distribution: the enterprise features work with the fork's code.
#
#   ci/elide/smoke-ee.sh <ee-home> <platform> <work-dir> [<maven-repo> <version>]
#
# Builds and runs images with PGO (instrumented, then optimized), and on Linux with G1 and with code
# compression. With a Maven repository (the release's), instead builds an image that embeds GraalJS
# with Truffle runtime compilation, which runs a function until Truffle compiles it: the Maven
# artifacts only exist once the release's Maven build has run.
set -euo pipefail

HOME_DIR=${1:?ee-home}
PLATFORM=${2:?platform}
WORK=${3:?work-dir}
MAVEN_REPO=${4:-}
VERSION=${5:-}
NI="${HOME_DIR}/bin/native-image"

mkdir -p "${WORK}"
cd "${WORK}"
"${HOME_DIR}/bin/java" --version
"${NI}" --version
grep -q '^ELIDE_GRAALVM=' "${HOME_DIR}/release" || { echo "not an Elide EE build: no ELIDE_GRAALVM in release" >&2; exit 1; }

if [ -z "${MAVEN_REPO}" ]; then
cat > Smoke.java <<'EOF'
import java.util.ArrayList;
import java.util.List;

public class Smoke {
    static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        List<long[]> keep = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            keep.add(new long[10000]);
            if (keep.size() > 20) {
                keep.remove(0);
            }
            sum += fib(20);
        }
        if (sum != 676500) {
            throw new AssertionError(sum);
        }
        System.out.println("ok " + System.getProperty("java.vendor.version"));
    }
}
EOF
"${HOME_DIR}/bin/javac" Smoke.java

build_and_run() { # <name> <native-image args...>
  local name=$1
  shift
  "${NI}" -cp . "$@" -o "${name}" Smoke > "${name}.log" 2>&1 || { cat "${name}.log"; echo "${name}: build failed" >&2; exit 1; }
  "./${name}"
}

build_and_run smoke-plain
build_and_run smoke-instrumented --pgo-instrument
[ -s default.iprof ] || { echo "the instrumented image wrote no profile" >&2; exit 1; }
build_and_run smoke-pgo --pgo=default.iprof
grep -q 'PGO: user-provided' smoke-pgo.log || { echo "the optimized build did not use the profile" >&2; exit 1; }

case "${PLATFORM}" in
  linux-*)
    build_and_run smoke-g1 --gc=G1
    grep -q 'Garbage collector: G1 GC' smoke-g1.log || { echo "the image does not use G1" >&2; exit 1; }
    build_and_run smoke-compressed -H:+UnlockExperimentalVMOptions -H:+EnableCodeCompression
    ;;
esac
else
  cp=""
  for ga in org/graalvm/polyglot/polyglot org/graalvm/sdk/collections org/graalvm/sdk/word org/graalvm/sdk/nativeimage \
            org/graalvm/sdk/jniutils org/graalvm/truffle/truffle-api org/graalvm/truffle/truffle-runtime \
            org/graalvm/truffle/truffle-compiler org/graalvm/js/js-language org/graalvm/regex/regex \
            org/graalvm/shadowed/icu4j org/graalvm/shadowed/xz; do
    jar="${MAVEN_REPO}/${ga}/${VERSION}/$(basename "${ga}")-${VERSION}.jar"
    [ -f "${jar}" ] || { echo "missing ${jar}" >&2; exit 1; }
    cp="${cp}:${jar}"
  done
  cat > JsSmoke.java <<'EOF'
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class JsSmoke {
    public static void main(String[] args) {
        try (Context context = Context.newBuilder("js").option("engine.CompileImmediately", "true")
                        .option("engine.BackgroundCompilation", "false").allowExperimentalOptions(true).build()) {
            Value fib = context.eval("js", "(function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); })");
            for (int i = 0; i < 3; i++) {
                int result = fib.execute(25).asInt();
                if (result != 75025) {
                    throw new AssertionError(result);
                }
            }
            System.out.println("ok js");
        }
    }
}
EOF
  "${HOME_DIR}/bin/javac" -cp "${cp#:}" JsSmoke.java
  js_build_and_run() { # <name> <native-image args...>
    local name=$1
    shift
    "${NI}" -cp ".${cp}" "$@" -o "${name}" JsSmoke > "${name}.log" 2>&1 || { cat "${name}.log"; echo "${name}: build failed" >&2; exit 1; }
    "./${name}"
  }
  case "${PLATFORM}" in
    linux-*) js_build_and_run js-g1 --gc=G1 ;;
    *) js_build_and_run js-plain ;;
  esac
fi
echo "EE smoke tests passed"
