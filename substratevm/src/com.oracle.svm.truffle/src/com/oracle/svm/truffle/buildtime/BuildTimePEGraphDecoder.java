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

import java.util.concurrent.ConcurrentHashMap;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.graal.compiler.bytecode.BytecodeProvider;
import jdk.graal.compiler.core.common.PermanentBailoutException;
import jdk.graal.compiler.graph.SourceLanguagePositionProvider;
import jdk.graal.compiler.nodes.EncodedGraph;
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

    BuildTimePEGraphDecoder(BigBang bb, StructuredGraph graph, CoreProviders providers, LoopExplosionPlugin loopExplosionPlugin, InvocationPlugins invocationPlugins,
                    InlineInvokePlugin[] inlineInvokePlugins, ParameterPlugin parameterPlugin, NodePlugin[] nodePlugins, SourceLanguagePositionProvider sourceLanguagePosition,
                    ConcurrentHashMap<SpecialCallTargetCacheKey, Object> specialCallTargetCache, ConcurrentHashMap<ResolvedJavaMethod, Object> invocationPluginsCache) {
        /* Same flags as the runtime decoder (SubstratePEGraphDecoder). */
        super(AnalysisParsedGraph.HOST_ARCHITECTURE, graph, providers, loopExplosionPlugin, invocationPlugins, inlineInvokePlugins, parameterPlugin, nodePlugins, sourceLanguagePosition,
                        specialCallTargetCache, invocationPluginsCache, false, false);
        this.bb = bb;
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
        ResolvedJavaMethod scopeMethod = encodedGraph != null ? runtimeVariant(method) : method;
        return super.createMethodScope(targetGraph, caller, callerLoopScope, encodedGraph, scopeMethod, invokeData, inliningDepth, arguments);
    }

    @Override
    protected EncodedGraph lookupEncodedGraph(ResolvedJavaMethod method, BytecodeProvider intrinsicBytecodeProvider) {
        AnalysisParsedGraph parsed = runtimeVariant(method).ensureGraphParsed(bb);
        EncodedGraph result = parsed == null ? null : parsed.getEncodedGraph();
        if (result == null) {
            /* Parsing failed or is not supported: a bailout, so the root is skipped. */
            throw new PermanentBailoutException("Graph not available for build-time compilation: %s", method.format("%H.%n(%p)"));
        }
        return result;
    }
}
