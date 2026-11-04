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
run_probe() {
  local output=$1 status=0 attempt
  shift
  for ((attempt = 1; attempt <= 32; attempt++)); do
    if "$@" > "${output}" 2>&1; then return 0; else status=$?; fi
    # Container seccomp can block TSan's personality() re-exec when ASLR chooses an
    # incompatible address layout. Retry that startup failure with a fresh layout.
    # Actual sanitizer findings and all other failures retain their original status.
    if [ "${sanitizer}" != tsan ] || ! grep -q '^FATAL: ThreadSanitizer: encountered an incompatible memory layout' "${output}"; then
      return "${status}"
    fi
    echo "TSan startup address-layout collision (${attempt}/32)" >&2
  done
  return "${status}"
}
for mode in machine-code thinlto; do
  flags=()
  if [ "${mode}" = thinlto ]; then flags=(-flto=thin -ffat-lto-objects); fi
  "${cc}" -g -O1 -fPIE -pie -pthread -shared-libsan -D"${define}" "${flags[@]}" \
    "${script_dir}/sanitizer-probe.c" "${clibs}/liblibchelper.a" "${clibs}/libzstd.a" \
    -o "${work}/probe-${mode}"
  if ! run_probe "${work}/${mode}.clean.log" "${work}/probe-${mode}"; then
    cat "${work}/${mode}.clean.log"
    exit 1
  fi
  if run_probe "${work}/${mode}.log" "${work}/probe-${mode}" bug; then
    echo "${sanitizer} ${mode}: the known bug was not reported" >&2
    exit 1
  fi
  if ! grep -q "${report}" "${work}/${mode}.log"; then
    cat "${work}/${mode}.log"
    exit 1
  fi
  echo "${sanitizer} ${mode}: clean probe passed; known bug reported"
done
