/*
 * Copyright (c) 2013, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.truffle.api;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.graal.RuntimeCompilationSupport;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.shared.util.ReflectionUtil;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.truffle.TruffleSupport;
import com.oracle.svm.truffle.isolated.IsolateAwareTruffleCompiler;
import com.oracle.svm.truffle.isolated.IsolatedTruffleRuntimeSupport;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.utilities.TriState;
import com.oracle.truffle.compiler.OptimizedAssumptionDependency;
import com.oracle.truffle.compiler.TruffleCompilable;
import com.oracle.truffle.compiler.TruffleCompilerRuntime;
import com.oracle.truffle.runtime.BackgroundCompileQueue;
import com.oracle.truffle.runtime.OptimizedAssumption;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedDirectCallNode;

import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.truffle.AbstractTruffleCompilationSupport;
import jdk.graal.compiler.truffle.EconomyPartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.KnownTruffleTypes;
import jdk.graal.compiler.truffle.PartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.TruffleTierConfiguration;
import jdk.vm.ci.meta.JavaConstant;

public class SubstrateTruffleSupport extends AbstractTruffleCompilationSupport {

    protected SubstrateTruffleSupport() {
    }

    public static SubstrateTruffleSupport create() {
        Class<? extends SubstrateTruffleSupport> supportClass;
        try {
            supportClass = Class.forName("com.oracle.svm.enterprise.truffle.EnterpriseTruffleSupport", false, SubstrateTruffleSupport.class.getClassLoader()).asSubclass(
                            SubstrateTruffleSupport.class);
        } catch (ClassNotFoundException e) {
            supportClass = SubstrateTruffleSupport.class;
        }
        return ReflectionUtil.newInstance(supportClass);
    }

    @Override
    public void registerRuntime(TruffleCompilerRuntime runtime) {
        throw CompilerDirectives.shouldNotReachHere("Should not be called. Not necessary to be called on SVM.");
    }

    /**
     * The {@link TruffleSupport} image singleton that Oracle's prebuilt enterprise Truffle jar
     * registers, to which this support delegates the methods that jar overrides; {@code null} if
     * there is none or if this support is a subclass.
     */
    private SubstrateTruffleSupport registeredTruffleSupport() {
        if (getClass() == SubstrateTruffleSupport.class && ImageSingletons.contains(TruffleSupport.class)) {
            return TruffleSupport.singleton();
        }
        return null;
    }

    @Override
    public String getCompilerConfigurationName(TruffleCompilerRuntime runtime) {
        SubstrateTruffleSupport registered = registeredTruffleSupport();
        if (registered != null) {
            return registered.getCompilerConfigurationName(runtime);
        }
        return "community";
    }

    @Override
    @Platforms(Platform.HOSTED_ONLY.class)
    public SubstrateTruffleCompiler createCompiler(TruffleCompilerRuntime compilerRuntime) {
        SubstrateTruffleSupport registered = registeredTruffleSupport();
        if (registered != null) {
            return registered.createCompiler(compilerRuntime);
        }
        SubstrateTruffleRuntime runtime = (SubstrateTruffleRuntime) compilerRuntime;
        SubstrateTruffleCompilerImpl compiler = new SubstrateTruffleCompilerImpl(createSubstrateTruffleCompilerConfig(runtime, getCompilerConfigurationName(runtime)));
        if (SubstrateOptions.SupportCompileInIsolates.getValue()) {
            return new IsolateAwareTruffleCompiler(compiler);
        }
        return compiler;
    }

    protected static TruffleCompilerConfiguration createSubstrateTruffleCompilerConfig(SubstrateTruffleRuntime runtime, String compilerConfigurationName) {
        RuntimeCompilationFeature runtimeCompilationFeature = RuntimeCompilationFeature.singleton();
        final GraphBuilderConfiguration.Plugins graphBuilderPlugins = runtimeCompilationFeature.getHostedProviders().getGraphBuilderPlugins();
        SubstrateBackend substrateBackend = RuntimeCompilationSupport.getRuntimeConfig().getBackendForNormalMethod();
        substrateBackend.setRuntimeToRuntimeInvokeMethod(ReflectionUtil.lookupMethod(SubstrateOptimizedCallTarget.class, "doInvoke", Object[].class));
        KnownTruffleTypes types = ImageSingletons.lookup(KnownTruffleTypes.class);
        Suites firstTierSuites = truffleSuites(RuntimeCompilationSupport.getFirstTierSuites());
        Suites fullOptSuites = truffleSuites(RuntimeCompilationSupport.getFullOptSuites());
        final TruffleTierConfiguration firstTier = new TruffleTierConfiguration(new EconomyPartialEvaluatorConfiguration(), substrateBackend,
                        RuntimeCompilationSupport.getFirstTierProviders(), firstTierSuites,
                        RuntimeCompilationSupport.getFirstTierLirSuites(),
                        types);

        PartialEvaluatorConfiguration peConfig = TruffleCompilerImpl.createPartialEvaluatorConfiguration(compilerConfigurationName);
        final TruffleTierConfiguration lastTier = new TruffleTierConfiguration(peConfig, substrateBackend,
                        RuntimeCompilationSupport.getRuntimeConfig().getProviders(), fullOptSuites,
                        RuntimeCompilationSupport.getLIRSuites(),
                        types);
        return new TruffleCompilerConfiguration(runtime, graphBuilderPlugins, runtimeCompilationFeature.getHostedProviders().getSnippetReflection(), firstTier, lastTier, types,
                        fullOptSuites, null);
    }

    /**
     * {@link TruffleTierConfiguration} adds the Truffle phases to the suites it is given. With
     * Ristretto, the shared runtime suites also compile bytecode loaded at run time, which those
     * phases cannot handle (they expect a Truffle compilation), so Truffle gets its own copy. Without
     * Ristretto, only Truffle uses the shared suites.
     */
    private static Suites truffleSuites(Suites runtimeSuites) {
        return SubstrateOptions.useRistretto() ? runtimeSuites.copy() : runtimeSuites;
    }

    public static boolean isIsolatedCompilation() {
        return !SubstrateUtil.HOSTED && SubstrateOptions.shouldCompileInIsolates();
    }

    public Consumer<OptimizedAssumptionDependency> registerOptimizedAssumptionDependency(JavaConstant optimizedAssumptionConstant) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.registerOptimizedAssumptionDependency(optimizedAssumptionConstant);
        }
        Object target = SubstrateObjectConstant.asObject(optimizedAssumptionConstant);
        OptimizedAssumption assumption = (OptimizedAssumption) target;
        return assumption.registerDependency();
    }

    public JavaConstant getCallTargetForCallNode(JavaConstant callNodeConstant) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.getCallTargetForCallNode(callNodeConstant);
        }
        Object target = SubstrateObjectConstant.asObject(callNodeConstant);
        OptimizedDirectCallNode callNode = (OptimizedDirectCallNode) target;
        OptimizedCallTarget callTarget = callNode.getCallTarget();
        return SubstrateObjectConstant.forObject(callTarget);
    }

    public BackgroundCompileQueue createBackgroundCompileQueue(SubstrateTruffleRuntime runtime) {
        SubstrateTruffleSupport registered = registeredTruffleSupport();
        if (registered != null) {
            return registered.createBackgroundCompileQueue(runtime);
        }
        return new BackgroundCompileQueue(runtime);
    }

    public TruffleCompilable asCompilableTruffleAST(JavaConstant constant) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.asCompilableTruffleAST(constant);
        }
        return SubstrateObjectConstant.asObject(OptimizedCallTarget.class, constant);
    }

    @SuppressWarnings("unused")
    public boolean tryLog(SubstrateTruffleRuntime runtime, String loggerId, TruffleCompilable compilable, String message) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.tryLog(loggerId, compilable, message);
        }
        return false;
    }

    public TriState tryIsSuppressedFailure(TruffleCompilable compilable, Supplier<String> serializedException) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.tryIsSuppressedFailure(compilable, serializedException);
        }
        return TriState.UNDEFINED;
    }

}
