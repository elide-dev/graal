#!/bin/sh
set -ex
ART=/build/caches/graal-pipeline/value-four/reviews/jfr-second-artifacts
CACHE=/build/caches/mx/graal-value-jfr
JDK=/home/sam/.mx/jdks/labsjdk-ce-latest-jvmci-25.6-b27_amd64
JUNIT=/home/sam/.mx/cache/JUNIT_a31b9950f929a7e5a600d89787ef40e42a8a8e2392e210d0c0f45b3572937670a18a524f1815508cd1152cd1eaa7275cb7430ba45c053be365c83c231bccd3f0/junit.jar
HAMCREST=/home/sam/.mx/cache/HAMCREST_e237ae735aac4fa5a7253ec693191f42ef7ddce384c11d29fbf605981c0be077d086757409acad53cb5b9e53d86a07cc428d459ff0f5b00d32a8cbbca390be49/hamcrest.jar
CP=$(find "$CACHE/substratevm/dists" "$CACHE/compiler/dists" "$CACHE/sdk/dists" -maxdepth 1 -name '*.jar' -printf '%p:' )
"$JDK/bin/javac" -proc:none -cp "$CP:$JUNIT:$HAMCREST" -d "$ART/classes" "$ART/JfrNativeReviewMain.java" /build/worktrees/graal/value-jfr-native-allocation/substratevm/src/com.oracle.svm.test/src/com/oracle/svm/test/jfr/TestNativeGCAllocationSampleEvent.java
"$JDK/bin/jar" cf "$ART/scaffold.jar" -C "$ART/classes" .
