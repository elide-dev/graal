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

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.graalvm.collections.EconomicMap;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.truffle.SubstrateTruffleCompilationIdentifier;
import com.oracle.svm.truffle.api.SubstratePostPartialEvaluationSuite;
import com.oracle.svm.truffle.api.SubstrateTruffleCompilerImpl;
import com.oracle.svm.truffle.api.SubstrateTruffleRuntime;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.compiler.OptimizedAssumptionDependency;
import com.oracle.truffle.compiler.TruffleCompilable;
import com.oracle.truffle.compiler.TruffleCompilationTask;
import com.oracle.truffle.compiler.TruffleCompilerRuntime;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.api.replacements.SnippetReflectionProvider;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.CompilationWrapper;
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.target.Backend;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.DiagnosticsOutputDirectory;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.truffle.PartialEvaluator;
import jdk.graal.compiler.truffle.PostPartialEvaluationSuite;
import jdk.graal.compiler.truffle.TruffleCompilationIdentifier;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.TruffleCompilerOptions;
import jdk.graal.compiler.truffle.TruffleTierConfiguration;
import jdk.graal.compiler.truffle.phases.InstrumentationSuite;
import jdk.graal.compiler.truffle.phases.TruffleTier;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;

/**
 * The Truffle compiler used at image build time to partially evaluate AOT shadows during analysis.
 * It only runs partial evaluation and the Truffle tier ({@link #partialEvaluateForBuildTime}); the
 * graphs are compiled by the hosted compile queue, never by this compiler's backend.
 * <p>
 * It is a separate instance from the pre-initialized runtime Truffle compiler, which must stay
 * uninitialized in the image heap: {@link TruffleCompilerImpl#initialize} would otherwise bake the
 * build-time options, Truffle tier and partial evaluator state into the runtime compiler, and
 * {@link TruffleCompilerImpl#initializeBackend} would register a second code installation task on
 * the runtime backend.
 * <p>
 * Analysis graphs hold {@code ImageHeapConstant}s, while {@link OptimizedCallTarget#asJavaConstant()}
 * returns a Substrate object constant. Call targets are therefore presented to the compiler as
 * {@link Compilable}s, whose constants come from the analysis snippet reflection, and constants
 * are turned back into call targets by {@link Runtime}.
 */
final class BuildTimeTruffleCompiler extends TruffleCompilerImpl {

    private final OptionValues options;

    BuildTimeTruffleCompiler(TruffleCompilerConfiguration config, OptionValues options) {
        super(config);
        this.options = options;
    }

    /** The compilable the build-time compiler partially evaluates for {@code target}. */
    TruffleCompilable asCompilable(OptimizedCallTarget target) {
        return ((Runtime) config.runtime()).wrap(target);
    }

    BuildTimePartialEvaluator partialEvaluator() {
        return (BuildTimePartialEvaluator) partialEvaluator;
    }

    @Override
    protected PartialEvaluator createPartialEvaluator(TruffleCompilerConfiguration configuration) {
        return new BuildTimePartialEvaluator(configuration, builderConfig, ((Runtime) configuration.runtime()).bb);
    }

    @Override
    public void initializeBackend(Backend backend) {
        /* No code is installed through the Truffle backend; leave the runtime backend untouched. */
    }

    /**
     * Same as {@link SubstrateTruffleCompilerImpl}, but with the plain
     * {@link PostPartialEvaluationSuite}: the phases {@link SubstratePostPartialEvaluationSuite} adds
     * require Substrate universe fields and methods. {@code TruffleBoundaryPhase} only acts with a
     * speculation log, which build-time partial evaluation does not have, and
     * {@code DeadStoreRemovalPhase} is an optimization only.
     */
    @Override
    protected TruffleTier newTruffleTier(OptionValues compilerOptions) {
        PostPartialEvaluationSuite postPartialEvaluationSuite = new PostPartialEvaluationSuite(options, TruffleCompilerOptions.IterativePartialEscape.getValue(compilerOptions));
        return new TruffleTier(compilerOptions, new InstrumentationSuite(partialEvaluator.instrumentationCfg, partialEvaluator.getInstrumentation()), postPartialEvaluationSuite);
    }

