#!/usr/bin/env bash
# Checks that a distribution distrusts the eMudhra intermediate CAs of CVE-2026-91403, on the JVM and
# in a native image built with it, and still trusts their roots (CheckBlockedCerts.java).
#
#   ci/elide/check-blocked-certs.sh <home> <work-dir>
set -euo pipefail

HOME_DIR=${1:?home}
WORK=${2:?work-dir}
CHECK="$(cd "$(dirname "$0")" && pwd)/CheckBlockedCerts.java"

mkdir -p "${WORK}"
cd "${WORK}"
"${HOME_DIR}/bin/java" "${CHECK}"
"${HOME_DIR}/bin/javac" -d . "${CHECK}"
# The image builder reads lib/security/blocked.certs at build time.
"${HOME_DIR}/bin/native-image" -cp . -o check-blocked-certs CheckBlockedCerts > check-blocked-certs.log 2>&1 ||
  { cat check-blocked-certs.log; echo "check-blocked-certs: build failed" >&2; exit 1; }
./check-blocked-certs
