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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.ClassUtil;
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

import jdk.graal.compiler.api.replacements.SnippetReflectionProvider;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.GraphDecoder;
import jdk.graal.compiler.nodes.GraphEncoder;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.BoxNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.java.NewArrayNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.phases.common.GuardLoweringPhase;
import jdk.graal.compiler.phases.util.Providers;
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
import jdk.graal.compiler.truffle.phases.TruffleSafepointInsertionPhase;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
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
 * <p>
 * Each root of the selection ends up compiled, skipped (not compiled, or its code dropped), or kept
 * (compiled, although an assumption it relies on was invalidated after the image heap snapshot was
 * sealed). The {@linkplain BuildTimeCompilationOptions#TruffleAOTPrintSummary summary} prints one
 * line per root with its final state, and with
 * {@link BuildTimeCompilationOptions#TruffleAOTFailOnError} a skipped root fails the build, unless
 * it is not eligible by design: excluded by the filter, or executed at build time
 * ({@link #isFailure}).
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
    private ResolvedJavaField aotInitializedField;
    private ResolvedJavaField unlinkedField;
    /** The fields of an OptimizedAssumption that registering a dependency changes. */
    private List<ResolvedJavaField> assumptionDependencyFields;
    /** {@code OptimizedAssumption.Entry.dependency} and {@code pending}. */
    private List<ResolvedJavaField> assumptionEntryFields;
    private Class<?> assumptionEntryClass;

    /**
     * The roots of the selection, in a deterministic order: the rejected ones, then the selected
     * ones, each in selection order. Only changed in {@link #beforeAnalysis}.
     */
    private final List<AOTRoot> roots = new ArrayList<>();
    /** Whether the summary was printed. Guarded by {@code this}. */
    private boolean summaryPrinted;

    /**
     * A root of the selection and what became of it. The fields other than {@link #name} and
     * {@link #method} are guarded by the feature.
     */
    static final class AOTRoot {
        final String name;
        /**
         * The AOT tier method of a selected root, once it is registered as an analysis root: from
         * then on it is compiled, and its entry must be immutable, whether or not the target ends
         * up linked to it. Only set in {@link #beforeAnalysis}.
         */
        BuildTimeCompiledMethod method;
        /**
         * Why the root is not compiled, or its code was dropped, or {@code null}. A skipped method
         * is never linked, and is not in the {@link AOTCodeTable} unless it was dropped after the
         * table was built.
         */
        private String rejection;
        /** The partial evaluation statistics, once the root is partially evaluated. */
        private String compiled;
        /** The node and allocation counts of the partially evaluated graph, see {@link #limitViolations}. */
        private int nodes;
        private int allocations;
        /** What the {@link #allocations} allocate, e.g., {@code Box(Integer), New(Foo)x2}. */
        private String allocationSites;
        /** The compiled method and its code size, once it is installed. */
        private String installed;
        /**
         * Why the code is kept although an assumption it relies on was invalidated during the build,
         * or {@code null}.
         */
        private String kept;

        AOTRoot(String name) {
            this.name = name;
        }
    }

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
            AOTRoot root = new AOTRoot(r.rootName());
            root.rejection = r.reason();
            roots.add(root);
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
            aotInitializedField = originalMetaAccess.lookupJavaField(OptimizedCallTarget.class.getDeclaredField("aotInitialized"));
            unlinkedField = originalMetaAccess.lookupJavaField(AOTInstalledCode.class.getDeclaredField("unlinked"));
            List<ResolvedJavaField> fields = new ArrayList<>();
            for (String name : new String[]{"dependencies", "size", "sizeAfterLastRemove"}) {
                fields.add(originalMetaAccess.lookupJavaField(OptimizedAssumption.class.getDeclaredField(name)));
            }
            assumptionDependencyFields = List.copyOf(fields);
            assumptionEntryClass = Class.forName(OptimizedAssumption.class.getName() + "$Entry");
            assumptionEntryFields = List.of(originalMetaAccess.lookupJavaField(assumptionEntryClass.getDeclaredField("dependency")),
                            originalMetaAccess.lookupJavaField(assumptionEntryClass.getDeclaredField("pending")));
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
            /* The selection named the root already. */
            AOTRoot root = new AOTRoot(AOTInstalledCode.rootName(pair.live()));
            roots.add(root);
            try {
                register(access, pair, root, holder, signature, constantPool);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                reject(root, failure(root.name, t));
            }
        }
    }

    private void register(BeforeAnalysisAccessImpl access, BuildTimeCompilationSupport.Pair pair, AOTRoot root, ResolvedJavaType holder, ResolvedSignature<ResolvedJavaType> signature,
                    ConstantPool constantPool) {
        String name = root.name;
        OptimizedCallTarget shadow = pair.shadow();
        VMError.guarantee(shadow.isAOTShadow(), "only AOT shadows are compiled at build time: %s", shadow);
        /*
         * Prepare the shadow now, which partial evaluation would do anyway: the shadow may be
         * scanned into the image heap as soon as its method is parsed, and it must not change
         * afterwards.
         */
        if (!compiler.asCompilable(shadow).prepareForCompilation(true, 1, false)) {
            reject(root, "not ready for compilation");
            return;
        }
        shadow.getNonTrivialNodeCount();

        BuildTimeCompiledMethod method = new BuildTimeCompiledMethod(this, root, pair.live(), shadow, holder, signature, constantPool);
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
        root.method = method;
        /* Link last: the live target must only reference a method that is compiled. */
        try {
            BuildTimeCompilationSupport.attach(pair.live(), method.code);
            heapScanner.rescanField(pair.live(), aotCodeField, new OtherReason("Truffle AOT tier code of " + name));
            heapScanner.rescanField(pair.live(), aotInitializedField, new OtherReason("Truffle AOT tier code of " + name));
        } catch (Throwable t) {
            /* Skipped by the caller, which records the rejection. */
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
        String name = method.root.name;
        if (isRejected(method.root)) {
            /* Skipped when it was registered: its live target is not linked. */
            return null;
        }
        try {
            StructuredGraph rootGraph = method.buildKitGraph(debug, aMethod, providers, null, profiledPERoot, method.shadow);
            EncodedGraph encodedRoot = GraphEncoder.encodeSingleGraph(rootGraph, AnalysisParsedGraph.HOST_ARCHITECTURE);
            String[] bailout = new String[1];
            StructuredGraph graph;
            List<BuildTimeConstantFieldProvider.Fold> folds;
            BuildTimeConstantFieldProvider.startRecording();
            try {
                graph = compiler.partialEvaluator().partiallyEvaluateAt(method.shadow, aMethod, encodedRoot,
                                () -> compiler.partialEvaluateForBuildTime(compiler.asCompilable(method.shadow), debug, reason -> bailout[0] = reason));
            } finally {
                folds = BuildTimeConstantFieldProvider.stopRecording();
            }
            if (graph == null) {
                unlink(method, String.valueOf(bailout[0]));
                return null;
            }
            removeSpeculativeExceptionAnchors(graph);
            new GuardLoweringPhase().apply(graph, providers);
            /*
             * Partial evaluation drops the explicit TruffleSafepoint.poll calls: Truffle
             * compilations insert their polls in the mid tier instead (TruffleCompilerPhases), at
             * loops, returns and calls. The hosted compilation of the AOT tier code does not run
             * the Truffle tiers, so the polls are inserted here, while the graph is still the
             * Truffle compilation of the shadow. Without them, thread-local actions, cancellation
             * and interrupts never reach AOT tier code.
             */
            insertTruffleSafepoints(graph);
            int nodes = graph.getNodeCount();
            AOTGraphHistogram.print(name, graph);
            Map<String, Integer> sites = allocationSites(graph);
            int allocations = sites.values().stream().mapToInt(Integer::intValue).sum();
            if (budget > 0 && nodes > budget) {
                unlink(method, "over budget (" + nodes + " > " + budget + ")");
                return null;
            }
            StructuredGraph result = toHostedGraph(graph, aMethod, debug);
            Class<?> unsupported = findUnsupportedNode(result);
            if (unsupported != null) {
                unlink(method, "unsupported node " + unsupported.getName());
                return null;
            }
            method.assumptions = graph.getAssumptions() == null ? List.of() : List.of(graph.getAssumptions().toArray());
            /* Last: nothing may skip the root after its code became a dependency. */
            String invalid = registerAssumptionDependencies(method, providers, name);
            if (invalid != null) {
                unlink(method, invalid);
                return null;
            }
            if (!folds.isEmpty()) {
                method.folded = foldedFields(folds, providers);
                /* Linked at run time, once patching left the folded fields unchanged. */
                BuildTimeCompilationSupport.attach(method.live, null);
                OtherReason reason = new OtherReason("Truffle AOT tier code of " + name + " linked after patching");
                heapScanner.rescanField(method.live, aotCodeField, reason);
                heapScanner.rescanField(method.live, aotInitializedField, reason);
            }
            compiled(method.root, nodes, allocations, describe(sites), method.assumptions.size());
            return result;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            unlink(method, failure(name, t));
            return null;
        }
    }

    private static List<BuildTimeCompiledMethod.FoldedField> foldedFields(List<BuildTimeConstantFieldProvider.Fold> folds, HostedProviders providers) {
        SnippetReflectionProvider snippetReflection = providers.getSnippetReflection();
        List<BuildTimeCompiledMethod.FoldedField> result = new ArrayList<>();
        for (BuildTimeConstantFieldProvider.Fold fold : folds) {
            Object receiver = snippetReflection.asObject(Object.class, fold.receiver());
            JavaConstant value = fold.value();
            Object reference = null;
            long bits = 0;
            switch (fold.field() == null ? JavaKind.Object : fold.field().getJavaKind()) {
                case Object -> reference = value.isNull() ? null : snippetReflection.asObject(Object.class, value);
                case Float -> bits = Float.floatToRawIntBits(value.asFloat());
                case Double -> bits = Double.doubleToRawLongBits(value.asDouble());
                case Boolean -> bits = value.asBoolean() ? 1 : 0;
                default -> bits = value.asLong();
            }
            result.add(new BuildTimeCompiledMethod.FoldedField(receiver, fold.field(), reference, bits));
        }
        return List.copyOf(result);
    }

    private void insertTruffleSafepoints(StructuredGraph graph) {
        Providers peProviders = compiler.partialEvaluator().getProviders();
        new TruffleSafepointInsertionPhase(compiler.partialEvaluator().getTypes(), peProviders).apply(graph);
        /*
         * The phase types the location of a poll as a RootNode (exact), while the analysis expects
         * an object constant to have the exact stamp of its value.
         */
        for (TruffleSafepointNode poll : graph.getNodes().filter(TruffleSafepointNode.class)) {
            ConstantNode location = poll.location();
            if (location.isAlive()) {
                ConstantNode exact = ConstantNode.forConstant(location.asJavaConstant(), peProviders.getMetaAccess(), graph);
                if (exact != location) {
                    location.replaceAtUsagesAndDelete(exact);
                }
            }
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
        /*
         * The entries too: the scanner may have seen an entry before it was notified, e.g., while
         * rescanning an assumption for another root. An entry left pending in the image would make
         * invalidating its assumption wait for it forever.
         */
        for (Consumer<OptimizedAssumptionDependency> entry : entries) {
            if (!assumptionEntryClass.isInstance(entry)) {
                /* E.g., the discarding consumer of an assumption that is always valid. */
                continue;
            }
            for (ResolvedJavaField field : assumptionEntryFields) {
                heapScanner.rescanField(entry, field, new OtherReason("Truffle AOT tier code of " + name + " registered"));
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
     * <li>After the analysis, and until the heap snapshot is sealed, the scanner does not accept
     * rescans any more; the heap snapshot verification after compilation and before the heap
     * layout, which then seals the snapshot, patches the snapshot with the unlinked target and the
     * invalidated assumption instead. Until the table is built (after compilation), the method is
     * excluded from it; after that, it stays in it, unlinked, so it is never entered.</li>
     * <li>After the final heap snapshot verification, which seals the snapshot (before the heap
     * layout), the image heap is written from the snapshot, which still has the valid assumption
     * and the linked code that relies on it: consistent, so the code is kept, and the root is
     * reported as kept rather than skipped.</li>
     * </ul>
     */
    static void dropAtBuildTime(AOTInstalledCode code, CharSequence reason) {
        ImageSingletons.lookup(TruffleBuildTimeCompilationFeature.class).drop(code, reason);
    }

    private synchronized void drop(AOTInstalledCode code, CharSequence reason) {
        AOTRoot root = null;
        for (AOTRoot r : roots) {
            if (r.method != null && r.method.code == code) {
                root = r;
            }
        }
        VMError.guarantee(root != null, "AOT tier code of no root: %s", code);
        if (code.isUnlinked() || root.kept != null || root.rejection != null) {
            /* Dropped or kept already, for another of its assumptions, or skipped. */
            return;
        }
        if (heapScanner.isSealed()) {
            /*
             * The last heap snapshot verification, which would have patched the snapshot, is over:
             * the image heap is written from the sealed snapshot, with the code linked and the
             * assumption valid. That is consistent, so the code is kept.
             */
            root.kept = "assumption invalidated after heap snapshot: " + reason;
            if (summaryPrinted && BuildTimeCompilationOptions.TruffleAOTPrintSummary.getValue()) {
                /*
                 * Only possible while the image is written or later. Not a summary line: the
                 * summary has one line per root, and this one is still compiled in the image.
                 */
                LogUtils.info("Truffle AOT tier: kept %s: %s", root.name, root.kept);
            }
            return;
        }
        String why = "assumption invalidated during build: " + reason;
        code.unlink(why);
        BuildTimeCompilationSupport.attach(code.live, null);
        if (!BuildPhaseProvider.isAnalysisFinished()) {
            OtherReason rescanReason = new OtherReason("Truffle AOT tier code of " + root.name + " dropped");
            heapScanner.rescanField(code.live, aotCodeField, rescanReason);
            heapScanner.rescanField(code.live, aotInitializedField, rescanReason);
            heapScanner.rescanField(code, unlinkedField, rescanReason);
        }
        reject(root, why);
    }

    /** Skips the root of {@code method} and unlinks its AOT code, before the image heap is written. */
    private void unlink(BuildTimeCompiledMethod method, String reason) {
        reject(method.root, reason);
        BuildTimeCompilationSupport.attach(method.live, null);
        OtherReason rescanReason = new OtherReason("Truffle AOT tier code of " + method.root.name + " unlinked");
        heapScanner.rescanField(method.live, aotCodeField, rescanReason);
        heapScanner.rescanField(method.live, aotInitializedField, rescanReason);
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        /* Every root is partially evaluated: the analysis parsed its method. */
        failOnError();
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess a) {
        BeforeCompilationAccessImpl access = (BeforeCompilationAccessImpl) a;
        for (AOTRoot root : roots) {
            if (root.method != null) {
                /* Objects that hold relocatable pointers must be immutable. */
                access.registerAsImmutable(root.method.code.entry);
            }
        }
    }

    @Override
    public synchronized void afterCompilation(AfterCompilationAccess a) {
        AfterCompilationAccessImpl access = (AfterCompilationAccessImpl) a;
        Map<HostedMethod, CompileQueue.CompileTask> compilations = access.getCompilations();
        List<PlacedCode> placed = new ArrayList<>();
        for (AOTRoot root : roots) {
            BuildTimeCompiledMethod method = root.method;
            if (method == null || root.rejection != null) {
                continue;
            }
            HostedMethod hMethod = access.getUniverse().lookup(method.analysisMethod);
            CompileQueue.CompileTask task = compilations.get(hMethod);
            VMError.guarantee(task != null && task.result != null && hMethod.isCodeAddressOffsetValid(), "AOT tier method not compiled: %s", hMethod);
            method.code.entry.codeSize = task.result.getTargetCodeSize();
            placed.add(new PlacedCode(hMethod.getCodeAddressOffset(), method.code, method.folded));
            root.installed = "as " + hMethod.getName() + " codeSize=" + method.code.entry.codeSize;
        }
        /*
         * Sorted by the offset in the code section: the relocated entry points have the same order
         * at run time.
         */
        placed.sort(Comparator.comparingInt(PlacedCode::offset));
        table.setCodes(placed.stream().map(PlacedCode::code).toArray(AOTInstalledCode[]::new));
        setFoldedState(access, placed);
    }

    /** The folded fields of each code, for the check after the context is patched. */
    private void setFoldedState(AfterCompilationAccessImpl access, List<PlacedCode> placed) {
        int total = placed.stream().mapToInt(p -> p.folded().size()).sum();
        int[] starts = new int[placed.size() + 1];
        Object[] receivers = new Object[total];
        long[] offsets = new long[total];
        char[] kinds = new char[total];
        Object[] references = new Object[total];
        long[] bits = new long[total];
        String[] names = new String[total];
        int j = 0;
        for (int i = 0; i < placed.size(); i++) {
            starts[i] = j;
            for (BuildTimeCompiledMethod.FoldedField f : placed.get(i).folded()) {
                receivers[j] = f.receiver();
                references[j] = f.reference();
                bits[j] = f.bits();
                if (f.field() == null) {
                    kinds[j] = AOTCodeTable.LANGUAGE_CONTEXT_OBJECT;
                    names[j] = "the context object of " + f.receiver();
                } else {
                    HostedField hField = access.getUniverse().lookup(f.field());
                    offsets[j] = hField.getLocation();
                    kinds[j] = f.field().getJavaKind().getTypeChar();
                    names[j] = f.field().format("%H.%n");
                }
                j++;
            }
        }
        starts[placed.size()] = j;
        table.setFoldedState(starts, receivers, offsets, kinds, references, bits, names);
    }

    private record PlacedCode(int offset, AOTInstalledCode code, List<BuildTimeCompiledMethod.FoldedField> folded) {
    }

    /**
     * The image heap is final (the snapshot was sealed before the heap layout), so every root is
     * in its final state: an assumption invalidated from now on no longer drops code.
     */
    @Override
    public void beforeImageWrite(BeforeImageWriteAccess access) {
        printSummary();
        /* A root whose code was dropped after the analysis. */
        failOnError();
    }

    /**
     * With {@link BuildTimeCompilationOptions#TruffleAOTFailOnError}, fails the build if a root is
     * skipped for a reason that {@link #isFailure} counts, listing all of them, after printing the
     * summary. Roots excluded by the filter or the total budget, or executed at build time, do not
     * fail it.
     */
    private void failOnError() {
        List<String> violations = limitViolations();
        if (!BuildTimeCompilationOptions.TruffleAOTFailOnError.getValue()) {
            violations.forEach(v -> LogUtils.warning("%s", v));
            return;
        }
        List<String> failures = new ArrayList<>(violations);
        for (BuildTimeCompilationSupport.Rejection r : rejections()) {
            if (isFailure(r)) {
                failures.add("Truffle AOT tier: root '" + r.rootName() + "' was not compiled: " + r.reason());
            }
        }
        if (!failures.isEmpty()) {
            printSummary();
            throw UserError.abort(failures);
        }
    }

    /**
     * Whether the skipped root {@code r} fails the build with
     * {@link BuildTimeCompilationOptions#TruffleAOTFailOnError}. Not the roots that are not
     * eligible by design: those that {@code polyglot.image-build-time.AOTCompileFilter} excludes,
     * and those executed at build time, whose live AST pre-initialization specialized (see
     * {@link BuildTimeCompilationSupport#selectPreinitialized}).
     */
    private static boolean isFailure(BuildTimeCompilationSupport.Rejection r) {
        return !BuildTimeCompilationSupport.FILTERED.equals(r.reason()) && !BuildTimeCompilationSupport.EXECUTED_AT_BUILD_TIME.equals(r.reason()) &&
                        !BuildTimeCompilationSupport.OVER_TOTAL_BUDGET.equals(r.reason());
    }

    /**
     * Prints the summary, once: a line per root, in {@link #roots} order, with its final state
     * ({@link #summaryLine}). An abort after the analysis prints it before the roots are installed.
     */
    private synchronized void printSummary() {
        if (summaryPrinted) {
            return;
        }
        summaryPrinted = true;
        for (AOTRoot root : roots) {
            summary(summaryLine(root));
        }
    }

    /**
     * {@code skipped <name>: <reason>}, {@code compiled <name> <statistics>}, or
     * {@code kept <name> <statistics>: <reason>}.
     */
    private static String summaryLine(AOTRoot root) {
        if (root.rejection != null) {
            return "skipped " + root.name + ": " + root.rejection;
        }
        StringBuilder line = new StringBuilder(root.kept != null ? "kept " : "compiled ").append(root.name);
        if (root.compiled != null) {
            line.append(' ').append(root.compiled);
        }
        if (root.installed != null) {
            line.append(' ').append(root.installed);
        }
        if (root.kept != null) {
            line.append(": ").append(root.kept);
        }
        return line.toString();
    }

    private static int parseBudget() {
        String value = System.getProperty(AOT_COMPILE_BUDGET, "0").strip();
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw UserError.abort("Invalid value for %s, expected a number of nodes: %s", AOT_COMPILE_BUDGET, value);
        }
    }

    /**
     * Records that {@code root} is not compiled, or its code dropped, and why. The first reason is
     * the final one: nothing compiles a skipped root again.
     */
    private synchronized void reject(AOTRoot root, String reason) {
        if (root.rejection == null) {
            root.rejection = reason;
        }
    }

    private synchronized boolean isRejected(AOTRoot root) {
        return root.rejection != null;
    }

    private synchronized void compiled(AOTRoot root, int nodes, int allocations, String allocationSites, int assumptions) {
        root.nodes = nodes;
        root.allocations = allocations;
        root.allocationSites = allocationSites;
        root.compiled = "nodes=" + nodes + " allocs=" + allocations + " assumptions=" + assumptions;
    }

    /**
     * The allocation sites of the partially evaluated {@code graph}, by what they allocate: object
     * and array allocations, virtual objects that are materialized, and boxings (except of booleans
     * and bytes), which remain after partial escape analysis only where the value escapes.
     */
    private static Map<String, Integer> allocationSites(StructuredGraph graph) {
        Map<String, Integer> sites = new TreeMap<>();
        for (jdk.graal.compiler.graph.Node node : graph.getNodes()) {
            if (node instanceof NewInstanceNode n) {
                sites.merge("New(" + n.instanceClass().toJavaName(false) + ")", 1, Integer::sum);
            } else if (node instanceof NewArrayNode n) {
                sites.merge("NewArray(" + n.elementType().toJavaName(false) + ")", 1, Integer::sum);
            } else if (node instanceof AbstractNewObjectNode n) {
                sites.merge(ClassUtil.getUnqualifiedName(n.getClass()), 1, Integer::sum);
            } else if (node instanceof BoxNode n && n.getBoxingKind() != JavaKind.Boolean && n.getBoxingKind() != JavaKind.Byte) {
                /* Boxing a boolean or a byte returns a cached box: it never allocates. */
                sites.merge("Box(" + n.getBoxingKind().getJavaName() + ")", 1, Integer::sum);
            } else if (node instanceof CommitAllocationNode commit) {
                for (VirtualObjectNode v : commit.getVirtualObjects()) {
                    sites.merge("Materialize(" + v.type().toJavaName(false) + ")", 1, Integer::sum);
                }
            }
        }
        return sites;
    }

    private static String describe(Map<String, Integer> sites) {
        StringBuilder sb = new StringBuilder();
        sites.forEach((site, count) -> sb.append(sb.isEmpty() ? "" : ", ").append(site).append(count > 1 ? "x" + count : ""));
        return sb.toString();
    }

    /**
     * The compiled roots that exceed {@link BuildTimeCompilationOptions#TruffleAOTMaxRootNodes} or
     * {@link BuildTimeCompilationOptions#TruffleAOTMaxRootAllocations}, as messages.
     */
    private synchronized List<String> limitViolations() {
        int maxNodes = BuildTimeCompilationOptions.TruffleAOTMaxRootNodes.getValue();
        int maxAllocations = BuildTimeCompilationOptions.TruffleAOTMaxRootAllocations.getValue();
        List<String> violations = new ArrayList<>();
        for (AOTRoot root : roots) {
            if (root.compiled == null || root.rejection != null) {
                continue;
            }
            if (maxNodes > 0 && root.nodes > maxNodes) {
                violations.add("Truffle AOT tier: root '" + root.name + "' has " + root.nodes + " nodes, more than -H:TruffleAOTMaxRootNodes=" + maxNodes);
            }
            if (maxAllocations >= 0 && root.allocations > maxAllocations) {
                violations.add("Truffle AOT tier: root '" + root.name + "' allocates at " + root.allocations + " sites, more than -H:TruffleAOTMaxRootAllocations=" + maxAllocations + ": " +
                                root.allocationSites);
            }
        }
        return violations;
    }

    /**
     * The roots that are not compiled, or whose code is dropped, with the reason, in
     * {@link #roots} order: what the summary reports as skipped.
     */
    synchronized List<BuildTimeCompilationSupport.Rejection> rejections() {
        List<BuildTimeCompilationSupport.Rejection> result = new ArrayList<>();
        for (AOTRoot root : roots) {
            if (root.rejection != null) {
                result.add(new BuildTimeCompilationSupport.Rejection(root.name, root.rejection));
            }
        }
        return result;
    }

    /**
     * Reports the unexpected failure {@code t} to compile the root {@code name}, and returns it as
     * the reason to skip the root. It never fails the build by itself; whether skipped roots are
     * errors is decided by TruffleAOTFailOnError.
     */
    private static String failure(String name, Throwable t) {
        /* Reported even without the summary, which prints the reason later. */
        LogUtils.warning("Truffle build-time compilation skipped %s: %s", name, t);
        if (BuildTimeCompilationOptions.TruffleAOTPrintSummary.getValue()) {
            t.printStackTrace(System.out);
        }
        return String.valueOf(t);
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
