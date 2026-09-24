# Truffle AOT Tier (`graal` PR) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compile AOT-prepared shadow copies of shared Truffle call targets at native-image build time and link the machine code into the image as a new AOT tier. That tier runs from the first call, deoptimizes correctly, and hands over to the runtime JIT. The whole mechanism is proven here with Truffle test languages; GraalJS comes in a separate plan.

**Architecture:**
- Pre-initialization builds a code-sharing engine, so shared call targets exist in the image heap.
- For each selected target, a shadow clone gets `prepareForAOT()`. A new `BuildTimePartialEvaluator` partially evaluates the shadow during points-to analysis, decoding the analysis-parsed `RUNTIME_COMPILED` variants. The result becomes the graph of a synthetic `BuildTimeCompiledMethod`, which is compiled as a deoptimization source with its own `DEOPT_TARGET` variant.
- The live call target gets an `AOTInstalledCode` that holds a relocatable pointer.
- Shadow counting, invalidation and deopt all unlink the AOT code and hand the target to the normal interpreter → first-tier → last-tier lifecycle.

**Tech Stack:** Java 25 (labsjdk-ce-latest-jvmci-25.4-b23), mx, Truffle, Graal compiler, SubstrateVM; JUnit via `mx unittest` and `mx native-unittest`.

**Spec:** `docs/superpowers/specs/2026-09-24-truffle-aot-tier-design.md`, including the 2026-09-24 amendments in §5.2 and §5.4.

## Global Constraints

- All work is on branch `elide-25.4.x-aot-tier`, branched from `elide-25.4.x`, in worktree `/home/sam/workspace/graalvm/worktrees/graal-aot-tier`. Never base this on `elide-25.4.x-ephemera`.
- No training runs. Guest code is never executed at build time beyond what pre-initialization already does. Build-time sources are **parsed, never executed**.
- The live AST of a selected call target is never prepared, specialized or executed at build time. Only its shadow (`cloneForAOT()` + `prepareForAOT()`) is.
- AOT code is compiled only for call targets of a SHARED sharing layer in multi-context mode.
- Build-time options are read from system properties `polyglot.image-build-time.<Name>` via `ImageBuildTimeOptions`: `PreinitializeCodeSharing`, `PreinitializeSources`, `AOTCompile`, `AOTCompileFilter`, `AOTCompileBudget`.
- Runtime engine options: `engine.AOTTierThreshold` (default = the value of `engine.FirstTierCompilationThreshold`), `engine.TraceAOT`.
- Hosted options: `-H:+TruffleAOTPrintSummary`, `-H:+TruffleAOTFailOnError`.
- Unlinking AOT code is per call target, permanent, and happens only at a safepoint. Image code is never freed.
- A root that can't be compiled is skipped with a logged reason. It never fails the build unless `-H:+TruffleAOTFailOnError` is set.
- Nothing changes on HotSpot: there, the AOT tier is inert.
- Run `mx` from the relevant suite directory. Always `mx build --build-logs=silent`. Never run two `mx build` / `mx checkstyle` at once in this worktree.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Truffle API changes need a `truffle/CHANGELOG.md` entry under `## Version 25.4` and a regenerated `snapshot.sigtest` (`cd truffle && mx sigtest --generate`).

## Review Focus

1. **Unlink racing with running AOT code.** Many threads call a target while the threshold unlink or an invalidation happens. Expected: every call returns the correct result; no call jumps to stale code. `doInvoke` reads `aotCode` exactly once, and unlinking only happens at a safepoint. Test: Task 13, `concurrentCallsDuringUnlink`.
2. **The pre-initialized context is rejected at run time** (incompatible options, or `engine.UsePreInitializedContext=false`). Expected: correct results from the interpreter/JIT; AOT code is never entered, because a fresh layer means different call targets. Test: Task 13, `incompatibleContextDoesNotUseAOT`.
3. **Guest exceptions through AOT frames**, including an exception thrown right after a deopt. Expected: the same exception type, message and guest stack trace as the interpreter. Test: Task 13, `exceptionThroughAOTCode`.
4. **Deep recursion in AOT code.** Expected: a `StackOverflowError` mapped to the language's usual behavior, never a crash. Test: Task 13, `deepRecursionInAOTCode`.
5. **The same image run with `engine.TraceAOT=true` and with a tiny `engine.AOTTierThreshold=1`.** Expected: identical results; the trace shows `aot enter` then `aot unlink` exactly once per target. Test: Task 13, `thresholdOneUnlinksOnce`.

---

## File Structure

**Truffle polyglot** (`truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/`):
- Modify `ImageBuildTimeOptions.java`: the five build-time options.
- Modify `PolyglotImpl.java` (`preInitializeEngine`, `createDefaultEngine` ~782-817): create a sharing pre-init engine.
- Modify `PolyglotContextImpl.java` (`preinitialize` ~3880-3970): parse `PreinitializeSources` while entered.
- Modify `PolyglotEngineImpl.java`: strong holder for pre-init parse results; the `BuildTimeCompilationSupport` bridge.
- Modify `EngineAccessor.java` (~2335 `forEachLoadedRootNode`): layer-filtered enumeration.

**Truffle API/runtime:**
- Modify `truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/nodes/RootNode.java`: `cloneForAOT()`.
- Modify `truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/impl/Accessor.java` and `.../nodes/NodeAccessor.java`: accessor for `cloneForAOT`.
- Modify `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/OptimizedCallTarget.java`: shadow creation, `aotShadow`, `aotCode`, `unlinkAOT()`, `firstTierCall()` redirect.
- Modify `.../runtime/OptimizedRuntimeOptions.java` and `.../runtime/EngineData.java`: `AOTTierThreshold`, `TraceAOT`.
- Modify `.../runtime/OptimizedRuntimeSupport.java` (`notifyOnLoad` ~118-128): skip shadows.
- Create `.../runtime/BuildTimeCompilationSupport.java`: hosted-only SPI (selection, pairs, attach).
- Create `.../runtime/AOTCode.java`: the runtime-agnostic AOT code handle interface.

**Compiler** (`compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/truffle/`):
- Modify `TruffleCompilerImpl.java`: `partialEvaluateForBuildTime(...)`.

**SVM core/hosted** (`substratevm/src/`):
- Modify `com.oracle.svm.hosted/.../code/SubstrateCompilationDirectives.java`: `deoptSourceMethods`.
- Modify `com.oracle.svm.hosted/.../code/DeoptimizationUtils.java`: register deopt sources.
- Modify `com.oracle.svm.graal/.../hosted/runtimecompilation/RuntimeCompilationFeature.java` (`validateGraph` ~840-891).
- Modify `com.oracle.svm.hosted/.../code/CompileQueue.java` (~749-755).
- Modify `com.oracle.svm.hosted/.../image/NativeImageCodeCache.java` (~924-950).
- Modify `com.oracle.svm.core/.../graal/snippets/DeoptHostedSnippets.java` (~108, ~157-221): keep action/reason for deopt sources.
- Create `com.oracle.svm.core/.../deopt/ImageCodeInstalledCodeLookup.java`: an `ImageSingletons` hook that maps image IPs to `SubstrateInstalledCode`.
- Modify `com.oracle.svm.core/.../deopt/Deoptimizer.java` (`invalidateMethodOfFrame` ~670-710).
- Create `com.oracle.svm.hosted/.../code/DeoptSourceGraphKit.java`: the kit/deopt-entry helpers factored from `heap/PodFactorySubstitutionMethod.java`.
- Test: create `com.oracle.svm.test/src/com/oracle/svm/test/deopt/DeoptSourceTest.java` and `DeoptSourceTestFeature.java`.

**SVM Truffle** (`substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/`):
- Create `buildtime/TruffleBuildTimeCompilationFeature.java`: orchestration.
- Create `buildtime/BuildTimePartialEvaluator.java` and `buildtime/BuildTimePEGraphDecoder.java`: PE on analysis types.
- Create `buildtime/BuildTimeCompiledMethod.java`: the synthetic method, both its source graph and its `D` graph.
- Create `buildtime/AOTInstalledCode.java`, `buildtime/AOTCodeTable.java` and `buildtime/InvalidateAOTCodeOperation.java`: install, lookup, invalidate.
- Create `buildtime/BuildTimeCompilationOptions.java`: the hosted options.
- Modify `api/SubstrateOptimizedCallTargetInstalledCode.java` (`doInvoke` ~243-258): AOT dispatch.
- Modify `api/SubstrateOptimizedCallTarget.java`: implement the `aotCode` accessors.

**Tests (Truffle, JVM and native):**
- Modify `truffle/src/com.oracle.truffle.api.test/src/com/oracle/truffle/api/test/polyglot/ContextPreInitializationTest.java`.
- Create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/AOTTestLanguage.java`: a test language whose roots implement `prepareForAOT`.
- Create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/AOTShadowTargetTest.java` (HotSpot).
- Create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/BuildTimePartialEvaluationTest.java` (HotSpot).
- Create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/AOTTierNativeTest.java` (native only).
- Modify `substratevm/mx.substratevm/mx_substratevm.py` (`truffle_unittest_task` ~463): dedicated AOT-tier image.

**Docs:**
- Modify `truffle/docs/AOT.md`.
- Modify `truffle/CHANGELOG.md`.
- Create `docs/reference-manual/native-image/TruffleBuildTimeCompilation.md` (or the matching existing Truffle-on-native page, found in Task 15).

---

## Task 1: Gate — the worktree builds and runtime-JIT Truffle images work on `elide-25.4.x`

**Files:** none modified.

**Interfaces:** Produces the baseline that every later task depends on.

- [ ] **Step 1: Build the SVM suite from the worktree**

Run:
```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/substratevm && mx build --build-logs=silent
```
Expected: exit 0.

- [ ] **Step 2: Run an existing runtime-compilation Truffle native test**

Run:
```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/substratevm && mx native-unittest jdk.graal.compiler.truffle.test.ContextLookupCompilationTest \
  --build-args --macro:truffle --language:nfi --add-exports=java.base/jdk.internal.module=ALL-UNNAMED -H:MaxRuntimeCompileMethods=5000 -H:+UnlockExperimentalVMOptions -H:-SupportCompileInIsolates
```
Expected: `OK (n tests)`.

- [ ] **Step 3: Decide whether to continue**

If Step 2 fails with "`OptimizedRuntimeSupport.callInlined%%D` reachable during compilation, but was not seen during bytecode parsing", stop. Debug it with superpowers:systematic-debugging on this branch before any other task: every later task needs working runtime compilation. Record the root cause and fix as a separate commit (`svm: ...`) on this branch.

If it passes, write down the command above: later tasks reuse it.

---

## Task 2: SVM — builder-compiled methods as deoptimization sources

This generalizes the `@DeoptTest` machinery so that an arbitrary AOT method can register every frame state in its graph, including inlined ones, as a deopt entry on the `DEOPT_TARGET_METHOD` variant of that frame's method. The method is then compiled with full local-value frame info.

**Files:**
- Modify: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/code/SubstrateCompilationDirectives.java` (fields near ~176-186; `resetDeoptEntries` ~316-333)
- Modify: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/code/DeoptimizationUtils.java` (`canDeoptForTesting` ~129; new method next to `registerDeoptEntriesForDeoptTesting` ~403)
- Modify: `substratevm/src/com.oracle.svm.graal/src/com/oracle/svm/graal/hosted/runtimecompilation/RuntimeCompilationFeature.java` (`validateGraph` ~850)
- Modify: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/code/CompileQueue.java` (~749-755)
- Test: create `substratevm/src/com.oracle.svm.test/src/com/oracle/svm/test/deopt/DeoptSourceTest.java`
- Test: create `substratevm/src/com.oracle.svm.test/src/com/oracle/svm/test/deopt/DeoptSourceTestFeature.java`

**Interfaces:**
- Produces: `SubstrateCompilationDirectives.singleton().registerDeoptSource(AnalysisMethod method)` (callable only before analysis finishes).
- Produces: `boolean isRegisteredDeoptSource(ResolvedJavaMethod method)`.
- Semantics: a deopt source gets `compilationInfo.canDeoptForTesting = true`. The `D` entries registered for its inlined frames survive `resetDeoptEntries`. The source method itself needs no `D` variant of its own; if it lacks one, deopt still works for inner frames, and Task 8 adds `D` variants for synthetic outer frames.

- [ ] **Step 1: Write the failing test and the test feature**

`DeoptSourceTestFeature.java`:
```java
package com.oracle.svm.test.deopt;

