#!/bin/sh
set -ex
ART=/build/caches/graal-pipeline/value-four/reviews/jfr-second-artifacts
CACHE=/build/caches/mx/graal-value-jfr
JDK=/home/sam/.mx/jdks/labsjdk-ce-latest-jvmci-25.6-b27_amd64
CP=$(find "$CACHE/substratevm/dists" "$CACHE/compiler/dists" "$CACHE/sdk/dists" -maxdepth 1 -name '*.jar' -printf '%p:' )
mkdir -p "$ART/classes" "$ART/native250502/include"
ln -sf /build/worktrees/labs-openjdk/value-jfr-native-250502/src/hotspot/svm/exports/g1GCStructs.h "$ART/native250502/include/g1GCStructs.h"
"$JDK/bin/javac" -cp "$CP" -processorpath "$CP" -processor jdk.graal.compiler.options.processor.OptionProcessor -d "$ART/classes" "$ART/G1ReviewOptions.java"
mkdir -p "$ART/classes/META-INF/services"
printf "review.G1ReviewOptions_OptionDescriptors\n" > "$ART/classes/META-INF/services/jdk.graal.compiler.options.OptionDescriptors"
"$JDK/bin/jar" cf "$ART/scaffold.jar" -C "$ART/classes" .
