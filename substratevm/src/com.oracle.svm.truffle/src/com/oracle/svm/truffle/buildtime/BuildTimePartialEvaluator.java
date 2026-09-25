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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.graalvm.collections.EconomicMap;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.truffle.api.SubstrateKnownTruffleTypes;
import com.oracle.svm.truffle.api.SubstratePartialEvaluator;
import com.oracle.svm.truffle.api.SubstrateTruffleGraphBuilderPlugins;
import com.oracle.svm.truffle.api.SubstrateTruffleUniverseFactory;
import com.oracle.svm.util.GuestAnnotationAccess;
import com.oracle.svm.util.OriginalClassProvider;
import com.oracle.truffle.compiler.TruffleCompilable;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.annotation.AnnotationValue;
import jdk.graal.compiler.graph.SourceLanguagePositionProvider;
import jdk.graal.compiler.java.GraphBuilderPhase;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.ParameterPlugin;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.PEGraphDecoder;
import jdk.graal.compiler.replacements.PEGraphDecoder.SpecialCallTargetCacheKey;
import jdk.graal.compiler.truffle.ConstantFieldInfo;
import jdk.graal.compiler.truffle.PartialEvaluationMethodInfo;
import jdk.graal.compiler.truffle.PartialEvaluator;
import jdk.graal.compiler.truffle.PartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleTierContext;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Truffle partial evaluator over analysis types, used during points-to analysis to compile the AOT
 * shadows of pre-initialized call targets.
 * <p>
 * {@link SubstratePartialEvaluator} cannot be reused: its method, field and type queries are final
 * and require the Substrate universe ({@code TruffleMethod}, {@code TruffleField},
 * {@code TruffleType}), and its decoder reads the runtime graphs that are only encoded after
 * analysis. This class computes the same information directly from analysis elements, the same way
 * {@link SubstrateTruffleUniverseFactory} does, and decodes callees from the analysis-parsed
 * runtime-compiled variants ({@link BuildTimePEGraphDecoder}). Plugin configuration is the same as
 * {@link SubstratePartialEvaluator}.
 */
final class BuildTimePartialEvaluator extends PartialEvaluator {
    private final BigBang bb;
    private final ConcurrentHashMap<ResolvedJavaMethod, PartialEvaluationMethodInfo> methodInfos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResolvedJavaField, Optional<ConstantFieldInfo>> fieldInfos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResolvedJavaType, Boolean> valueTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResolvedJavaMethod, Object> invocationPluginsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SpecialCallTargetCacheKey, Object> specialCallTargetCache = new ConcurrentHashMap<>();
    /** The compilation root method of each shadow being partially evaluated, see {@link #partiallyEvaluateAt}. */
    private final ConcurrentHashMap<OptimizedCallTarget, AnalysisMethod> rootMethods = new ConcurrentHashMap<>();
    /** The graphs of the compilation root methods of {@link #rootMethods}. */
    private final ConcurrentHashMap<ResolvedJavaMethod, EncodedGraph> rootGraphs = new ConcurrentHashMap<>();

    private final BuildTimeConstantFieldProvider buildTimeConstantFieldProvider;

    BuildTimePartialEvaluator(TruffleCompilerConfiguration config, GraphBuilderConfiguration graphBuilderConfigForRoot, BigBang bb) {
        super(config, graphBuilderConfigForRoot);
        this.bb = bb;
        this.buildTimeConstantFieldProvider = new BuildTimeConstantFieldProvider(this.constantFieldProvider, bb.getMetaAccess(), bb.getConstantReflectionProvider());
    }

    @Override
    protected PEGraphDecoder createGraphDecoder(TruffleTierContext context, InvocationPlugins invocationPlugins, InlineInvokePlugin[] inlineInvokePlugins, ParameterPlugin parameterPlugin,
                    NodePlugin[] nodePlugins, SourceLanguagePositionProvider sourceLanguagePositionProvider, EconomicMap<ResolvedJavaMethod, EncodedGraph> graphCache,
                    Supplier<AutoCloseable> createCachedGraphScope) {
        return new BuildTimePEGraphDecoder(bb, rootGraphs, context.graph, config.lastTier().providers().copyWith(buildTimeConstantFieldProvider), loopExplosionPlugin, invocationPlugins,
                        inlineInvokePlugins, parameterPlugin, nodePlugins, sourceLanguagePositionProvider, specialCallTargetCache, invocationPluginsCache);
    }

    /*
     * The graphs partially evaluated from these roots are decoded from their runtime-compiled
     * variants, whose frame states denote the variant (see BuildTimePEGraphDecoder), so the graphs
     * are rooted in the variant too. Invokes in decoded graphs still target the original methods,
     * which is what the Truffle types refer to.
     */

    private static ResolvedJavaMethod runtimeVariant(ResolvedJavaMethod method) {
        return ((AnalysisMethod) method).getOrCreateMethodVariant(SubstrateCompilationDirectives.RUNTIME_COMPILED_METHOD);
    }

    /**
     * Runs {@code partialEvaluation} with {@code rootMethod} and its graph {@code rootGraph} as the
     * compilation root of {@code shadow}, instead of {@code profiledPERoot}: the graph produced for
     * {@code shadow} is then a graph of {@code rootMethod}, whose outermost frame is
     * {@code rootMethod}, and in which {@code profiledPERoot} is inlined where {@code rootGraph}
     * calls it (with the shadow as a constant receiver).
     */
    <T> T partiallyEvaluateAt(OptimizedCallTarget shadow, AnalysisMethod rootMethod, EncodedGraph rootGraph, Supplier<T> partialEvaluation) {
        VMError.guarantee(rootMethods.putIfAbsent(shadow, rootMethod) == null, "concurrent partial evaluation of %s", shadow);
        rootGraphs.put(rootMethod, rootGraph);
        try {
            return partialEvaluation.get();
        } finally {
            rootGraphs.remove(rootMethod);
            rootMethods.remove(shadow);
        }
    }

