# Shenandoah Runtime Metadata and Code

Shenandoah runtime support targets the Native Image G1 contract for metaspace and installed code.
Metaspace objects occupy a bounded reservation, remain live, and never move.
Their outgoing references participate in garbage collection.
Class unloading and multiple isolates are outside this contract.

## Build Matching Libraries

You must build the Java implementation and the native GC libraries from matching runtime-parity branches.
The Shenandoah library interface version is `250502`.
The version check rejects mismatched libraries at startup.

Use the platform setup in [Linux](shenandoah-linux.md) or [macOS](shenandoah-macos.md).
Run the following command from _substratevm_ to include the Truffle test dependencies:

```shell
mx --dynamicimports substratevm-gcs \
  --components=ni,nju,svmgc,tflsm,tfla -c 6 build --build-logs=silent
```

Runtime compilation must use the application isolate.
Use `-H:-SupportCompileInIsolates` when building a Truffle executable.
Ristretto configures this option when you enable `-H:+GraalJITCompileAtRuntime`.
Performance-data counters still require `-XX:-UsePerfData` at run time.

## Metaspace Contract

You configure the reservation at build time with `-H:MaxMetaspaceSize=<size>m` and enable class loading with `-H:+RuntimeClassLoading`.
The reservation is separate from the Java heap limit.
Java heap memory reports exclude permanent reservations from capacity and use the collected generations for used-byte counts.
Memory is committed as allocation reaches each region.
Allocation initializes object headers and array lengths before publishing the region top.
Objects larger than one region use consecutive permanent regions.
Exhaustion returns the standard metaspace `OutOfMemoryError` without reclaiming existing metadata.

Permanent metadata regions are excluded from collection sets and compaction.
The collector scans their outgoing references alongside open image-heap roots.
Keeping a reference object in metaspace does not give its referent strong reachability.

## Installed Code Contract

Installed code uses the shared Native Image `CodeInfo` lifetime and tether mechanism.
Active compiled frames retain their tethers.
Global and old-generation marking conditionally retain other code and invalidates code whose weak dependencies are unreachable.
Young-only collections retain all code references.
Image-heap and metaspace objects always satisfy code-reference liveness checks.

Runtime registration preserves the concurrent marking snapshot and canonicalizes references when forwarding is active.
Before concurrent evacuation resumes mutators, the collector evacuates and updates embedded code constants at a safepoint.
Full and degenerated collections update code references during their root-relocation phases.
Code cleanup publishes the current thread barrier state before calling Java and runs before mutators resume after a collection that condemns installed code.
Runtime-compiled barrier calls use indirect targets when required by the compiler backend.

## Regression Commands

Run each command from _substratevm_, using the dynamic imports and components shown above:

```shell
mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc,tflsm,tfla \
  shenandoah-metaspace-test

mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc,tflsm,tfla \
  shenandoah-ristretto-test

mx --dynamicimports substratevm-gcs --components=ni,nju,svmgc,tflsm,tfla \
  shenandoah-truffle-test
```

The commands build separate test executables.
The metaspace test loads bytecode from outside the image classpath, checks reflection and interface dispatch, defines classes on another thread, and collects while a runtime-defined frame is active.
A second executable checks ordinary metadata objects, arrays spanning multiple regions, membership, outgoing references, oversized requests, and reservation exhaustion.

The Ristretto test requires a code-install trace for the runtime-loaded payload.
An interpreter-only run fails validation.
The Truffle test requires compiled targets, checks embedded constants across collection, invalidates and recompiles targets, and checks weak reachability after a compiled target is discarded.
The Truffle probe registers a test language through annotation processing.

The default test mode is passive.
To test concurrent modes, append one of the following option blocks:

```shell
-H:+UnlockExperimentalVMOptions -H:ShenandoahGCMode=satb -H:-UnlockExperimentalVMOptions
-H:+UnlockExperimentalVMOptions -H:ShenandoahGCMode=generational -H:-UnlockExperimentalVMOptions
```

Append `-H:-UseCompressedReferences` to test uncompressed references.
For native assertions, build the matching `genshen` fastdebug libraries and pass `-H:ShenandoahDebugLevel=fastdebug` and `-H:CLibraryPath=<native-library-directory>` inside an experimental-option unlock block.
Run builds and tests sequentially because the suites share build outputs.
