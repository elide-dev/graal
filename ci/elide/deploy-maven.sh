#!/usr/bin/env bash
# Deploys Elide's GraalVM Maven artifacts into a local repository directory.
#
#   ci/elide/deploy-maven.sh <version> <repo-dir>
#
# Run from the workspace that holds the graal, graaljs and graalpython checkouts side by side,
# after the release workflow's languages build built them. Needs JAVA_HOME (labsjdk) and MX (the
# mx launcher).
# The jars carry this platform's natives only; the publish job merges in the other platforms'
# (ci/elide/natives.py).
set -euo pipefail

VERSION=${1:?version}
REPO=${2:?repo-dir}
WORKSPACE=$(pwd)
MX_ARGS=(--java-home "${JAVA_HOME}" --env ni-ce --dynamicimports "${LANGUAGE_IMPORTS:-/graal-js,/graalpython,/wasm}")
LICENSES=GPLv2-CPE,UPL,MIT,BSD-new,Apache-2.0,ICU,PSF-License
SVM_DISTS=SVM,POINTSTO,OBJECTFILE,SVM_DRIVER,NATIVE_IMAGE_BASE,LIBRARY_SUPPORT,SVM_SHARED,SVM_GUEST_STAGING,SVM_CAPNPROTO_RUNTIME,TRUFFLE_RUNTIME_SVM,SVM_CONFIGURE

mkdir -p "${REPO}"
REPO=$(cd "${REPO}" && pwd)
(cd "${WORKSPACE}/graal/vm" && "${MX}" "${MX_ARGS[@]}" maven-deploy --all-suites --version-string "${VERSION}" --licenses "${LICENSES}" local "file://${REPO}")
(cd "${WORKSPACE}/graal/substratevm" && "${MX}" --java-home "${JAVA_HOME}" maven-deploy --only "${SVM_DISTS}" --version-string "${VERSION}" --licenses "${LICENSES}" local "file://${REPO}")

echo "deployed $(find "${REPO}" -name '*.pom' | wc -l) artifacts to ${REPO}"
