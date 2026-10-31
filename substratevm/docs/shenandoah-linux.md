# Building Shenandoah on Linux

This development branch integrates Shenandoah with Native Image on Linux/AMD64 and Linux/AArch64.
Generational Shenandoah uses the same native library as the other Shenandoah modes, but requires different barriers in the generated executable.
Select the mode at build time with `-H:ShenandoahGCMode=generational`.
The default mode is `satb`.

## Prerequisites

Use a Linux environment matching your target architecture, with Python 3, Git, a C++ compiler, GNU Make, Ninja, and zlib development headers installed.
Use the `mx` version specified by _common.json_ or a compatible newer version.
Fetch the branch's Labs JDK from the GraalVM repository root:

```shell
mx -p substratevm -y fetch-jdk --jdk-id labsjdk-ce-latest
```

Set `JAVA_HOME` to the path printed by that command.
The companion _labs-openjdk_ checkout contains reduced HotSpot GC sources, not the JDK used to run the build.

Keep the two repositories as siblings:

```text
workspace/
  graal/          # simonis/GR-70306-upstream
  labs-openjdk/   # simonis/GR-77360-GenShen
```

## Build Native Image and the GC Libraries

From _graal/substratevm_, run:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc -c 4 build --build-logs=silent
```

The dynamic import discovers the `substratevm-gcs` suite in the sibling checkout.
The `svmgc` component includes the native GC libraries in the resulting distribution.
The `nju` component supplies native unit test support.
Run build and checkstyle commands sequentially in this workspace.

For a standalone C++ compilation check, run this command from _labs-openjdk_:

```shell
mx -c 4 build --build-logs=silent --dependencies hotspot
```

By default, this builds product static libraries for G1 and Shenandoah, with compressed and uncompressed references.
The current build uses `mx` and Ninja; the older Makefile instructions do not apply.
Custom targets include `build_genshen_debug_ur_so` and `build_genshen_product_ur_a`, selected through `SVM_GC_TARGETS`.
Leave that variable unset for the distribution build above so all required product libraries are available.

## Build an Executable

Compile your application with the selected JDK, then run this command from _graal/substratevm_:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc native-image \
  --gc=shenandoah -H:+UnlockExperimentalVMOptions \
  -H:ShenandoahGCMode=generational -H:-UnlockExperimentalVMOptions \
  -cp /absolute/path/to/classes -o /absolute/path/to/application Main
```

Replace the class path, output path, and main class with your application's values.
Use the same dynamic imports and components for build and execution commands so `mx` selects the same distribution.
The packaged product libraries are static archives, so this configuration does not require `LD_LIBRARY_PATH`.

## Test Truffle Runtime Compilation

After building the Truffle test projects, run the following command from _graal/substratevm_ with the same dynamic imports and components used for your distribution:

```shell
mx shenandoah-truffle-test -H:CLibraryPath=/absolute/path/to/native-gc-libraries
```

The test builds a Shenandoah executable, runs a SimpleLanguage guest with synchronous compilation, and verifies that guest code was compiled.
It also executes compiled functions across forced collections to check runtime code roots.
Use companion collector libraries that include runtime code registration, relocation, and unloading support; older libraries contain unimplemented entry points for these operations.
On AMD64, it also verifies that enabling `MemoryMaskingAndFencing` is rejected without changing the option value.
Shenandoah does not support this mitigation or isolated compilation.
The test command disables isolated compilation and the unimplemented Shenandoah performance counters explicitly.

## Current Limitations

- The native GC supports one isolate per process.
- Runtime class loading is not supported by the companion Shenandoah implementation.
  Its metaspace allocation and statistics entry points are not implemented.

- Shenandoah performance counters are not implemented.
  If you include JVMStat monitoring, disable those counters with `-XX:-UsePerfData` at run time.
