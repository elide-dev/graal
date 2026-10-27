# Building Shenandoah on macOS

This development branch supports Shenandoah in Native Image on macOS/AArch64 (Apple Silicon).
The default collector mode is `satb`.
Select `generational` at image build time to enable generational Shenandoah and its additional write barriers.

## Prerequisites

You need Xcode or the Xcode Command Line Tools, Python 3, and `mx` on your `PATH`.
Use the `mx` version specified in _common.json_ or a compatible newer version.
Keep the GraalVM repository and the companion GC sources as sibling checkouts:

```text
workspace/
  graal/          # simonis/GR-70306-upstream, with the macOS integration changes
  labs-openjdk/   # simonis/GR-77360-GenShen, with the Darwin native changes
```

Fetch the matching Labs JDK from the GraalVM repository root:

```shell
mx -p substratevm -y fetch-jdk --jdk-id labsjdk-ce-latest
```

Set `JAVA_HOME` to the printed JDK home, including _Contents/Home_ for a macOS JDK bundle.

## Build Native Image

From _graal/substratevm_, run:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc -c 6 build --build-logs=silent
```

This compiles the native libraries and includes both Shenandoah reference variants in the Native Image distribution.
The companion suite uses the Darwin OS sources and the Darwin/AArch64 object-copy assembly from OpenJDK tag `jdk-25.0.4+6`.
Run all `mx` commands sequentially in the shared workspace.

## Build and Run the Regression Test

From _graal/substratevm_, run the dedicated test command:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc shenandoah-gc-stress
```

The command compiles _src/native-image-tests/shenandoah/GCStress.java_, builds a separate generational Shenandoah executable, and runs it with a bounded heap and timeout.
The test validates retained object contents, linked object graphs, atomic reference updates, four concurrent mutator threads, and weak reference processing.
It throws an error if any check fails.
Pass additional Native Image options to select the mode or reference size, for example:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc shenandoah-gc-stress \
  -H:+UnlockExperimentalVMOptions -H:ShenandoahGCMode=satb \
  -H:-UseCompressedReferences -H:-UnlockExperimentalVMOptions
```

Use `-H:ShenandoahGCMode=passive` to exercise passive mode.
The command enables diagnostic runtime options, which passive mode requires.

For an application, use `mx native-image` with the same dynamic imports and components, `--gc=shenandoah`, and `-H:ShenandoahGCMode=generational` inside an experimental-option unlock block.

## Native Assertions

From _labs-openjdk_, build assertion-enabled static libraries:

```shell
SVM_GC_TARGETS='build_genshen_fastdebug_cr_a build_genshen_fastdebug_ur_a' \
  mx -c 6 build --build-logs=silent --dependencies hotspot
```

When building the executable, also pass these options inside the experimental-option unlock block:

```shell
-H:ShenandoahDebugLevel=fastdebug \
-H:CLibraryPath=/absolute/path/to/labs-openjdk/mxbuild/darwin-aarch64/hotspot/darwin-aarch64/default
```

Leave `SVM_GC_TARGETS` unset when rebuilding the complete distribution so all required product libraries are available.

## Native Unit Tests

The existing Shenandoah performance-data counters are unimplemented.
Images that enable performance-data support, including the native JUnit harness, must disable the counters at run time with `-XX:-UsePerfData`.
For example, run selected thread and process tests from _graal/substratevm_:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc native-unittest \
  com.oracle.svm.test.FinishedThreadTest com.oracle.svm.test.ForkJoinTest \
  com.oracle.svm.test.ProcessPropertiesTest \
  --build-args --gc=shenandoah -H:+UnlockExperimentalVMOptions \
  -H:ShenandoahGCMode=generational -H:-UnlockExperimentalVMOptions \
  --run-args -XX:-UsePerfData --verbose
```

## Scope

Validation used macOS 26.6.2 on Apple Silicon and Labs JDK `25.0.4.1.1+1-jvmci-25.4-b23`.
The stress test passed in all three modes with both reference encodings, and in generational mode with both fastdebug native libraries.
The collector supports one isolate per process.
Runtime class loading and Intel macOS are not supported by this integration.
The static GC archives link against the macOS C++ runtime; this configuration does not require a separate Shenandoah dynamic library or `DYLD_LIBRARY_PATH`.

## Runtime Metadata and Code

See [Shenandoah Runtime Metadata and Code](shenandoah-runtime.md) for the metaspace contract and isolated Crema, Ristretto, and Truffle regression commands.
