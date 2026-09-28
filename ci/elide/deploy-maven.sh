#!/usr/bin/env bash
# Deploys Elide's GraalVM Maven artifacts into a local repository directory.
#
#   ci/elide/deploy-maven.sh <version> <repo-dir>
#
# Run from the workspace that holds the graal, graaljs and graalpython checkouts side by side,
# after ci/elide/build-dist.sh built them. Needs JAVA_HOME (labsjdk) and MX (the mx launcher).
set -euo pipefail

VERSION=${1:?version}
REPO=${2:?repo-dir}
WORKSPACE=$(pwd)
MX_ARGS=(--java-home "${JAVA_HOME}" --env ni-ce --dynamicimports /graal-js,/graalpython,/wasm)
LICENSES=GPLv2-CPE,UPL,MIT,BSD-new,Apache-2.0,ICU,PSF-License
SVM_DISTS=SVM,POINTSTO,OBJECTFILE,SVM_DRIVER,NATIVE_IMAGE_BASE,LIBRARY_SUPPORT,SVM_SHARED,SVM_GUEST_STAGING,SVM_CAPNPROTO_RUNTIME,TRUFFLE_RUNTIME_SVM,SVM_CONFIGURE
# Upstream release whose platform natives replace ours; their sources are unchanged in the fork.
UPSTREAM_TRUFFLE=${UPSTREAM_TRUFFLE:-25.4.4.1.1}

mkdir -p "${REPO}"
REPO=$(cd "${REPO}" && pwd)
(cd "${WORKSPACE}/graal/vm" && "${MX}" "${MX_ARGS[@]}" maven-deploy --all-suites --version-string "${VERSION}" --licenses "${LICENSES}" local "file://${REPO}")
(cd "${WORKSPACE}/graal/substratevm" && "${MX}" --java-home "${JAVA_HOME}" maven-deploy --only "${SVM_DISTS}" --version-string "${VERSION}" --licenses "${LICENSES}" local "file://${REPO}")

# Replaces the native resources under <prefix> in <jar> with those of the upstream artifact, which
# carries them for every platform, and rehashes the jar.
repackage_natives() {
  local jar=$1 artifact=$2 prefix=$3
  local up
  up=$(mktemp)
  local url="https://repo1.maven.org/maven2/org/graalvm/truffle/${artifact}/${UPSTREAM_TRUFFLE}/${artifact}-${UPSTREAM_TRUFFLE}.jar"
  curl -sSfL -o "${up}" "${url}"
  [ "$(curl -sSfL "${url}.sha1")" = "$(sha1sum "${up}" | cut -d' ' -f1)" ] || { echo "checksum mismatch for ${url}" >&2; exit 1; }
  python3 - "${jar}" "${up}" "${prefix}" <<'EOF'
import sys, zipfile, os
ours, up, prefix = sys.argv[1:]
tmp = ours + '.tmp'
zo, zu = zipfile.ZipFile(ours), zipfile.ZipFile(up)
with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as w:
    for i in zo.infolist():
        if not i.filename.startswith(prefix):
            w.writestr(i, zo.read(i.filename))
    n = 0
    for i in zu.infolist():
        if i.filename.startswith(prefix):
            w.writestr(i, zu.read(i.filename)); n += 1
os.replace(tmp, ours)
print(f'  {os.path.basename(ours)}: {n} upstream native entries')
EOF
  rm -f "${up}"
  (cd "$(dirname "${jar}")" && md5sum "$(basename "${jar}")" | cut -d' ' -f1 > "$(basename "${jar}").md5" && sha1sum "$(basename "${jar}")" | cut -d' ' -f1 > "$(basename "${jar}").sha1")
}

repackage_natives "${REPO}/org/graalvm/truffle/truffle-api/${VERSION}/truffle-api-${VERSION}.jar" truffle-api META-INF/resources/engine/libtruffleattach/
repackage_natives "${REPO}/org/graalvm/truffle/truffle-nfi-libffi/${VERSION}/truffle-nfi-libffi-${VERSION}.jar" truffle-nfi-libffi META-INF/resources/nfi-native/libnfi/
echo "deployed $(find "${REPO}" -name '*.pom' | wc -l) artifacts to ${REPO}"
