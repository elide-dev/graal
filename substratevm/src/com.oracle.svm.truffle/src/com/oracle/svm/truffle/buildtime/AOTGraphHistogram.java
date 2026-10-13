/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 */
package com.oracle.svm.truffle.buildtime;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/** Debugging only: node histograms of AOT tier graphs (-Dsvm.truffle.aot.histogram=regex). */
final class AOTGraphHistogram {
    private static final String FILTER = System.getProperty("svm.truffle.aot.histogram");

    static synchronized void print(String name, StructuredGraph graph) {
        if (FILTER == null || !Pattern.compile(FILTER).matcher(name).matches()) {
            return;
        }
        Map<String, Integer> classes = new HashMap<>();
        Map<String, Integer> invokes = new HashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        Map<String, Integer> outer = new HashMap<>();
        Map<String, Integer> deopts = new HashMap<>();
        Map<String, Integer> pairs = new HashMap<>();
        Map<String, Integer> triples = new HashMap<>();
        Map<String, Integer> allocs = new HashMap<>();
        int states = 0;
        int stateValues = 0;
        for (Node n : graph.getNodes()) {
            classes.merge(n.getClass().getSimpleName(), 1, Integer::sum);
            if (n instanceof Invoke i && i.callTarget() != null && i.callTarget().targetMethod() != null) {
                invokes.merge(i.callTarget().targetMethod().format("%H.%n(%p)"), 1, Integer::sum);
            }
            if (n instanceof jdk.graal.compiler.nodes.virtual.CommitAllocationNode commit) {
                for (jdk.graal.compiler.nodes.virtual.VirtualObjectNode v : commit.getVirtualObjects()) {
                    NodeSourcePosition p = commit.getNodeSourcePosition();
                    String where = "?";
                    for (NodeSourcePosition q = p; q != null; q = q.getCaller()) {
                        String h = q.getMethod().getDeclaringClass().toJavaName();
                        if (h.startsWith("com.oracle.truffle.js.nodes")) {
                            where = q.getMethod().format("%h.%n");
                            break;
                        }
                    }
                    allocs.merge(v.type().toJavaName(false) + " @ " + where, 1, Integer::sum);
                }
            }
            if (n instanceof FrameState fs) {
                states++;
                stateValues += fs.values().size();
            }
            NodeSourcePosition pos = n.getNodeSourcePosition();
            if (pos != null) {
                String inner = null;
                String second = null;
                String third = null;
                String out = null;
                for (NodeSourcePosition p = pos; p != null; p = p.getCaller()) {
                    ResolvedJavaMethod m = p.getMethod();
                    String h = m.getDeclaringClass().toJavaName();
                    if (h.startsWith("com.oracle.truffle.js.nodes") || h.startsWith("com.oracle.truffle.js.runtime") || h.startsWith("com.oracle.truffle.js.builtins")) {
                        String f = m.format("%h.%n");
                        if (inner == null) {
                            inner = f;
                        } else if (second == null && !f.equals(inner)) {
                            second = f;
                        } else if (second != null && third == null && !f.equals(second)) {
                            third = f;
                        }
                        if (!f.startsWith("FunctionRootNode") && !f.startsWith("FunctionBodyNode") && !f.contains("Block") && !f.startsWith("ReturnNode") && !f.startsWith("ReturnTargetNode") && !f.startsWith("JavaScriptNode") && !f.startsWith("AbstractBlock") && !f.startsWith("VoidBlockNode") && !f.startsWith("ExprBlockNode") && !f.startsWith("DiscardResultNode") && !f.startsWith("FunctionRootNode")) {
                            out = f;
                        }
                    }
                }
                positions.merge(inner == null ? "<none>" : inner, 1, Integer::sum);
                pairs.merge((inner == null ? "<none>" : inner) + " < " + second, 1, Integer::sum);
                triples.merge((inner == null ? "<none>" : inner) + " < " + second + " < " + third, 1, Integer::sum);
                if (n instanceof jdk.graal.compiler.nodes.DeoptimizeNode d) {
                    deopts.merge(d.getReason() + " " + pos.getMethod().format("%h.%n") + " < " + (inner == null ? "<none>" : inner), 1, Integer::sum);
                }
                outer.merge(out == null ? "<none>" : out, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[aot-hist] ").append(name).append(" nodes=").append(graph.getNodeCount()).append(" frameStates=").append(states).append(" stateValues=").append(stateValues).append('\n');
        dump(sb, "class", classes, 30);
        dump(sb, "invoke", invokes, 60);
        dump(sb, "innerJS", positions, 60);
        dump(sb, "outerJS", outer, 10);
        dump(sb, "deopt", deopts, 40);
        dump(sb, "pair", pairs, 80);
        dump(sb, "triple", triples, 80);
        dump(sb, "alloc", allocs, 40);
        System.out.print(sb);
    }

    private static void dump(StringBuilder sb, String kind, Map<String, Integer> map, int limit) {
        map.entrySet().stream().sorted(Map.Entry.<String, Integer> comparingByValue().reversed()).limit(limit).forEach(
                        e -> sb.append("[aot-hist]   ").append(kind).append(' ').append(e.getValue()).append(' ').append(e.getKey()).append('\n'));
    }

    private AOTGraphHistogram() {
    }
}