    @Override
    protected OptionValues getGraalOptions() {
        return options;
    }

    /**
     * Not cached: the compiler options cache of {@link TruffleCompilerImpl} is static, so it is in
     * the image heap, and its entries are used by runtime compilations of the same engine.
     */
    @Override
    public OptionValues getOrCreateCompilerOptions(TruffleCompilable compilable) {
        return createCompilerOptions(compilable);
    }

    @Override
    public TruffleCompilationIdentifier createCompilationIdentifier(TruffleCompilationTask task, TruffleCompilable compilable) {
        return new SubstrateTruffleCompilationIdentifier(task, compilable);
    }

    @Override
    protected DebugContext createDebugContext(OptionValues debugOptions, CompilationIdentifier compilationId, TruffleCompilable compilable, PrintStream logStream) {
        throw VMError.shouldNotReachHere("The build-time Truffle compiler only partially evaluates.");
    }

    @Override
    protected void parseGraalOptions(String[] optionSettings, EconomicMap<OptionKey<?>, Object> values) {
        throw VMError.shouldNotReachHere("The build-time Truffle compiler only partially evaluates.");
    }

    @Override
    protected DiagnosticsOutputDirectory getDebugOutputDirectory() {
        throw VMError.shouldNotReachHere("The build-time Truffle compiler only partially evaluates.");
    }

    @Override
    protected Map<CompilationWrapper.ExceptionAction, Integer> getCompilationProblemsPerAction() {
        throw VMError.shouldNotReachHere("The build-time Truffle compiler only partially evaluates.");
    }

    @Override
    protected InstalledCode createInstalledCode(TruffleCompilable compilable) {
        throw VMError.shouldNotReachHere("The build-time Truffle compiler only partially evaluates.");
    }

    @Override
    protected CompilationResult createCompilationResult(String name, CompilationIdentifier compilationIdentifier, TruffleCompilable compilable) {
        throw VMError.shouldNotReachHere("The build-time Truffle compiler only partially evaluates.");
    }

    @Override
    public PhaseSuite<HighTierContext> createGraphBuilderSuite(TruffleTierConfiguration tier) {
        return null;
    }

    /**
     * The {@link TruffleCompilerRuntime} of the build-time compiler. Delegates to the Substrate
     * Truffle runtime, but converts between analysis constants and call targets.
     */
    static final class Runtime implements TruffleCompilerRuntime {
        private final SubstrateTruffleRuntime delegate;
        final BigBang bb;
        private final SnippetReflectionProvider snippetReflection;
        private final ConcurrentHashMap<OptimizedCallTarget, Compilable> compilables = new ConcurrentHashMap<>();

        Runtime(SubstrateTruffleRuntime delegate, BigBang bb) {
            this.delegate = delegate;
            this.bb = bb;
            this.snippetReflection = bb.getSnippetReflectionProvider();
        }

        Compilable wrap(OptimizedCallTarget target) {
            return compilables.computeIfAbsent(target, t -> new Compilable(this, t));
        }

        private static OptimizedCallTarget unwrap(TruffleCompilable compilable) {
            return compilable instanceof Compilable c ? c.target : (OptimizedCallTarget) compilable;
        }

        @Override
        public TruffleCompilable asCompilableTruffleAST(JavaConstant constant) {
            if (constant == null || constant.isNull()) {
                return null;
            }
            Object object = snippetReflection.asObject(Object.class, constant);
            return object instanceof OptimizedCallTarget target ? wrap(target) : null;
        }

        @Override
        public void onCodeInstallation(TruffleCompilable compilable, InstalledCode installedCode) {
            throw VMError.shouldNotReachHere("The build-time Truffle compiler never installs code.");
        }