import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.nodes.TestDeoptimizeNode;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import jdk.graal.compiler.phases.util.Providers;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Registers {@link DeoptSourceTest#compute} as a deoptimization source. In compiled code
 * {@link DeoptSourceTest#deoptHere()} becomes a transfer to the interpreter; in the deopt
 * target it is an ordinary call that counts executions.
 */
public final class DeoptSourceTestFeature implements InternalFeature {
    @Override
    public void registerInvocationPlugins(Providers providers, Plugins plugins, ParsingReason reason) {
        Registration r = new Registration(plugins.getInvocationPlugins(), DeoptSourceTest.class);
        r.register(new InvocationPlugin.RequiredInvocationPlugin("deoptHere") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, InvocationPlugin.Receiver receiver) {
                if (b.getMethod().getName().equals("helper") && !com.oracle.svm.hosted.code.SubstrateCompilationDirectives.isDeoptTarget(b.getMethod())) {
                    b.add(new TestDeoptimizeNode());
                    return true;
                }
                return false; // keep the real call in the deopt target
            }
        });
    }

    @Override
    public void beforeAnalysis(Feature.BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        try {
            AnalysisMethod compute = access.getMetaAccess().lookupJavaMethod(DeoptSourceTest.class.getDeclaredMethod("compute", int.class));
            SubstrateCompilationDirectives.singleton().registerDeoptSource(compute);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
```

`DeoptSourceTest.java`:
```java
package com.oracle.svm.test.deopt;

import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.api.directives.GraalDirectives;

public class DeoptSourceTest {
    static int deoptCount;

    static void deoptHere() {
        deoptCount++;
    }

    static int helper(int x) {
        int y = x * 2;
        deoptHere();          // compiled: TestDeoptimizeNode; deopt target: counts
        return y + 1;
    }

    static int compute(int x) {
        int a = helper(x);    // inlined into the deopt source
        return a + GraalDirectives.opaque(1);
    }

    @Test
    public void deoptimizesInlinedFrameOfSource() {
        deoptCount = 0;
        Assert.assertEquals(2 * 20 + 1 + 1, compute(20));
        Assert.assertEquals("execution must have resumed in the deopt target of helper", 1, deoptCount);
    }
}
```

Register the feature in `substratevm/src/com.oracle.svm.test/src/META-INF/native-image/com.oracle.svm.test/native-image.properties` by appending `,com.oracle.svm.test.deopt.DeoptSourceTestFeature` to the existing `--features=` list.

- [ ] **Step 2: Run the test and confirm it fails to compile**

Run:
```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/substratevm && mx build --build-logs=silent
```
Expected: FAIL, "cannot find symbol: method registerDeoptSource".

- [ ] **Step 3: Implement deopt-source registration in `SubstrateCompilationDirectives`**

Add next to `deoptForTestingMethods`:
```java
/**
 * Builder-compiled methods whose frames (including all inlined frames) must be deoptimizable.
 * Unlike {@link #deoptForTestingMethods}, a deopt source does not need a deopt target of its own
 * for correctness of inner frames, and all deopt entries registered for its inlined frames are
 * kept by {@link #resetDeoptEntries()}.
 */
private final Set<AnalysisMethod> deoptSourceMethods = ConcurrentHashMap.newKeySet();
/** Deopt-target methods that received entries on behalf of a deopt source. */
private final Set<AnalysisMethod> deoptSourceTargets = ConcurrentHashMap.newKeySet();

public void registerDeoptSource(AnalysisMethod method) {
    assert deoptInfoModifiable();
    deoptSourceMethods.add(method);
}

public boolean isRegisteredDeoptSource(ResolvedJavaMethod method) {
    return deoptSourceMethods.contains(toAnalysisMethod(method));
}

void recordDeoptSourceTarget(ResolvedJavaMethod deoptTarget) {
    deoptSourceTargets.add(toAnalysisMethod(deoptTarget));
}
```
In `resetDeoptEntries()` (~316-333), keep the entries of `deoptSourceTargets` as well. Right after the loop that preserves `deoptForTestingMethod.getMethodVariant(DEOPT_TARGET_METHOD)`, add:
```java
for (AnalysisMethod target : deoptSourceTargets) {
    Map<Long, DeoptSourceFrameInfo> entries = deoptEntries.get(target);
    if (entries != null) {
        newDeoptEntries.put(target, entries);
    }
}
```
Use the same map variable name the method already uses for the retained entries.

- [ ] **Step 4: Register frame-state entries for deopt sources in `DeoptimizationUtils`**

In `canDeoptForTesting` (~129), as the first check:
```java
if (SubstrateCompilationDirectives.singleton().isRegisteredDeoptSource(method)) {
    return true;
}
```
Add next to `registerDeoptEntriesForDeoptTesting`:
```java
public static void registerDeoptEntriesForDeoptSource(PointsToAnalysis bb, StructuredGraph graph, PointsToAnalysisMethod aMethod) {
    Iterable<ResolvedJavaMethod> recomputeMethods = DeoptimizationUtils.registerDeoptEntries(graph, true,
                    deoptEntryMethod -> {
                        AnalysisMethod target = ((PointsToAnalysisMethod) deoptEntryMethod).getOrCreateMethodVariant(DEOPT_TARGET_METHOD);
                        SubstrateCompilationDirectives.singleton().recordDeoptSourceTarget(target);
                        return target;
                    });
    for (ResolvedJavaMethod method : recomputeMethods) {
        ((PointsToAnalysisMethod) method).getTypeFlow().updateFlowsGraph(bb, MethodFlowsGraph.GraphKind.FULL, null, true);
    }
}
```
Make `recordDeoptSourceTarget` package-visible or public so that `DeoptimizationUtils` can call it.

- [ ] **Step 5: Route deopt sources in `RuntimeCompilationFeature.validateGraph`**

At ~850, before the `canDeoptForTesting` branch:
```java
} else if (SubstrateCompilationDirectives.singleton().isRegisteredDeoptSource(aMethod)) {
    DeoptimizationUtils.registerDeoptEntriesForDeoptSource(bb, graph, aMethod);
    return true;
} else if (DeoptimizationUtils.canDeoptForTesting(aMethod, false, graphChecker)) {
```

- [ ] **Step 6: Mark deopt sources in `CompileQueue`**

At ~749-755:
```java
for (HostedMethod method : universe.getMethods()) {
    if (SubstrateCompilationDirectives.singleton().isRegisteredDeoptSource(method)) {
        method.compilationInfo.canDeoptForTesting = true;
    } else if (SubstrateCompilationDirectives.singleton().isRegisteredForDeoptTesting(method)) {
        method.compilationInfo.canDeoptForTesting = true;
        assert SubstrateCompilationDirectives.singleton().isRegisteredDeoptTarget(method.getMethodVariant(DEOPT_TARGET_METHOD));
    }
}
```
`NativeImageCodeCache.includeLocalValues` (~926) already returns true for `canDeoptForTesting()`, so it needs no change. Confirm that by reading it.

- [ ] **Step 7: Build and run the test**

Run:
```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/substratevm && mx build --build-logs=silent && mx native-unittest com.oracle.svm.test.deopt.DeoptSourceTest
```
Expected: `OK (1 test)`.

If it fails with `fatalDeoptimizationError` "no deoptimization entry point", the outer frame `compute` has no `D` variant. In that case register one: in the feature, call `aMethod.getOrCreateMethodVariant(DEOPT_TARGET_METHOD)` and `access.registerAsRoot(compute, true, "deopt source test", DEOPT_TARGET_METHOD)`. Record in the commit message which variant was needed. That is spec risk 3 (frame info → `D` offset mapping); if it still fails after the fix, stop and revisit spec §5.4.

- [ ] **Step 8: Checkstyle and commit**

```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/substratevm && mx checkstyle
git add -A substratevm/src/com.oracle.svm.hosted substratevm/src/com.oracle.svm.graal substratevm/src/com.oracle.svm.test
git commit -m "svm: support builder-compiled methods as deoptimization sources

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: SVM — deopt actions and invalidation for image-code deopt sources

Two gaps are closed here. Hosted lowering hard-codes `DeoptimizationAction.None` for `TransferToInterpreter` and rejects other reasons. And `Deoptimizer.invalidateMethodOfFrame` throws for image code. For deopt sources, lowering keeps the action and reason, and invalidation resolves the frame's installed code through a lookup hook that Task 11 fills.

**Files:**
- Create: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/deopt/ImageCodeInstalledCodeLookup.java`
- Modify: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/deopt/Deoptimizer.java` (`invalidateMethodOfFrame` ~670-710)
- Modify: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/graal/snippets/DeoptHostedSnippets.java` (~108, ~157-221)
- Test: modify `substratevm/src/com.oracle.svm.test/src/com/oracle/svm/test/deopt/DeoptSourceTest.java` and `DeoptSourceTestFeature.java`

**Interfaces:**
- Consumes: Task 2's `isRegisteredDeoptSource`.
- Produces:
  ```java
  public interface ImageCodeInstalledCodeLookup {
      /** Returns the installed code owning an image-code deopt-source frame at {@code ip}, or null. */
      SubstrateInstalledCode lookup(CodePointer ip);
  }
  ```
  It is registered as an `ImageSingletons` entry by whoever owns such code (Task 11). If no singleton is registered, behavior is unchanged.
- Produces: `DeoptHostedSnippets` lowers any `DeoptimizeNode` in a deopt-source method to a deoptimize call carrying the original action and reason and a null speculation.

- [ ] **Step 1: Extend the test**

Add to `DeoptSourceTest`:
```java
static int invalidateCount;

static void invalidateHere() {
    invalidateCount++;
}

static int helper2(int x) {
    invalidateHere();     // compiled: DeoptimizeNode(InvalidateReprofile, TransferToInterpreter)
    return x + 3;
}

static int computeInvalidating(int x) {
    return helper2(x) + GraalDirectives.opaque(0);
}

@Test
public void invalidatingDeoptInDeoptSourceCallsLookup() {
    DeoptSourceTestFeature.TestInstalledCode.invalidations = 0;
    Assert.assertEquals(10, computeInvalidating(7));
    Assert.assertEquals(1, invalidateCount);
    Assert.assertEquals("image-code lookup must have resolved the frame and invalidated it", 1, DeoptSourceTestFeature.TestInstalledCode.invalidations);
}
```
Add to `DeoptSourceTestFeature`:
- register `computeInvalidating` as a deopt source;
- an invocation plugin for `invalidateHere` that, when parsing `helper2` outside the deopt target, adds `new DeoptimizeNode(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.TransferToInterpreter)`;
- a lookup that recognizes any IP and returns a `TestInstalledCode`:

```java
public static final class TestInstalledCode implements com.oracle.svm.core.deopt.SubstrateInstalledCode {
    public static int invalidations;
    @Override public String getName() { return "DeoptSourceTest"; }
    @Override public long getAddress() { return 0; }
    @Override public long getEntryPoint() { return 0; }
    @Override public boolean isValid() { return true; }
    @Override public boolean isAlive() { return true; }
    @Override public void invalidate() { invalidations++; }
    @Override public jdk.vm.ci.meta.SpeculationLog getSpeculationLog() { return null; }
    @Override public void setAddress(long address, long entryPoint, jdk.vm.ci.meta.ResolvedJavaMethod method) { throw new UnsupportedOperationException(); }
    @Override public void clearAddress() { throw new UnsupportedOperationException(); }
    @Override public com.oracle.svm.core.deopt.SubstrateInstalledCode.Factory getFactory() { return null; }
}

@Override
public void afterRegistration(AfterRegistrationAccess access) {
    TestInstalledCode code = new TestInstalledCode();
    ImageSingletons.add(ImageCodeInstalledCodeLookup.class, ip -> code);
}
```
Before writing this class, open `com.oracle.svm.core.deopt.SubstrateInstalledCode` and implement exactly its abstract methods. Remove any `@Override` above that the interface doesn't declare.

- [ ] **Step 2: Confirm the test fails**

Run: `mx build --build-logs=silent && mx native-unittest com.oracle.svm.test.deopt.DeoptSourceTest`
Expected: FAIL. Either the build fails ("Unexpected reason" from `DeoptHostedSnippets`), or at run time "Only runtime compiled methods can be invalidated".

- [ ] **Step 3: Create `ImageCodeInstalledCodeLookup`**

```java
package com.oracle.svm.core.deopt;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CodePointer;

/**
 * Maps a program counter inside image (AOT) code of a deoptimization-source method to the
 * {@link SubstrateInstalledCode} that owns it, so that invalidating deopts work for image code.
 */
public interface ImageCodeInstalledCodeLookup {
    SubstrateInstalledCode lookup(CodePointer ip);

    static SubstrateInstalledCode lookupOrNull(CodePointer ip) {
        if (ImageSingletons.contains(ImageCodeInstalledCodeLookup.class)) {
            return ImageSingletons.lookup(ImageCodeInstalledCodeLookup.class).lookup(ip);
        }
        return null;
    }
}
```

- [ ] **Step 4: Resolve image-code frames in `invalidateMethodOfFrame`**

In `Deoptimizer.invalidateMethodOfFrame` (~670), immediately after `SubstrateInstalledCode installedCode = CodeInfoTable.lookupInstalledCode(ip);`:
```java
if (installedCode == null) {
    installedCode = ImageCodeInstalledCodeLookup.lookupOrNull(ip);
}
```
The existing `installedCode == null` branch keeps throwing for everything else. Leave `registerSpeculationFailure` unchanged; with a null speculation it is a no-op.

- [ ] **Step 5: Keep action and reason for deopt sources in `DeoptHostedSnippets`**

In `DeoptimizeLowering.lower` (~157-221), before the reason switch:
```java
if (SubstrateCompilationDirectives.singleton().isRegisteredDeoptSource(node.graph().method())) {
    // Deopt sources resume in DEOPT_TARGET variants: keep the compiler's action and reason.
    // Speculations are never used by builder-compiled deopt sources.
    long actionAndReason = Deoptimizer.encodeDeoptActionAndReasonToLong(node.getAction(), node.getReason(), 0);
    lowerToDeoptimizeCall(node, tool, actionAndReason);   // the same helper/branch used for TransferToInterpreter at ~108
    return;
}
```
If the `TransferToInterpreter` branch at ~108 inlines its snippet call instead of using a helper, extract that code into `private static void lowerToDeoptimizeCall(DeoptimizeNode node, LoweringTool tool, long actionAndReason)` first and use it in both places. `SubstrateCompilationDirectives` lives in `com.oracle.svm.hosted`, but this snippet class is in `com.oracle.svm.core`. So don't reference it directly: add `boolean isDeoptSource(ResolvedJavaMethod m)` to `com.oracle.svm.core.graal.code.SubstrateBackend`'s existing hosted configuration hook, or add a new core-side `DeoptSourceMethods` `ImageSingletons` interface implemented in hosted by `SubstrateCompilationDirectives`. Pick the singleton interface, `com.oracle.svm.core.deopt.DeoptSourceMethods`, with a single method `boolean isDeoptSource(ResolvedJavaMethod)`, and register it in `SubstrateCompilationDirectives`' constructor.

- [ ] **Step 6: Run the tests**

Run: `mx build --build-logs=silent && mx native-unittest com.oracle.svm.test.deopt.DeoptSourceTest`
Expected: `OK (2 tests)`.

- [ ] **Step 7: Checkstyle and commit**

```bash
mx checkstyle
git add -A substratevm/src
git commit -m "svm: keep deopt actions and resolve installed code for image-code deopt sources

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Polyglot — pre-initialization with code sharing

**Files:**
- Modify: `truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/ImageBuildTimeOptions.java`
- Modify: `truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/PolyglotImpl.java` (~782-817)
- Test: modify `truffle/src/com.oracle.truffle.api.test/src/com/oracle/truffle/api/test/polyglot/ContextPreInitializationTest.java`

**Interfaces:**
- Produces: `ImageBuildTimeOptions` constants for all five build-time options (Tasks 5 and 8 read the others):
  - `PREINITIALIZE_CODE_SHARING_NAME = "PreinitializeCodeSharing"` (Boolean)
  - `PREINITIALIZE_SOURCES_NAME = "PreinitializeSources"` (String)
  - `AOT_COMPILE_NAME = "AOTCompile"` (Boolean)
  - `AOT_COMPILE_FILTER_NAME = "AOTCompileFilter"` (String, glob, default `""` = all)
  - `AOT_COMPILE_BUDGET_NAME = "AOTCompileBudget"` (String holding an int, default `"0"` = unlimited)
- Behavior: with `polyglot.image-build-time.PreinitializeCodeSharing=true`, `preInitializeEngine()` creates a non-bound engine. The pre-init context therefore claims a SHARED layer (for languages with `ContextPolicy.SHARED`), and `initializeMultipleContexts()` runs before `createContext`.

- [ ] **Step 1: Write the failing test**

In `ContextPreInitializationTest`:
- add `int multiContextInitOrder = -1;` and `Object languageInstance;` fields to `CountingContext`;
- make `ContextPreInitializationTestSharedLanguage` record them. Add a static `static volatile int multiContextOrder = -1;` in the language, set in `initializeMultipleContexts()`, and in `createContext` do `ctx.multiContextInitOrder = multiContextOrder; ctx.languageInstance = this;`.

```java
@Override
protected void initializeMultipleContexts() {
    multiContextOrder = NEXT_ORDER_INDEX.getAndIncrement();
}
```
New test:
```java
@Test
public void testPreinitializeCodeSharing() throws Exception {
    setPatchable(SHARED);
    System.setProperty("polyglot.image-build-time.PreinitializeCodeSharing", "true");
    try {
        doContextPreinitialize(SHARED);
    } finally {
        System.clearProperty("polyglot.image-build-time.PreinitializeCodeSharing");
    }
    CountingContext preinit = findContext(SHARED, emittedContexts);
    assertNotNull(preinit);
    assertTrue("multi-context must be initialized before createContext", preinit.multiContextInitOrder >= 0 && preinit.multiContextInitOrder < preinit.createContextOrder);
    try (Engine engine = Engine.create()) {
        try (Context first = Context.newBuilder().engine(engine).build(); Context second = Context.newBuilder().engine(engine).build()) {
            first.initialize(SHARED);
            second.initialize(SHARED);
            List<CountingContext> contexts = findContexts(SHARED, emittedContexts);
            assertEquals(1, preinit.patchContextCount);
            CountingContext secondCtx = contexts.get(contexts.size() - 1);
            assertSame("contexts on the engine must share the pre-initialized language instance", preinit.languageInstance, secondCtx.languageInstance);
        }
    }
}
```

- [ ] **Step 2: Confirm it fails**

Run:
```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/truffle && mx build --build-logs=silent && mx unittest ContextPreInitializationTest#testPreinitializeCodeSharing
```
Expected: FAIL on the multi-context order assertion: the pre-init engine is bound, so its layer is EXCLUSIVE.

- [ ] **Step 3: Add the options**

In `ImageBuildTimeOptions`:
```java
static final String PREINITIALIZE_CODE_SHARING_NAME = "PreinitializeCodeSharing";
@Option(name = PREINITIALIZE_CODE_SHARING_NAME, category = OptionCategory.EXPERT, help = "Pre-initialize contexts in a shared (multi-context) sharing layer so that pre-initialized code is shared by all contexts of the engine.")//
static final OptionKey<Boolean> PreinitializeCodeSharing = new OptionKey<>(false);

static final String PREINITIALIZE_SOURCES_NAME = "PreinitializeSources";
@Option(name = PREINITIALIZE_SOURCES_NAME, category = OptionCategory.EXPERT, help = "Comma separated list of <language>:<path> sources parsed (never executed) during pre-initialization.")//
static final OptionKey<String> PreinitializeSources = new OptionKey<>("");

static final String AOT_COMPILE_NAME = "AOTCompile";
@Option(name = AOT_COMPILE_NAME, category = OptionCategory.EXPERT, help = "Compile AOT-preparable shared call targets at image build time. Requires PreinitializeCodeSharing.")//
static final OptionKey<Boolean> AOTCompile = new OptionKey<>(false);

static final String AOT_COMPILE_FILTER_NAME = "AOTCompileFilter";
@Option(name = AOT_COMPILE_FILTER_NAME, category = OptionCategory.EXPERT, help = "Glob over root names selecting call targets for build-time compilation (empty: all).")//
static final OptionKey<String> AOTCompileFilter = new OptionKey<>("");

static final String AOT_COMPILE_BUDGET_NAME = "AOTCompileBudget";
@Option(name = AOT_COMPILE_BUDGET_NAME, category = OptionCategory.EXPERT, help = "Maximum partial-evaluation graph size (nodes) per build-time compiled root (0: unlimited).")//
static final OptionKey<String> AOTCompileBudget = new OptionKey<>("0");
```

- [ ] **Step 4: Create the pre-init engine unbound when sharing is requested**

In `PolyglotImpl`, change `createDefaultEngine(TruffleLanguage<Object> hostLanguage)` to `createDefaultEngine(TruffleLanguage<Object> hostLanguage, boolean boundEngine)`, passing `boundEngine` as the third of the four `true` literals at ~814. Update existing callers to pass `true`. In `preInitializeEngine()`:
```java
boolean sharing = ImageBuildTimeOptions.getBoolean(ImageBuildTimeOptions.PREINITIALIZE_CODE_SHARING_NAME);
PolyglotEngineImpl engine = createDefaultEngine(new PreInitContextHostLanguage(), !sharing);
```
No change is needed in `PolyglotEngineImpl.preInitialize()`: with sharing enabled and `sharedLayers` still empty, the `!usedSharing` branch creates the pre-init context. Its layer is claimed SHARED lazily in `PolyglotLanguageContext.ensureCreated`, and `allocateInstance` calls `initializeMultiContext`. At run time `PolyglotEngineImpl.patch` overwrites `boundEngine` with the user engine's value (`:750`).

- [ ] **Step 5: Run the test and the whole class**

Run: `mx unittest ContextPreInitializationTest`
Expected: all pass, including `testPreinitializeCodeSharing`.

- [ ] **Step 6: Checkstyle and commit**

```bash
mx checkstyle
git add -A truffle/src/com.oracle.truffle.polyglot truffle/src/com.oracle.truffle.api.test
git commit -m "truffle: pre-initialize contexts in a shared layer with PreinitializeCodeSharing

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Polyglot — parse (never execute) `PreinitializeSources`

**Files:**
- Modify: `truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/PolyglotContextImpl.java` (`preinitialize`, between the language init loop ~3920 and `leaveThreadChanged` ~3922)
- Modify: `truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/PolyglotEngineImpl.java` (new field)
- Test: modify `ContextPreInitializationTest.java`

**Interfaces:**
- Consumes: Task 4's `PREINITIALIZE_SOURCES_NAME`.
- Produces: `PolyglotEngineImpl.preinitializedCallTargets` (`final List<CallTarget>`). It holds the parse results strongly: the source cache and `loadedRoots` are weak, so without it nothing would survive until the heap snapshot. Task 8 reads it via `EngineAccessor`.
- Format: `<languageId>:<path>[,<languageId>:<path>...]`. The path is a filesystem path on the build machine. A missing file or unknown language throws `IllegalArgumentException` whose message names the entry.

- [ ] **Step 1: Write the failing test**

The SHARED test language's `parse` must be observable. In `BaseLanguage` (the shared base of the test languages), add a static `List<String> parsedSources` recorded in `parse(ParsingRequest)`, and a static `AtomicInteger executed` incremented by the returned root's `execute`. Check first how `BaseLanguage` implements `parse`, and wrap it.

```java
@Test
public void testPreinitializeSourcesParseOnly() throws Exception {
    Path file = Files.createTempFile("preinit", ".shared");
    Files.writeString(file, "test source");
    setPatchable(SHARED);
    System.setProperty("polyglot.image-build-time.PreinitializeCodeSharing", "true");
    System.setProperty("polyglot.image-build-time.PreinitializeSources", SHARED + ":" + file);
    try {
        BaseLanguage.parsedSources.clear();
        BaseLanguage.executed.set(0);
        doContextPreinitialize(SHARED);
    } finally {
        System.clearProperty("polyglot.image-build-time.PreinitializeCodeSharing");
        System.clearProperty("polyglot.image-build-time.PreinitializeSources");
        Files.delete(file);
    }
    assertEquals(List.of("test source"), BaseLanguage.parsedSources);
    assertEquals("pre-initialization must never execute build-time sources", 0, BaseLanguage.executed.get());
}

@Test
public void testPreinitializeSourcesUnknownLanguage() throws Exception {
    System.setProperty("polyglot.image-build-time.PreinitializeCodeSharing", "true");
    System.setProperty("polyglot.image-build-time.PreinitializeSources", "nosuchlanguage:/x");
    try {
        doContextPreinitialize(SHARED);
        fail("expected IllegalArgumentException");
    } catch (InvocationTargetException e) {
        assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("nosuchlanguage:/x"));
    } finally {
        System.clearProperty("polyglot.image-build-time.PreinitializeCodeSharing");
        System.clearProperty("polyglot.image-build-time.PreinitializeSources");
    }
}
```
If `doContextPreinitialize` unwraps exceptions differently, match its actual wrapping: it uses reflection, so expect `InvocationTargetException`.

- [ ] **Step 2: Confirm it fails**

Run: `mx unittest ContextPreInitializationTest#testPreinitializeSourcesParseOnly`
Expected: FAIL, `parsedSources` is empty.

- [ ] **Step 3: Implement parsing in `PolyglotContextImpl.preinitialize`**

In `PolyglotEngineImpl`:
```java
/** Parse results of build-time sources; strong so they survive into the image heap. */
final List<CallTarget> preinitializedCallTargets = new ArrayList<>();
```
In `PolyglotContextImpl.preinitialize`, inside the entered block after the language-initialization loop:
```java
String sourcesOption = ImageBuildTimeOptions.get(ImageBuildTimeOptions.PREINITIALIZE_SOURCES_NAME);
if (!sourcesOption.isEmpty()) {
    for (String entry : sourcesOption.split(",")) {
        int colon = entry.indexOf(':');
        String languageId = colon > 0 ? entry.substring(0, colon) : null;
        PolyglotLanguage language = languageId == null ? null : engine.idToLanguage.get(languageId);
        if (language == null || colon == entry.length() - 1) {
            throw new IllegalArgumentException("Invalid " + ImageBuildTimeOptions.PREINITIALIZE_SOURCES_NAME + " entry '" + entry + "': expected <language>:<path> with an installed language.");
        }
        Path path = Path.of(entry.substring(colon + 1));
        com.oracle.truffle.api.source.Source source;
        try {
            source = com.oracle.truffle.api.source.Source.newBuilder(languageId, Files.readString(path), path.getFileName().toString()).build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + ImageBuildTimeOptions.PREINITIALIZE_SOURCES_NAME + " entry '" + entry + "': " + e.getMessage(), e);
        }
        PolyglotLanguageContext languageContext = context.getContextInitialized(language, null);
        CallTarget target = languageContext.parseCached(ParseOrigin.EMBEDDING, null, source, null);
        engine.preinitializedCallTargets.add(target);
    }
}
```
The source is built from content, not from a `TruffleFile`, on purpose. Image code must not depend on build-machine paths, and `sourcesToInvalidate` still records it.

- [ ] **Step 4: Run both tests and the whole class**

Run: `mx unittest ContextPreInitializationTest`
Expected: all pass.

- [ ] **Step 5: Checkstyle and commit**

```bash
mx checkstyle
git add -A truffle/src
git commit -m "truffle: parse PreinitializeSources during pre-initialization without executing them

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Truffle API — `RootNode.cloneForAOT()`

**Files:**
- Modify: `truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/nodes/RootNode.java` (next to `cloneUninitialized()` ~399)
- Modify: `truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/impl/Accessor.java` (`NodeSupport`)
- Modify: `truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/nodes/NodeAccessor.java`
- Modify: `truffle/CHANGELOG.md`
- Modify: `truffle/src/com.oracle.truffle.api/snapshot.sigtest` (regenerated)
- Test: create `truffle/src/com.oracle.truffle.api.test/src/com/oracle/truffle/api/test/nodes/CloneForAOTTest.java`

**Interfaces:**
- Produces:
  ```java
  /**
   * Creates the copy of this root that is prepared ({@link #prepareForAOT()}) and compiled at image
   * build time instead of this root. The default is an uninitialized clone
   * ({@link #cloneUninitialized()} or a node copy of the uninitialized root). Override if the clone
   * would otherwise share mutable speculative state with this root (for example frame-slot kinds in
   * a shared FrameDescriptor): preparing or executing the returned tree must never change state
   * observable by this root. Return {@code null} if this root must not be compiled ahead of time.
   *
   * @since 25.4
   */
  protected RootNode cloneForAOT()
  ```
- Produces: `NodeSupport.cloneForAOT(RootNode root)`, public in the accessor and callable from `com.oracle.truffle.runtime`.

- [ ] **Step 1: Write the failing test**

```java
package com.oracle.truffle.api.test.nodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.test.polyglot.AbstractPolyglotTest;

public class CloneForAOTTest extends AbstractPolyglotTest {

    static final class Cloneable extends RootNode {
        final int value;
        Cloneable(int value) { super(null, new FrameDescriptor()); this.value = value; }
        @Override public Object execute(VirtualFrame frame) { return value; }
        @Override public boolean isCloningAllowed() { return true; }
        @Override protected boolean isCloneUninitializedSupported() { return true; }
        @Override protected RootNode cloneUninitialized() { return new Cloneable(value); }
        RootNode aotClone() { return cloneForAOT(); }
    }

    static final class Refusing extends RootNode {
        Refusing() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return 0; }
        @Override protected RootNode cloneForAOT() { return null; }
        RootNode aotClone() { return cloneForAOT(); }
    }

    @Test
    public void defaultIsUninitializedClone() {
        Cloneable root = new Cloneable(42);
        RootNode clone = root.aotClone();
        assertNotSame(root, clone);
        assertEquals(42, clone.getCallTarget().call());
    }

    @Test
    public void rootMayRefuse() {
        assertNull(new Refusing().aotClone());
    }
}
```

- [ ] **Step 2: Confirm it fails to compile**

Run: `cd truffle && mx build --build-logs=silent`
Expected: FAIL, `cloneForAOT()` is undefined.

- [ ] **Step 3: Implement**

In `RootNode`:
```java
protected RootNode cloneForAOT() {
    if (isCloneUninitializedSupported()) {
        return cloneUninitialized();
    }
    if (isCloningAllowed()) {
        return NodeUtil.cloneNode(this);
    }
    return null;
}
```
In `Accessor.NodeSupport`, add `public abstract RootNode cloneForAOT(RootNode root);`. In `NodeAccessor.AccessNodes`, add `@Override public RootNode cloneForAOT(RootNode root) { return root.cloneForAOT(); }`.

- [ ] **Step 4: Run the tests**

Run: `mx unittest CloneForAOTTest`
Expected: `OK (2 tests)`.

- [ ] **Step 5: Changelog, sigtest, checkstyle, commit**

Add to `truffle/CHANGELOG.md` under `## Version 25.4`:
```
* Added `RootNode.cloneForAOT()`: the copy of a root that is prepared and compiled at native-image build time instead of the root itself, so AOT preparation never affects the live AST.
```
Then:
```bash
cd truffle && mx sigtest --generate && mx checkstyle
git add -A truffle
git commit -m "truffle: add RootNode.cloneForAOT()

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Truffle runtime — shadow targets and AOT tier state

**Files:**
- Create: `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/AOTCode.java`
- Modify: `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/OptimizedCallTarget.java` (fields near ~212; `firstTierCall` ~730-747; new methods next to `cloneUninitialized` ~1095)
- Modify: `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/OptimizedRuntimeOptions.java` (alphabetical)
- Modify: `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/EngineData.java` (imports ~43-86, fields ~150-182, `loadOptions` ~315-374)
- Modify: `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/OptimizedRuntimeSupport.java` (`notifyOnLoad` ~118-128)
- Test: create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/AOTTestLanguage.java`
- Test: create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/AOTShadowTargetTest.java`

**Interfaces:**
- Produces, in `AOTCode`:
  ```java
  /** Handle to build-time compiled code of a call target. Implemented by the Native Image runtime. */
  public interface AOTCode {
      /** Unlinks this code from its call target; the next call runs in the interpreter. Idempotent. */
      void unlink(CharSequence reason);
  }
  ```
- Produces, in `OptimizedCallTarget`:
  - `public final OptimizedCallTarget createAOTShadow()`, hosted use only. It returns `null` if `NodeAccessor.cloneForAOT` returns `null` or `prepareForAOT()` fails. It sets `aotShadow = true` on the result.
  - `@CompilationFinal private boolean aotShadow;` with `public final boolean isAOTShadow()`
  - `private volatile AOTCode aotCode;` with `public final AOTCode getAOTCode()`, `public final void setAOTCode(AOTCode code)` (build time only), and `public final void unlinkAOT(CharSequence reason)` (calls `aotCode.unlink(reason)` if non-null, clears the field, and traces)
- `firstTierCall()` on a shadow: when the threshold is reached, it calls `sourceCallTarget.unlinkAOT("AOT tier threshold reached")` and returns `false`, never `lastTierCompile()`. `aotShadow` is `@CompilationFinal` and the PE folds the receiver to the shadow, so on non-shadow targets this compiles away.
- Engine options:
  - `engine.AOTTierThreshold` (Integer, 0 = use `FirstTierCompilationThreshold`) → `EngineData.aotTierThreshold`
  - `engine.TraceAOT` (Boolean) → `EngineData.traceAOT`
- `notifyOnLoad` skips `compileAOTOnCreate` and `TruffleSplittingStrategy.newTargetCreated` for shadows.

- [ ] **Step 1: Write the test language**

`AOTTestLanguage.java`:
```java
package jdk.graal.compiler.truffle.test.aot;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.GenerateAOT;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.AOTSupport;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Test language for the AOT tier. A source "add:N" parses to a root computing {@code arg0 + N}
 * with a DSL node that is AOT-preparable; "refuse" parses to a root that refuses AOT; "throw"
 * throws; "recurse" calls itself {@code arg0} times.
 */
@TruffleLanguage.Registration(id = AOTTestLanguage.ID, name = AOTTestLanguage.ID, contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class AOTTestLanguage extends TruffleLanguage<Object> {
    public static final String ID = "AOTTestLanguage";

    @Override
    protected Object createContext(Env env) {
        return env;
    }

    @Override
    protected boolean patchContext(Object context, Env newEnv) {
        return true;
    }

    @Override
    protected boolean areOptionsCompatible(org.graalvm.options.OptionValues firstOptions, org.graalvm.options.OptionValues newOptions) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String code = request.getSource().getCharacters().toString().trim();
        RootNode root;
        if (code.startsWith("add:")) {
            root = new AddRoot(this, Integer.parseInt(code.substring(4)));
        } else if (code.equals("refuse")) {
            root = new RefusingRoot(this);
        } else if (code.equals("throw")) {
            root = new ThrowRoot(this);
        } else if (code.equals("recurse")) {
            root = new RecurseRoot(this);
        } else {
            throw new IllegalArgumentException(code);
        }
        return root.getCallTarget();
    }

    @GenerateAOT
    public abstract static class AddNode extends Node {
        abstract Object execute(Object left, int right);

        @Specialization(rewriteOn = ArithmeticException.class)
        static int doInt(int left, int right) {
            return Math.addExact(left, right);
        }

        @Specialization(replaces = "doInt")
        static long doLong(long left, int right) {
            return left + right;
        }

        @Specialization
        static String doString(String left, int right) {
            return left + right;
        }
    }

    public static final class AddRoot extends RootNode {
        final int constant;
        @Child AddNode add = AOTTestLanguageFactory.AddNodeGen.create();

        AddRoot(TruffleLanguage<?> language, int constant) {
            super(language, new FrameDescriptor());
            this.constant = constant;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return add.execute(frame.getArguments()[0], constant);
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected boolean isCloneUninitializedSupported() {
            return true;
        }

        @Override
        protected RootNode cloneUninitialized() {
            return new AddRoot(getLanguage(AOTTestLanguage.class), constant);
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            AOTSupport.prepareForAOT(this);
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "add" + constant;
        }

        /** Test hook: the DSL state of the add node, to prove the live AST is untouched. */
        public boolean isUninitialized() {
            return com.oracle.truffle.api.dsl.Introspection.getSpecializations(add).stream().noneMatch(com.oracle.truffle.api.dsl.Introspection.SpecializationInfo::isActive);
        }
    }

    public static final class RefusingRoot extends RootNode {
        RefusingRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return 0;
        }

        @Override
        protected RootNode cloneForAOT() {
            return null;
        }

        @Override
        public String getName() {
            return "refuse";
        }
    }

    public static final class ThrowRoot extends RootNode {
        ThrowRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            throw new AOTTestException("thrown by " + getName(), this);
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "throw";
        }
    }

    public static final class RecurseRoot extends RootNode {
        @Child com.oracle.truffle.api.nodes.IndirectCallNode call = com.oracle.truffle.api.nodes.IndirectCallNode.create();

        RecurseRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int n = (Integer) frame.getArguments()[0];
            if (n <= 0) {
                return 0;
            }
            return 1 + (Integer) call.call(getCallTarget(), n - 1);
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "recurse";
        }
    }

    @SuppressWarnings("serial")
    public static final class AOTTestException extends com.oracle.truffle.api.exception.AbstractTruffleException {
        AOTTestException(String message, Node location) {
            super(message, location);
        }
    }
}
```
If `ExecutionSignature.GENERIC` doesn't exist, use `ExecutionSignature.create(Object.class, new Class<?>[]{Object.class})`. Check `com.oracle.truffle.api.nodes.ExecutionSignature` first.

- [ ] **Step 2: Write the failing HotSpot test**

`AOTShadowTargetTest.java`:
```java
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.runtime.AOTCode;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.truffle.test.TestWithPolyglotOptions;

