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
package com.oracle.svm.graal.hosted.runtimecompilation;

import com.oracle.svm.core.graal.nodes.SubstrateAtomicReadAndAddNode;
import com.oracle.svm.core.graal.nodes.SubstrateAtomicReadAndWriteNode;
import com.oracle.svm.core.graal.nodes.SubstrateUnsafeCompareAndExchangeNode;
import com.oracle.svm.core.graal.nodes.SubstrateUnsafeCompareAndSwapNode;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.AtomicReadAndAddNode;
import jdk.graal.compiler.nodes.java.AtomicReadAndWriteNode;
import jdk.graal.compiler.nodes.java.UnsafeCompareAndExchangeNode;
import jdk.graal.compiler.nodes.java.UnsafeCompareAndSwapNode;
import jdk.graal.compiler.nodes.memory.AbstractMemoryCheckpoint;
import jdk.graal.compiler.phases.Phase;

/**
 * Replaces atomic memory accesses in graphs for runtime compilation with SVM nodes that escape
 * analysis can also virtualize on scalar-replaced pods ({@code VirtualPodNode}), and, for get-and-set
 * and get-and-add, on any virtual object. They are lowered to the same code as the nodes they
 * replace.
 */
public final class UseVirtualizableAtomicsPhase extends Phase {
    @Override
    protected void run(StructuredGraph graph) {
        for (UnsafeCompareAndSwapNode node : graph.getNodes().filter(UnsafeCompareAndSwapNode.class).snapshot()) {
            replace(graph, node, new SubstrateUnsafeCompareAndSwapNode(node.object(), node.offset(), node.expected(), node.newValue(), node.getValueKind(),
                            node.getKilledLocationIdentity(), node.getMemoryOrder()));
        }
        for (UnsafeCompareAndExchangeNode node : graph.getNodes().filter(UnsafeCompareAndExchangeNode.class).snapshot()) {
            replace(graph, node, new SubstrateUnsafeCompareAndExchangeNode(node));
        }
        for (AtomicReadAndWriteNode node : graph.getNodes().filter(AtomicReadAndWriteNode.class).snapshot()) {
            replace(graph, node, new SubstrateAtomicReadAndWriteNode(node));
        }
        for (AtomicReadAndAddNode node : graph.getNodes().filter(AtomicReadAndAddNode.class).snapshot()) {
            replace(graph, node, new SubstrateAtomicReadAndAddNode(node));
        }
    }

    private static void replace(StructuredGraph graph, AbstractMemoryCheckpoint original, AbstractMemoryCheckpoint replacement) {
        AbstractMemoryCheckpoint added = graph.add(replacement);
        added.setNodeSourcePosition(original.getNodeSourcePosition());
        added.setStateAfter(original.stateAfter());
        graph.replaceFixedWithFixed(original, added);
    }
}
