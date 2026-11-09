#!/bin/sh
set -ex
export JAVA_HOME=/home/sam/.mx/jdks/labsjdk-ce-latest-jvmci-25.6-b27_amd64
export MX_ALT_OUTPUT_ROOT=/build/caches/mx/value-jfr-native-250502
export SVM_GC_TARGETS=build_product_cr_a
cd /build/worktrees/labs-openjdk/value-jfr-native-250502
mx -c 8 --multitarget=linux-amd64-glibc build --dependencies=SVM_G1GC --build-logs=silent
