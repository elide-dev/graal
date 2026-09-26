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
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.shared.util.VMError;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.bytecode.BytecodeProvider;
import jdk.graal.compiler.core.common.PermanentBailoutException;
import jdk.graal.compiler.graph.SourceLanguagePositionProvider;
import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.LoopExplosionPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.ParameterPlugin;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.replacements.PEGraphDecoder;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Decodes callee graphs for build-time partial evaluation from the analysis-parsed
 * {@link SubstrateCompilationDirectives#RUNTIME_COMPILED_METHOD runtime-compiled} variants, i.e.,
 * the same graphs the runtime partial evaluator decodes, but before they are encoded for the image
 * and still over analysis types.
 */
final class BuildTimePEGraphDecoder extends PEGraphDecoder {
    private final BigBang bb;
    /**
     * The graphs of compilation root methods that are not runtime-compiled methods, see
     * {@code BuildTimePartialEvaluator.partiallyEvaluateAt}.
     */
    private final Map<ResolvedJavaMethod, EncodedGraph> rootGraphs;
    /** {@code OptimizedCallTarget.profileArguments} and {@code profileIndirectArguments}. */
    private final ResolvedJavaMethod profileArguments;
    private final ResolvedJavaMethod profileIndirectArguments;

    BuildTimePEGraphDecoder(BigBang bb, Map<ResolvedJavaMethod, EncodedGraph> rootGraphs, StructuredGraph graph, CoreProviders providers, LoopExplosionPlugin loopExplosionPlugin, InvocationPlugins invocationPlugins,
                    InlineInvokePlugin[] inlineInvokePlugins, ParameterPlugin parameterPlugin, NodePlugin[] nodePlugins, SourceLanguagePositionProvider sourceLanguagePosition,
                    ConcurrentHashMap<SpecialCallTargetCacheKey, Object> specialCallTargetCache, ConcurrentHashMap<ResolvedJavaMethod, Object> invocationPluginsCache) {
        /* Same flags as the runtime decoder (SubstratePEGraphDecoder). */
        super(AnalysisParsedGraph.HOST_ARCHITECTURE, graph, providers, loopExplosionPlugin, invocationPlugins, inlineInvokePlugins, parameterPlugin, nodePlugins, sourceLanguagePosition,
                        specialCallTargetCache, invocationPluginsCache, false, false);
        this.bb = bb;
        this.rootGraphs = rootGraphs;
        try {
            this.profileArguments = bb.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.class.getDeclaredMethod("profileArguments", Object[].class));
            this.profileIndirectArguments = bb.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.class.getDeclaredMethod("profileIndirectArguments", Object[].class));
        } catch (NoSuchMethodException e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    /**
     * The argument profile of a callee that was not executed at build time is still
     * uninitialized, and partial evaluation folds it: {@code callDirect} then profiles no
     * arguments in compiled code. JIT callers are usually compiled only after they called the
     * callee in the interpreter, but every call site in AOT tier code is compiled before: once
     * interpreted callers initialize the callee's profile and the JIT compiles the callee trusting
     * it, calls from AOT tier code would pass arguments of other types unchecked. So AOT tier code
     * profiles the arguments of every direct call as indirect calls do, behind a boundary, which
     * reads the current profile and invalidates it on a mismatch.
     */
    @Override
    protected MethodCallTargetNode trySimplifyCallTarget(PEMethodScope methodScope, InvokeData invokeData, MethodCallTargetNode callTarget) {
        if (callTarget.targetMethod().equals(profileArguments)) {
            callTarget.setTargetMethod(profileIndirectArguments);
        }
        return super.trySimplifyCallTarget(methodScope, invokeData, callTarget);
    }

    private static AnalysisMethod runtimeVariant(ResolvedJavaMethod method) {
        return ((AnalysisMethod) method).getOrCreateMethodVariant(SubstrateCompilationDirectives.RUNTIME_COMPILED_METHOD);
    }

    /**
     * Invokes in the runtime-compiled graphs target the original methods, but the frame states of
     * a runtime-compiled graph denote its runtime-compiled variant. A decoded graph is therefore
     * scoped as that variant, as frame state processing during inlining requires. At image run
     * time both are the same {@code SubstrateMethod}.
     */
    @Override
    protected PEMethodScope createMethodScope(StructuredGraph targetGraph, PEMethodScope caller, LoopScope callerLoopScope, EncodedGraph encodedGraph, ResolvedJavaMethod method,
                    InvokeData invokeData, int inliningDepth, ValueNode[] arguments) {
        ResolvedJavaMethod scopeMethod = encodedGraph != null && !rootGraphs.containsKey(method) ? runtimeVariant(method) : method;
        return super.createMethodScope(targetGraph, caller, callerLoopScope, encodedGraph, scopeMethod, invokeData, inliningDepth, arguments);
    }

    private static boolean isRuntimeCompilationCandidate(ResolvedJavaMethod method) {
        return RuntimeCompilationFeature.singleton().isRuntimeCompilationCandidate((AnalysisMethod) method);
    }

    /**
     * Only runtime compilation candidates are inlined, as at image run time: there, a method that
     * is not a candidate has no graph in the image, so it
     * {@linkplain ResolvedJavaMethod#hasNeverInlineDirective() is never inlined} and the call
     * remains. Invocation plugins still apply, since they are tried before inlining.
     */
    @Override
    protected LoopScope tryInline(PEMethodScope methodScope, LoopScope loopScope, InvokeData invokeData, MethodCallTargetNode callTarget) {
        if (callTarget.invokeKind().isDirect() && !isRuntimeCompilationCandidate(callTarget.targetMethod())) {
            return null;
        }
        if (isOutOfLine(callTarget.targetMethod())) {
            return null;
        }
        return super.tryInline(methodScope, loopScope, invokeData, callTarget);
    }

    /**
     * Methods that AOT tier code calls instead of inlining: those of TruffleString. A string
     * operation inlines hundreds of nodes (a concatenation about 400, a number to string conversion
     * about 1000), and a language node that uses several of them multiplies that in every function
     * that uses the node, while AOT tier code only runs until the regular tiers take over, which
     * inline them as usual. Called, a string operation runs in its image-compiled method, where it
     * still specializes without deoptimizing anything.
     */
    private static boolean isOutOfLine(ResolvedJavaMethod method) {
        return method.getDeclaringClass().toJavaName().startsWith("com.oracle.truffle.api.strings.");
    }

    /**
     * Never creates and parses a runtime-compiled variant the JIT would not see. After
     * {@link #tryInline}, this is only reached for candidates; like the runtime decoder, which
     * fails for a method without a graph ({@code SubstratePEGraphDecoder}), anything else is an
     * error, but here it only skips the root.
     */
    @Override
    protected EncodedGraph lookupEncodedGraph(ResolvedJavaMethod method, BytecodeProvider intrinsicBytecodeProvider) {
        EncodedGraph rootGraph = rootGraphs.get(method);
        if (rootGraph != null) {
            return rootGraph;
        }
        if (!isRuntimeCompilationCandidate(method)) {
            throw new PermanentBailoutException("Not a runtime compilation candidate: %s", method.format("%H.%n(%p)"));
        }
        AnalysisParsedGraph parsed = runtimeVariant(method).ensureGraphParsed(bb);
        EncodedGraph result = parsed == null ? null : parsed.getEncodedGraph();
        if (result == null) {
            /* Parsing failed or is not supported: a bailout, so the root is skipped. */
            throw new PermanentBailoutException("Graph not available for build-time compilation: %s", method.format("%H.%n(%p)"));
        }
        return result;
    }
}
