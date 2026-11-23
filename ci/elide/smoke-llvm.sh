#!/usr/bin/env bash
# Smoke-tests the Native Image LLVM backend (component svml, with the LLVM.org toolchain llp) of a
# CE or EE distribution: builds and runs images with --tool:llvm-backend, which puts the backend's
# builder jars on the image builder's module path and selects it (-H:CompilerBackend=llvm).
#
#   ci/elide/smoke-llvm.sh <graalvm-home> <work-dir> [--expect-lto]
#
# --expect-lto fails unless the home's static C libraries carry LTO bitcode, so that a run that
# should cover the LLVM backend linking against them (see below) cannot pass without doing so.
#
# The release ships the backend on linux-amd64 only (the workflow's matrix.llvm), and runs this there.
set -euo pipefail

HOME_DIR=${1:?graalvm-home}
WORK=${2:?work-dir}
EXPECT_LTO=${3:-}
NI="${HOME_DIR}/bin/native-image"

for f in lib/svm/tools/llvm-backend/native-image.properties lib/svm/tools/llvm-backend/builder/svm-llvm.jar lib/llvm/bin/clang lib/llvm/bin/llc; do
  [ -e "${HOME_DIR}/${f}" ] || { echo "no ${f} in ${HOME_DIR}: the distribution lacks the LLVM backend" >&2; exit 1; }
done

# On Linux, the release builds SVM's static C libraries as fat LTO objects with the Elide toolchain's
# LLVM (LTO_LLVM_MAJOR), which may be newer than lib/llvm's. The LLVM backend links images with
# lib/llvm's clang, without -flto, so the linker must take the objects' native code and never read
# their bitcode, which an older LLVM cannot. Say which case this run covers.
lto=0
while IFS= read -r -d '' archive; do
  # grep -c reads all of it: with grep -q, llvm-objdump's SIGPIPE would fail the pipeline.
  if [ "$("${HOME_DIR}/lib/llvm/bin/llvm-objdump" -h "${archive}" 2>/dev/null | grep -c '\.llvm\.lto')" -gt 0 ]; then
    lto=$((lto + 1))
  fi
done < <(find "${HOME_DIR}/lib/svm/clibraries" -name '*.a' -print0)
echo "$("${HOME_DIR}/lib/llvm/bin/clang" --version | head -1)"
echo "static C libraries with LTO bitcode (.llvm.lto): ${lto}"
if [ "${EXPECT_LTO}" = "--expect-lto" ] && [ "${lto}" -eq 0 ]; then
  echo "expected static C libraries with LTO bitcode in ${HOME_DIR}/lib/svm/clibraries" >&2
  exit 1
fi

mkdir -p "${WORK}"
cd "${WORK}"
cat > LlvmSmoke.java <<'EOF'
import java.util.ArrayList;
import java.util.List;

public class LlvmSmoke {
    static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        List<String> keep = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            keep.add("item " + i);
            sum += fib(20);
        }
        try {
            Object o = keep.size() > 0 ? null : keep;
            o.hashCode();
            throw new AssertionError("no NullPointerException");
        } catch (NullPointerException expected) {
            // implicit exception from LLVM-compiled code
        }
        if (sum != 676500 || keep.size() != 100) {
            throw new AssertionError(sum);
        }
        System.out.println("ok llvm " + System.getProperty("java.vendor.version"));
    }
}
EOF
"${HOME_DIR}/bin/javac" LlvmSmoke.java
# -H:TempDirectory keeps the builder's work files, so that the LLVM bitcode shows which backend ran.
rm -rf llvm-tmp
if ! "${NI}" -cp . --tool:llvm-backend -H:TempDirectory="${PWD}/llvm-tmp" -o llvm-smoke LlvmSmoke > llvm-smoke.log 2>&1; then
  cat llvm-smoke.log
  for report in svm_err_*.md; do
    # The report's error and stack trace, without the build output it repeats.
    [ -f "${report}" ] && sed '/^## Build Output/,/^## /{/^## Build Output/d;/^## /!d}' "${report}" | head -200
  done
  # An LLVM tool failure names its command and input but not the tool's output: run it again, and
  # keep the input (the failed/ directory, which the release workflow uploads).
  command=$(sed -n 's/^Command: //p' llvm-smoke.log | head -1)
  input=$(grep -oE '\(/[^()]*\.bc\)' llvm-smoke.log | head -1 | tr -d '()')
  if [ -n "${command}" ] && [ -f "${input}" ]; then
    mkdir -p failed
    cp "${input}" failed/
    echo "${command}" > failed/command.txt
    echo "--- ${command} (in $(dirname "${input}")):"
    (cd "$(dirname "${input}")" && PATH="${HOME_DIR}/lib/llvm/bin:${PATH}" ${command}) 2>&1 | tail -50 || true
  fi
  echo "the LLVM backend build failed" >&2
  exit 1
fi
bitcode=$(find llvm-tmp -name '*.bc' | wc -l)
rm -rf llvm-tmp
[ "${bitcode}" -gt 0 ] || { cat llvm-smoke.log; echo "the image was not compiled by the LLVM backend: no LLVM bitcode" >&2; exit 1; }
echo "the LLVM backend compiled ${bitcode} bitcode files"
out=$(./llvm-smoke)
echo "${out}"
case "${out}" in
  "ok llvm "*) ;;
  *) echo "unexpected output from the LLVM-backend image" >&2; exit 1 ;;
esac
ls -la llvm-smoke
echo "LLVM backend smoke test passed"
