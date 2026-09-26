# Truffle AOT Tier: Build-Time Compilation of Shared Guest Code in Native Image

- **Status:** design approved in conversation 2026-09-24; pending written-spec review
- **Repos / branches:** `graal`, `graaljs`, `graalpython`, each on `elide-25.4.x-aot-tier` branched from `elide-25.4.x`
- **Proving target:** GraalJS. GraalPy follows with its own prerequisite (see [GraalPy outlook](#graalpy-outlook-pr-3)).

## 1. Summary

Compile guest code at native-image build time with the real Truffle partial evaluator, as part of points-to analysis, and link the resulting machine code into the image as a new **AOT tier** of `OptimizedCallTarget`.

- AOT code runs from the first call, with no training runs and no user workload.
- It is attached to call targets of a **shared, pre-initialized sharing layer**, so every context on the engine uses it: one-shot scripts and many-context serving alike.
- It is compiled from an **AOT-prepared shadow copy** of each root (`@GenerateAOT` / `RootNode.prepareForAOT`). The live AST stays pristine, so the runtime JIT still reaches today's peak performance.
- AOT code counts calls and loops. Past a threshold it unlinks itself and the target enters the normal interpreter → first-tier → last-tier lifecycle.
- Invalidation deoptimizes AOT frames and hands off the same way.

A spike (`docs/superpowers/prototypes/build-time-pe/` on `elide-25.4.x-ephemera`, untracked) showed the core idea with a toy language and a hand-written partial evaluator:

- about 10× over the in-image interpreter,
- parity with warmed-up HotSpot Truffle JIT on `fib`/`tak`,
- zero warmup.

This design replaces every shortcut the spike took.

## 2. Goals and non-goals

### Goals

1. Guest code runs AOT-compiled from call 1 in the Elide native binary. Nothing is required from users: no training, no profiles.
2. Peak performance is unchanged from today's runtime JIT (the JIT never sees AOT-prepared node state).
3. AOT code is shared by all contexts on the engine that adopts the pre-initialized engine.
4. Observable behavior is identical to the interpreter/JIT path, including deoptimization and invalidation.
5. Scope order:
   - (a) runtime internals, proven first on a small GraalJS slice;
   - (b) user application code later, through the same mechanism and a separate spec.

### Non-goals (this delivery)

- OSR call targets. Loops get OSR at run time only outside AOT code: AOT code has no OSR, and its threshold is checked on call entry only, so a long-running activation stays in AOT code (see the implementation notes of 2026-09-25)
- polyglot isolates / `IsolateAwareTruffleCompiler`
- layered images (`TruffleBaseFeature` is `DisallowLayered`)
- single-context specialized variants
- shape/property caches or call-site inline caches inside AOT code (generic paths only)
- user-code packaging tooling
- the HotSpot mode (the AOT tier stays inert there)

## 3. Key decisions and rationale

| Decision | Rationale |
|---|---|
| No training runs; state comes from `@GenerateAOT` / `prepareForAOT` | Seamless adoption: users supply nothing. |
| Prepare a **shadow** root (`cloneForAOT()`), never the live root | AOT preparation is sticky: DSL `replaces`, generic cache modes, frame-slot kinds. On the live AST it would permanently degrade the runtime JIT's peak. On a split-like clone it can't. |
| AOT code is its own tier with a threshold hand-off | Truffle's feedback *is* the AST state, so AOT code cannot collect feedback. Hot functions briefly run in the interpreter on the pristine AST to collect real specializations, then the JIT compiles normally. |
| Compile shared call targets of a SHARED pre-init layer in multi-context mode | Code sharing is a hard requirement. Multi-context code has no context-specific constants and serves all contexts. |
| Real Truffle PE inside the builder, during analysis | The only option where analysis and the AOT compiler see interpreter and guest code together, and the only one that supports every Truffle feature the languages rely on. |
| Deoptimizable image code, generalizing the `@DeoptTest` machinery | Truffle PE output is full of deopts. Correct invalidation requires deoptimizing image frames into existing `DEOPT_TARGET` variants. |
| GraalJS first | JS roots are AST roots (`@GenerateAOT` works today) and `cloneUninitialized` is implemented broadly. GraalPy's Python code is Bytecode DSL, which rejects `@GenerateAOT` (`BytecodeDSLParser.java:286`). |

Rejected alternatives:

- **Persisting runtime-JIT code and heap and reloading it.** This re-implements Oracle GraalVM's auxiliary engine cache, gives no joint optimization, and needs a second artifact.
- **Layered images.** Truffle can't be layered today. This remains a possible future carrier for per-app user code.

## 4. Architecture overview

### Build time (one `native-image` invocation)

```
pre-init with code sharing ON → SHARED layer → initializeMultipleContexts() before language init
  load internals (+ PreinitializeSources: parse only)  → shared call targets; pre-init context stays clean
select: shared call targets whose root allows cloning and whose shadow prepareForAOT() != null,
        filtered by AOTCompileFilter and AOTCompileBudget; each rejection logged with a reason
for each: shadow = cloneForAOT() + prepareForAOT()    ← live AST untouched
analysis: one BuildTimeCompiledMethod per target
          graph = Truffle PE of the shadow target (first-tier style, with call/loop counting),
                  decoded from RUNTIME_COMPILED-variant analysis graphs, + Truffle tier
          deopt entries registered on DEOPT_TARGET variants for all frame states
compile:  hosted backend, full local-value frame info (deopt source)
heap:     live target.aotCode = AOTInstalledCode (relocatable entry pointer);
          AOTInstalledCode registered as a dependent of every assumption used by the PE
```

### Run time (per shared call target, every context on the engine)

```
dispatch: JIT entryPoint != 0 ? JIT code : aotCode != null ? AOT code : interpreter
AOT code ── counts calls/loops on the shadow ── ≥ AOTTierThreshold ──► unlink aotCode
                                                                        → interpreter on pristine AST → normal JIT
   │ transferToInterpreter: finish this activation in shadow nodes; AOT code stays linked
   └ invalidation (assumption, transferToInterpreterAndInvalidate, @Exclude hit):
        unlink aotCode, deoptimize its frames, next call → interpreter → normal JIT
```

Unlinking is per call target and permanent. Image code is never freed.

## 5. Components

### 5.1 Truffle polyglot (`truffle/src/com.oracle.truffle.polyglot`)

**Shared pre-initialization.**
- Today `PolyglotImpl.preInitializeEngine()` creates a *bound* engine (`PolyglotImpl.java` ~781-797). So `PolyglotEngineImpl.preInitialize()` stores the pre-init context in `engine.preInitializedContext`, outside any layer.
- New build-time option `polyglot.image-build-time.PreinitializeCodeSharing=true` (`ImageBuildTimeOptions`) creates the pre-init engine with sharing enabled. The pre-init context claims a layer whose policy is SHARED (both GraalJS and GraalPy declare `ContextPolicy.SHARED`).
- `PolyglotSharingLayer.allocateInstance` calls `initializeMultiContext` on each language instance *before* language initialization. `PolyglotSharingLayer.preInitialize()` / `loadPreinitializedContext` (today only used by the engine-store path) store and load the context.
- At run time the first context loads and patches the layer's pre-init context. Later contexts on the same engine claim the SHARED layer (`PolyglotEngineImpl.claimSharingLayer`) and share its language instances and call targets.
- **Constraint (documented for embedders, including Elide):** serving must place its contexts on one explicit shared `Engine`, the one that adopts the pre-initialized engine. Separately created bound engines get neither shared code nor AOT code.

**Build-time sources.**
- New option `polyglot.image-build-time.PreinitializeSources=<lang>:<path>[,...]`. Each source is parsed (`Env.parsePublic`) in the pre-init context and never called.
- The resulting call targets live in the layer's `PolyglotSourceCache`.

**Selection.**
- With `polyglot.image-build-time.AOTCompile=true` (requires `PreinitializeCodeSharing`), after pre-initialization the engine enumerates the shared layer's call targets.
- It keeps those whose root `isCloningAllowed()`, whose shadow's `prepareForAOT()` returns a non-null `ExecutionSignature`, that match `AOTCompileFilter` (a root-name glob), and that fit `AOTCompileBudget`.
- Every rejection is recorded with a reason.

### 5.2 Truffle API and runtime (`truffle/src/com.oracle.truffle.api`, `com.oracle.truffle.runtime`)

**`RootNode.cloneForAOT()`** (new, protected, `@since` next).
- Defaults to `cloneUninitialized()`.
- Languages override it when the uninitialized clone would share mutable speculative state with the live root. For example GraalJS keeps frame-slot kinds in the `FrameDescriptor`, which `FunctionRootNode.cloneUninitialized()` reuses.
- Contract: preparing or executing the returned tree never mutates state observable by the original root.

**Shadow targets.** *(amended)* `cloneUninitialized()` goes through `RootNode.getCallTarget()` → `notifyOnLoad`, so a shadow shows up in the engine's loaded call targets. `OptimizedCallTarget` therefore gains an `aotShadow` `@CompilationFinal` flag. Shadows are excluded from selection, from `CompileAOTOnCreate`, and from splitting heuristics. In `firstTierCall()` a shadow redirects "threshold reached" to `sourceCallTarget.unlinkAOT()` instead of `lastTierCompile()`. The PE folds the receiver to the shadow (`InterceptReceiverPlugin`), so the flag folds too.
- `OptimizedCallTarget` gains a hosted-only factory that builds a split-like clone (the same shape as `cloneUninitialized()` at `OptimizedCallTarget.java:1095`: `sourceCallTarget` = the live target) from `cloneForAOT()`, then calls `prepareForAOT()` on it.
- The shadow is never registered for calls. It is the PE root and owns AOT counters and profiles.
- Its `DirectCallNode`s point at live callee targets, so calls between AOT roots dispatch through each callee's own code.

**AOT tier state.**
- `OptimizedCallTarget.aotCode`, set only at build time.
- `isValid()` / `isValidLastTier()` keep describing JIT code only, so the compilation policy is unchanged.
- Shadow counting reaching `engine.AOTTierThreshold` calls `sourceCallTarget.unlinkAOT()`. The default equals the engine's `FirstTierCompilationThreshold`, the point at which an interpreted target would have been first-tier compiled.
- A shadow invalidation unlinks as well.
- `engine.TraceAOT` logs enter / unlink / invalidate per target.

**Build-time SPI.**
- `BuildTimeCompilationSupport` (hosted-only, in `com.oracle.truffle.runtime`) exposes the selected (live, shadow) pairs and a method to attach AOT code to a live target.
- The Truffle layer never compiles; SVM drives compilation.

### 5.3 Compiler (`compiler/src/jdk.graal.compiler/.../truffle`)

- Split `TruffleCompilerImpl` so an entry point returns the graph **after partial evaluation and the Truffle tier, before the backend**. The graph is partially evaluated as first-tier code of the given compilable (the shadow), including call/loop counting.
- `PartialEvaluator` keeps its `createGraphDecoder` hook. SVM supplies the build-time decoder (5.4).
- Build-time PE uses **no speculation log**: speculative optimizations that require one are off in AOT code, and the runtime JIT keeps its own log.

### 5.4 SVM (`substratevm/src/com.oracle.svm.truffle`, `com.oracle.svm.hosted`, `com.oracle.svm.core`)

**`TruffleBuildTimeCompilationFeature`** (requires `TruffleFeature`, so the runtime JIT is always present).
- `beforeAnalysis`, after pre-initialization: obtain the pairs from the SPI.
- For each, create a `BuildTimeCompiledMethod` (a `NonBytecodeMethod` with the call-boundary signature `(OptimizedCallTarget, Object[]) → Object`) and register it as an analysis root.
- Its `buildGraph` runs the build-time PE.

**Build-time PE on analysis types** *(amended 2026-09-24 after code research)*.
- `SubstratePartialEvaluator` can't be reused. Its method, field and type queries are `final` and hard-cast to `TruffleMethod`/`TruffleField`/`TruffleType`, which only the Substrate universe implements. Its decoder reads encoded runtime graphs, and `RuntimeCompilationSupport.encodedGraph` returns `null` until `onCompileQueueCreation`.
- A new `BuildTimePartialEvaluator extends PartialEvaluator` runs over analysis `HostedProviders`, with its own `KnownTruffleTypes` on the `AnalysisMetaAccess`. It computes `PartialEvaluationMethodInfo`/`ConstantFieldInfo` the same way `SubstrateTruffleUniverseFactory` does (`PartialEvaluator.computePartialEvaluationMethodInfo`, `computeConstantFieldInfo`).
- Its `BuildTimePEGraphDecoder` returns, for each callee, `getOrCreateMethodVariant(RUNTIME_COMPILED_METHOD).ensureGraphParsed(bb).getEncodedGraph()`. The `R` variants are parsed during analysis with Truffle's runtime plugins (`RuntimeCompilationFeature.parseRuntimeCompiledMethod`).
- The first version skips the pre-encoding runtime-graph optimizations (early inlining, early escape analysis). That costs performance, not correctness.
- **Graph normalization for the hosted compile queue.** The PE graph inherits guards: the `R` graphs ran `ConvertDeoptimizeToGuardPhase`. Hosted compilation requires `FIXED_DEOPTS` after guard lowering (`CompileQueue` ~1214, ~1538), so `buildGraph` lowers guards to fixed deopts before returning the graph.
- **Outermost frame.** `DeoptimizationUtils.isDeoptEntry` asserts that the root frame's method is the compiled method, and deoptimization needs a deopt target for *every* frame in the chain (`Deoptimizer.hasAOTDeoptTargetMethod`). So a `BuildTimeCompiledMethod` graph is a kit graph that invokes `profiledPERoot`, inlined by the PE, and the method has its own `DEOPT_TARGET_METHOD` variant. That variant's `buildGraph` emits the same kit graph with `DeoptEntryNode`s at the invoke, following the `PodFactorySubstitutionMethod` pattern.

**Deoptimizable image code.** Generalize the `@DeoptTest` path (`DeoptimizationUtils`, `SubstrateCompilationDirectives`, `CompileQueue`, `NativeImageCodeCache.includeLocalValues`, `FrameInfoEncoder`). Escape-analyzed values are already encoded for hosted deopt sources (`FrameInfoEncoder.makeVirtualObject`, `AnalysisToHostedGraphTransplanter.patchEscapeAnalysisState`). The changes:
- Add a `deoptSourceMethods` set to `SubstrateCompilationDirectives`. It is kept separate from `deoptForTestingMethods` so that `resetDeoptEntries` retains the `D` entries of *all inlined frame methods* for those sources, not just the source's own `D` variant.
- `DeoptHostedSnippets.DeoptimizeLowering` hard-codes `DeoptimizationAction.None` and rejects most reasons. For deopt-source methods it must keep the original action and reason, and the speculation is always null.
- `DeoptimizationRuntime.deoptimize` → `Deoptimizer.invalidateMethodOfFrame` must resolve AOT-tier frames through the AOT table below, not throw.
- Register `BuildTimeCompiledMethod`s as **deopt sources**. For every frame state in their graphs, including inlined states, register deopt entries on the `DEOPT_TARGET_METHOD` variant of the frame's method.
- Compile them with `canDeoptimize()` and full local-value frame info, including virtual-object materialization data.
- Add an address-sorted table of AOT methods consulted by `CodeInfoTable.lookupInstalledCode`, so that `Deoptimizer.invalidateMethodOfFrame` works for their frames. Today it throws "Only runtime compiled methods can be invalidated".

**Installation and dispatch.**
- Each live target's `aotCode` is an `AOTInstalledCode` (implements `SubstrateInstalledCode`, `OptimizedAssumptionDependency`). It references an immutable holder, registered with `registerAsImmutable`, containing a relocatable `CFunctionPointer` (`MethodPointer` to the compiled method).
- `SubstrateOptimizedCallTargetInstalledCode.doInvoke` becomes: JIT `entryPoint` if non-zero, else the AOT pointer if `aotCode != null`, else `invokeCallBoundary`.
- There is no isolate-startup hook: the pointer is relocated at image load.
- Unlinking sets `aotCode = null` at a safepoint, the same discipline `installedCode` uses.

**Invalidation.**
- `AOTInstalledCode.invalidate()` / `onAssumptionInvalidated()` run a *new* VM operation. `CodeInfoTable.invalidateInstalledCode` can't be used because it only consults the runtime code cache. The operation unlinks, then calls `Deoptimizer.deoptimizeInRange` over the method's `.text` range [entry, entry + size), lazy by default like runtime code. The size is recorded in `afterCompilation` (`@UnknownPrimitiveField(availability = BuildPhaseProvider.AfterCompilation.class)`).
- There is no `RuntimeCodeCache` involvement and no freeing.
- The threshold unlink does not deoptimize; running activations complete normally.

**Assumptions.**
- After PE, for each `TruffleAssumption` recorded in the graph: `OptimizedAssumption.registerDependency()` then `accept(aotInstalledCode)`, then rescan the assumption object so the image heap contains the entry. Entries are held strongly (`OptimizedAssumption.Entry`).
- An assumption that is already invalid means the root is skipped.
- An assumption invalidated later in the build drops that target's AOT code before the heap is written.
- Multi-context mode means single-context assumptions never occur.

**Failures and diagnostics.**
- PE bailouts, `CompilerAsserts` violations, budget overruns and unsupported nodes skip the root with a reason. They never fail the build.
- `-H:+TruffleAOTFailOnError` turns skips into build errors (CI).
- `-H:+TruffleAOTPrintSummary` prints per root: compiled or skipped (reason), graph size, allocation sites, code size, and assumptions.
- `-H:TruffleAOTMaxRootNodes` and `-H:TruffleAOTMaxRootAllocations` report node and allocation explosions; the native test harness enforces per-root ceilings (`_AOT_MAX_NODES`, `_AOT_MAX_ALLOCS` and their exceptions in `mx_substratevm.py`).

### 5.5 GraalJS proof slice (`graaljs`, PR 2)

**Call-target materialization** (only under AOT pre-initialization, gated by the new internal option `js.aot-materialize-call-targets`).
- Force `JSFunctionData.getCallTarget()` for the `Math` builtins. Their `JSFunctionData` lives on `JSContext`, per language instance, and so is shared across realms.
- For `PreinitializeSources`: after parsing, eagerly initialize call targets of every function data the parse produced. Today these are lazy via `JSFunctionData.lazyInit` / `JSBuiltin implements CallTargetInitializer`.
- Nothing is executed.

**`FunctionRootNode`.**
- `cloneForAOT()`: `cloneUninitialized()` plus a copied `FrameDescriptor`.
- `prepareForAOT()`: `AOTSupport.prepareForAOT(this)`, then a JS-side checker over the shadow tree that returns `null` (veto, recording the offending node class) if any reachable node cannot be prepared. It returns an all-`Object` `ExecutionSignature`.

**Slice coverage.**
- `@GenerateAOT` goes on specific node classes, not `JavaScriptBaseNode` (inheritance would demand AOT validity of about 480 node files at once):
  - `MathOperation` and subclasses
  - numeric `+ - * / %` and comparisons, and the `ToNumber`/`ToPrimitive` conversions they reach
  - constants, `if`/`while`/`for`, `return`
  - local variable reads/writes (generic slot kinds in the shadow's descriptor copy)
  - function calls
- Hand-written caches implement `GenerateAOT.Provider` by switching the **shadow** node to its existing generic mode:
  - `PropertyGetNode`, `PropertySetNode` and the global-property nodes → generic (megamorphic) lookup
  - `JSFunctionCallNode` → generic indirect call
- Vetoed in the slice:
  - closures reading outer-scope variables (outer frames come from live descriptors)
  - `try`/`catch`
  - generators and async
  - `eval`/`with`
  - object and array literals

**Tests and benchmark.**
- A native-image gate builds with `PreinitializeCodeSharing`, a fixture via `PreinitializeSources`, and `AOTCompile`. It asserts:
  1. results match the JIT for normal inputs and for types never seen at build time (exercising exclusion deopts and invalidation);
  2. AOT runs from call 1 (via `engine.TraceAOT`), unlinks at the threshold, and the JIT then compiles;
  3. two contexts on one `Engine` share AOT code with correct results;
  4. no fixture state is visible in the first context's globals;
  5. the summary lists the expected compiled and vetoed roots.
- A benchmark measures time-to-result for the fixture: interpreter-only image vs. AOT image vs. JIT image.

## 6. Options

| Option | Kind | Purpose |
|---|---|---|
| `polyglot.image-build-time.PreinitializeCodeSharing` | build | Pre-init into a SHARED layer, multi-context |
| `polyglot.image-build-time.PreinitializeSources` | build | `lang:path,...` parsed (never executed) during pre-init |
| `polyglot.image-build-time.AOTCompile` | build | Select and compile shared call targets (requires code sharing) |
| `polyglot.image-build-time.AOTCompileFilter` | build | Root-name glob |
| `polyglot.image-build-time.AOTCompileBudget` | build | PE node budget per root |
| `engine.AOTTierThreshold` | run time | Calls + loop iterations before unlinking AOT code |
| `engine.TraceAOT` | run time | Trace AOT enter / unlink / invalidate |
| `-H:+TruffleAOTPrintSummary` | hosted | Per-root build summary |
| `-H:+TruffleAOTFailOnError` | hosted | Skipped roots fail the build |
| `-H:TruffleAOTMaxRootNodes=<n>` | hosted | Reports roots above `<n>` nodes after PE (fails the build with `FailOnError`) |
| `-H:TruffleAOTMaxRootAllocations=<n>` | hosted | Reports roots above `<n>` allocation sites (fails the build with `FailOnError`) |
| `js.aot-materialize-call-targets` | JS internal | Eager call targets during AOT pre-init |

## 7. Testing (`graal` PR)

**Truffle, JVM.**
- Pre-initialization tests extended for: SHARED layer, `initializeMultiContext` before language init, `PreinitializeSources` parsing without executing.
- Selection, `cloneForAOT()`/`prepareForAOT()` and the veto log, on a test language modeled on `AOTSupportTest` (`com.oracle.truffle.api.dsl.test`).

**Native unit tests** (`mx native-unittest`, test language image):
1. AOT runs from call 1.
2. A `transferToInterpreter` deopt from AOT code, with escape-analyzed objects rematerialized, keeps AOT linked.
3. Invalidating at run time an assumption registered at build time deoptimizes frames and unlinks; the next call goes to the interpreter, then the JIT.
4. The threshold unlink hands off to the JIT.
5. Two contexts on one engine share AOT code.
6. `-H:+TruffleAOTFailOnError` fails the build on a deliberately unpreparable root.

**SVM.**
- A deopt-source test that registers a builder-compiled method through the generalized registration and deoptimizes it. It extends the existing `@DeoptTest` coverage in `com.oracle.svm.test`.

**Before submitting.**
- `mx build --build-logs=silent`, `mx checkstyle`, and the targeted native unit tests. Never run two builds concurrently in one workspace.

## 8. Risks and milestone 0

These are verified before building on them. If any fails, section 5.4 is revisited before further implementation.

1. A runtime-JIT native image (`TruffleFeature`) of a trivial Truffle app builds on `elide-25.4.x`. On `-ephemera` the spike hit "`OptimizedRuntimeSupport.callInlined%%D` reachable during compilation, but was not seen during bytecode parsing".
2. Hosted frame-info encoding (`FrameInfoEncoder` with local values) supports virtual-object materialization the way runtime-compiled code does.
3. Frame info of `RUNTIME_COMPILED`-variant frames in a hosted-compiled graph maps onto their `DEOPT_TARGET` variant code offsets (`HostedMethod.getImageCodeDeoptOffset`, `FrameInfoEncoder` `deoptMethodOffset`).

Further risks, tracked during implementation:

- **Analysis ordering.** Build-time PE triggers parsing of further runtime-compiled variants during analysis. This is expected to converge in the analysis fixpoint (the spike's on-demand parsing did).
- **Code size and build time.** Controlled by `AOTCompileFilter` and `AOTCompileBudget`, and reported by the summary.
- **The interpreter window on tier-up.** An accepted cost, limited to functions that cross the threshold.

## 9. Delivery

**`graal` PR.** One PR with an ordered, individually reviewable commit series:
1. shared pre-initialization and build-time sources (polyglot)
2. runtime AOT tier, `RootNode.cloneForAOT()`, build-time SPI (API/runtime)
3. SVM deopt-source generalization
4. build-time PE, installation, invalidation, assumption persistence (compiler + SVM)
5. tests and docs

**`graaljs` PR.** The proof slice (5.5), plus bumping the `graal` suite import to the `graal` branch head.

**`graalpython` PR.** After the JS proof lands (see below).

**Docs.**
- Update `truffle/docs/AOT.md` (the `@GenerateAOT` tutorial) with the AOT tier and `cloneForAOT()`.
- Add a Native Image Truffle section under `docs/` covering build-time compilation and the shared-`Engine` requirement.

## 10. GraalPy outlook (PR 3)

**Prerequisite, a follow-up change in `graal`: Bytecode DSL AOT preparation.**
- A `@GenerateBytecode` opt-in that prepares a root directly into the cached tier with all AOT-capable operation specializations active.
- It must account for quickening and boxing elimination (GraalPy uses `boxingEliminationTypes = {int.class}` and `enableUncachedInterpreter = true`).
- Frame and descriptor state needs the same `cloneForAOT()` care.

**GraalPy.**
- Make `singleContext` false before core initialization under shared pre-initialization, so that `PythonLanguage.cacheCode` and cached root nodes land in the shared layer.
- A slice of numeric builtins (`BuiltinFunctionRootNode`) and simple Python functions.
- The same gates as 5.5.
- This gets its own spec addendum when started.

## Implementation notes (2026-09-24)

Deviations and decisions made while implementing this design, as built at HEAD `898e448e31a`.

- **§5.1: the first bound engine adopts.** A bound engine, for example one created by `Context.create()` without an explicit `Engine`, also adopts the pre-initialized engine and its shared layer, provided it is the first engine created in the process. Only one engine per process can adopt the pre-initialized engine; this widens the shared-`Engine` requirement in §5.1 to also cover the common no-explicit-engine embedding style, not only explicit multi-context sharing.
- **Contexts created during the patch get no shared or AOT code.** A context created while the pre-initialized context is still being patched claims its own, unshared layer instead of waiting for the patch to finish. Waiting was rejected because a context can be created from the very thread that performs the patch, which would deadlock.
- **§5.4: code is kept after the heap snapshot is sealed.** An assumption invalidated after the build's heap snapshot has already been written is not treated as a build-time failure. The root's compiled code and the assumption, still recorded as valid in the sealed snapshot, are kept in the image; the summary reports it as `kept` rather than `skipped`, because dropping the code at that point would require re-running the snapshot.
- **Shadows are visible to tools.** Instruments wrap the shadow ASTs the same way they wrap any other loaded root. Since a shadow is a loaded call target like the live root, a tool that enumerates loaded roots can observe each AOT-compiled root twice. This was accepted rather than hidden, because filtering shadows out of the loaded-roots view would require plumbing an AOT-specific exception through the generic instrumentation API.
- **R13-A/R13-B: the shadow profile policy.** In the shadow, the call target's and call nodes' exception profiles start generic, and non-inlined `Profile` instances are replaced with disabled copies. This keeps the shadow's speculative state from ever appearing "trained" by build-time execution that never happened, which would otherwise bias its first real executions.
- **R14-B/R14-C: `FailOnError` semantics.** `-H:+TruffleAOTFailOnError` fails the build only for roots rejected for a reason other than `filtered` or `executed at build time`, since both are exclusions by design rather than compilation failures; it also enables `-H:+VerifyDeoptSourceEntryPoints`, because a `FailOnError` build is the point at which missing deopt entries should also be caught.
- **`PreinitializeSources` equality and languages.** Every entry's language must also appear in `PreinitializeContexts` and must override `TruffleLanguage.patchContext`, or the build fails with an `IllegalArgumentException` naming the entry; this ties source pre-parsing to context pre-initialization, which is the only way a parsed source can be reused at run time. A source is matched at run time by an equal source (same content, same name), and the recorded name drops the build-time path, so no build-machine path is embedded in the image.
- **T11-B: calls to a shadow redirect to the live target.** `callDirect`/`callIndirect` on a shadow redirect to its live target's `callTarget()`, so a call target that calls itself (directly or indirectly) through its own `CallTarget` reference during AOT compilation dispatches to the live, not the shadow, code. This keeps the shadow purely a partial-evaluation root, never an entry point at run time.
- **Live callees are never prepared or inlined at build time.** Build-time partial evaluation only follows a shadow's own AOT-prepared tree; it does not inline or prepare the live call targets that the shadow calls. This preserves the invariant that AOT preparation is confined to shadows and never leaks into the live AST.
- **Build-time PE inlines only runtime-compilation candidates.** The build-time partial evaluator decodes callees from their `RUNTIME_COMPILED_METHOD` variant, the same encoded graphs the runtime JIT would inline. This reuses one encoding and one set of inlining decisions instead of maintaining a second, build-time-only inlining policy.
- **The pre-existing static-final-assumption bug fix.** Independently of the AOT tier, `Assumption`s read from static final fields in code partially evaluated at run time could be folded at image build time, so the JIT compiler recorded no dependency on them and a later invalidation did not deoptimize the affected JIT code. They are now registered as dependencies (commit `7c14fec1f56`). This applies to every Truffle native image, including ones built without `AOTCompile`, because it was a correctness gap in ordinary runtime compilation on Native Image, not something the AOT tier introduced.
- **A separate build-time Truffle compiler.** Build-time compilation uses its own `BuildTimePartialEvaluator` over the analysis universe (`HostedProviders`, `AnalysisMetaAccess`), rather than the runtime `SubstrateTruffleUniverseFactory`/`SubstratePartialEvaluator` pair, because the runtime compiler's method, field and type queries are hard-cast to Substrate-universe types that do not exist yet during analysis, and its decoder reads encoded runtime graphs that are not available until after the compile queue is created.

## Implementation notes (2026-09-25)

Changes and decisions after the final review, as built at HEAD `98852975107`.

- **Shadow argument and return profiles start generic (C1).** The argument and return types of the `ExecutionSignature` a shadow's `prepareForAOT()` returns are not trusted, because no caller checks arguments against them.
- **AOT code profiles the arguments of live callees (C2).** Build-time partial evaluation retargets `profileArguments` to the boundary `profileIndirectArguments`, at the cost of one boundary call per call from AOT code to a live target.
- **Profiles of live call targets are folded conservatively (P1, re-review m1).** Build-time partial evaluation folds `OptimizedCallTarget.exceptionProfile` as generic and `returnProfile` as unset, so AOT code never depends on the profiles of a live callee and is not unlinked by the callee's first exception or first return of another type.
- **Selection guards (I1, I7, re-review m5).** An exception in `cloneForAOT()` or `prepareForAOT()`, a copy that shares an adoptable node with the live root, and a `prepareForAOT()` that changes a shared `FrameDescriptor` reject the root; the slot kinds are restored.
- **Typed source registration (I5).** `org.graalvm.polyglot.PreinitializedSources.register(Source...)`, called while the image is built, registers sources that can carry a MIME type, path, or URI; `PreinitializeSources` stays as a shorthand. Registering is a hint: a registered source whose language is not pre-initialized is skipped, so libraries can register from their own features (re-review I-1). Modules that a language loads itself are never embedder sources, so the GraalJS follow-up needs its own pre-initialization hook for module graphs.
- **I2, long-running activations: documented, follow-up.** The threshold is checked on call entry only, and AOT code has no OSR. A non-invalidating transfer to the interpreter at a loop back-edge is follow-up work; OSR of a shadow needs its own design.
- **I4, single-context speed: documented, measured later.** A bound engine that adopts the shared layer runs its single context multi-context, without single-context constant folding. It is to be measured on GraalJS in the follow-up PR before deciding on an option.
- **I3, the static-final-assumption fix `7c14fec1f56`, is split out** into its own upstream PR, which this branch stacks on.
- **M10: internal invariants stay hard build failures.** An unreachable deoptimization target of AOT code and a failed compilation after the analysis fail the build even without `FailOnError`; the error names the `aot_<id>_<root>` method, and `AOTCompileFilter` excludes the root as a workaround.
- **Frame identity (I6).** In frames of AOT code, the call target and root node are the shadow's, like a split.

