---
layout: docs
toc_group: native-image
link_title: Virtual Threads with Runtime Compilation
permalink: /reference-manual/native-image/VirtualThreadsWithRuntimeCompilation/
---
# Virtual Threads with Runtime Compilation

Native Image implements virtual threads with continuations: when a virtual thread blocks, its stack frames are copied into the heap and its carrier thread is free to run other virtual threads.
By default, continuations are disabled in native executables that compile code at run time, for example, executables that embed a Graal Language with the Truffle JIT compiler.
In such executables, each virtual thread is backed by its own platform thread.

The expert option `-H:+VMContinuationsWithRuntimeCompilation` enables continuations together with runtime compilation.
Then virtual threads can block inside code that was compiled at run time, and many virtual threads can share a few carrier threads.

## Enabling the Option

The option requires the serial garbage collector (the default) and lazy deoptimization (also the default).
It is not supported with the G1 garbage collector (`--gc=G1`) or the LLVM backend.

To build a native executable with continuation-backed virtual threads and runtime compilation, run:

```shell
native-image -H:+UnlockExperimentalVMOptions -H:+VMContinuationsWithRuntimeCompilation -H:-UnlockExperimentalVMOptions ...
```

## Behavior

A blocked virtual thread can have frames of code that was compiled at run time.
Native Image keeps that code alive for as long as the frames are stored in the heap.

If the code of a blocked virtual thread is invalidated, for example, because a speculation failed, its frames cannot be deoptimized while they are in the heap.
Instead, they are deoptimized when the virtual thread continues.
Code that was only replaced by a newer compilation, for example, after Truffle tier-up, keeps running until its frames return.

A virtual thread cannot be unmounted while its stack has a frame that was eagerly deoptimized.
Such a frame is created when a caller frame is accessed in write or materialize mode, for example, with the Truffle `FrameInstance.getFrame(FrameAccess.MATERIALIZE)` method, which some tools and languages use.
When such a virtual thread blocks, it stays pinned to its carrier thread, as if it were in a native frame, until the deoptimized frame returns.
A pinned virtual thread blocks its carrier thread, so many pinned virtual threads can exhaust the carriers of the default scheduler.

When a polyglot context is used on a virtual thread, the polyglot engine logs a warning about this limitation.
To disable the warning, use the `--engine.WarnVirtualThreadSupport=false` option or the `-Dpolyglot.engine.WarnVirtualThreadSupport=false` system property.

## Limitations

- Mount and unmount events of virtual threads are not reported to debuggers and other tools, as with any other native executable.
- The option can be combined with [JDWP support](JDWP.md) (`-H:+JDWP`), but the debugger does not track virtual threads across mounts.

### Related Documentation

- [Java Debug Wire Protocol (JDWP) with Native Image](JDWP.md)
- [Truffle Ahead-of-Time Overview](../../../truffle/docs/AOTOverview.md)
