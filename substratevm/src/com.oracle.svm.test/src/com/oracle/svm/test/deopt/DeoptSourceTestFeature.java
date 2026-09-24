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
package com.oracle.svm.test.deopt;

import static com.oracle.svm.hosted.code.SubstrateCompilationDirectives.DEOPT_TARGET_METHOD;

import java.util.List;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.PointsToAnalysisMethod;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.nodes.TestDeoptimizeNode;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import jdk.graal.compiler.phases.util.Providers;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Registers {@link DeoptSourceTest#compute} as a deoptimization source and inlines
 * {@link DeoptSourceTest#helper} into it during parsing, the way a partially evaluated graph has
 * its frames inlined before analysis. In that inlined copy {@link DeoptSourceTest#deoptHere()}
 * becomes a transfer to the interpreter; everywhere else, including the deopt target of
 * {@code helper}, it is an ordinary call that counts executions.
 */
public final class DeoptSourceTestFeature implements InternalFeature {

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        /* Deoptimization of image code is only supported together with runtime compilation. */
        return List.of(RuntimeCompilationFeature.class);
    }

    /** Whether the graph being built is the one of a registered deopt source. */
    private static boolean inDeoptSourceGraph(GraphBuilderContext b) {
        ResolvedJavaMethod root = b.getGraph().method();
        return root instanceof AnalysisMethod && SubstrateCompilationDirectives.singleton().isRegisteredDeoptSource(root);
    }

    @Override
    public void registerGraphBuilderPlugins(Providers providers, Plugins plugins, ParsingReason reason) {
        if (reason != ParsingReason.PointsToAnalysis) {
            return;
        }
        plugins.appendInlineInvokePlugin(new InlineInvokePlugin() {
            @Override
            public InlineInfo shouldInlineInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
                if (method.getName().equals("helper") && method.getDeclaringClass().equals(b.getMetaAccess().lookupJavaType(DeoptSourceTest.class)) && inDeoptSourceGraph(b)) {
                    return InlineInfo.createStandardInlineInfo(method);
                }
                return null;
            }
        });
    }

    @Override
    public void registerInvocationPlugins(Providers providers, Plugins plugins, ParsingReason reason) {
        if (reason != ParsingReason.PointsToAnalysis) {
            return;
        }
        Registration r = new Registration(plugins.getInvocationPlugins(), DeoptSourceTest.class);
        r.register(new InvocationPlugin.RequiredInvocationPlugin("deoptHere") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, InvocationPlugin.Receiver receiver) {
                if (inDeoptSourceGraph(b)) {
                    /* Only the copy of helper inlined into the deopt source deoptimizes. */
                    b.add(new TestDeoptimizeNode());
                    return true;
                }
                return false; // keep the real call, e.g., in the deopt target of helper
            }
        });
        r.register(new InvocationPlugin.RequiredInvocationPlugin("isDeoptTarget") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, InvocationPlugin.Receiver receiver) {
                b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(SubstrateCompilationDirectives.isDeoptTarget(b.getGraph().method())));
                return true;
            }
        });
        r.register(new InvocationPlugin.RequiredInvocationPlugin("isFeatureActive") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, InvocationPlugin.Receiver receiver) {
                b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(true));
                return true;
            }
        });
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        /* Deopt target graphs are only accepted for classes initialized at build time. */
        RuntimeClassInitialization.initializeAtBuildTime(DeoptSourceTest.class);
    }

    @Override
    public void beforeAnalysis(Feature.BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        try {
            AnalysisMethod compute = access.getMetaAccess().lookupJavaMethod(DeoptSourceTest.class.getDeclaredMethod("compute", int.class));
            SubstrateCompilationDirectives.singleton().registerDeoptSource(compute);

            /*
             * Nothing calls the deopt target of the inlined helper, so it must be made reachable
             * explicitly for the analysis to parse it.
             */
            AnalysisMethod helper = access.getMetaAccess().lookupJavaMethod(DeoptSourceTest.class.getDeclaredMethod("helper", int.class));
            ((PointsToAnalysisMethod) helper).getOrCreateMethodVariant(DEOPT_TARGET_METHOD);
            access.registerAsRoot(helper, true, "deopt source test", DEOPT_TARGET_METHOD);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
