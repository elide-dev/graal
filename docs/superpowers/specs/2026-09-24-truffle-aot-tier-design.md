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

- OSR call targets (loops still get OSR at run time)
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

**Shadow targets.**
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

**Build-time PE on analysis types.**
- Reuse the `SubstratePartialEvaluator` built by `TruffleFeature.preinitializeTruffleCompiler()`. Its `KnownTruffleTypes` already hold `AnalysisType`s.
- A new `BuildTimePEGraphDecoder` returns, for each callee, the analysis-parsed graph of its `RUNTIME_COMPILED_METHOD` variant (`ensureGraphParsed`). These variants are parsed during analysis with Truffle's runtime plugins; the encoded runtime graphs don't exist until `onCompileQueueCreation`.
- The first version skips the pre-encoding runtime-graph optimizations (early inlining, early escape analysis). That costs performance, not correctness.

**Deoptimizable image code.** Generalize the `@DeoptTest` path (`DeoptimizationUtils`, `SubstrateCompilationDirectives`, `CompileQueue`, `NativeImageCodeCache.includeLocalValues`, `FrameInfoEncoder`):
- Register `BuildTimeCompiledMethod`s as **deopt sources**. For every frame state in their graphs, including inlined states, register deopt entries on the `DEOPT_TARGET_METHOD` variant of the frame's method.
- Compile them with `canDeoptimize()` and full local-value frame info, including virtual-object materialization data.
- Add an address-sorted table of AOT methods consulted by `CodeInfoTable.lookupInstalledCode`, so that `Deoptimizer.invalidateMethodOfFrame` works for their frames. Today it throws "Only runtime compiled methods can be invalidated".

**Installation and dispatch.**
- Each live target's `aotCode` is an `AOTInstalledCode` (implements `SubstrateInstalledCode`, `OptimizedAssumptionDependency`). It references an immutable holder, registered with `registerAsImmutable`, containing a relocatable `CFunctionPointer` (`MethodPointer` to the compiled method).
- `SubstrateOptimizedCallTargetInstalledCode.doInvoke` becomes: JIT `entryPoint` if non-zero, else the AOT pointer if `aotCode != null`, else `invokeCallBoundary`.
- There is no isolate-startup hook: the pointer is relocated at image load.
- Unlinking sets `aotCode = null` at a safepoint, the same discipline `installedCode` uses.

**Invalidation.**
- `AOTInstalledCode.invalidate()` / `onAssumptionInvalidated()` run a VM operation: unlink, then `Deoptimizer.deoptimizeInRange` over the method's `.text` range (lazy by default, like runtime code).
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
- `-H:+TruffleAOTPrintSummary` prints per root: compiled or skipped (reason), graph size, code size, and assumptions.

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