public class AOTShadowTargetTest extends TestWithPolyglotOptions {

    private OptimizedCallTarget parse(String code) {
        setupContext(Context.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "true")
                        .option("engine.FirstTierCompilationThreshold", "10").option("engine.AOTTierThreshold", "5"));
        getContext().initialize(AOTTestLanguage.ID);
        getContext().enter();
        try {
            return (OptimizedCallTarget) jdk.graal.compiler.truffle.test.TruffleTestHelpers.parseTarget(getContext(), Source.create(AOTTestLanguage.ID, code));
        } finally {
            getContext().leave();
        }
    }

    @Test
    public void shadowIsPreparedAndLiveAstUntouched() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        assertNotNull(shadow);
        assertTrue(shadow.isAOTShadow());
        assertFalse(live.isAOTShadow());
        assertSame(live, shadow.getSourceCallTarget());
        assertTrue("live AST must stay uninitialized", ((AOTTestLanguage.AddRoot) live.getRootNode()).isUninitialized());
        assertFalse("shadow AST must be AOT-prepared", ((AOTTestLanguage.AddRoot) shadow.getRootNode()).isUninitialized());
    }

    @Test
    public void refusingRootHasNoShadow() {
        assertNull(parse("refuse").createAOTShadow());
    }

    @Test
    public void firstTierShadowUnlinksLiveAtThreshold() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        AtomicReference<CharSequence> unlinked = new AtomicReference<>();
        live.setAOTCode(unlinked::set);
        getContext().enter();
        try {
            shadow.compile(false);                 // first tier: counts via firstTierCall()
            assertTrue(shadow.isValid());
            for (int i = 0; i < 5; i++) {
                assertEquals(i + 1, shadow.call(i));
            }
            assertNotNull("threshold must unlink the live target's AOT code", unlinked.get());
            assertNull(live.getAOTCode());
            assertFalse("a shadow must never be compiled in the last tier", shadow.isValidLastTier());
        } finally {
            getContext().leave();
        }
    }
}
```
If there's no `TruffleTestHelpers.parseTarget`, use the pattern existing compiler Truffle tests use to get a call target from a polyglot source. For example, parse through a small `RootNode` via `AOTTestLanguage` directly in the entered context: `new AOTTestLanguage.AddRoot(language, 1).getCallTarget()`, with the language obtained from `AOTTestLanguage` via `LanguageReference`. Check `jdk.graal.compiler.truffle.test` for an existing helper (grep `getCallTarget()` in `MultiTierCompilationTest`) and reuse it.

- [ ] **Step 3: Confirm it fails**

Run: `cd compiler && mx build --build-logs=silent`
Expected: FAIL, `createAOTShadow`, `setAOTCode` and `AOTCode` are undefined.

- [ ] **Step 4: Implement options**

`OptimizedRuntimeOptions`:
```java
@Option(help = "Number of calls and loop iterations after which build-time compiled (AOT tier) code of a call target is unlinked so that the target is profiled and JIT compiled. 0 uses FirstTierCompilationThreshold.", usageSyntax = "[0, inf)", category = OptionCategory.EXPERT) //
public static final OptionKey<Integer> AOTTierThreshold = new OptionKey<>(0);

