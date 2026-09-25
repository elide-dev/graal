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

import java.util.List;

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
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * {@code static Object aot_<id>_<name>(OptimizedCallTarget receiver, Object[] args)}: the AOT tier
 * code of one shared call target, entered through {@link AOTInstalledCode} with the call boundary
 * calling convention.
 * <p>
 * Both variants are built from the same kit graph, which calls {@link AOTInstalledCode#enter} and
 * then {@code profiledPERoot} of the shadow target (a constant), so that the frame states of the
 * two variants correspond:
 * <ul>
 * <li>The original variant is a deoptimization source: its kit graph is the compilation root of the
 * build-time partial evaluation of the shadow, in which {@code profiledPERoot} is inlined. Its
 * outermost frame is this method.</li>
 * <li>The {@link SubstrateCompilationDirectives#DEOPT_TARGET_METHOD} variant is the kit graph with
 * the deoptimization entries that the partially evaluated graph registered for this method, so
 * that the outermost frame of a deoptimization has a target.</li>
 * </ul>
 */
final class BuildTimeCompiledMethod extends NonBytecodeMethod {
    final TruffleBuildTimeCompilationFeature feature;
    final OptimizedCallTarget live;
    final OptimizedCallTarget shadow;
    /** The analysis method of this method; set right after creation. */
    AnalysisMethod analysisMethod;
    /** The code linked to {@link #live}; set right after creation. */
    AOTInstalledCode code;
    /**
     * The assumptions the partially evaluated graph relies on; the code must be invalidated when
     * one of them is. Not yet registered: see {@code AOTInstalledCode}.
     */
    volatile List<Assumptions.Assumption> assumptions = List.of();

    BuildTimeCompiledMethod(TruffleBuildTimeCompilationFeature feature, OptimizedCallTarget live, OptimizedCallTarget shadow, ResolvedJavaType holder, Signature signature, ConstantPool cp) {
        super("aot_" + live.id + "_" + sanitize(live.getRootNode().getName()), true, holder, signature, cp);
        this.feature = feature;
        this.live = live;
        this.shadow = shadow;
    }

    @Override
    public StructuredGraph buildGraph(DebugContext debug, AnalysisMethod method, HostedProviders providers, Purpose purpose) {
        if (SubstrateCompilationDirectives.isDeoptTarget(method)) {
            /* Not partially evaluated: the callee frames have their own deoptimization targets. */
            return buildKitGraph(debug, method, providers, new DeoptSourceGraphKit.DeoptInfoProvider(method), feature.profiledPERoot(), shadow);
        }
        StructuredGraph graph = feature.partiallyEvaluate(this, debug, method, providers);
        if (graph == null) {
            /*
             * Skipped: the target is not linked to this code, so it is unreachable at run time. The
             * graph still has the shape of the kit graph, which the deoptimization target mirrors.
             */
            return buildKitGraph(debug, method, providers, null, feature.callBoundary(), live);
        }
        return graph;
    }

    /**
     * The kit graph: {@code AOTInstalledCode.enter(code); return receiver.target(args);}. Values
     * are only taken from the kit's frame state after a call, which the deoptimization entries of
     * the call proxy.
     */
    StructuredGraph buildKitGraph(DebugContext debug, AnalysisMethod method, HostedProviders providers, DeoptSourceGraphKit.DeoptInfoProvider deoptInfo, AnalysisMethod target,
                    OptimizedCallTarget receiver) {
        HostedGraphKit kit = new HostedGraphKit(debug, providers, method);
        int nextDeoptIndex = DeoptSourceGraphKit.startMethod(kit, deoptInfo, 0);
        nextDeoptIndex = DeoptSourceGraphKit.invokeWithDeoptAndExceptionUnwind(kit, deoptInfo, nextDeoptIndex, feature.enter(), InvokeKind.Static, kit.createObject(code));
        DeoptSourceGraphKit.invokeWithDeoptAndExceptionUnwind(kit, deoptInfo, nextDeoptIndex, target, InvokeKind.Special, kit.createObject(receiver), kit.loadLocal(1, JavaKind.Object));
        kit.createReturn(kit.getFrameState().pop(JavaKind.Object), JavaKind.Object);
        return kit.finalizeGraph();
    }

    private static String sanitize(String name) {
        return name == null ? "root" : name.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
