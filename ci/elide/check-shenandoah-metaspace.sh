#!/usr/bin/env bash
# Build and run the runtime class-loading regression against a packaged distribution.
# Usage: check-shenandoah-metaspace.sh <graalvm-home> <work-dir> [native-image options...]
set -euo pipefail

graalvm_home=${1:?graalvm-home}
work=${2:?work-dir}
shift 2
sources=$(cd "$(dirname "$0")/../../substratevm/src/native-image-tests/shenandoah" && pwd)
mkdir -p "${work}/classes" "${work}/payload"
work=$(cd "${work}" && pwd)
"${graalvm_home}/bin/javac" -d "${work}/classes" "${sources}/RuntimeMetaspaceProbe.java"
# The payload must be absent from the image classpath so it is defined at run time.
"${graalvm_home}/bin/javac" -d "${work}/payload" "${sources}/RuntimeLoadedPayload.java"
"${graalvm_home}/bin/native-image" --gc=shenandoah -H:+UnlockExperimentalVMOptions \
  -H:+RuntimeClassLoading -H:MaxMetaspaceSize=16m -H:ShenandoahGCMode=generational \
  "$@" -cp "${work}/classes" RuntimeMetaspaceProbe -o "${work}/metaspace-probe"
"${work}/metaspace-probe" -Xms32m -Xmx64m -XX:-UsePerfData "${work}/payload/RuntimeLoadedPayload.class"
