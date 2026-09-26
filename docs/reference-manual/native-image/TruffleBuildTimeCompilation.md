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
| `-Dpolyglot.image-build-time.PreinitializeSources=<languageId>:<path>,...` | build | Parses, but never executes, a comma-separated list of sources during pre-initialization. A shorthand for [registering sources](#registering-sources). |
| `-Dpolyglot.image-build-time.AOTCompile=true` | build | Selects and compiles AOT-preparable shared call targets at build time. Requires `PreinitializeCodeSharing`. |
| `-Dpolyglot.image-build-time.AOTCompileFilter=<glob>,...` | build | A glob over root names (`*` and `?` as wildcards, comma-separated alternatives) that selects which call targets are compiled. An empty value selects all roots. |
| `-Dpolyglot.image-build-time.AOTCompileBudget=<n>` | build | The maximum partial-evaluation graph size, in nodes, per compiled root. `0` means unlimited. |
| `-Dpolyglot.image-build-time.AOTCompileTotalBudget=<n>` | build | The maximum total size, in AST nodes before partial evaluation, of all compiled roots together. Roots are kept by priority while they fit: those with more known call sites first, then smaller ones, then by name. The others are skipped as `over total budget`. `0` means unlimited. |
| `engine.AOTTierThreshold` | run time | The number of calls and loop iterations after which a call target's AOT code is unlinked. It is checked when the call target is called. `0`, the default, uses the value of `FirstTierCompilationThreshold`. |
| `engine.TraceAOT` | run time, internal | Traces the AOT code of call targets: when it is first entered, unlinked, and deoptimized. See [Diagnostics](#diagnostics). |
| `-H:+TruffleAOTPrintSummary` | hosted | Prints a per-root build summary. |
| `-H:+TruffleAOTFailOnError` | hosted | Fails the build if a selected root was not compiled, or exceeds a size limit below. |
| `-H:TruffleAOTMaxRootNodes=<n>` | hosted | Reports a compiled root with more than `<n>` nodes after partial evaluation. `0`, the default, means no limit. See [Size limits](#size-limits). |
| `-H:TruffleAOTMaxRootAllocations=<n>` | hosted | Reports a compiled root that allocates at more than `<n>` sites. `-1`, the default, means no limit. See [Size limits](#size-limits). |

For example, build a native executable with the AOT tier enabled for a JavaScript context:

```shell
native-image \
  -Dpolyglot.image-build-time.PreinitializeContexts=js \
  -Dpolyglot.image-build-time.PreinitializeCodeSharing=true \
  -Dpolyglot.image-build-time.AOTCompile=true \
  -H:+TruffleAOTPrintSummary \
  -cp app.jar MainClass
```

## Registering Sources

Context pre-initialization can parse, but never execute, sources of the application.
Their call targets are then shared call targets of the pre-initialized context, and with `AOTCompile` they are compiled at build time.
To reach such a call target at run time, the application must parse a source that is equal to the registered one.
Two sources are equal only if everything the source builder can set is equal: the characters, name, MIME type, path, URI, and flags.
So build a registered source exactly as the application builds it at run time.

Register sources with `org.graalvm.polyglot.PreinitializedSources` while the image is built, before the contexts are pre-initialized, for example from `Feature.duringSetup`:

```java
public final class MyFeature implements Feature {
    @Override
    public void duringSetup(DuringSetupAccess access) {
        PreinitializedSources.register(Source.newBuilder("js", moduleCode, "main.mjs").mimeType("application/javascript+module").buildLiteral());
    }
}
```

Registering is a hint.
A registered source is parsed only if the image pre-initializes its language, that is, the language is listed in `-Dpolyglot.image-build-time.PreinitializeContexts`.
Otherwise the source is skipped, so a library can register its sources from its own feature without failing the builds of images that do not pre-initialize its language.
If the language is pre-initialized but does not override `TruffleLanguage.patchContext`, the build fails.
Registering after the contexts were pre-initialized, or in a native executable at run time, throws an `IllegalStateException`.

Keep the following limits in mind:

* Only sources that the application parses, for example with `Context.eval` or `Context.parse`, can use a registered source. A source that a language creates itself, for example a module that its module loader loads for an `import`, is never equal to a registered source. Pre-initializing such sources needs support from the language.
* A source built from a `File` or a `URL` has the path or URI of the build machine. It is equal to a source of the native executable only if the executable builds its source from the same path or URI. Prefer a literal source with a name and a MIME type.
* On the Java HotSpot VM, setting `-Dpolyglot.image-build-time.PreinitializeContexts` pre-initializes the contexts when the polyglot implementation is loaded, to debug pre-initialization. Sources cannot be registered in this mode.

The `-Dpolyglot.image-build-time.PreinitializeSources=<languageId>:<path>,...` system property registers the sources read from the listed files.
Each source has the file name as its name, without the build-time path, and no MIME type, so no build-machine path is embedded in the native executable.
Unlike a registered source, every listed language must be listed in `PreinitializeContexts` and must override `TruffleLanguage.patchContext`, otherwise the build fails with an `IllegalArgumentException` naming the offending entry.
Because the property cannot express a MIME type, a JavaScript module (`application/javascript+module`) listed in it never matches a module that the application parses.
Register such sources with `PreinitializedSources` instead.

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
For a server that creates contexts from several threads, create the first context before serving requests, so that no request creates a context while the first one is patched.

A bound engine that adopts the pre-initialized engine runs its single context in the shared, multi-context layer.
It uses the AOT code, but the JIT compiler then compiles the context's call targets for multiple contexts, without the constant folding it applies to a single context.
For a short-running script, the AOT code usually outweighs this; for a long-running single context, measure both configurations.

## Diagnostics

Pass `-H:+TruffleAOTPrintSummary` to print one line per selected root during the build, in a deterministic order, for example:

```
[truffle-aot] compiled myFunction nodes=42 allocs=1 assumptions=1 as aot_17_myFunction codeSize=512
[truffle-aot] skipped otherFunction: filtered
[truffle-aot] kept thirdFunction nodes=30 allocs=0 assumptions=2 as aot_18_thirdFunction codeSize=384: assumption invalidated after heap snapshot: someAssumption
```

The name after `as` is the name of the compiled method in the native executable, `aot_<id>_<root name>`.
`nodes` is the size of the root's graph after partial evaluation, and `allocs` is the number of allocation sites in its code (see [Size limits](#size-limits)).
A `skipped` line reports a root that was not compiled and why, for example `filtered` (excluded by `AOTCompileFilter`), `over total budget` (left out by `AOTCompileTotalBudget`), `executed at build time` (the root ran during pre-initialization, so its live AST is already specialized), a partial-evaluation bailout, an over-budget graph, an unsupported node, or an exception thrown by the language while it prepared the root.
A `kept` line reports a root whose backing assumption was invalidated only after the build's heap snapshot was already sealed; the image keeps the compiled code and the assumption as still valid, and this is not a build failure.

Pass `-H:+TruffleAOTFailOnError` to turn every remaining skipped root into a build failure, printed as one line per root:

```
Truffle AOT tier: root 'myFunction' was not compiled: <reason>
```

`-H:+TruffleAOTFailOnError` never counts roots that were filtered out, left out by the total budget, or executed at build time as failures, since those are excluded by design.
It also enables `-H:+VerifyDeoptSourceEntryPoints`, which fails the build if the AOT tier code is missing a deoptimization entry point that it needs.

A few checks fail the build even without `-H:+TruffleAOTFailOnError`, because they detect an internal error of the AOT tier rather than a problem of the language: a deoptimization target of AOT code that is not reachable, and a compilation of AOT code that fails after the analysis.
The error names the `aot_<id>_<root name>` method.
To build the native executable anyway, exclude that root with `AOTCompileFilter`, and report the error.

### Size Limits

A root whose code grows unexpectedly large or allocates unexpectedly, for example because a helper was inlined into it, costs image size and run-time performance without failing the build.
To catch this at build time, set `-H:TruffleAOTMaxRootNodes` and `-H:TruffleAOTMaxRootAllocations`.
Each compiled root above a limit is reported as a warning, or with `-H:+TruffleAOTFailOnError`, as a build failure:

```
Truffle AOT tier: root 'myFunction' has 312 nodes, more than -H:TruffleAOTMaxRootNodes=250
Truffle AOT tier: root 'otherFunction' allocates at 3 sites, more than -H:TruffleAOTMaxRootAllocations=2: Box(int), New(MyObject)x2
```

An allocation site is one of these in the compiled code, listed by type:

- `New(<class>)` and `NewArray(<element type>)`: an explicit allocation.
- `Box(<kind>)`: a boxed primitive, for example the result of a root that returns `Object`. Boxing a `boolean` or a `byte` returns a cached box, so it is not counted.
- `Materialize(<type>)`: an object that escape analysis removed on some paths but has to allocate on others, for example an exception that is thrown or the arguments array of a call.

Allocations inside methods that the compiled code calls, rather than inlines, are not counted.
For example, `TruffleString` operations are always called.

At run time, `engine.TraceAOT` prints a line the first time each target's AOT code is entered, a line each time the runtime unlinks a call target's AOT code, for example when the `engine.AOTTierThreshold` is reached or an assumption is invalidated, and a line each time invalidating the code deoptimizes its frames:

```
[aot] enter myFunction
[aot] unlink myFunction: AOT tier threshold reached
[aot] deoptimize myFunction: <reason>
```

## Behavior at Run Time

Reaching `engine.AOTTierThreshold` unlinks the AOT code by clearing a field of the call target; it does not stop the threads.
Activations that are running the AOT code finish in it, and the next call runs in the interpreter.
Only an invalidation, for example of an assumption that the AOT code depends on, deoptimizes the code's frames, at a safepoint.

`engine.Compilation=false` disables the JIT compiler, not the AOT tier: AOT code still runs until it is unlinked.

## Limitations

The threshold is checked only when a call target is called, and AOT code has no on-stack replacement (OSR).
A single long-running activation, for example a hot loop at the top level of a script, runs in the AOT code for its whole duration, and the JIT compiler never compiles it.
A call target that is called again after reaching the threshold runs in the interpreter and warms up to the JIT compiler as usual.

In frames of AOT code, the call target of a frame (`FrameInstance.getCallTarget()`), the root node of its nodes (`Node.getRootNode()`), and the target of a guest stack trace element (`TruffleStackTraceElement.getTarget()`) are those of the shadow, not of the live call target.
Like a split call target, the shadow is a different object with equal source sections.

Instruments also wrap the shadow ASTs that back the AOT tier.
Since shadows are loaded roots like any other, a tool that enumerates loaded roots can observe each AOT-compiled root twice: once as the shadow and once as the live root node.

A native executable built without `-Dpolyglot.image-build-time.AOTCompile=true` is unaffected by the AOT tier, with one exception: a pre-existing bug fix for `Assumption`s held in static final fields and read by run-time partial evaluation, which previously could be folded at build time and lose the JIT compiler's dependency on them, applies to every Truffle native image, whether or not it uses the AOT tier.
