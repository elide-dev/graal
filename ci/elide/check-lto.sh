#!/usr/bin/env bash
# Checks that SVM's static C libraries in a GraalVM home carry ThinLTO bitcode from the expected
# LLVM major, next to their machine code (fat LTO objects, see SVM_LTO_CC in mx_substratevm.py), and
# that an image links both normally and with ThinLTO.
#
# Usage: check-lto.sh <llvm-bin-dir> <llvm-major> <graalvm-home> <work-dir>
set -euo pipefail

llvm_bin=$1
major=$2
home=$3
work=$4

libs="libjvm.a libsvm_container.a liblibchelper.a"
mkdir -p "${work}"
work=$(cd "${work}" && pwd)

# The glibc archives (clibraries/<os>-<arch>/glibc); the musl ones are built with musl-gcc and have
# no bitcode.
clibs=$(find "${home}/lib/svm/clibraries" -mindepth 2 -maxdepth 2 -type d -name glibc)
if [ "$(wc -l <<<"${clibs}")" -ne 1 ] || [ ! -d "${clibs}" ]; then
  echo "Expected one glibc directory in ${home}/lib/svm/clibraries, found: ${clibs}"
  exit 1
fi
echo "Checking ${clibs}"

status=0
for lib in ${libs}; do
  archive="${clibs}/${lib}"
  if [ ! -f "${archive}" ]; then
    echo "MISSING ${archive}"
    status=1
    continue
  fi
  x="${work}/${lib}.x"
  rm -rf "${x}" && mkdir -p "${x}"
  (cd "${x}" && "${llvm_bin}/llvm-ar" x "${archive}")
  total=0
  lto=0
  for o in "${x}"/*.o; do
    total=$((total + 1))
    sections=$("${llvm_bin}/llvm-readelf" -S "${o}")
    if ! grep -q '\.text' <<<"${sections}"; then
      echo "  $(basename "${o}"): no machine code"
      status=1
    fi
    if grep -q '\.llvm\.lto' <<<"${sections}"; then
      lto=$((lto + 1))
      producer=$("${llvm_bin}/llvm-readelf" -p .comment "${o}" | grep -o 'clang version [0-9]*' | head -1)
      if [ "${producer}" != "clang version ${major}" ]; then
        echo "  $(basename "${o}"): produced by '${producer}', expected clang version ${major}"
        status=1
      fi
    fi
  done
  echo "${lib}: ${lto}/${total} objects with ThinLTO bitcode"
  if [ "${total}" -eq 0 ] || [ "${lto}" -ne "${total}" ]; then
    status=1
  fi
done

# An image links with a ThinLTO link through lld that uses the libraries' bitcode. lld uses the
# machine code of fat LTO objects unless the link passes -ffat-lto-objects (--fat-lto-objects).
# The default link, which uses the machine code, is covered by the other smoke tests.
rm -rf "${work}/link" && mkdir -p "${work}/link" && cd "${work}/link"
printf 'public class Hello { public static void main(String[] a) { System.out.println("hello " + Runtime.getRuntime().availableProcessors()); } }\n' > Hello.java
"${home}/bin/javac" Hello.java
"${home}/bin/native-image" -cp . -o hello-thinlto --native-compiler-path="${llvm_bin}/clang" \
  -H:NativeLinkerOption=-flto=thin -H:NativeLinkerOption=-ffat-lto-objects -H:NativeLinkerOption=-fuse-ld=lld \
  -H:NativeLinkerOption=-Wl,--save-temps -H:TempDirectory="${work}/link/tmp" Hello
./hello-thinlto
resolution=$(find "${work}/link" -name 'hello-thinlto.resolution.txt' | head -1)
for lib in ${libs}; do
  members=$(grep -o "/${lib}([^)]*)" "${resolution}" | sort -u | wc -l)
  echo "${lib}: ${members} members linked from bitcode by ThinLTO"
  if [ "${members}" -eq 0 ]; then
    status=1
  fi
done

if [ "${status}" -ne 0 ]; then
  echo "LTO check failed"
fi
exit "${status}"