@Option(help = "Trace AOT tier events (enter, unlink, invalidate).", category = OptionCategory.INTERNAL) //
public static final OptionKey<Boolean> TraceAOT = new OptionKey<>(false);
```
`EngineData`: add the imports, the fields `@CompilationFinal public int aotTierThreshold; @CompilationFinal public boolean traceAOT;`, and in `loadOptions`:
```java
int aotThreshold = options.get(AOTTierThreshold);
this.aotTierThreshold = aotThreshold > 0 ? aotThreshold : options.get(FirstTierCompilationThreshold);
this.traceAOT = options.get(TraceAOT);
```

- [ ] **Step 5: Implement shadow state in `OptimizedCallTarget`**

Fields next to `aotInitialized`:
```java
@CompilationFinal private boolean aotShadow;
private volatile AOTCode aotCode;
```
Methods next to `cloneUninitialized()`:
```java
public final OptimizedCallTarget createAOTShadow() {
    assert !isSplit() && !aotShadow;
    ensureInitialized();
    RootNode clonedRoot = OptimizedRuntimeAccessor.NODES.cloneForAOT(rootNode);
    if (clonedRoot == null) {
        return null;
    }
    OptimizedCallTarget shadow = (OptimizedCallTarget) OptimizedRuntimeAccessor.NODES.newCallTargetForClone(this, clonedRoot);
    shadow.aotShadow = true;
    if (!shadow.prepareForAOT()) {
        return null;
    }
    return shadow;
}

public final boolean isAOTShadow() {
    return aotShadow;
}

public final AOTCode getAOTCode() {
    return aotCode;
}

public final void setAOTCode(AOTCode code) {
    assert !aotShadow;
    this.aotCode = code;
}

public final void unlinkAOT(CharSequence reason) {
    AOTCode code = aotCode;
    if (code != null) {
        aotCode = null;
        code.unlink(reason);
        if (engine.traceAOT) {
            log("[aot] unlink " + this + ": " + reason);
        }
    }
}
```
`newCallTargetForClone` must build the clone exactly as `RootNode.cloneUninitialized(...)` does at `RootNode.java:403-432`: `NodeAccessor.RUNTIME.newCallTarget(sourceCallTarget, clonedRoot)` plus `setupCallTarget`. Add it to `Accessor.NodeSupport`/`NodeAccessor` as `public abstract CallTarget newCallTargetForClone(CallTarget source, RootNode clonedRoot)`, implemented by reusing the private code path in `RootNode` (extract it into a package-private static helper `RootNode.createClonedCallTarget(CallTarget, RootNode)`).

In `firstTierCall()` (~730-747), keep the counter updates unchanged and replace the threshold check with:
```java
if (aotShadow) {
    if (firstTierLoopCallCount >= engine.aotTierThreshold) {
        sourceCallTarget.unlinkAOTFromCompiledCode();
    }
    return false;
}
if (firstTierCallCount >= engine.callThresholdInFirstTier
                && firstTierLoopCallCount >= scaledThreshold(engine.callAndLoopThresholdInFirstTier)
                && !compilationFailed && !isSubmittedForCompilation()) {
    return lastTierCompile();
}
return false;
```
Add:
```java
@TruffleBoundary
private void unlinkAOTFromCompiledCode() {
    unlinkAOT("AOT tier threshold reached");
}
```
`aotShadow` is `@CompilationFinal` and the receiver is a PE constant, so the non-shadow code is unchanged after partial evaluation. `unlinkAOT` is idempotent, so repeated calls after the threshold are harmless.

- [ ] **Step 6: Skip shadows in `notifyOnLoad`**

In `OptimizedRuntimeSupport.notifyOnLoad` (~118-128), the clone is created before `aotShadow` is set. So have `createAOTShadow` set a thread-local flag (`static final ThreadLocal<Boolean> CREATING_AOT_SHADOW`) around `newCallTargetForClone`. In `notifyOnLoad`, return right after `INSTRUMENT.onLoad(root)` when that flag is set, skipping `compileAOTOnCreate` and `TruffleSplittingStrategy.newTargetCreated`, but still calling `target.setLoaded()`.

- [ ] **Step 7: Run the tests**

Run: `cd compiler && mx build --build-logs=silent && mx unittest AOTShadowTargetTest`
Expected: `OK (3 tests)`.

- [ ] **Step 8: Run the neighbouring tier tests and commit**

Run: `mx unittest MultiTierCompilationTest AOTSupportCompilationTest OptimizedCallTargetTest`
Expected: all pass.
```bash
cd truffle && mx sigtest --generate; cd ../compiler && mx checkstyle
git add -A truffle compiler
git commit -m "truffle: AOT shadow targets and AOT tier state on OptimizedCallTarget

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
`com.oracle.truffle.runtime` has no sigtest snapshot, so skip `mx sigtest` if it reports nothing to do.

---

## Task 8: Truffle runtime — `BuildTimeCompilationSupport` (selection SPI)

**Files:**
- Create: `truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/BuildTimeCompilationSupport.java`
- Modify: `truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/EngineAccessor.java` (next to `forEachLoadedRootNode` ~2335)
- Modify: `truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/impl/Accessor.java` (`EngineSupport`)
- Test: create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/BuildTimeSelectionTest.java`

**Interfaces:**
- Consumes: Task 4/5 options and `preinitializedCallTargets`; Task 7 `createAOTShadow()`.
- Produces:
  ```java
  public final class BuildTimeCompilationSupport {
      public record Pair(OptimizedCallTarget live, OptimizedCallTarget shadow) { }
      public record Rejection(String rootName, String reason) { }
      public record Selection(List<Pair> selected, List<Rejection> rejected) { }

      /** Hosted only: select shared call targets of the pre-initialized engine for build-time compilation. */
      public static Selection selectPreinitialized();

      /** Hosted only: link build-time compiled code to a live target. */
      public static void attach(OptimizedCallTarget live, AOTCode code);
  }
  ```
- Selection rules, in order, with rejection reasons worded exactly as listed:
  1. `AOTCompile` is false → empty selection.
  2. `PreinitializeCodeSharing` is false → `IllegalStateException("AOTCompile requires PreinitializeCodeSharing")`.
  3. Enumerate the loaded roots of every SHARED layer of the pre-initialized engine, plus `preinitializedCallTargets`, deduplicated.
  4. Skip shadows and splits, silently.
  5. Name doesn't match `AOTCompileFilter` → `"filtered"`.
  6. Target already executed → `"executed at build time"`.
  7. `createAOTShadow()` returns null → `"root refused AOT (cloneForAOT or prepareForAOT)"`.

  Budget enforcement happens in SVM (Task 10), after PE.
- Produces, in `EngineSupport`: `public abstract List<RootNode> getPreinitializedSharedRoots()`. It returns the roots from `forEachLoadedRootNode` filtered to SHARED layers of `PolyglotImpl.preInitializedEngineRef.get()`, plus `preinitializedCallTargets`' roots.

- [ ] **Step 1: Write the failing test**

```java
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.oracle.truffle.runtime.BuildTimeCompilationSupport;

public class BuildTimeSelectionTest {

    @After
    public void clear() throws Exception {
        for (String p : List.of("PreinitializeCodeSharing", "PreinitializeSources", "AOTCompile", "AOTCompileFilter", "PreinitializeContexts")) {
            System.clearProperty("polyglot.image-build-time." + p);
        }
        Class.forName("org.graalvm.polyglot.Engine$ImplHolder").getDeclaredMethod("resetPreInitializedEngine").invoke(null);
    }

    private static BuildTimeCompilationSupport.Selection preinit(String sources, String filter) throws Exception {
        System.setProperty("polyglot.image-build-time.PreinitializeContexts", AOTTestLanguage.ID);
        System.setProperty("polyglot.image-build-time.PreinitializeCodeSharing", "true");
        System.setProperty("polyglot.image-build-time.PreinitializeSources", sources);
        System.setProperty("polyglot.image-build-time.AOTCompile", "true");
        System.setProperty("polyglot.image-build-time.AOTCompileFilter", filter);
        var m = Class.forName("org.graalvm.polyglot.Engine$ImplHolder").getDeclaredMethod("preInitializeEngine");
        m.setAccessible(true);
        m.invoke(null);
        return BuildTimeCompilationSupport.selectPreinitialized();
    }

