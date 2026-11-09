#!/bin/sh
set -ex
ulimit -c 0
ART=/build/caches/graal-pipeline/value-four/reviews/jfr-second-artifacts
cd "$ART"
variant=$1
shift
timeout 180 "$ART/jfr-$variant" -Xmx256m -Xms32m "$@"
