#!/usr/bin/env bash
# Build a Linux amd64 native-library add-on without building instrumented Java images.
# Usage: build-sanitizer-natives.sh <version> <asan|tsan|msan> <out-dir>
# Run from the workspace containing graal and graalpython. Requires JAVA_HOME, MX and
# ELIDE_TOOLCHAIN_HOME, with the matching sanitizer add-on installed.
set -euo pipefail

version=${1:?version}
sanitizer=${2:?sanitizer}
out=${3:?out-dir}
case "${sanitizer}" in asan|tsan|msan) ;; *) echo "Unsupported sanitizer: ${sanitizer}" >&2; exit 2 ;; esac
[ "$(uname -sm)" = "Linux x86_64" ] || { echo 'Only Linux amd64 is supported' >&2; exit 2; }
workspace=$(pwd)
toolchain=${ELIDE_TOOLCHAIN_HOME:?ELIDE_TOOLCHAIN_HOME}
triple=x86_64-unknown-linux-gnu
[ -f "${toolchain}/share/elide-toolchain/sanitizers/${sanitizer}.addon.json" ] || {
  echo "Install the matching toolchain add-on: elide-toolchain addon install sanitizer-${sanitizer}" >&2
  exit 2
}
mkdir -p "${out}"
out=$(cd "${out}" && pwd)
work=${out}/work-${sanitizer}
mkdir -p "${work}/bin"
# GraalPy freezes modules in a build-time JVM. That JVM must load an ordinary attach library,
# even while TRUFFLE_API is being rebuilt with the instrumented resource. In particular, an
# ordinary JVM cannot run under MSan. This bootstrap library never enters the add-on.
MX_ALT_OUTPUT_ROOT=${work}/bootstrap "${MX:?MX}" --java-home "${JAVA_HOME:?JAVA_HOME}" \
  -p "${workspace}/graal/truffle" build --build-logs=silent --dependencies com.oracle.truffle.attach \
  --alt-cc "${toolchain}/bin/${triple}-clang" --alt-cxx "${toolchain}/bin/${triple}-clang++"
bootstrap=$(find "${work}/bootstrap" -name libtruffleattach.so -type f)
if [ "$(wc -l <<< "${bootstrap}")" -ne 1 ] || [ ! -f "${bootstrap}" ]; then
  echo "Expected one bootstrap attach library, found: ${bootstrap}" >&2
  exit 1
fi
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-Dtruffle.attach.library=${bootstrap}"
# Never reuse ordinary or another sanitizer's build output, including CMake caches and libffi.
export MX_ALT_OUTPUT_ROOT=${work}/mxbuild
# Wrappers put the flags in the compiler command itself: libffi's configure and CMake projects
# sometimes replace CFLAGS. Both machine code and bitcode must carry instrumentation.
for driver in clang clang++; do
  {
    echo '#!/usr/bin/env bash'
    printf 'exec %q -shared-libsan -flto=thin -ffat-lto-objects -Wno-unused-command-line-argument -Wno-backend-plugin "$@"\n' \
      "${toolchain}/bin/${triple}-${sanitizer}-${driver}"
  } > "${work}/bin/${driver}"
  chmod +x "${work}/bin/${driver}"
done
export CC=${work}/bin/clang CXX=${work}/bin/clang++ AR=${toolchain}/bin/llvm-ar
export SVM_LTO_CC=${CC} SVM_LTO_CXX=${CXX} SVM_LTO_AR=${AR}
# Use the add-on's dependency search paths as well as its compilers. Override the compiler
# settings after including the toolchain so CMake also uses the wrappers' fat-LTO/shared flags.
cat > "${work}/toolchain.cmake" <<EOF
include("${toolchain}/share/elide-toolchain/cmake/${triple}-${sanitizer}.cmake")
set(CMAKE_C_COMPILER "${CC}")
set(CMAKE_CXX_COMPILER "${CXX}")
set(CMAKE_ASM_COMPILER "${CC}")
# mx passes absolute paths for generated headers and its instrumented libffi build, outside
# the toolchain sysroot. Search those paths after the sanitizer sysroot.
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
EOF
export EXTRA_CMAKE_ARGS="-DCMAKE_TOOLCHAIN_FILE=${work}/toolchain.cmake -DCMAKE_SKIP_RPATH=ON"
runtime_dir=$("${CC}" --print-runtime-dir)
export LD_LIBRARY_PATH=${runtime_dir}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}
cd "${workspace}/graal/vm"
mx_args=(--java-home "${JAVA_HOME:?JAVA_HOME}" --env ni-ce --dynamicimports /graalpython
  --multitarget=linux-amd64-glibc)
"${MX:?MX}" "${mx_args[@]}" build --build-logs=silent \
  --alt-cc "${CC}" --alt-cxx "${CXX}" --alt-ar "${AR}" \
  --dependencies SVM_HOSTED_NATIVE,TRUFFLE_API,TRUFFLE_NFI_LIBFFI,GRAALPYTHON_RESOURCES
paths=()
for dist in SVM_HOSTED_NATIVE TRUFFLE_API TRUFFLE_NFI_LIBFFI GRAALPYTHON_RESOURCES; do
  paths+=("$("${MX}" "${mx_args[@]}" paths "${dist}" | tail -1)")
done
python3 ../ci/elide/sanitizer-natives.py "${version}" "${sanitizer}" "${toolchain}" "${out}" "${paths[@]}"
../ci/elide/check-sanitizer-natives.sh "${sanitizer}" "${toolchain}" \
  "${out}/graalvm-natives-${version}-linux-amd64-sanitizer-${sanitizer}" "${work}/smoke"
# Publish only after both the artifact inspection and the runtime probes succeed.
name=graalvm-natives-${version}-linux-amd64-sanitizer-${sanitizer}
tar -C "${out}" -cJf "${out}/${name}.tar.xz" "${name}"
(cd "${out}" && sha256sum "${name}.tar.xz" > "${name}.tar.xz.sha256")
echo "archive=${out}/${name}.tar.xz"