    private static String files(String... codes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            Path f = Files.createTempFile("aot", ".txt");
            Files.writeString(f, code);
            f.toFile().deleteOnExit();
            sb.append(sb.isEmpty() ? "" : ",").append(AOTTestLanguage.ID).append(':').append(f);
        }
        return sb.toString();
    }

    @Test
    public void selectsPreparableAndReportsRefusal() throws Exception {
        var selection = preinit(files("add:1", "refuse"), "");
        assertEquals(List.of("add1"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertTrue(selection.rejected().contains(new BuildTimeCompilationSupport.Rejection("refuse", "root refused AOT (cloneForAOT or prepareForAOT)")));
        selection.selected().forEach(p -> assertTrue(p.shadow().isAOTShadow()));
    }

    @Test
    public void filterRejects() throws Exception {
        var selection = preinit(files("add:1", "add:2"), "add2");
        assertEquals(List.of("add2"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertTrue(selection.rejected().contains(new BuildTimeCompilationSupport.Rejection("add1", "filtered")));
    }
}
```
`Engine$ImplHolder.resetPreInitializedEngine` must exist, reaching `PolyglotImpl.resetPreInitializedEngine` (`:829-831`). If `ImplHolder` doesn't expose it, reset through the same reflective path `TruffleBaseFeature.cleanup()` uses (`invokeStaticMethod(...)`). Read `TruffleBaseFeature.java:513` for the exact class and method names and use them.

- [ ] **Step 2: Confirm it fails**

Run: `cd compiler && mx build --build-logs=silent`
Expected: FAIL, `BuildTimeCompilationSupport` is undefined.

- [ ] **Step 3: Implement the engine-side enumeration**

In `EngineAccessor`:
```java
@Override
public List<RootNode> getPreinitializedSharedRoots() {
    PolyglotEngineImpl engine = PolyglotImpl.getPreInitializedEngine();   // add a static accessor for preInitializedEngineRef.get()
    if (engine == null) {
        return List.of();
    }
    LinkedHashSet<RootNode> roots = new LinkedHashSet<>();
    synchronized (engine.lock) {
        for (PolyglotSharingLayer layer : engine.sharedLayers) {
            if (layer.getContextPolicy() == ContextPolicy.SHARED) {
                forEachLoadedRootNode(layer, root -> {
                    if (layer.equals(NODES.getSharingLayer(root))) {
                        roots.add(root);
                    }
                });
            }
        }
    }
    for (CallTarget target : engine.preinitializedCallTargets) {
        roots.add(((RootCallTarget) target).getRootNode());
    }
    return new ArrayList<>(roots);
}
```
If `PolyglotSharingLayer` has no `getContextPolicy()`, add one that returns `shared.contextPolicy` (`PolyglotSharingLayer.java:91-126`).

- [ ] **Step 4: Implement `BuildTimeCompilationSupport`**

```java
package com.oracle.truffle.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.oracle.truffle.api.nodes.RootNode;

/**
 * Hosted-only entry points used by Native Image to compile shared call targets of the
 * pre-initialized engine at build time (the AOT tier). Never used at run time.
 */
public final class BuildTimeCompilationSupport {
    public record Pair(OptimizedCallTarget live, OptimizedCallTarget shadow) {
    }

    public record Rejection(String rootName, String reason) {
    }

    public record Selection(List<Pair> selected, List<Rejection> rejected) {
    }

    private BuildTimeCompilationSupport() {
    }

    public static Selection selectPreinitialized() {
        if (!Boolean.getBoolean("polyglot.image-build-time.AOTCompile")) {
            return new Selection(List.of(), List.of());
        }
        if (!Boolean.getBoolean("polyglot.image-build-time.PreinitializeCodeSharing")) {
            throw new IllegalStateException("AOTCompile requires PreinitializeCodeSharing");
        }
        Pattern filter = globToPattern(System.getProperty("polyglot.image-build-time.AOTCompileFilter", ""));
        List<Pair> selected = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        Map<OptimizedCallTarget, Boolean> seen = new LinkedHashMap<>();
        for (RootNode root : OptimizedRuntimeAccessor.ENGINE.getPreinitializedSharedRoots()) {
            OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
            if (target.isAOTShadow() || target.isSplit() || seen.putIfAbsent(target, Boolean.TRUE) != null) {
                continue;
            }
            String name = root.getName() == null ? root.toString() : root.getName();
            if (filter != null && !filter.matcher(name).matches()) {
                rejected.add(new Rejection(name, "filtered"));
            } else if (target.wasExecuted()) {
                rejected.add(new Rejection(name, "executed at build time"));
            } else {
                OptimizedCallTarget shadow = target.createAOTShadow();
                if (shadow == null) {
                    rejected.add(new Rejection(name, "root refused AOT (cloneForAOT or prepareForAOT)"));
                } else {
                    selected.add(new Pair(target, shadow));
                }
            }
        }
        return new Selection(List.copyOf(selected), List.copyOf(rejected));
    }

    public static void attach(OptimizedCallTarget live, AOTCode code) {
        live.setAOTCode(code);
    }

    private static Pattern globToPattern(String glob) {
        if (glob.isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case ',' -> regex.append('|');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
```
`createAOTShadow()` calls `ensureInitialized()`, which uses `transferToInterpreterAndInvalidate` and `initialize(true)`. `initialize(true)` asserts the target is in sync with its root. If that assertion fires for pre-init roots, have `createAOTShadow()` call `initialize(false)`, as `prepareForAOT()` does.

- [ ] **Step 5: Run the tests**

Run: `mx build --build-logs=silent && mx unittest BuildTimeSelectionTest`
Expected: `OK (2 tests)`.

- [ ] **Step 6: Commit**

```bash
mx checkstyle
git add -A truffle compiler
git commit -m "truffle: select shared pre-initialized call targets for build-time compilation

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Compiler — `TruffleCompilerImpl.partialEvaluateForBuildTime`

**Files:**
- Modify: `compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/truffle/TruffleCompilerImpl.java` (next to `truffleTier` ~578-615)
- Test: create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/BuildTimePartialEvaluationTest.java`

**Interfaces:**
- Produces:
  ```java
  /**
   * Partially evaluates {@code compilable} as first-tier code (with call/loop counting) and applies
   * the Truffle tier, stopping before the backend. Used by Native Image to compile the AOT tier at
   * build time. No speculation log is used. Returns null on a bailout; the reason is passed to
   * {@code onBailout}.
   */
  public StructuredGraph partialEvaluateForBuildTime(TruffleCompilable compilable, DebugContext debug, java.util.function.Consumer<String> onBailout)
  ```
- Produces an overload taking the partial evaluator explicitly, used by Native Image (Task 10) with its `BuildTimePartialEvaluator`:
  ```java
  public StructuredGraph partialEvaluateForBuildTime(PartialEvaluator pe, TruffleCompilable compilable, DebugContext debug, java.util.function.Consumer<String> onBailout)
  ```
  The 3-argument method delegates to it with this compiler's own `partialEvaluator`.
- Consumes: Task 7 `createAOTShadow()` (the compilable is a shadow).

- [ ] **Step 1: Write the failing test**

```java
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.nodes.TruffleAssumption;
import jdk.graal.compiler.truffle.test.PartialEvaluationTest;

public class BuildTimePartialEvaluationTest extends PartialEvaluationTest {

    @Test
    public void shadowGraphIsFirstTierAndRecordsRootAssumptions() {
        setupContext(Context.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false"));
        getContext().initialize(AOTTestLanguage.ID);
        getContext().enter();
        try {
            OptimizedCallTarget live = (OptimizedCallTarget) new AOTTestLanguage.AddRoot(AOTTestLanguage.getCurrent(), 1).getCallTarget();
            OptimizedCallTarget shadow = live.createAOTShadow();
            TruffleCompilerImpl compiler = getTruffleCompiler(shadow);
            StructuredGraph graph = compiler.partialEvaluateForBuildTime(shadow, getDebugContext(), reason -> {
                throw new AssertionError(reason);
            });
            assertNotNull(graph);
            assertTrue("root assumptions must be recorded", graph.getAssumptions().stream().anyMatch(a -> a instanceof TruffleAssumption));
            assertTrue("AOT-prepared AST must be fully inlined: no residual execute calls",
                            graph.getNodes(MethodCallTargetNode.TYPE).filter(n -> ((MethodCallTargetNode) n).targetMethod().getName().startsWith("execute")).isEmpty());
        } finally {
            getContext().leave();
        }
    }
}
```
Add a static `AOTTestLanguage.getCurrent()` backed by `LanguageReference<AOTTestLanguage> REFERENCE = LanguageReference.create(AOTTestLanguage.class)`. `getTruffleCompiler(target)` and `getDebugContext()` come from `PartialEvaluationTest` / `TruffleCompilerImplTest`; use their exact names from the superclass.

- [ ] **Step 2: Confirm it fails**

Run: `cd compiler && mx build --build-logs=silent`
Expected: FAIL, `partialEvaluateForBuildTime` is undefined.

- [ ] **Step 3: Implement**

Base it on `PartialEvaluationTest.partialEval` (`PartialEvaluationTest.java:417-446`) and `TruffleInliningPolicy.createTruffleMethodGraph`:
```java
public StructuredGraph partialEvaluateForBuildTime(TruffleCompilable compilable, DebugContext debug, Consumer<String> onBailout) {
    return partialEvaluateForBuildTime(partialEvaluator, compilable, debug, onBailout);
}

public StructuredGraph partialEvaluateForBuildTime(PartialEvaluator pe, TruffleCompilable compilable, DebugContext debug, Consumer<String> onBailout) {
    initialize(compilable, true);
    TruffleCompilationTask task = new TruffleCompilationTask() {
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isLastTier() { return false; }
        @Override public boolean hasNextTier() { return true; }
    };
    try (TruffleCompilation compilation = openCompilation(task, compilable);
                    DebugContext.Scope _ = debug.scope("TruffleBuildTimeCompilation", new TruffleDebugJavaMethod(task, compilable));
                    PerformanceInformationHandler handler = PerformanceInformationHandler.install(config.runtime(), getOrCreateCompilerOptions(compilable));
                    CompilationAlarm _ = CompilationAlarm.trackCompilationPeriod(debug.getOptions())) {
        TruffleTierContext context = TruffleTierContext.createInitialContext(pe, getOrCreateCompilerOptions(compilable), debug, compilable,
                        compilation.getCompilationId(), null, task, handler);
        truffleTier.apply(context.graph, context);
        return context.graph;
    } catch (GraalBailoutException | RetryableBailoutException e) {
        onBailout.accept(e.getMessage());
        return null;
    }
}
```
Use whatever import names are already present in `TruffleCompilerImpl`. If `truffleTier` is not a field, call `getTruffleTier()`.

- [ ] **Step 4: Run the tests**

Run: `mx unittest BuildTimePartialEvaluationTest`
Expected: `OK (1 test)`.

- [ ] **Step 5: Commit**

```bash
mx checkstyle
git add -A compiler
git commit -m "compiler: add TruffleCompilerImpl.partialEvaluateForBuildTime

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: SVM — `BuildTimePartialEvaluator` over analysis types

This is the riskiest integration step, so it stands alone. It produces analysis-typed PE graphs during analysis but doesn't install anything yet. It is verified through the build summary of a native test image.

**Files:**
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/BuildTimePartialEvaluator.java`
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/BuildTimePEGraphDecoder.java`
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/BuildTimeCompilationOptions.java`
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/TruffleBuildTimeCompilationFeature.java` (partial here; completed in Task 11)
- Test: create `compiler/src/jdk.graal.compiler.test/src/jdk/graal/compiler/truffle/test/aot/AOTTierNativeTest.java`
- Modify: `substratevm/mx.substratevm/mx_substratevm.py` (`truffle_unittest_task` ~463)

**Interfaces:**
- Consumes:
  - Task 8 `BuildTimeCompilationSupport.selectPreinitialized()`
  - Task 9 `partialEvaluateForBuildTime(...)`
  - `RuntimeCompilationFeature.singleton()`
  - `SubstrateCompilationDirectives.RUNTIME_COMPILED_METHOD`
  - `AnalysisMethod.getOrCreateMethodVariant(key)` and `ensureGraphParsed(bb)`
- Produces:
  - `BuildTimePartialEvaluator extends PartialEvaluator`, constructed from `(TruffleCompilerConfiguration config, GraphBuilderConfiguration graphBuilderConfigForRoot, BigBang bb)`. Its `createGraphDecoder(...)` returns a `BuildTimePEGraphDecoder`.
  - `BuildTimePEGraphDecoder.lookupEncodedGraph(m, bp)` returns `((AnalysisMethod) m).getOrCreateMethodVariant(RUNTIME_COMPILED_METHOD).ensureGraphParsed(bb).getEncodedGraph()`.
  - Hosted options `TruffleAOTPrintSummary`, `TruffleAOTFailOnError` (`HostedOptionKey<Boolean>`, default false).
  - The summary line format, which tests match: `[truffle-aot] compiled <rootName> nodes=<n>` and `[truffle-aot] skipped <rootName>: <reason>`.

- [ ] **Step 1: Write the native test (build summary only in this task)**

`AOTTierNativeTest.java`:
```java
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertEquals;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Runs only in an image built with:
 * -Dpolyglot.image-build-time.PreinitializeContexts=AOTTestLanguage
 * -Dpolyglot.image-build-time.PreinitializeCodeSharing=true
 * -Dpolyglot.image-build-time.PreinitializeSources=AOTTestLanguage:<add1>,AOTTestLanguage:<refuse>,...
 * -Dpolyglot.image-build-time.AOTCompile=true -H:+TruffleAOTPrintSummary
 * (see mx_substratevm.truffle_aot_tier_native_tests).
 */
public class AOTTierNativeTest {

    @Test
    public void addStillWorks() {
        TruffleTestAssumptions.assumeAOT();
        try (Engine engine = Engine.create(); Context context = Context.newBuilder().engine(engine).build()) {
            assertEquals(3, context.eval(AOTTestLanguage.ID, "add:1").execute(2).asInt());
        }
    }
}
```

In `mx_substratevm.py`, add after `truffle_unittest_task`:
```python
def truffle_aot_tier_native_tests(extra_build_args=None, run_args=None):
    extra_build_args = extra_build_args or []
    fixtures = {}
    tmp = tempfile.mkdtemp(prefix='truffle-aot-')
    for name, code in [('add1', 'add:1'), ('add2', 'add:2'), ('refuse', 'refuse'), ('throw', 'throw'), ('recurse', 'recurse')]:
        path = os.path.join(tmp, name + '.aot')
        with open(path, 'w', encoding='utf-8') as f:
            f.write(code)
        fixtures[name] = path
    sources = ','.join('AOTTestLanguage:' + p for p in fixtures.values())
    build_args = extra_build_args + svm_experimental_options(['-H:-SupportCompileInIsolates', '-H:+TruffleAOTPrintSummary']) + [
        '-Dpolyglot.image-build-time.PreinitializeContexts=AOTTestLanguage',
        '-Dpolyglot.image-build-time.PreinitializeCodeSharing=true',
        '-Dpolyglot.image-build-time.PreinitializeSources=' + sources,
        '-Dpolyglot.image-build-time.AOTCompile=true',
    ]
    native_unittest(['jdk.graal.compiler.truffle.test.aot.AOTTierNativeTest'] + truffle_args(build_args) + (run_args or []))
```
Call it at the end of `truffle_unittest_task`. Also expose it as an mx command: `mx_substratevm.register_... 'truffle-aot-native-tests'`. Follow how other commands in this file are registered (look for `mx.update_commands` or `@mx.command`).

- [ ] **Step 2: Confirm the image builds without the feature and the summary is missing**

Run: `cd substratevm && mx build --build-logs=silent && mx truffle-aot-native-tests 2>&1 | tee /tmp/claude-aot-t10.log | tail -5 ; grep -c "\[truffle-aot\]" /tmp/claude-aot-t10.log`
Expected: the test passes (it is interpreted) and the grep count is `0`.

- [ ] **Step 3: Implement the options**

```java
package com.oracle.svm.truffle.buildtime;

import com.oracle.svm.core.option.HostedOptionKey;

import jdk.graal.compiler.options.Option;

public final class BuildTimeCompilationOptions {
    @Option(help = "Print a per-root summary of Truffle build-time (AOT tier) compilation.")//
    public static final HostedOptionKey<Boolean> TruffleAOTPrintSummary = new HostedOptionKey<>(false);

    @Option(help = "Fail the image build if a selected root cannot be compiled at build time.")//
    public static final HostedOptionKey<Boolean> TruffleAOTFailOnError = new HostedOptionKey<>(false);

    private BuildTimeCompilationOptions() {
    }
}
```

- [ ] **Step 4: Implement `BuildTimePEGraphDecoder`**

```java
package com.oracle.svm.truffle.buildtime;

import java.util.concurrent.ConcurrentHashMap;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.graal.compiler.bytecode.BytecodeProvider;
import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.LoopExplosionPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.ParameterPlugin;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.replacements.PEGraphDecoder;
import jdk.graal.compiler.nodes.SourceLanguagePositionProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/** Decodes callee graphs from the analysis-parsed RUNTIME_COMPILED variants during analysis. */
final class BuildTimePEGraphDecoder extends PEGraphDecoder {
    private final BigBang bb;

    BuildTimePEGraphDecoder(BigBang bb, StructuredGraph graph, CoreProviders providers, LoopExplosionPlugin loopExplosionPlugin, InvocationPlugins invocationPlugins,
                    InlineInvokePlugin[] inlineInvokePlugins, ParameterPlugin parameterPlugin, NodePlugin[] nodePlugins, SourceLanguagePositionProvider sourceLanguagePosition,
                    ConcurrentHashMap<SpecialCallTargetCacheKey, Object> specialCallTargetCache, ConcurrentHashMap<ResolvedJavaMethod, Object> invocationPluginsCache) {
        super(AnalysisParsedGraph.HOST_ARCHITECTURE, graph, providers, loopExplosionPlugin, invocationPlugins, inlineInvokePlugins, parameterPlugin, nodePlugins, sourceLanguagePosition,
                        specialCallTargetCache, invocationPluginsCache, true, false);
        this.bb = bb;
    }

    @Override
    protected EncodedGraph lookupEncodedGraph(ResolvedJavaMethod method, BytecodeProvider intrinsicBytecodeProvider) {
        AnalysisMethod runtimeVariant = ((AnalysisMethod) method).getOrCreateMethodVariant(SubstrateCompilationDirectives.RUNTIME_COMPILED_METHOD);
        return runtimeVariant.ensureGraphParsed(bb).getEncodedGraph();
    }
}
```
If an `R` variant doesn't exist for a callee, i.e. it was never registered by `prepareMethodForRuntimeCompilation`, `getOrCreateMethodVariant` creates it without runtime-compilation registration. In that case call `RuntimeCompilationFeature.singleton().prepareMethodForRuntimeCompilation(method, access)` from the feature before PE, for every root method (`profiledPERoot`, `callInlined`, `callDirect`); `TruffleFeature` already does this at `TruffleFeature.java:470-471`. Callees reached by PE are ordinary runtime-compilation candidates. Confirm this with Step 7's summary: no "Graph not available" or null graph.

- [ ] **Step 5: Implement `BuildTimePartialEvaluator`**

```java
package com.oracle.svm.truffle.buildtime;

import java.util.function.Supplier;

import org.graalvm.collections.EconomicMap;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.util.GuestAnnotationAccess;
import com.oracle.svm.util.OriginalClassProvider;

import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.ParameterPlugin;
import jdk.graal.compiler.nodes.SourceLanguagePositionProvider;
import jdk.graal.compiler.replacements.PEGraphDecoder;
import jdk.graal.compiler.truffle.PartialEvaluator;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleTierContext;
import com.oracle.truffle.compiler.ConstantFieldInfo;
import com.oracle.truffle.compiler.PartialEvaluationMethodInfo;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/** Truffle partial evaluator on analysis types, used during points-to analysis. */
final class BuildTimePartialEvaluator extends PartialEvaluator {
    private final BigBang bb;
    private final java.util.concurrent.ConcurrentHashMap<ResolvedJavaMethod, PartialEvaluationMethodInfo> methodInfos = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<ResolvedJavaField, ConstantFieldInfo> fieldInfos = new java.util.concurrent.ConcurrentHashMap<>();

    BuildTimePartialEvaluator(TruffleCompilerConfiguration config, GraphBuilderConfiguration graphBuilderConfigForRoot, BigBang bb) {
        super(config, graphBuilderConfigForRoot);
        this.bb = bb;
    }

    @Override
    protected PEGraphDecoder createGraphDecoder(TruffleTierContext context, InvocationPlugins invocationPlugins, InlineInvokePlugin[] inlineInvokePlugins, ParameterPlugin parameterPlugin,
                    NodePlugin[] nodePlugins, SourceLanguagePositionProvider sourceLanguagePositionProvider, EconomicMap<ResolvedJavaMethod, EncodedGraph> graphCache,
                    Supplier<AutoCloseable> createCachedGraphScope) {
        return new BuildTimePEGraphDecoder(bb, context.graph, config.lastTier().providers().copyWith(this.constantFieldProvider), loopExplosionPlugin, invocationPlugins,
                        inlineInvokePlugins, parameterPlugin, nodePlugins, sourceLanguagePositionProvider, new java.util.concurrent.ConcurrentHashMap<>(), new java.util.concurrent.ConcurrentHashMap<>());
    }

    @Override
    public PartialEvaluationMethodInfo getMethodInfo(ResolvedJavaMethod method) {
        return methodInfos.computeIfAbsent(method, m -> computePartialEvaluationMethodInfo(config.runtime(), m, GuestAnnotationAccess.getDeclaredAnnotationValues(m), types,
                        OriginalClassProvider::getOriginalType));
    }

    @Override
    public ConstantFieldInfo getConstantFieldInfo(ResolvedJavaField field) {
        return fieldInfos.computeIfAbsent(field, f -> computeConstantFieldInfo(f, GuestAnnotationAccess.getDeclaredAnnotationValues(f), types, OriginalClassProvider::getOriginalType));
    }

    @Override
    public boolean isValueType(ResolvedJavaType type) {
        return valueTypes.computeIfAbsent(type, t -> computeIsValueType(t));
    }
}
```
`constantFieldProvider`, `loopExplosionPlugin` and `types` are the protected `PartialEvaluator` fields that `SubstratePartialEvaluator.createGraphDecoder` already uses. Before writing this, open `PartialEvaluator.java` and `SubstrateTruffleUniverseFactory.java:70-98`. Match the exact abstract method set and the parameter lists of `computePartialEvaluationMethodInfo`/`computeConstantFieldInfo`. Declare `private final ConcurrentHashMap<ResolvedJavaType, Boolean> valueTypes = new ConcurrentHashMap<>();`, and implement `computeIsValueType(ResolvedJavaType)` by copying the value-type computation that `SubstrateTruffleUniverseFactory.createType` performs.

`TruffleCompilerConfiguration` for build time: copy `TruffleSupport.createSubstrateTruffleCompilerConfig` (`TruffleSupport.java:97-131`), but use the **analysis** `HostedProviders` of the current `bb` for both tiers, and a `KnownTruffleTypes` created with `new SubstrateKnownTruffleTypes(truffleRuntime, analysisMetaAccess, constantReflection)`. Put this in a factory method `static TruffleCompilerConfiguration createAnalysisConfig(BeforeAnalysisAccessImpl access)` in `TruffleBuildTimeCompilationFeature`.

- [ ] **Step 6: Implement the feature skeleton, which runs PE and prints the summary**

```java
package com.oracle.svm.truffle.buildtime;

import java.util.List;

import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.truffle.TruffleFeature;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;

/** Orchestrates the Truffle AOT tier: selection, build-time PE, installation (Task 11+). */
public final class TruffleBuildTimeCompilationFeature implements InternalFeature {
    private BuildTimeCompilationSupport.Selection selection;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return TruffleFeature.isInConfiguration() && Boolean.getBoolean("polyglot.image-build-time.AOTCompile");
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return List.of(TruffleFeature.class);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        selection = BuildTimeCompilationSupport.selectPreinitialized();
        for (BuildTimeCompilationSupport.Rejection r : selection.rejected()) {
            summary("skipped " + r.rootName() + ": " + r.reason());
        }
        // Task 10: PE eagerly to validate the pipeline; Task 11 moves PE into BuildTimeCompiledMethod.buildGraph.
        BuildTimePartialEvaluator pe = new BuildTimePartialEvaluator(createAnalysisConfig(access), graphBuilderConfigForRoot(access), access.getBigBang());
        TruffleCompilerImpl compiler = (TruffleCompilerImpl) SubstrateTruffleRuntime.getRuntime().getTruffleCompiler(null);   // the compiler pre-initialized by TruffleFeature
        DebugContext debug = new DebugContext.Builder(access.getBigBang().getOptions()).build();
        for (BuildTimeCompilationSupport.Pair pair : selection.selected()) {
            String name = pair.live().getRootNode().getName();
            var graph = compiler.partialEvaluateForBuildTime(pe, pair.shadow(), debug, reason -> summary("skipped " + name + ": " + reason));
            if (graph != null) {
                summary("compiled " + name + " nodes=" + graph.getNodeCount());
            }
        }
    }

    static void summary(String line) {
        if (BuildTimeCompilationOptions.TruffleAOTPrintSummary.getValue()) {
            System.out.println("[truffle-aot] " + line);
        }
    }
}
```
`graphBuilderConfigForRoot(access)` returns the same `GraphBuilderConfiguration` that `TruffleFeature.beforeAnalysis` passes to `SubstratePartialEvaluator` (~444-469): copy that construction into a static helper here. Obtain the pre-initialized compiler the way `TruffleFeature` does (`truffleRuntime.preinitializeTruffleCompiler()` returns it at ~436). If `getTruffleCompiler(null)` isn't the right accessor, store that return value in a static on `TruffleFeature` and read it here. Register the feature for automatic inclusion with `@AutomaticallyRegisteredFeature` (as other `com.oracle.svm.truffle` features do), guarded by `isInConfiguration`.

- [ ] **Step 7: Build and check the summary**

Run:
```bash
cd substratevm && mx build --build-logs=silent && mx truffle-aot-native-tests 2>&1 | tee /tmp/claude-aot-t10.log | tail -5; grep "\[truffle-aot\]" /tmp/claude-aot-t10.log
```
Expected: the test passes, and the log contains:
```
[truffle-aot] skipped refuse: root refused AOT (cloneForAOT or prepareForAOT)
[truffle-aot] compiled add1 nodes=<n>
[truffle-aot] compiled add2 nodes=<n>
[truffle-aot] compiled throw nodes=<n>
[truffle-aot] compiled recurse nodes=<n>
```
If PE fails inside the builder (exceptions from `KnownTruffleTypes` lookups, invocation-plugin mismatches, or missing `R` graphs), stop and bring the stack trace back to the spec's §5.4 build-time PE decision. This is spec risk "analysis ordering" / PE-on-analysis-types.

- [ ] **Step 8: Commit**

```bash
mx checkstyle
git add -A substratevm compiler
git commit -m "svm: partially evaluate AOT shadow targets on analysis types during the image build

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: SVM — `BuildTimeCompiledMethod`, installation and AOT dispatch

**Files:**
- Create: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/code/DeoptSourceGraphKit.java` (factored from `heap/PodFactorySubstitutionMethod.java`: `startMethod`, `invokeWithDeoptAndExceptionUnwind`, `DeoptInfoProvider`)
- Modify: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/heap/PodFactorySubstitutionMethod.java` (use the kit)
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/BuildTimeCompiledMethod.java`
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/AOTInstalledCode.java`
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/AOTCodeTable.java`
- Modify: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/TruffleBuildTimeCompilationFeature.java`
- Modify: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/api/SubstrateOptimizedCallTargetInstalledCode.java` (`doInvoke` ~243-258)
- Test: modify `AOTTierNativeTest.java`

**Interfaces:**
- Consumes:
  - Task 2 `registerDeoptSource`
  - Task 3 `ImageCodeInstalledCodeLookup`
  - Task 7 `AOTCode` and `setAOTCode`/`getAOTCode`
  - Task 10's partial evaluator
- Produces:
  - `BuildTimeCompiledMethod extends NonBytecodeMethod`: static, `(OptimizedCallTarget, Object[]) → Object`, named `aot_<id>_<rootName>`. Its `buildGraph` has two modes:
    - **source** (`!isDeoptTarget(method)`): a kit graph invoking `profiledPERoot` on the shadow constant, then PE inlining, then guard lowering to fixed deopts;
    - **deopt target**: a kit graph with `DeoptEntryNode`s at the invoke of `profiledPERoot`, via `DeoptSourceGraphKit`.
  - `AOTInstalledCode implements AOTCode, SubstrateInstalledCode, OptimizedAssumptionDependency`, holding a final `AOTEntry entry` (an immutable holder of `CFunctionPointer entryPoint`, plus `int codeSize` as an `@UnknownPrimitiveField(availability = BuildPhaseProvider.AfterCompilation.class)`) and a `WeakReference`-free final `OptimizedCallTarget live`.
  - `AOTCodeTable implements ImageCodeInstalledCodeLookup`: an array of `AOTInstalledCode` sorted by entry address at first use. `lookup(ip)` is a binary search over [entry, entry + codeSize).
  - Dispatch: `doInvoke` → JIT `entryPoint` if non-zero; else `callTarget.getAOTCode()` is an `AOTInstalledCode` → `entry.invoke(callTarget, args)` via `CallBoundaryFunctionPointer`; else `invokeCallBoundary`.
  - `AOTInstalledCode.unlink(reason)`, called from the threshold at a normal call site, must not require a safepoint for the plain field clear. `unlinkAOT` already nulled the field on the target; `unlink` records the state for tracing only. Invalidation is Task 12.

- [ ] **Step 1: Extend the native test**

Add to `AOTTierNativeTest`:
```java
@Test
public void aotCodeRunsFromFirstCall() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").logHandler(LOG).build();
                    Context context = Context.newBuilder().engine(engine).build()) {
        assertEquals(3, context.eval(AOTTestLanguage.ID, "add:1").execute(2).asInt());
    }
    assertTrue(LOG.toString(), LOG.toString().contains("[aot] enter add1"));
}
```
with `static final java.io.ByteArrayOutputStream LOG = new java.io.ByteArrayOutputStream();`, and clear it in `@Before`. `TraceAOT` logs `enter` on the *first* AOT dispatch per target: add that trace in `doInvoke`'s AOT branch through a `@TruffleBoundary`-free, uninterruptible-safe call. Since `doInvoke` is `@Uninterruptible`, don't log there. Instead, the compiled `profiledPERoot` of the shadow is the first frame that runs, so have `BuildTimeCompiledMethod`'s source graph call a `@NeverInline static void AOTInstalledCode.traceEnter(AOTInstalledCode)` before the PE'd body. That method checks `live.engine.traceAOT` and a one-shot flag.

- [ ] **Step 2: Confirm it fails**

Run: `cd substratevm && mx build --build-logs=silent && mx truffle-aot-native-tests`
Expected: FAIL, the log has no `[aot] enter add1`.

- [ ] **Step 3: Factor `DeoptSourceGraphKit` out of `PodFactorySubstitutionMethod`**

Move `DeoptInfoProvider`, `startMethod`, `invokeWithDeoptAndExceptionUnwind`, `appendDeoptWithExceptionUnwind` and `shouldInsertDeoptEntry` from `PodFactorySubstitutionMethod` into `public final class DeoptSourceGraphKit` in `com.oracle.svm.hosted.code`, as `public static` methods taking `HostedGraphKit`. Keep them byte-for-byte equivalent and change `PodFactorySubstitutionMethod` to call them. Then run the existing Pod tests to prove nothing changed:
```bash
mx build --build-logs=silent && mx native-unittest com.oracle.svm.test.pod
```
Expected: pass. If the package name differs, find it with `grep -rl "PodTest\|Pod.Builder" substratevm/src/com.oracle.svm.test` and use it.

- [ ] **Step 4: Implement `BuildTimeCompiledMethod`**

```java
package com.oracle.svm.truffle.buildtime;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.hosted.code.DeoptSourceGraphKit;
import com.oracle.svm.hosted.code.NonBytecodeMethod;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.phases.HostedGraphKit;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * {@code static Object aot_<id>_<name>(OptimizedCallTarget receiver, Object[] args)}: the AOT tier
 * code of one shared call target. The source variant is the partially evaluated shadow target; the
 * DEOPT_TARGET variant is a kit graph with deopt entries at the call of profiledPERoot so the
 * outermost frame of a deoptimization has a target.
 */
final class BuildTimeCompiledMethod extends NonBytecodeMethod {
    final OptimizedCallTarget live;
    final OptimizedCallTarget shadow;
    final TruffleBuildTimeCompilationFeature feature;

    BuildTimeCompiledMethod(TruffleBuildTimeCompilationFeature feature, OptimizedCallTarget live, OptimizedCallTarget shadow, ResolvedJavaType holder, Signature signature, ConstantPool cp) {
        super("aot_" + live.id + "_" + sanitize(live.getRootNode().getName()), true, holder, signature, cp);
        this.feature = feature;
        this.live = live;
        this.shadow = shadow;
    }

    @Override
    public StructuredGraph buildGraph(DebugContext debug, AnalysisMethod method, HostedProviders providers, Purpose purpose) {
        HostedGraphKit kit = new HostedGraphKit(debug, providers, method);
        DeoptSourceGraphKit.DeoptInfoProvider deoptInfo = SubstrateCompilationDirectives.isDeoptTarget(method) ? new DeoptSourceGraphKit.DeoptInfoProvider(method) : null;
        int nextDeoptIndex = DeoptSourceGraphKit.startMethod(kit, deoptInfo, 0);
        ValueNode receiver = kit.createObject(shadow);
        ValueNode args = kit.getInitialArguments().get(1);
        nextDeoptIndex = DeoptSourceGraphKit.invokeWithDeoptAndExceptionUnwind(kit, deoptInfo, nextDeoptIndex, feature.profiledPERoot(), InvokeKind.Special, receiver, args);
        kit.createReturn(kit.getFrameState().pop(JavaKind.Object), JavaKind.Object);
        StructuredGraph rootGraph = kit.finalizeGraph();
        if (deoptInfo != null) {
            return rootGraph;                                  // deopt target: no PE
        }
        return feature.partiallyEvaluate(this, rootGraph, debug, method, providers);   // source: PE + guard lowering
    }

    private static String sanitize(String name) {
        return name == null ? "root" : name.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
```
`invokeWithDeoptAndExceptionUnwind` returns the next deopt index, and the invoke's value is on the kit frame state. Check how `PodFactorySubstitutionMethod` obtains the result of its constructor invoke (it has none, since it's void) and adapt: use `kit.startInvokeWithException(...)` and take the invoke node's value if the helper doesn't push. Adjust the two lines accordingly while keeping the deopt-entry behavior.

`feature.partiallyEvaluate(...)` does:
1. Encode `rootGraph` (`GraphEncoder.encodeSingleGraph(rootGraph, AnalysisParsedGraph.HOST_ARCHITECTURE)`).
2. Call `partialEvaluateForBuildTime` with the `BuildTimePartialEvaluator`, rooted at this method, so that the outermost frame state is this method's. This follows the spike's pattern: `lookupEncodedGraph(this)` returns the encoded root graph, and `profiledPERoot` is inlined with the shadow as a constant receiver. The build-time decoder needs a `rootMethod`/`encodedRoot` pair (as in the spike's `Decoder`) when the requested method is the `BuildTimeCompiledMethod`, so extend `BuildTimePEGraphDecoder` with an optional `(AnalysisMethod rootMethod, EncodedGraph encodedRoot)`.
3. Run guard lowering: `new GuardLoweringPhase().apply(graph, providers)` and set the graph's stage/state exactly as `CompileQueue.configureExplicitExceptionsNoDeoptIfNecessary` (~1214) expects hosted graphs. Read that method and replicate the state flags.
4. Check the budget (`AOTCompileBudget` > 0 and `graph.getNodeCount()` > budget → skip with `"over budget (<n> > <budget>)"`).
5. Return the graph, or, when skipped, a trivial graph that tail-calls `live.callBoundary` (`kit` invoke of `OptimizedCallTarget.callBoundary` on the `live` constant). In that case the feature does not link AOT code for this target.

- [ ] **Step 5: Implement `AOTInstalledCode` and `AOTCodeTable`**

```java
package com.oracle.svm.truffle.buildtime;

import org.graalvm.nativeimage.c.function.CFunctionPointer;

import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.truffle.runtime.AOTCode;
import com.oracle.truffle.runtime.OptimizedCallTarget;

/** Immutable image-heap holder of the relocatable entry point of one AOT tier method. */
final class AOTEntry {
    final CFunctionPointer entryPoint;
    @UnknownPrimitiveField(availability = BuildPhaseProvider.AfterCompilation.class) int codeSize;

    AOTEntry(CFunctionPointer entryPoint) {
        this.entryPoint = entryPoint;
    }
}
```
```java
/** The AOT tier code of one live call target. */
public final class AOTInstalledCode implements AOTCode /* , SubstrateInstalledCode, OptimizedAssumptionDependency: Task 12 */ {
    final OptimizedCallTarget live;
    final AOTEntry entry;
    private volatile boolean unlinked;
    private volatile boolean traced;

    AOTInstalledCode(OptimizedCallTarget live, AOTEntry entry) {
        this.live = live;
        this.entry = entry;
    }

    @Override
    public void unlink(CharSequence reason) {
        unlinked = true;
    }

    boolean isUnlinked() {
        return unlinked;
    }

    @com.oracle.svm.core.NeverInline("trace hook called from AOT code")
    static void traceEnter(AOTInstalledCode code) {
        if (!code.traced && code.live.engine.traceAOT) {
            code.traced = true;
            code.live.log("[aot] enter " + code.live.getRootNode().getName());
        }
    }
}
```
`AOTEntry` must be registered with `registerAsImmutable` in `beforeCompilation`, because objects holding relocatable pointers must be immutable (the spike's lesson). `AOTInstalledCode` stays mutable and holds the entry *by reference*. Check that `OptimizedCallTarget.log(String)` (`:1241`) is accessible; if it is package-private, add a public `logAOT(String)` in Task 7's style.

`AOTCodeTable`:
```java
final class AOTCodeTable implements com.oracle.svm.core.deopt.ImageCodeInstalledCodeLookup {
    private final AOTInstalledCode[] codes;   // sorted by entry address at build time (afterCompilation)

    AOTCodeTable(AOTInstalledCode[] codes) {
        this.codes = codes;
    }

    @Override
    public com.oracle.svm.core.deopt.SubstrateInstalledCode lookup(org.graalvm.nativeimage.c.function.CodePointer ip) {
        long pc = ip.rawValue();
        int lo = 0;
        int hi = codes.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            AOTInstalledCode c = codes[mid];
            long start = c.entry.entryPoint.rawValue();
            if (pc < start) {
                hi = mid - 1;
            } else if (pc >= start + c.entry.codeSize) {
                lo = mid + 1;
            } else {
                return (com.oracle.svm.core.deopt.SubstrateInstalledCode) (Object) c;   // AOTInstalledCode implements SubstrateInstalledCode in Task 12
            }
        }
        return null;
    }
}
```
Build-time relocated pointers have no raw value. So sort by the `HostedMethod.getCodeAddressOffset()` of each method in `afterCompilation` (via `FeatureImpl.AfterCompilationAccessImpl.getCompilations()`), and set `codeSize` from `compilations.get(hMethod).result.getTargetCodeSize()` (`CompileQueue.java:691`). At run time the relocated pointers keep that order, because they're offsets from the same text base.

- [ ] **Step 6: Wire everything in the feature**

In `TruffleBuildTimeCompilationFeature`:
- **`beforeAnalysis`**, for each selected pair:
  1. Create a `BuildTimeCompiledMethod`, declared in holder class `AOTInstalledCode`, using its constructor's `ConstantPool`, as in the spike.
  2. `AnalysisMethod aMethod = access.getUniverse().lookup(m)`
  3. `SubstrateCompilationDirectives.singleton().registerDeoptSource(aMethod)`
  4. `aMethod.getOrCreateMethodVariant(DEOPT_TARGET_METHOD)`
  5. `access.registerAsRoot(aMethod, true, "Truffle AOT tier " + name, DEOPT_TARGET_METHOD)`
  6. `AOTEntry entry = new AOTEntry(new MethodPointer(aMethod))`
  7. `AOTInstalledCode code = new AOTInstalledCode(pair.live(), entry)`
  8. `BuildTimeCompilationSupport.attach(pair.live(), code)`
  9. `access.rescanField(pair.live(), OptimizedCallTarget.class.getDeclaredField("aotCode"), new OtherReason("Truffle AOT tier"))`

  Also call `RuntimeCompilationFeature.singleton().prepareMethodForRuntimeCompilation(...)` for `profiledPERoot` if not already prepared. Remove Task 10's eager PE loop: PE now happens in `buildGraph`.
- **`afterRegistration`**: `ImageSingletons.add(ImageCodeInstalledCodeLookup.class, table)` with a table whose array is filled in `afterCompilation`. Make `codes` non-final with an `@UnknownObjectField(availability = AfterCompilation.class)`.
- **`beforeCompilation`**: `registerAsImmutable` each `AOTEntry`.
- **`afterCompilation`**: fill each `codeSize` and the sorted table.

In `SubstrateOptimizedCallTargetInstalledCode.doInvoke`:
```java
long start = callTarget.installedCode.entryPoint;
if (start != 0) {
    SubstrateOptimizedCallTarget.CallBoundaryFunctionPointer target = Word.pointer(start);
    return target.invoke(callTarget, args);
}
AOTCode aot = callTarget.getAOTCode();
if (aot instanceof AOTInstalledCode code) {
    return ((SubstrateOptimizedCallTarget.CallBoundaryFunctionPointer) code.entry.entryPoint).invoke(callTarget, args);
}
return callTarget.invokeCallBoundary(args);
```
`AOTInstalledCode` lives in `com.oracle.svm.truffle.buildtime`; make the `entry` field and the `AOTEntry.entryPoint` accessible from `api` (public getters with `@Uninterruptible(reason = "called from doInvoke", mayBeInlined = true)`). Add the `traceEnter` call as the first node of the source graph in `partiallyEvaluate` (a static invoke of `AOTInstalledCode.traceEnter` with the `AOTInstalledCode` constant, before `profiledPERoot`).

- [ ] **Step 7: Run the native tests**

Run: `mx build --build-logs=silent && mx truffle-aot-native-tests`
Expected: `OK (2 tests)`, and the summary lists the compiled roots.

- [ ] **Step 8: Commit**

```bash
mx checkstyle
git add -A substratevm
git commit -m "svm: install build-time compiled AOT tier code into shared Truffle call targets

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: SVM — invalidation and assumption persistence for AOT code

**Files:**
- Modify: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/AOTInstalledCode.java`
- Create: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/InvalidateAOTCodeOperation.java`
- Modify: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/TruffleBuildTimeCompilationFeature.java`
- Test: modify `AOTTestLanguage.java` and `AOTTierNativeTest.java`

**Interfaces:**
- Consumes: Task 3 lookup; Task 11 table and dispatch.
- Produces:
  - `AOTInstalledCode implements SubstrateInstalledCode, OptimizedAssumptionDependency`. Its `invalidate()` and `onAssumptionInvalidated(source, reason)` enqueue `InvalidateAOTCodeOperation`, which at a safepoint does `live.unlinkAOT(reason)`, then `Deoptimizer.deoptimizeInRange(entry, entry + codeSize, false, currentThread)`. The operation is idempotent.
  - At build time, after PE of each shadow, for each `TruffleAssumption` in `graph.getAssumptions()`: resolve the `OptimizedAssumption` object (via the snippet reflection of the constant), call `registerDependency()`, `accept(code)`, and rescan the assumption. If the assumption is already invalid, skip the root with reason `"assumption invalid at build time"`.

- [ ] **Step 1: Extend the test language and the tests**

In `AOTTestLanguage`, add a source `"assume"`: a root that reads `Assumption STABLE` (a static `Assumption` created by `Truffle.getRuntime().createAssumption("aot-test")` in the language class). If valid it returns `1`, otherwise it `transferToInterpreterAndInvalidate`s and returns `2`. Expose `static void invalidateStable()`.

Add to `AOTTierNativeTest` (and add the `assume` fixture to `mx_substratevm.truffle_aot_tier_native_tests`):
```java
@Test
public void assumptionRegisteredAtBuildTimeInvalidatesAOTCode() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = traceEngine(); Context context = Context.newBuilder().engine(engine).build()) {
        Value f = context.eval(AOTTestLanguage.ID, "assume");
        assertEquals(1, f.execute().asInt());
        AOTTestLanguage.invalidateStable();
        assertEquals(2, f.execute().asInt());
    }
    assertTrue(LOG.toString(), LOG.toString().contains("[aot] unlink assume"));
}

@Test
public void exclusionDeoptKeepsCorrectResult() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = traceEngine(); Context context = Context.newBuilder().engine(engine).build()) {
        Value add = context.eval(AOTTestLanguage.ID, "add:2");
        assertEquals(3, add.execute(1).asInt());
        assertEquals(Integer.MAX_VALUE + 2L, add.execute(Integer.MAX_VALUE).asLong());   // overflow: rewriteOn in the shadow
        assertEquals("x2", add.execute("x").asString());
    }
}
```
`traceEngine()` builds an explicit engine with `engine.TraceAOT=true` and `logHandler(LOG)`.

- [ ] **Step 2: Confirm it fails**

Run: `mx build --build-logs=silent && mx truffle-aot-native-tests`
Expected: FAIL. `assumptionRegisteredAtBuildTimeInvalidatesAOTCode` either returns `1` after invalidation (no dependency) or crashes in `invalidateMethodOfFrame`.

- [ ] **Step 3: Implement the VM operation**

```java
package com.oracle.svm.truffle.buildtime;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.thread.JavaVMOperation;

import jdk.graal.compiler.word.Word;

final class InvalidateAOTCodeOperation extends JavaVMOperation {
    private final AOTInstalledCode code;
    private final CharSequence reason;

    InvalidateAOTCodeOperation(AOTInstalledCode code, CharSequence reason) {
        super(VMOperationInfos.get(InvalidateAOTCodeOperation.class, "Invalidate Truffle AOT tier code", SystemEffect.SAFEPOINT));
        this.code = code;
        this.reason = reason;
    }

    @Override
    protected void operate() {
        code.live.unlinkAOT(reason);
        Pointer start = Word.pointer(code.entry.entryPoint.rawValue());
        Deoptimizer.deoptimizeInRange((org.graalvm.nativeimage.c.function.CodePointer) start, (org.graalvm.nativeimage.c.function.CodePointer) start.add(code.entry.codeSize), false, CurrentIsolate.getCurrentThread());
    }
}
```
Match the constructor of `JavaVMOperation` and `VMOperationInfos.get` against an existing operation, such as `CodeInfoTable.InvalidateInstalledCodeOperation` (`CodeInfoTable.java:297-311`). Use the same pointer conversion idiom that `RuntimeCodeCache.invalidateMethod` uses for `deoptimizeInRange`.

- [ ] **Step 4: Make `AOTInstalledCode` a `SubstrateInstalledCode` and an `OptimizedAssumptionDependency`**

Implement exactly the abstract methods of both interfaces:
- `invalidate()` → `new InvalidateAOTCodeOperation(this, "invalidated").enqueue()`
- `onAssumptionInvalidated(Object source, CharSequence reason)` → `new InvalidateAOTCodeOperation(this, reason).enqueue()`
- `isValid()` / `isAlive()` → `!unlinked`
- `getAddress()` / `getEntryPoint()` → `entry.entryPoint.rawValue()`
- `setAddress` / `clearAddress` → `throw VMError.shouldNotReachHere(...)`
- `getSpeculationLog()` → `null`
- `getCompilable()` (for `OptimizedAssumptionDependency`) → `live`

Remove the cast in `AOTCodeTable.lookup`.

- [ ] **Step 5: Register assumptions at build time**

In `partiallyEvaluate`, after PE and before guard lowering:
```java
for (Assumptions.Assumption a : graph.getAssumptions()) {
    if (a instanceof TruffleAssumption ta) {
        OptimizedAssumption assumption = providers.getSnippetReflection().asObject(OptimizedAssumption.class, ta.getAssumption());
        java.util.function.Consumer<OptimizedAssumptionDependency> entry = assumption.registerDependency();
        if (entry == null) {
            return skip(method, "assumption invalid at build time");
        }
        entry.accept(code);
        rescanObject(assumption);
    }
}
```
`ta.getAssumption()` must match `TruffleAssumption`'s accessor for its `JavaConstant` (see `TC/nodes/TruffleAssumption.java:30`), and `registerDependency()`'s return type must match `OptimizedAssumption.java:284-305`; adjust both names to the real API. `rescanObject` goes through the analysis access (`BeforeAnalysisAccessImpl.rescanObject` or the `DuringAnalysis` variant captured by the feature).

Also handle assumptions invalidated **during the build**, after registration. At build time `onAssumptionInvalidated` must not enqueue a VM operation, so start both `invalidate()` and `onAssumptionInvalidated` with:
```java
if (SubstrateUtil.HOSTED) {
    TruffleBuildTimeCompilationFeature.dropAtBuildTime(this, reason);   // records "assumption invalidated during build: <reason>"
    return;
}
```
`dropAtBuildTime` calls `live.setAOTCode(null)`, rescans `live`'s `aotCode` field, and records the rejection for the summary and for `TruffleAOTFailOnError`. Add this native test:
```java
@Test
public void droppedAtBuildTimeRunsInterpreted() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = traceEngine(); Context context = Context.newBuilder().engine(engine).build()) {
        assertEquals(7, context.eval(AOTTestLanguage.ID, "buildinval").execute().asInt());
    }
    assertFalse(LOG.toString(), LOG.toString().contains("[aot] enter buildinval"));
}
```
Here `"buildinval"` is a root that depends on a second static assumption, `BUILD_STABLE`. The language invalidates it in a static initializer hook that the test image runs during `afterAnalysis`: add `--features=jdk.graal.compiler.truffle.test.aot.AOTTestBuildTimeInvalidationFeature`, a tiny feature whose `afterAnalysis` calls `AOTTestLanguage.invalidateBuildStable()`, to the build args of `truffle_aot_tier_native_tests`. Add the `buildinval` fixture too.

- [ ] **Step 6: Run the tests**

Run: `mx build --build-logs=silent && mx truffle-aot-native-tests`
Expected: `OK (5 tests)`.

- [ ] **Step 7: Commit**

```bash
mx checkstyle
git add -A substratevm compiler
git commit -m "svm: invalidate and deoptimize Truffle AOT tier code; persist assumption dependencies

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: End-to-end native behavior (threshold, sharing, Review Focus)

**Files:**
- Test: modify `AOTTierNativeTest.java`
- Modify: whichever production file a failing test points to. Each fix is committed with its test.

**Interfaces:** Consumes everything above. Produces no new API.

- [ ] **Step 1: Write the tests**

```java
@Test
public void thresholdUnlinksThenJITTakesOver() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").option("engine.AOTTierThreshold", "10")
                    .option("engine.BackgroundCompilation", "false").logHandler(LOG).build(); Context context = Context.newBuilder().engine(engine).build()) {
        Value add = context.eval(AOTTestLanguage.ID, "add:1");
        for (int i = 0; i < 2000; i++) {
            assertEquals(i + 1, add.execute(i).asInt());
        }
    }
    String log = LOG.toString();
    assertTrue(log, log.contains("[aot] enter add1"));
    assertTrue(log, log.contains("[aot] unlink add1"));
}

@Test
public void thresholdOneUnlinksOnce() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").option("engine.AOTTierThreshold", "1").logHandler(LOG).build();
                    Context context = Context.newBuilder().engine(engine).build()) {
        Value add = context.eval(AOTTestLanguage.ID, "add:1");
        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1, add.execute(i).asInt());
        }
    }
    assertEquals(1, LOG.toString().split("\\[aot\\] unlink add1", -1).length - 1);
}

@Test
public void twoContextsShareAOTCode() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = traceEngine(); Context c1 = Context.newBuilder().engine(engine).build(); Context c2 = Context.newBuilder().engine(engine).build()) {
        assertEquals(3, c1.eval(AOTTestLanguage.ID, "add:1").execute(2).asInt());
        assertEquals(5, c2.eval(AOTTestLanguage.ID, "add:1").execute(4).asInt());
    }
    assertEquals("one shared call target, entered once", 1, LOG.toString().split("\\[aot\\] enter add1", -1).length - 1);
}

