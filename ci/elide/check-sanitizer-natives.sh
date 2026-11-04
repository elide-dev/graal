#!/usr/bin/env bash
# Prove that both halves of the packaged SVM archives link, run cleanly, and report a known bug.
set -euo pipefail
sanitizer=${1:?sanitizer}
toolchain=${2:?toolchain}
addon=${3:?add-on directory}
work=${4:?work directory}
script_dir=$(cd "$(dirname "$0")" && pwd)
cc=${toolchain}/bin/x86_64-unknown-linux-gnu-${sanitizer}-clang
clibs=${addon}/lib/svm/clibraries/linux-amd64/glibc
mkdir -p "${work}"
runtime_dir=$("${cc}" --print-runtime-dir)
export LD_LIBRARY_PATH=${runtime_dir}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}
case "${sanitizer}" in
  asan) report=heap-buffer-overflow; define=PROBE_ASAN ;;
  tsan) report='data race'; define=PROBE_TSAN ;;
  msan) report=use-of-uninitialized-value; define=PROBE_MSAN ;;
  *) exit 2 ;;
esac
for mode in machine-code thinlto; do
  flags=()
  if [ "${mode}" = thinlto ]; then flags=(-flto=thin -ffat-lto-objects); fi
  "${cc}" -g -O1 -fPIE -pie -pthread -shared-libsan -D"${define}" "${flags[@]}" \
    "${script_dir}/sanitizer-probe.c" "${clibs}/liblibchelper.a" "${clibs}/libzstd.a" \
    -o "${work}/probe-${mode}"
  "${work}/probe-${mode}"
  if "${work}/probe-${mode}" bug > "${work}/${mode}.log" 2>&1; then
    echo "${sanitizer} ${mode}: the known bug was not reported" >&2
    exit 1
  fi
  if ! grep -q "${report}" "${work}/${mode}.log"; then
    cat "${work}/${mode}.log"
    exit 1
  fi
  echo "${sanitizer} ${mode}: clean probe passed; known bug reported"
done
