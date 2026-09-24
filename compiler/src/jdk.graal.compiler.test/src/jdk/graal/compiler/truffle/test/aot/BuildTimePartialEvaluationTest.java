/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.graalvm.polyglot.Context;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.nodes.TruffleAssumption;
import jdk.graal.compiler.truffle.test.PartialEvaluationTest;
import jdk.vm.ci.meta.Assumptions.Assumption;

public class BuildTimePartialEvaluationTest extends PartialEvaluationTest {

    private OptimizedCallTarget parse(String code) {
        setupContext(Context.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false"));
        getContext().initialize(AOTTestLanguage.ID);
        return (OptimizedCallTarget) AOTTestLanguage.parseTarget(AOTTestLanguage.get(), code);
    }

    private StructuredGraph partialEvaluateForBuildTime(OptimizedCallTarget target, List<String> bailouts) {
        TruffleCompilerImpl compiler = getTruffleCompiler(target);
        return compiler.partialEvaluateForBuildTime(target, getDebugContext(compiler.getOrCreateCompilerOptions(target)), bailouts::add);
    }

    private static boolean hasCall(StructuredGraph graph, Predicate<String> methodName) {
        return graph.getNodes(MethodCallTargetNode.TYPE).stream().anyMatch(n -> methodName.test(n.targetMethod().getName()));
    }

    private static int countTruffleAssumptions(StructuredGraph graph) {
        int count = 0;
        for (Assumption a : graph.getAssumptions()) {
            if (a instanceof TruffleAssumption) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void shadowGraphIsFirstTierAndRecordsRootAssumptions() {
        OptimizedCallTarget shadow = parse("add:1").createAOTShadow();
        assertNotNull(shadow);
        List<String> bailouts = new ArrayList<>();
        StructuredGraph graph = partialEvaluateForBuildTime(shadow, bailouts);
        Assert.assertEquals("no bailout expected", List.of(), bailouts);
        assertNotNull(graph);
        /* The shadow's valid-root and node-rewriting assumptions. */
        assertTrue("root assumptions must be recorded: " + graph.getAssumptions(),
                        countTruffleAssumptions(graph) >= 2);
        assertFalse("AOT-prepared AST must be fully inlined: no residual execute calls", hasCall(graph, name -> name.startsWith("execute")));
        /*
         * First tier with a next tier: firstTierCall() is partially evaluated, and for a shadow its
         * threshold branch unlinks the live target through a boundary instead of compiling.
         */
        assertTrue("first-tier call counting must unlink the live target at the AOT threshold", hasCall(graph, name -> name.equals("unlinkAOTFromCompiledCode")));
        assertFalse("a shadow must never request a last-tier compilation", hasCall(graph, name -> name.equals("lastTierCompile")));
    }

    @Test
    public void bailoutIsReportedAndReturnsNull() {
        /* A never-executed live target is not initialized, so it is not ready for compilation. */
        OptimizedCallTarget live = parse("add:1");
        List<String> bailouts = new ArrayList<>();
        assertNull(partialEvaluateForBuildTime(live, bailouts));
        Assert.assertEquals(bailouts.toString(), 1, bailouts.size());
        assertTrue(bailouts.get(0), bailouts.get(0).contains("not ready for compilation"));
    }
}