@Test
public void concurrentCallsDuringUnlink() throws Exception {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.AOTTierThreshold", "100").build()) {
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        for (int t = 0; t < 8; t++) {
            threads.add(new Thread(() -> {
                try (Context c = Context.newBuilder().engine(engine).build()) {
                    Value add = c.eval(AOTTestLanguage.ID, "add:1");
                    for (int i = 0; i < 5000; i++) {
                        if (add.execute(i).asInt() != i + 1) {
                            throw new AssertionError("wrong result at " + i);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread th : threads) {
            th.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}

@Test
public void incompatibleContextDoesNotUseAOT() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").option("engine.UsePreInitializedContext", "false").logHandler(LOG).build();
                    Context context = Context.newBuilder().engine(engine).build()) {
        assertEquals(3, context.eval(AOTTestLanguage.ID, "add:1").execute(2).asInt());
    }
    assertFalse(LOG.toString(), LOG.toString().contains("[aot] enter"));
}

@Test
public void exceptionThroughAOTCode() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = traceEngine(); Context context = Context.newBuilder().engine(engine).build()) {
        Value f = context.eval(AOTTestLanguage.ID, "throw");
        for (int i = 0; i < 3; i++) {
            try {
                f.execute();
                fail();
            } catch (PolyglotException e) {
                assertEquals("thrown by throw", e.getMessage());
                assertTrue(e.isGuestException());
            }
        }
    }
    assertTrue(LOG.toString(), LOG.toString().contains("[aot] enter throw"));
}

@Test
public void deepRecursionInAOTCode() {
    TruffleTestAssumptions.assumeAOT();
    try (Engine engine = traceEngine(); Context context = Context.newBuilder().engine(engine).build()) {
        Value f = context.eval(AOTTestLanguage.ID, "recurse");
        assertEquals(1000, f.execute(1000).asInt());
        try {
            f.execute(100_000_000);
            fail();
        } catch (PolyglotException e) {
            assertTrue(e.toString(), e.isResourceExhausted() || e.getMessage().contains("StackOverflow"));
        }
        assertEquals(10, f.execute(10).asInt());   // still usable after the overflow
    }
}
```
With `engine.UsePreInitializedContext=false`, the engine refuses the pre-initialized context. So `add:1` is parsed afresh in a new layer, and its call target has no AOT code: that's the expected behavior (Review Focus 2).

- [ ] **Step 2: Run the tests**

Run: `mx build --build-logs=silent && mx truffle-aot-native-tests`
Expected: all tests pass. For each failure, use superpowers:systematic-debugging, fix it in the owning file, and commit the fix together with this task's tests.

- [ ] **Step 3: Run the regression suites the feature touches**

Run:
```bash
cd substratevm && mx native-unittest com.oracle.svm.test.deopt.DeoptSourceTest && mx gate --tags truffle_unittests
cd ../truffle && mx unittest ContextPreInitializationTest CloneForAOTTest
cd ../compiler && mx unittest AOTShadowTargetTest BuildTimeSelectionTest BuildTimePartialEvaluationTest MultiTierCompilationTest AOTSupportCompilationTest
```
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
mx checkstyle
git add -A
git commit -m "test: end-to-end native tests for the Truffle AOT tier

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Build-failure mode and summary

**Files:**
- Modify: `substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/buildtime/TruffleBuildTimeCompilationFeature.java`
- Modify: `substratevm/mx.substratevm/mx_substratevm.py`

**Interfaces:**
- Behavior: with `-H:+TruffleAOTFailOnError`, any rejection *other than* `filtered` makes the build fail after analysis with `UserError.abort("Truffle AOT tier: root '<name>' was not compiled: <reason>")`. The failure lists all such roots, one per line.
- Every compiled root appears in the summary exactly once. A skipped root appears exactly once, with its reason.

- [ ] **Step 1: Add the failing check to the mx task**

In `truffle_aot_tier_native_tests`, after the passing run, add a second image build that must fail. The fixtures include the refusing root:
```python
fail_args = build_args + svm_experimental_options(['-H:+TruffleAOTFailOnError'])
try:
    native_unittest(['jdk.graal.compiler.truffle.test.aot.AOTTierNativeTest'] + truffle_args(fail_args))
except SystemExit:
    mx.log('TruffleAOTFailOnError failed the build as expected')
else:
    mx.abort('expected -H:+TruffleAOTFailOnError to fail the build because of the refusing root')
```
The abort message is checked manually once, in Step 4, by reading the build output above the `SystemExit`.

- [ ] **Step 2: Confirm it fails**

Run: `mx truffle-aot-native-tests`
Expected: `mx.abort("expected -H:+TruffleAOTFailOnError to fail the build ...")`.

- [ ] **Step 3: Implement**

In the feature, collect every non-`filtered` rejection: from selection, from PE bailouts, from the budget, and from assumptions invalid at build time. In `afterAnalysis`:
```java
if (BuildTimeCompilationOptions.TruffleAOTFailOnError.getValue() && !failures.isEmpty()) {
    throw UserError.abort("Truffle AOT tier: %s", String.join(System.lineSeparator(), failures));
}
```
where each failure string is `"root '" + name + "' was not compiled: " + reason`.

- [ ] **Step 4: Run and commit**

Run: `mx truffle-aot-native-tests`
Expected: passes, including the expected-failure build. Its output contains `Truffle AOT tier: root 'refuse' was not compiled: root refused AOT (cloneForAOT or prepareForAOT)`.
```bash
mx checkstyle
git add -A substratevm
git commit -m "svm: TruffleAOTFailOnError and complete AOT tier build summary

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: Documentation

**Files:**
- Modify: `truffle/docs/AOT.md`
- Modify: the Native Image Truffle documentation page under `docs/reference-manual/native-image/`. Find the page that documents context pre-initialization with `grep -rl "PreinitializeContexts" docs/` and add a section to it. Create `TruffleBuildTimeCompilation.md` there only if no such page exists.
- Modify: `truffle/CHANGELOG.md` (the runtime options and the build-time options)

**Interfaces:** none.

- [ ] **Step 1: Read `docs/AGENTS.md` and follow its terminology and Markdown rules for all new text.**

- [ ] **Step 2: Add an "AOT tier" section to `truffle/docs/AOT.md`**

Cover, with one short code example each:
- What the AOT tier is: build-time compiled code of AOT-prepared *shadow* copies. It runs from the first call and hands over to the JIT at `engine.AOTTierThreshold`.
- What a language must do: `@GenerateAOT` nodes, `RootNode.prepareForAOT()`, `isCloningAllowed()`, and `cloneForAOT()` when the uninitialized clone would share mutable speculative state.
- Why the live AST is never prepared: sticky specialization state would lower JIT peak performance.

- [ ] **Step 3: Add the Native Image section**

Cover:
- the build-time options, in a table copied from spec §6;
- the shared-`Engine` requirement: contexts must be created on the explicit `Engine` that adopts the pre-initialized engine to share code and use AOT code;
- `-H:+TruffleAOTPrintSummary` and `-H:+TruffleAOTFailOnError`;
- `engine.TraceAOT`.

- [ ] **Step 4: Changelog**

Add under `## Version 25.4`:
```
* Added the AOT tier for Native Image: with `-Dpolyglot.image-build-time.PreinitializeCodeSharing=true` and `-Dpolyglot.image-build-time.AOTCompile=true`, AOT-preparable shared call targets are compiled at image build time and run from the first call. New engine options `engine.AOTTierThreshold` and `engine.TraceAOT`.
```

- [ ] **Step 5: Commit**

```bash
git add -A docs truffle/docs truffle/CHANGELOG.md
git commit -m "docs: document the Truffle AOT tier

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: Final verification of the branch

**Files:** none.

- [ ] **Step 1: Full build and checkstyle, sequentially**

```bash
cd /home/sam/workspace/graalvm/worktrees/graal-aot-tier/substratevm && mx build --build-logs=silent && mx checkstyle
cd ../truffle && mx checkstyle && mx sigtest --check binary
cd ../compiler && mx checkstyle
```
Expected: all exit 0.

- [ ] **Step 2: All tests this branch added or touched**

```bash
cd ../truffle && mx unittest ContextPreInitializationTest CloneForAOTTest
cd ../compiler && mx unittest AOTShadowTargetTest BuildTimeSelectionTest BuildTimePartialEvaluationTest MultiTierCompilationTest AOTSupportCompilationTest OptimizedCallTargetTest
cd ../substratevm && mx native-unittest com.oracle.svm.test.deopt.DeoptSourceTest && mx truffle-aot-native-tests && mx gate --tags truffle_unittests
```
Expected: all pass. Report exact counts in the PR description.

- [ ] **Step 3: Check that the commit series matches spec §9**

`git log --oneline elide-25.4.x..HEAD` must show the commits grouped in spec §9 order:
1. shared pre-init and sources
2. runtime AOT tier, `cloneForAOT` and the SPI
3. SVM deopt sources
4. build-time PE, installation, invalidation and assumptions
5. tests and docs

The SVM deopt-source commits (Tasks 2-3) come first because they are the milestone-0 gate. Reordering history is **not** required: explain the order in the PR description instead.
