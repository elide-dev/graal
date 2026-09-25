/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.truffle.buildtime;

import static com.oracle.svm.common.meta.MethodVariant.ORIGINAL_METHOD;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.ObjectScanner.OtherReason;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.heap.ImageHeapScanner;
import com.oracle.graal.pointsto.infrastructure.ResolvedSignature;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.core.deopt.ImageCodeInstalledCodeLookup;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.graal.RuntimeCompilationSupport;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.hosted.FeatureHandler;
import com.oracle.svm.hosted.FeatureImpl.AfterCompilationAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.BeforeCompilationAccessImpl;
import com.oracle.svm.hosted.NativeImageGenerator;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.code.DeoptimizationUtils;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.LogUtils;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.truffle.TruffleFeature;
import com.oracle.svm.truffle.api.SubstrateKnownTruffleTypes;
import com.oracle.svm.truffle.api.SubstrateTruffleRuntime;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.compiler.OptimizedAssumptionDependency;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;
import com.oracle.truffle.runtime.OptimizedAssumption;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.GraphDecoder;
import jdk.graal.compiler.nodes.GraphEncoder;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.phases.common.GuardLoweringPhase;
import jdk.graal.compiler.truffle.EconomyPartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.KnownTruffleTypes;
import jdk.graal.compiler.truffle.PartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.TruffleTierConfiguration;
import jdk.graal.compiler.truffle.nodes.IsCompilationConstantNode;
import jdk.graal.compiler.truffle.nodes.SpeculativeExceptionAnchorNode;
import jdk.graal.compiler.truffle.nodes.TruffleAssumption;
import jdk.graal.compiler.truffle.nodes.TruffleSafepointNode;
import jdk.graal.compiler.truffle.nodes.frame.ForceMaterializeNode;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The Truffle AOT tier: compiles the AOT shadows of the shared call targets of the pre-initialized
 * engine into the image, and links each live target to its code.
 * <p>
 * For each selected target, a {@link BuildTimeCompiledMethod} is registered as an analysis root and
 * as a deoptimization source. Its graph is the build-time partial evaluation of the shadow, built
 * when the analysis parses the method. The live target's {@link OptimizedCallTarget#getAOTCode()
 * AOT code} is an {@link AOTInstalledCode} pointing to the compiled method, which
 * {@code SubstrateOptimizedCallTargetInstalledCode.doInvoke} calls while it is linked.
 * <p>
 * The code is registered as a dependency of the Truffle assumptions its partial evaluation folded,
 * and these registrations are in the image heap, so that invalidating one of them at run time
 * invalidates the code. An assumption invalidated during the build drops the code instead
 * ({@link #dropAtBuildTime}).
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class TruffleBuildTimeCompilationFeature implements InternalFeature {

    /** The {@code polyglot.image-build-time} option that limits the graph size of a root. */
    private static final String AOT_COMPILE_BUDGET = "polyglot.image-build-time.AOTCompileBudget";

    private BuildTimeCompilationSupport.Selection selection;
    /**
     * The graph builder plugins of runtime compilation, before TruffleFeature adds the Truffle
     * parsing plugins: the plugins the runtime Truffle compiler is configured with.
     */
    private GraphBuilderConfiguration.Plugins runtimePlugins;
    private final AOTCodeTable table = new AOTCodeTable();

    private BuildTimeTruffleCompiler compiler;
    private int budget;
    private AnalysisMethod profiledPERoot;
    private AnalysisMethod callBoundary;
    private AnalysisMethod enter;
    private ImageHeapScanner heapScanner;
    private ResolvedJavaField aotCodeField;
    private ResolvedJavaField unlinkedField;
    /** The fields of an OptimizedAssumption that registering a dependency changes. */
    private List<ResolvedJavaField> assumptionDependencyFields;

    /** The AOT tier methods, in selection order. */
    private final List<BuildTimeCompiledMethod> methods = new ArrayList<>();
    /**
     * The methods whose root was skipped when its graph was built, or whose code was dropped
     * because an assumption was invalidated during the build: never linked, and not in the table.
     */
    private final Set<BuildTimeCompiledMethod> skipped = ConcurrentHashMap.newKeySet();
    /** Every root that is not compiled, or whose code is dropped, with the reason. */
    private final List<BuildTimeCompilationSupport.Rejection> rejections = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return TruffleFeature.isInConfiguration() && Boolean.getBoolean("polyglot.image-build-time.AOTCompile");
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return List.of(TruffleFeature.class);
    }

    @Override
    public void onRegistration(OnRegistrationAccess access) {
        /* Looked up by dropAtBuildTime. */
        ImageSingletons.add(TruffleBuildTimeCompilationFeature.class, this);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        RuntimeCompilationFeature.singleton().addAfterInstallRuntimeConfigCallback(() -> runtimePlugins = RuntimeCompilationFeature.singleton().getHostedProviders().getGraphBuilderPlugins());
        /* Lookups must be registered before setup finishes; the table is filled after compilation. */
        ImageCodeInstalledCodeLookup.register(table);
        /* Marks the image as containing the AOT tier: AOTInstalledCode.isAOTTierInImage. */
        ImageSingletons.add(AOTCodeTable.class, table);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        /* TruffleFeature.beforeAnalysis, which runs first, pre-initialized the engine. */
        selection = BuildTimeCompilationSupport.selectPreinitialized();
        for (BuildTimeCompilationSupport.Rejection r : selection.rejected()) {
            reject(r.rootName(), r.reason());
        }
        if (selection.selected().isEmpty()) {
            return;
        }
        compiler = new BuildTimeTruffleCompiler(createAnalysisConfig(access, runtimePlugins), access.getBigBang().getOptions());
        budget = parseBudget();
        heapScanner = access.getUniverse().getHeapScanner();
        MetaAccessProvider originalMetaAccess = access.getMetaAccess().getWrapped();
        try {
            profiledPERoot = access.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.class.getDeclaredMethod("profiledPERoot", Object[].class));
            callBoundary = access.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.class.getDeclaredMethod("callBoundary", Object[].class));
            enter = access.getMetaAccess().lookupJavaMethod(AOTInstalledCode.class.getDeclaredMethod("enter", AOTInstalledCode.class));
            Field field = OptimizedCallTarget.class.getDeclaredField("aotCode");
            aotCodeField = originalMetaAccess.lookupJavaField(field);
            unlinkedField = originalMetaAccess.lookupJavaField(AOTInstalledCode.class.getDeclaredField("unlinked"));
            List<ResolvedJavaField> fields = new ArrayList<>();
            for (String name : new String[]{"dependencies", "size", "sizeAfterLastRemove"}) {
                fields.add(originalMetaAccess.lookupJavaField(OptimizedAssumption.class.getDeclaredField(name)));
            }
            assumptionDependencyFields = List.copyOf(fields);
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere(e);
        }
        /*
         * The graph of an AOT tier method is complete when the analysis parses it: inlining
         * original methods into it would add frames whose deoptimization targets may not be
         * reachable (see SubstrateCompilationDirectives.registerDeoptSource).
         */
        access.getHostVM().registerNeverInlineTrivialHandler((caller, callee) -> caller.getWrapped() instanceof BuildTimeCompiledMethod);

        ResolvedJavaType holder = originalMetaAccess.lookupJavaType(AOTInstalledCode.class);
        ConstantPool constantPool = holder.getDeclaredConstructors(false)[0].getConstantPool();
        ResolvedSignature<ResolvedJavaType> signature = ResolvedSignature.fromMethodType(MethodType.methodType(Object.class, OptimizedCallTarget.class, Object[].class), originalMetaAccess);
        for (BuildTimeCompilationSupport.Pair pair : selection.selected()) {
            String name = "<unnamed root>";
            try {
                name = AOTInstalledCode.rootName(pair.live());
                register(access, pair, name, holder, signature, constantPool);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                skip(name, t);
            }
        }
    }

    private void register(BeforeAnalysisAccessImpl access, BuildTimeCompilationSupport.Pair pair, String name, ResolvedJavaType holder, ResolvedSignature<ResolvedJavaType> signature,
                    ConstantPool constantPool) {
        OptimizedCallTarget shadow = pair.shadow();
        VMError.guarantee(shadow.isAOTShadow(), "only AOT shadows are compiled at build time: %s", shadow);
        /*
         * Prepare the shadow now, which partial evaluation would do anyway: the shadow may be
         * scanned into the image heap as soon as its method is parsed, and it must not change
         * afterwards.
         */
        if (!compiler.asCompilable(shadow).prepareForCompilation(true, 1, false)) {
            reject(name, "not ready for compilation");
            return;
        }
        shadow.getNonTrivialNodeCount();

        BuildTimeCompiledMethod method = new BuildTimeCompiledMethod(this, pair.live(), shadow, holder, signature, constantPool);
        AnalysisMethod aMethod = access.getUniverse().lookup(method);
        method.analysisMethod = aMethod;
        method.code = new AOTInstalledCode(pair.live(), new AOTEntry(new MethodPointer(aMethod)));
        /* The deoptimization target of the outermost frame must be reachable on its own. */
        SubstrateCompilationDirectives.singleton().registerDeoptSource(aMethod);
        aMethod.getOrCreateMethodVariant(SubstrateCompilationDirectives.DEOPT_TARGET_METHOD);
        access.registerAsRoot(aMethod, true, "Truffle AOT tier " + name, SubstrateCompilationDirectives.DEOPT_TARGET_METHOD);
        /*
         * Known from now on: the method is compiled and its entry, which its graph references, must
         * be immutable, whether or not the target ends up linked to it.
         */
        methods.add(method);
        /* Link last: the live target must only reference a method that is compiled. */
        try {
            BuildTimeCompilationSupport.attach(pair.live(), method.code);
            heapScanner.rescanField(pair.live(), aotCodeField, new OtherReason("Truffle AOT tier code of " + name));
        } catch (Throwable t) {
            skipped.add(method);
            BuildTimeCompilationSupport.attach(pair.live(), null);
            throw t;
        }
    }

    AnalysisMethod profiledPERoot() {
        return profiledPERoot;
    }

    AnalysisMethod callBoundary() {
        return callBoundary;
    }

    AnalysisMethod enter() {
        return enter;
    }

    /**
     * The graph of the original variant of {@code method}: the kit graph as the compilation root
     * of the partial evaluation of the shadow, with guards lowered to fixed deoptimizations as
     * hosted compilation requires. Returns {@code null} if the root is skipped, in which case its
     * live target is unlinked again.
     */
    StructuredGraph partiallyEvaluate(BuildTimeCompiledMethod method, DebugContext debug, AnalysisMethod aMethod, HostedProviders providers) {
        String name = "<unnamed root>";
        try {
            name = AOTInstalledCode.rootName(method.live);
            String rootName = name;
            StructuredGraph rootGraph = method.buildKitGraph(debug, aMethod, providers, null, profiledPERoot, method.shadow);
            EncodedGraph encodedRoot = GraphEncoder.encodeSingleGraph(rootGraph, AnalysisParsedGraph.HOST_ARCHITECTURE);
            String[] bailout = new String[1];
            StructuredGraph graph = compiler.partialEvaluator().partiallyEvaluateAt(method.shadow, aMethod, encodedRoot,
                            () -> compiler.partialEvaluateForBuildTime(compiler.asCompilable(method.shadow), debug, reason -> bailout[0] = reason));
            if (graph == null) {
                unlink(method, rootName, String.valueOf(bailout[0]));
                return null;
            }
            removeSpeculativeExceptionAnchors(graph);
            new GuardLoweringPhase().apply(graph, providers);
            int nodes = graph.getNodeCount();
            if (budget > 0 && nodes > budget) {
                unlink(method, rootName, "over budget (" + nodes + " > " + budget + ")");
                return null;
            }
            StructuredGraph result = toHostedGraph(graph, aMethod, debug);
            Class<?> unsupported = findUnsupportedNode(result);
            if (unsupported != null) {
                unlink(method, rootName, "unsupported node " + unsupported.getName());
                return null;
            }
            method.assumptions = graph.getAssumptions() == null ? List.of() : List.of(graph.getAssumptions().toArray());
            /* Last: nothing may skip the root after its code became a dependency. */
            String invalid = registerAssumptionDependencies(method, providers, rootName);
            if (invalid != null) {
                unlink(method, rootName, invalid);
                return null;
            }
            summary("compiled " + name + " nodes=" + nodes + " assumptions=" + method.assumptions.size());
            return result;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            skip(name, t);
            unlink(method, name, null);
            return null;
        }
    }

    /**
     * Without a speculation log, the exception edges of Truffle boundary calls cannot speculate
     * that no exception is thrown ({@link SpeculativeExceptionAnchorNode} would stay). They are
     * compiled as if the speculation had failed: the exception is propagated in the compiled code,
     * instead of deoptimizing (and invalidating the AOT code) on every such exception.
     */
    private static void removeSpeculativeExceptionAnchors(StructuredGraph graph) {
        for (SpeculativeExceptionAnchorNode anchor : graph.getNodes().filter(SpeculativeExceptionAnchorNode.class).snapshot()) {
            graph.removeFixed(anchor);
        }
    }

    /**
     * Nodes of the Truffle compiler ({@code jdk.graal.compiler.truffle.nodes}) that hosted
     * compilation can lower. All others only exist during partial evaluation and the Truffle tier,
     * and no hosted lowering is registered for them.
     */
    private static final Set<Class<? extends Node>> HOSTED_TRUFFLE_NODES = Set.of(
                    /* Lowered by SubstrateThreadLocalHandshakeSnippets, registered for hosted too. */
                    TruffleSafepointNode.class,
                    /* Lowers itself. */
                    IsCompilationConstantNode.class,
                    /* LIR-lowerable, generating nothing. */
                    ForceMaterializeNode.class);

    /**
     * Returns the class of a node of {@code graph} that the hosted compile queue cannot compile, or
     * {@code null}: the root is skipped rather than failing the image build later. These are the
     * Truffle compiler nodes that are left after partial evaluation and have no hosted lowering, and
     * the nodes that cannot be compiled in a method with deoptimization targets.
     */
    static Class<?> findUnsupportedNode(StructuredGraph graph) {
        for (Node node : graph.getNodes()) {
            Class<? extends Node> nodeClass = node.getClass();
            if (nodeClass.getPackageName().startsWith(TRUFFLE_NODES_PACKAGE) && !HOSTED_TRUFFLE_NODES.contains(nodeClass)) {
                return nodeClass;
            }
            if (DeoptimizationUtils.AOT_COMPILATION_INVALID_NODES.test(node)) {
                return nodeClass;
            }
        }
        return null;
    }

    private static final String TRUFFLE_NODES_PACKAGE = TruffleSafepointNode.class.getPackageName();

    /**
     * A copy of the partially evaluated {@code graph} in the form the analysis and the hosted
     * compile queue expect of a hosted graph: without assumptions (the partial evaluation records
     * the Truffle assumptions it folded, which are recorded separately), and with explicit
     * exceptions and fixed deoptimizations (see
     * {@code GraphState.configureExplicitExceptionsNoDeoptIfNecessary}), guards being lowered
     * already.
     */
    private static StructuredGraph toHostedGraph(StructuredGraph graph, AnalysisMethod aMethod, DebugContext debug) {
        EncodedGraph encoded = GraphEncoder.encodeSingleGraph(graph, AnalysisParsedGraph.HOST_ARCHITECTURE);
        EncodedGraph withoutAssumptions = new EncodedGraph(encoded.getEncoding(), encoded.getStartOffset(), encoded.getObjects(), encoded.getNodeClasses(), null,
                        encoded.getInlinedMethods(), encoded.hasUnsafeAccess(), encoded.trackNodeSourcePosition());
        StructuredGraph result = new StructuredGraph.Builder(graph.getOptions(), debug, StructuredGraph.AllowAssumptions.NO)
                        .method(aMethod)
                        .trackNodeSourcePosition(encoded.trackNodeSourcePosition())
                        .recordInlinedMethods(encoded.getInlinedMethods() != null)
                        .build();
        new GraphDecoder(AnalysisParsedGraph.HOST_ARCHITECTURE, result).decode(withoutAssumptions);
        result.getGraphState().configureExplicitExceptionsNoDeoptIfNecessary();
        return result;
    }

    /**
     * Registers the code of {@code method} as a dependency of each Truffle assumption its partial
     * evaluation folded, and rescans the assumptions, so that the registrations are in the image
     * heap: invalidating the assumption at run time invalidates the code. Returns the reason to skip
     * the root, or {@code null}. Either all assumptions are registered or none.
     * <p>
     * Graphs of hosted methods cannot record other assumptions: if the partial evaluation recorded
     * one, the root is skipped, since nothing would invalidate the code when it is.
     */
    private String registerAssumptionDependencies(BuildTimeCompiledMethod method, HostedProviders providers, String name) {
        List<OptimizedAssumption> assumptions = new ArrayList<>();
        for (Assumptions.Assumption a : method.assumptions) {
            if (a instanceof TruffleAssumption ta) {
                assumptions.add(providers.getSnippetReflection().asObject(OptimizedAssumption.class, ta.getAssumption()));
            } else {
                return "unsupported assumption " + a;
            }
        }
        List<Consumer<OptimizedAssumptionDependency>> entries = new ArrayList<>();
        String invalid = null;
        for (OptimizedAssumption assumption : assumptions) {
            Consumer<OptimizedAssumptionDependency> entry = assumption.registerDependency();
            if (entry == null) {
                /* Invalidated after the partial evaluation relied on it. */
                invalid = "assumption invalid at build time";
                break;
            }
            entries.add(entry);
        }
        /* A registered entry must be notified, or invalidating the assumption would wait for it. */
        for (Consumer<OptimizedAssumptionDependency> entry : entries) {
            entry.accept(invalid == null ? method.code : null);
        }
        for (OptimizedAssumption assumption : assumptions) {
            for (ResolvedJavaField field : assumptionDependencyFields) {
                heapScanner.rescanField(assumption, field, new OtherReason("Truffle AOT tier code of " + name + " depends on " + assumption));
            }
        }
        return invalid;
    }

    /**
     * Drops the AOT code {@code code}: an assumption it depends on was invalidated while the image
     * is built ({@code AOTInstalledCode.invalidate}). There are no activations of the code yet, but
     * the image must not link it:
     * <ul>
     * <li>During the analysis, the target is unlinked and rescanned, and the method is excluded
     * from the {@link AOTCodeTable}.</li>
     * <li>After the analysis, and until the image heap is laid out, the scanner does not accept
     * rescans any more; the heap snapshot verification after compilation and before the heap
     * layout patches the snapshot with the unlinked target and the invalidated assumption instead.
     * Until the table is built (after compilation), the method is excluded from it; after that, it
     * stays in it, unlinked, so it is never entered.</li>
     * <li>After the heap layout, the image heap is written from the snapshot, which still has the
     * valid assumption and the linked code that relies on it: consistent, so the code is kept.</li>
     * </ul>
     */
    static void dropAtBuildTime(AOTInstalledCode code, CharSequence reason) {
        ImageSingletons.lookup(TruffleBuildTimeCompilationFeature.class).drop(code, reason);
    }

    private synchronized void drop(AOTInstalledCode code, CharSequence reason) {
        if (code.isUnlinked()) {
            /* Dropped already, for another of its assumptions. */
            return;
        }
        String name = AOTInstalledCode.rootName(code.live);
        String why = "assumption invalidated during build: " + reason;
        if (BuildPhaseProvider.isHeapLayoutFinished()) {
            reject(name, why + " (after the heap layout: the image keeps the code, with the assumption valid)");
            return;
        }
        for (BuildTimeCompiledMethod method : methods) {
            if (method.code == code) {
                skipped.add(method);
            }
        }
        code.unlink(why);
        BuildTimeCompilationSupport.attach(code.live, null);
        if (!BuildPhaseProvider.isAnalysisFinished()) {
            OtherReason rescanReason = new OtherReason("Truffle AOT tier code of " + name + " dropped");
            heapScanner.rescanField(code.live, aotCodeField, rescanReason);
            heapScanner.rescanField(code, unlinkedField, rescanReason);
        }
        reject(name, why);
    }

    /** Unlinks the AOT code of a skipped root, before the image heap is written. */
    private void unlink(BuildTimeCompiledMethod method, String name, String reason) {
        if (reason != null) {
            reject(name, reason);
        }
        skipped.add(method);
        BuildTimeCompilationSupport.attach(method.live, null);
        heapScanner.rescanField(method.live, aotCodeField, new OtherReason("Truffle AOT tier code of " + name + " unlinked"));
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess a) {
        BeforeCompilationAccessImpl access = (BeforeCompilationAccessImpl) a;
        for (BuildTimeCompiledMethod method : methods) {
            /* Objects that hold relocatable pointers must be immutable. */
            access.registerAsImmutable(method.code.entry);
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess a) {
        AfterCompilationAccessImpl access = (AfterCompilationAccessImpl) a;
        Map<HostedMethod, CompileQueue.CompileTask> compilations = access.getCompilations();
        List<PlacedCode> placed = new ArrayList<>();
        for (BuildTimeCompiledMethod method : methods) {
            if (skipped.contains(method)) {
                continue;
            }
            HostedMethod hMethod = access.getUniverse().lookup(method.analysisMethod);
            CompileQueue.CompileTask task = compilations.get(hMethod);
            VMError.guarantee(task != null && task.result != null && hMethod.isCodeAddressOffsetValid(), "AOT tier method not compiled: %s", hMethod);
            method.code.entry.codeSize = task.result.getTargetCodeSize();
            placed.add(new PlacedCode(hMethod.getCodeAddressOffset(), method.code));
            summary("installed " + AOTInstalledCode.rootName(method.live) + " as " + hMethod.getName() + " codeSize=" + method.code.entry.codeSize);
        }
        /*
         * Sorted by the offset in the code section: the relocated entry points have the same order
         * at run time.
         */
        placed.sort(Comparator.comparingInt(PlacedCode::offset));
        table.setCodes(placed.stream().map(PlacedCode::code).toArray(AOTInstalledCode[]::new));
    }

    private record PlacedCode(int offset, AOTInstalledCode code) {
    }

    private static int parseBudget() {
        String value = System.getProperty(AOT_COMPILE_BUDGET, "0").strip();
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw UserError.abort("Invalid value for %s, expected a number of nodes: %s", AOT_COMPILE_BUDGET, value);
        }
    }

    /** Records that the root {@code name} is not compiled, or its code dropped, and why. */
    private void reject(String name, String reason) {
        rejections.add(new BuildTimeCompilationSupport.Rejection(name, reason));
        summary("skipped " + name + ": " + reason);
    }

    /** The roots that are not compiled, or whose code is dropped, with the reason. */
    List<BuildTimeCompilationSupport.Rejection> rejections() {
        synchronized (rejections) {
            return List.copyOf(rejections);
        }
    }

    private void skip(String name, Throwable t) {
        /*
         * A failure to compile one root never fails the build by itself; whether skipped roots
         * are errors is decided by TruffleAOTFailOnError.
         */
        rejections.add(new BuildTimeCompilationSupport.Rejection(name, String.valueOf(t)));
        if (BuildTimeCompilationOptions.TruffleAOTPrintSummary.getValue()) {
            summary("skipped " + name + ": " + t);
            t.printStackTrace(System.out);
        } else {
            /* Unexpected failures are reported even without the summary. */
            LogUtils.warning("Truffle build-time compilation skipped %s: %s", name, t);
        }
    }

    /**
     * The configuration of the build-time Truffle compiler. Like
     * {@code TruffleSupport.createSubstrateTruffleCompilerConfig} (including the root {@code plugins}),
     * but both tiers use the analysis providers of {@code access}, and the Truffle types are looked
     * up on analysis types.
     */
    static TruffleCompilerConfiguration createAnalysisConfig(BeforeAnalysisAccessImpl access, GraphBuilderConfiguration.Plugins plugins) {
        BigBang bb = access.getBigBang();
        HostedProviders providers = bb.getProviders(ORIGINAL_METHOD);
        BuildTimeTruffleCompiler.Runtime runtime = new BuildTimeTruffleCompiler.Runtime((SubstrateTruffleRuntime) Truffle.getRuntime(), bb);
        KnownTruffleTypes types = new SubstrateKnownTruffleTypes(runtime, providers.getMetaAccess(), providers.getConstantReflection());

        /*
         * The runtime backend only provides the target architecture: the build-time compiler never
         * uses its backend. The suites are created like the runtime compiler's, but are not used
         * either: the graphs are compiled by the hosted compile queue.
         */
        RuntimeConfiguration runtimeConfig = RuntimeCompilationSupport.getRuntimeConfig();
        SubstrateBackend backend = runtimeConfig.getBackendForNormalMethod();
        FeatureHandler featureHandler = access.getFeatureHandler();
        TruffleTierConfiguration firstTier = new TruffleTierConfiguration(new EconomyPartialEvaluatorConfiguration(), backend, providers,
                        NativeImageGenerator.createFirstTierSuites(featureHandler, runtimeConfig, false),
                        NativeImageGenerator.createFirstTierLIRSuites(featureHandler, runtimeConfig.getProviders(), false), types);
        /* The configuration TruffleSupport.createTruffleCompiler uses. */
        PartialEvaluatorConfiguration peConfig = TruffleCompilerImpl.createPartialEvaluatorConfiguration("community");
        TruffleTierConfiguration lastTier = new TruffleTierConfiguration(peConfig, backend, providers,
                        NativeImageGenerator.createSuites(featureHandler, runtimeConfig, false),
                        NativeImageGenerator.createLIRSuites(featureHandler, runtimeConfig.getProviders(), false), types);
        return new TruffleCompilerConfiguration(runtime, plugins, bb.getSnippetReflectionProvider(),
                        firstTier, lastTier, types, null, null);
    }

    static void summary(String line) {
        if (BuildTimeCompilationOptions.TruffleAOTPrintSummary.getValue()) {
            System.out.println("[truffle-aot] " + line);
        }
    }
}
