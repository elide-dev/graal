#!/usr/bin/env bash
# Builds Elide's GraalVM distribution and packages it as an archive.
#
#   ci/elide/build-dist.sh <version> <platform> <out-dir>
#
# Run from the workspace that holds the graal, graaljs and graalpython checkouts side by side.
# Needs JAVA_HOME (labsjdk), MX (the mx launcher), and DIST_COMPONENTS and DIST_NATIVE_IMAGES (the
# GraalVM components and images the distribution holds; see the release workflow). On Windows, the workflow builds in its own
# step, with the MSVC environment, and calls this script with --package-only and GVM_HOME set.
set -euo pipefail

VERSION=${1:?version}
PLATFORM=${2:?platform}
OUT=${3:?out-dir}
PACKAGE_ONLY=${4:-}
WORKSPACE=$(pwd)
MX_ARGS=(--java-home "${JAVA_HOME}" --env ni-ce "--components=${DIST_COMPONENTS:?DIST_COMPONENTS}"
  "--native-images=${DIST_NATIVE_IMAGES:?DIST_NATIVE_IMAGES}" --non-rebuildable-images=lib:jvmcicompiler)
NAME="graalvm-ce-${VERSION}-${PLATFORM}"

cd "${WORKSPACE}/graal/vm"
if [ "${PACKAGE_ONLY}" != "--package-only" ]; then
  "${MX}" "${MX_ARGS[@]}" build
fi
# GVM_HOME, if set, names the built home instead of asking mx (the Windows step records it).
HOME_DIR=${GVM_HOME:-$("${MX}" "${MX_ARGS[@]}" graalvm-home | tail -1)}
HOME_DIR=$(echo "${HOME_DIR}" | tr '\\' '/')
[ -d "${HOME_DIR}" ] || { echo "no GraalVM home at ${HOME_DIR}" >&2; exit 1; }

# On macOS, the home is <bundle>/Contents/Home; the archive holds the bundle, as Oracle's do.
ROOT=${HOME_DIR%/Contents/Home}
mkdir -p "${OUT}"
STAGE=$(mktemp -d)
cp -R "${ROOT}" "${STAGE}/${NAME}"

case "${PLATFORM}" in
  windows-*)
    ARCHIVE="${NAME}.zip"
    # Windows' own bsdtar writes zip archives; Git Bash's GNU tar cannot.
    BSDTAR="$(cygpath -u "${SYSTEMROOT:-C:\\Windows}")/System32/tar.exe"
    (cd "${STAGE}" && "${BSDTAR}" -a -c -f "${ARCHIVE}" "${NAME}")
    ;;
  *)
    ARCHIVE="${NAME}.tar.gz"
    tar -C "${STAGE}" -czf "${STAGE}/${ARCHIVE}" "${NAME}"
    ;;
esac
mv "${STAGE}/${ARCHIVE}" "${OUT}/"
(cd "${OUT}" && { sha256sum "${ARCHIVE}" 2>/dev/null || shasum -a 256 "${ARCHIVE}"; } > "${ARCHIVE}.sha256")
rm -rf "${STAGE}"
echo "home=${HOME_DIR}"
echo "archive=${OUT}/${ARCHIVE}"