        @Override
        public Consumer<OptimizedAssumptionDependency> registerOptimizedAssumptionDependency(JavaConstant optimizedAssumption) {
            throw VMError.shouldNotReachHere("The build-time Truffle compiler never installs code.");
        }

        @Override
        public void log(String loggerId, TruffleCompilable compilable, String message) {
            delegate.log(loggerId, unwrap(compilable), message);
        }

        @Override
        public ResolvedJavaType resolveType(MetaAccessProvider metaAccess, String className, boolean required) {
            return delegate.resolveType(metaAccess, className, required);
        }

        @Override
        public boolean isSuppressedFailure(TruffleCompilable compilable, Supplier<String> serializedException) {
            return delegate.isSuppressedFailure(unwrap(compilable), serializedException);
        }

        @Override
        public boolean isJavaInstrumentationActive() {
            return delegate.isJavaInstrumentationActive();
        }
    }

    /**
     * A call target as seen by the build-time compiler. Only queries are delegated, and none of
     * them mutates a live target; compilation callbacks are ignored, since nothing is compiled or
     * installed for the call target here.
     */
    static final class Compilable implements TruffleCompilable {
        private final Runtime runtime;
        final OptimizedCallTarget target;
        private volatile JavaConstant constant;

        Compilable(Runtime runtime, OptimizedCallTarget target) {
            this.runtime = runtime;
            this.target = target;
        }

        @Override
        public JavaConstant asJavaConstant() {
            JavaConstant result = constant;
            if (result == null) {
                /*
                 * Created on first use, i.e., after prepareForCompilation: image heap field values
                 * are read when first accessed.
                 */
                result = runtime.snippetReflection.forObject(target);
                constant = result;
            }
            return result;
        }

        @Override
        public SpeculationLog getCompilationSpeculationLog() {
            return null;
        }

        @Override
        public void onCompilationFailed(Supplier<String> serializedException, boolean suppressed, boolean bailout, boolean permanentBailout, boolean graphTooBig) {
        }

        @Override
        public void onCompilationSuccess(int compilationTier, boolean lastTier) {
        }

        @Override
        public boolean onInvalidate(Object source, CharSequence reason, boolean wasActive) {
            return false;
        }

        @Override
        public String getName() {
            return target.getName();
        }

        /** Uncached for live targets: the target caches the count in a field. */
        @Override
        public int getNonTrivialNodeCount() {
            return target.isAOTShadow() ? target.getNonTrivialNodeCount() : NodeUtil.countNodes(target.getRootNode());
        }

        @Override
        public int countDirectCallNodes() {
            return target.countDirectCallNodes();
        }

        @Override
        public int getCallCount() {
            return target.getCallCount();
        }

        @Override
        public boolean cancelCompilation(CharSequence reason) {
            return false;
        }

        @Override
        public boolean isSameOrSplit(TruffleCompilable ast) {
            return target.isSameOrSplit(Runtime.unwrap(ast));
        }

        @Override
        public int getKnownCallSiteCount() {
            return target.getKnownCallSiteCount();
        }

        /**
         * Only AOT shadows are prepared at build time. A live callee must stay untouched: preparing
         * it would run {@code RootNode.prepareForCompilation} on the live root and create the
         * target's rewriting and valid-root assumptions if it was executed during
         * pre-initialization. Refusing makes the call site a real call, dispatching through the
         * callee's own code, instead of inlining the callee.
         */
        @Override
        public boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
            if (!target.isAOTShadow()) {
                return false;
            }
            return target.prepareForCompilation(rootCompilation, compilationTier, lastTier);
        }

        @Override
        public boolean isTrivial() {
            return target.isTrivial();
        }

        @Override
        public long engineId() {
            return target.engineId();
        }

        @Override
        public Map<String, String> getCompilerOptions() {
            return target.getCompilerOptions();
        }

        @Override
        public int getSuccessfulCompilationCount() {
            return target.getSuccessfulCompilationCount();
        }

        @Override
        public boolean canBeInlined() {
            return target.canBeInlined();
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }
}