    @Override
    public ResolvedJavaMethod rootForCallTarget(TruffleCompilable compilable) {
        if (compilable instanceof BuildTimeTruffleCompiler.Compilable c) {
            AnalysisMethod rootMethod = rootMethods.get(c.target);
            if (rootMethod != null) {
                return rootMethod;
            }
        }
        return runtimeVariant(super.rootForCallTarget(compilable));
    }

    @Override
    public ResolvedJavaMethod inlineRootForCallTarget(TruffleCompilable compilable) {
        return runtimeVariant(super.inlineRootForCallTarget(compilable));
    }

    @Override
    public ResolvedJavaMethod getCallDirect() {
        return runtimeVariant(super.getCallDirect());
    }

    @Override
    public ResolvedJavaMethod getCallInlined() {
        return runtimeVariant(super.getCallInlined());
    }

    @Override
    protected GraphBuilderPhase.Instance createGraphBuilderPhaseInstance(CoreProviders providers, GraphBuilderConfiguration graphBuilderConfig, OptimisticOptimizations optimisticOpts) {
        throw VMError.shouldNotReachHere("callee graphs are parsed by the analysis");
    }

    @Override
    public PartialEvaluationMethodInfo getMethodInfo(ResolvedJavaMethod method) {
        return methodInfos.computeIfAbsent(method, m -> SubstrateTruffleUniverseFactory.createPartialEvaluationMethodInfo(config.runtime(), m, types));
    }

    @Override
    public ConstantFieldInfo getConstantFieldInfo(ResolvedJavaField field) {
        return fieldInfos.computeIfAbsent(field, f -> Optional.ofNullable(computeConstantFieldInfo(f, GuestAnnotationAccess.getDeclaredAnnotationValues(f), types,
                        OriginalClassProvider::getOriginalType))).orElse(null);
    }

    @Override
    public boolean isValueType(ResolvedJavaType type) {
        return valueTypes.computeIfAbsent(type, this::computeIsValueType);
    }

    /** Same as {@link SubstrateTruffleUniverseFactory#createType}. */
    private boolean computeIsValueType(ResolvedJavaType type) {
        Map<ResolvedJavaType, AnnotationValue> annotations = GuestAnnotationAccess.getDeclaredAnnotationValues(type);
        return annotations.containsKey(OriginalClassProvider.getOriginalType(types.CompilerDirectives_ValueType));
    }

    /** Same as {@link SubstratePartialEvaluator}. */
    @Override
    protected boolean foldAssumptionsWhenParsing() {
        return false;
    }

    /** Same as {@link SubstratePartialEvaluator}. */
    @Override
    protected void registerGraphBuilderInvocationPlugins(InvocationPlugins invocationPlugins, boolean canDelayIntrinsification) {
        super.registerGraphBuilderInvocationPlugins(invocationPlugins, canDelayIntrinsification);
        SubstrateTruffleGraphBuilderPlugins.registerInvocationPlugins(invocationPlugins, canDelayIntrinsification, (SubstrateKnownTruffleTypes) getTypes());
        registerLanguageContextObjectPlugin(invocationPlugins);
    }

    /**
     * Without code sharing, single-context code reaches the context object of a language through
     * the environment of its language context, which patching replaces, so that environment is
     * never folded ({@link BuildTimeConstantFieldProvider}). The context object itself survives
     * patching: {@code PolyglotLanguageContext.getContextImpl()} of a constant language context is
     * folded to it, and recorded, so that the code is only linked if patching kept it.
     */
    private static void registerLanguageContextObjectPlugin(InvocationPlugins invocationPlugins) {
        if (!BuildTimeCompilationSupport.isBoundPreinitialization()) {
            return;
        }
        Registration r = new Registration(invocationPlugins, "com.oracle.truffle.polyglot.PolyglotLanguageContext");
        r.register(new InvocationPlugin("getContextImpl", InvocationPlugin.Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode languageContext = receiver.get(false);
                if (!languageContext.isJavaConstant()) {
                    return false;
                }
                JavaConstant contextObject = BuildTimeConstantFieldProvider.foldLanguageContextObject(languageContext.asJavaConstant(), b.getMetaAccess(), b.getConstantReflection());
                if (contextObject == null) {
                    return false;
                }
                b.addPush(JavaKind.Object, ConstantNode.forConstant(contextObject, b.getMetaAccess()));
                return true;
            }
        });
    }

    /** Same as {@link SubstratePartialEvaluator}. */
    @Override
    protected InvocationPlugins createDecodingInvocationPlugins(PartialEvaluatorConfiguration peConfig, Plugins parent, Providers tierProviders) {
        InvocationPlugins decodingInvocationPlugins = new InvocationPlugins();
        parent.getInvocationPlugins().collectRuntimeCheckedPlugins(decodingInvocationPlugins, config.architecture());
        registerGraphBuilderInvocationPlugins(decodingInvocationPlugins, false);
        peConfig.registerDecodingInvocationPlugins(decodingInvocationPlugins, false, config.lastTier().providers(), config.architecture());
        decodingInvocationPlugins.closeRegistration();
        return decodingInvocationPlugins;
    }

    /** Same as {@link SubstratePartialEvaluator}: the analysis already applied the node plugins. */
    @Override
    protected NodePlugin[] createNodePlugins(Plugins plugins) {
        return null;
    }
}
