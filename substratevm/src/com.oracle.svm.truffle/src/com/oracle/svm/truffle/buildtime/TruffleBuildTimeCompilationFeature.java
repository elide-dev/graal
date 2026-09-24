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

import java.util.List;

import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.graal.RuntimeCompilationSupport;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.hosted.FeatureHandler;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.NativeImageGenerator;
import com.oracle.svm.truffle.TruffleFeature;
import com.oracle.svm.truffle.api.SubstrateKnownTruffleTypes;
import com.oracle.svm.truffle.api.SubstrateTruffleRuntime;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.truffle.EconomyPartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.KnownTruffleTypes;
import jdk.graal.compiler.truffle.PartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.TruffleTierConfiguration;

/**
 * Orchestrates the Truffle AOT tier: selects the shared call targets of the pre-initialized engine
 * and partially evaluates their AOT shadows on analysis types. Installation follows in a later
 * step; for now the partial evaluation results are only reported
 * ({@link BuildTimeCompilationOptions#TruffleAOTPrintSummary}).
 */
public final class TruffleBuildTimeCompilationFeature implements InternalFeature {

    private BuildTimeCompilationSupport.Selection selection;
    /**
     * The graph builder plugins of runtime compilation, before TruffleFeature adds the Truffle
     * parsing plugins: the plugins the runtime Truffle compiler is configured with.
     */
    private GraphBuilderConfiguration.Plugins runtimePlugins;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return TruffleFeature.isInConfiguration() && Boolean.getBoolean("polyglot.image-build-time.AOTCompile");
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return List.of(TruffleFeature.class);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        RuntimeCompilationFeature.singleton().addAfterInstallRuntimeConfigCallback(() -> runtimePlugins = RuntimeCompilationFeature.singleton().getHostedProviders().getGraphBuilderPlugins());
    }

    @Override
    @SuppressWarnings("try")
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        /* TruffleFeature.beforeAnalysis, which runs first, pre-initialized the engine. */
        selection = BuildTimeCompilationSupport.selectPreinitialized();
        for (BuildTimeCompilationSupport.Rejection r : selection.rejected()) {
            summary("skipped " + r.rootName() + ": " + r.reason());
        }
        if (selection.selected().isEmpty()) {
            return;
        }
        /*
         * Partial evaluation runs eagerly here to validate the pipeline. Installation will move it
         * into the graph building of the build-time compiled methods.
         */
        BuildTimeTruffleCompiler compiler = new BuildTimeTruffleCompiler(createAnalysisConfig(access, runtimePlugins), access.getBigBang().getOptions());
        try (DebugContext debug = new DebugContext.Builder(access.getBigBang().getOptions()).build()) {
            for (BuildTimeCompilationSupport.Pair pair : selection.selected()) {
                partialEvaluate(compiler, pair, debug);
            }
        }
    }

    private static void partialEvaluate(BuildTimeTruffleCompiler compiler, BuildTimeCompilationSupport.Pair pair, DebugContext debug) {
        OptimizedCallTarget shadow = pair.shadow();
        assert shadow.isAOTShadow() : "only AOT shadows are compiled at build time: " + shadow;
        String name = rootName(pair.live());
        try {
            StructuredGraph graph = compiler.partialEvaluateForBuildTime(compiler.asCompilable(shadow), debug, reason -> summary("skipped " + name + ": " + reason));
            if (graph != null) {
                summary("compiled " + name + " nodes=" + graph.getNodeCount());
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            /*
             * A failure to compile one root never fails the build by itself; whether skipped roots
             * are errors is decided by TruffleAOTFailOnError.
             */
            summary("skipped " + name + ": " + t);
            if (BuildTimeCompilationOptions.TruffleAOTPrintSummary.getValue()) {
                t.printStackTrace(System.out);
            }
        }
    }

    private static String rootName(OptimizedCallTarget target) {
        String name = target.getRootNode().getName();
        return name == null ? target.getRootNode().toString() : name;
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
