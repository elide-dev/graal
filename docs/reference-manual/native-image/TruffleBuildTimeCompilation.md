---
layout: docs
toc_group: native-image
link_title: Truffle Build-Time Compilation
permalink: /reference-manual/native-image/TruffleBuildTimeCompilation/
---
# Truffle Build-Time Compilation (AOT Tier)

Native Image can compile ahead-of-time (AOT) prepared shadow copies of shared Truffle call targets while building a native executable.
The compiled code, the AOT tier, runs from the first call at run time and hands over to the just-in-time (JIT) compiler once the call target warms up.
This lets a Truffle-based native executable skip interpretation for call targets that a language could prepare at build time, without giving up peak JIT performance for the rest of the application.

The AOT tier builds on [context pre-initialization](../../../truffle/docs/AOTOverview.md) and code sharing: it compiles shared call targets of a pre-initialized `Context` that were never executed at build time.
See the [Truffle AOT Tutorial](../../../truffle/docs/AOT.md#aot-tier) for what a language implementation must do to take part.

> Note: On the Java HotSpot VM the AOT tier is inert; it applies only when embedding Truffle languages in a native executable.

## Build-Time Options

Enable the AOT tier with the following system properties, passed to `native-image` at build time:

| Option | Kind | Purpose |
|---|---|---|
| `-Dpolyglot.image-build-time.PreinitializeCodeSharing=true` | build | Pre-initializes the context in a shared, multi-context sharing layer. Requires an unbound `Engine` at build time. |
| `-Dpolyglot.image-build-time.PreinitializeSources=<languageId>:<path>,...` | build | Parses, but never executes, a comma-separated list of sources during pre-initialization. |
| `-Dpolyglot.image-build-time.AOTCompile=true` | build | Selects and compiles AOT-preparable shared call targets at build time. Requires `PreinitializeCodeSharing`. |
| `-Dpolyglot.image-build-time.AOTCompileFilter=<glob>,...` | build | A glob over root names (`*` and `?` as wildcards, comma-separated alternatives) that selects which call targets are compiled. An empty value selects all roots. |
| `-Dpolyglot.image-build-time.AOTCompileBudget=<n>` | build | The maximum partial-evaluation graph size, in nodes, per compiled root. `0` means unlimited. |
| `engine.AOTTierThreshold` | run time | The number of calls and loop iterations after which a call target's AOT code is unlinked. `0`, the default, uses the value of `FirstTierCompilationThreshold`. |
| `engine.TraceAOT` | run time, internal | Traces `[aot] enter <name>` once per target, on its first call, and `[aot] unlink <name>: <reason>` each time the runtime unlinks a target's AOT code. |
| `-H:+TruffleAOTPrintSummary` | hosted | Prints a per-root build summary. |
| `-H:+TruffleAOTFailOnError` | hosted | Fails the build if a selected root was not compiled. |

For `PreinitializeSources`, every listed language must also be listed in `-Dpolyglot.image-build-time.PreinitializeContexts` and must override `TruffleLanguage.patchContext`, otherwise the build fails with an `IllegalArgumentException` naming the offending entry.
To reach a pre-parsed call target at run time, the application must parse a source that is equal to the pre-initialized one: same content and same name.
The source name recorded in the image is the file name without its build-time path, so no build-machine path is embedded in the native executable.

For example, build a native executable with the AOT tier enabled for a JavaScript context:

```shell
native-image \
  -Dpolyglot.image-build-time.PreinitializeContexts=js \
  -Dpolyglot.image-build-time.PreinitializeCodeSharing=true \
  -Dpolyglot.image-build-time.AOTCompile=true \
  -H:+TruffleAOTPrintSummary \
  -cp app.jar MainClass
```

## Sharing the Pre-Initialized Engine

AOT code is attached to the shared call targets of the pre-initialized engine.
To use AOT code, create contexts on one explicit `Engine` that adopts the pre-initialized engine and its shared layer:

```java
Engine engine = Engine.create();
try (Context context = Context.newBuilder().engine(engine).build()) {
    context.eval("js", "...");
}
```

A bound engine, for example one created implicitly by `Context.create()` without an explicit `Engine`, also adopts the pre-initialized engine and uses its AOT code, provided it is the first engine created in the process.
Only one engine per process can adopt the pre-initialized engine.
A context created on any other, separately created engine gets a fresh sharing layer with no shared code and no AOT code.
A context with incompatible options, or created with `engine.UsePreInitializedContext=false`, likewise gets a fresh layer without AOT code.
A context created while the pre-initialized context is still being patched also gets its own layer, to avoid a deadlock that would otherwise occur if it waited for the patch to finish.

## Diagnostics

Pass `-H:+TruffleAOTPrintSummary` to print one line per selected root during the build, in a deterministic order, for example:

```
[truffle-aot] compiled myFunction nodes=42 assumptions=1 as com.example.MyFunction_0 codeSize=512
[truffle-aot] skipped otherFunction: filtered
[truffle-aot] kept thirdFunction: assumption invalidated after heap snapshot: someAssumption
```

A `skipped` line reports a root that was not compiled and why, for example `filtered` (excluded by `AOTCompileFilter`), `executed at build time` (the root ran during pre-initialization, so its live AST is already specialized), a partial-evaluation bailout, an over-budget graph, or an unsupported node.
A `kept` line reports a root whose backing assumption was invalidated only after the build's heap snapshot was already sealed; the image keeps the compiled code and the assumption as still valid, and this is not a build failure.

Pass `-H:+TruffleAOTFailOnError` to turn every remaining skipped root into a build failure, printed as one line per root:

```
Truffle AOT tier: root 'myFunction' was not compiled: <reason>
```

`-H:+TruffleAOTFailOnError` never counts roots that were filtered out or executed at build time as failures, since those are excluded by design.
It also enables `-H:+VerifyDeoptSourceEntryPoints`, which fails the build if the AOT tier code is missing a deoptimization entry point that it needs.

At run time, `engine.TraceAOT` prints a line the first time each target's AOT code is entered, and a line each time the runtime unlinks a call target's AOT code, for example when the `engine.AOTTierThreshold` is reached or an assumption is invalidated:

```
[aot] enter myFunction
[aot] unlink myFunction: AOT tier threshold reached
```

## Limitations

Instruments also wrap the shadow ASTs that back the AOT tier.
Since shadows are loaded roots like any other, a tool that enumerates loaded roots can observe each AOT-compiled root twice: once as the shadow and once as the live root node.

A native executable built without `-Dpolyglot.image-build-time.AOTCompile=true` is unaffected by the AOT tier, with one exception: a pre-existing bug fix for `Assumption`s held in static final fields and read by run-time partial evaluation, which previously could be folded at build time and lose the JIT compiler's dependency on them, applies to every Truffle native image, whether or not it uses the AOT tier.
