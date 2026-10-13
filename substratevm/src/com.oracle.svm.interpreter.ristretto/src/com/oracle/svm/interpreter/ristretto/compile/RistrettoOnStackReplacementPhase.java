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
package com.oracle.svm.interpreter.ristretto.compile;

import static jdk.graal.compiler.phases.common.DeadCodeEliminationPhase.Optionality.Required;

import java.util.Optional;

import com.oracle.svm.interpreter.ristretto.RistrettoOSRSupport;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.core.common.PermanentBailoutException;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.iterators.NodeIterable;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodes.EntryMarkerNode;
import jdk.graal.compiler.nodes.EntryProxyNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.StartNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.debug.ControlFlowAnchored;
import jdk.graal.compiler.nodes.loop.Loop;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.DeadCodeEliminationPhase;
import jdk.graal.compiler.phases.common.util.OnStackReplacementUtils;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.JavaKind;

/**
 * Rewrites a parsed Ristretto OSR graph so execution starts at the bytecode backedge target.
 *
 * HotSpot OSR reads locals and locks from VM-managed OSR buffer nodes. Ristretto enters compiled code
 * from the Java interpreter instead, and the compiled graph reads the live state from the interpreter
 * frame that {@link RistrettoOSRSupport} records in a thread local before the entry:
 *
 * <ol>
 * <li>Peel containing loops until the entry marker is reachable from the graph start.</li>
 * <li>Replace the old start with an OSR start at the entry marker.</li>
 * <li>Replace local and lock entry proxies with {@link RistrettoOSRLocalNode}s.</li>
 * <li>Remove the old invocation-entry CFG.</li>
 * </ol>
 *
 * For a source loop such as:
 *
 * <pre>
 * beforeLoop();
 * int sum = 0;
 * int i = start;
 * while (i < limit) {
 *     osrEntryMarker();
 *     sum += i;
 *     i++;
 * }
 * return sum;
 * </pre>
 *
 * parsing from the invocation entry initially leaves the requested OSR BCI inside the loop. Peeling
 * clones one loop iteration so the graph has a legal preheader immediately before the loop body.
 *
 * After the start rewrite, the code before the loop is no longer part of the compiled entry path. The
 * rewritten graph instead starts at a synthetic OSR entry whose frame state holds the live state:
 *
 * <pre>
 * nativeOSREntryPoint();   // state: i = osrLocal(1), sum = osrLocal(2)
 * peeledOsrEntryMarker();
 * while (i < limit) {
 *     sum += i;
 *     i++;
 * }
 * return sum;
 * </pre>
 *
 * The OSR locals stay floating until {@link RistrettoOSRLocalLoweringPhase} loads them from the
 * interpreter frame at the front of the graph, after all deoptimization points are fixed. So the
 * entry state and every state derived from it name the transferred values, and a deoptimization
 * anywhere in the compiled code, even one that an optimization moved up to the entry, resumes the
 * interpreter with the locals it had (elide-dev/graal#129). The loop peeling and start replacement
 * mirror HotSpot's {@code OnStackReplacementPhase}.
 */
public final class RistrettoOnStackReplacementPhase extends BasePhase<HighTierContext> {
    @Override
    public Optional<NotApplicable> notApplicableTo(jdk.graal.compiler.nodes.GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    /**
     * Performs the OSR graph rewrite for graphs whose entry BCI is not the invocation entry.
     */
    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (!graph.isOSR()) {
            return;
        }

        EntryMarkerNode osr = OnStackReplacementUtils.peelEntryLoops(graph, context.getLoopsDataProvider(), () -> getEntryMarker(graph),
                        RistrettoOnStackReplacementPhase::ensurePeelableRistrettoOSRLoop, (iterations, maxIterations) -> {
                            throw VMError.shouldNotReachHere("Ristretto OSR loop peeling exceeded original loop depth: " + iterations + " > " + maxIterations);
                        }, null);
        stripPlaceholderOuterFrameStates(graph);
        osr = getEntryMarker(graph);
        FrameState osrState = osr.stateAfter();
        if (osrState.stackSize() != 0) {
            throw new PermanentBailoutException("Ristretto OSR currently supports only empty expression stacks.");
        }

        StartNode oldStart = graph.start();
        StartNode osrStart = replaceStartAtOSREntry(graph, osr, osrState);
        replaceEntryProxies(graph, osrState);
        osr.replaceAtUsages(osrStart, InputType.Guard);
        osr.replaceAtUsages(osrStart, InputType.Anchor);
        GraphUtil.killCFG(oldStart);
        new DeadCodeEliminationPhase(Required).apply(graph);
        verifyEntryRewrite(graph);
    }

    /**
     * Replaces the invocation-entry start with a new start immediately before the OSR entry marker.
     *
     * The parsed graph still contains the normal method-entry control flow. Ristretto OSR needs a graph
     * start at the peeled loop header instead, so this method detaches the entry marker and gives the
     * new start the marker's frame state. The old start is left for the caller to delete.
     */
    private static StartNode replaceStartAtOSREntry(StructuredGraph graph, EntryMarkerNode osr, FrameState osrState) {
        StartNode osrStart = graph.add(new StartNode());
        FixedNode next = osr.next();
        osr.setStateAfter(null);
        osr.setNext(null);
        osrStart.setNext(next);
        osrStart.setStateAfter(osrState);
        graph.setStart(osrStart);
        return osrStart;
    }

    private static void ensurePeelableRistrettoOSRLoop(Loop loop) {
        for (Node node : loop.inside().nodes()) {
            if (node instanceof ControlFlowAnchored) {
                throw new PermanentBailoutException("Ristretto OSR loop cannot be peeled because it contains a non-duplicable node: %s", node);
            }
        }
    }

    /**
     * Replaces each local and lock entry proxy of the entry state with an OSR local node.
     *
     * The stamp of a proxy may be too precise for the values the interpreter transfers (a branch
     * that assigns the local may not have been parsed, for example), so the OSR locals get the
     * unrestricted stamp of their kind, as in HotSpot.
     */
    private static void replaceEntryProxies(StructuredGraph graph, FrameState osrState) {
        for (int i = 0; i < osrState.localsSize(); i++) {
            if (osrState.localAt(i) instanceof EntryProxyNode proxy) {
                proxy.replaceAndDelete(createOSRLocal(graph, i, proxy, false));
            }
        }
        for (int i = 0; i < osrState.locksSize(); i++) {
            if (osrState.lockAt(i) instanceof EntryProxyNode proxy) {
                proxy.replaceAndDelete(createOSRLocal(graph, i, proxy, true));
            }
        }
    }

    private static ValueNode createOSRLocal(StructuredGraph graph, int index, EntryProxyNode proxy, boolean lock) {
        JavaKind kind = lock ? JavaKind.Object : proxy.getStackKind();
        Stamp stamp = kind == JavaKind.Object ? proxy.stamp(NodeView.DEFAULT).unrestricted() : StampFactory.forKind(kind);
        return graph.addOrUnique(new RistrettoOSRLocalNode(index, kind, lock, stamp));
    }

    private static void verifyEntryRewrite(StructuredGraph graph) {
        if (graph.getNodes(ParameterNode.TYPE).count() != 0) {
            throw new PermanentBailoutException("Ristretto OSR graph still references Java method parameters after entry rewrite.");
        }
        if (graph.getNodes().filter(EntryProxyNode.class).isNotEmpty()) {
            throw new PermanentBailoutException("Ristretto OSR graph still references entry proxies after entry rewrite.");
        }
    }

    /**
     * Drops synthetic caller frame states that were only needed while parsing the OSR entry graph.
     *
     * Runtime OSR enters the Ristretto method directly, so preserving a placeholder caller state would
     * expose deoptimization metadata for a caller frame that does not exist on this compiled path.
     */
    private static void stripPlaceholderOuterFrameStates(StructuredGraph graph) {
        for (FrameState state : graph.getNodes(FrameState.TYPE)) {
            if (state.outerFrameState() != null && state.outerFrameState().bci < 0) {
                // Runtime OSR enters this method directly, so placeholder caller states cannot be
                // preserved as deoptimization metadata.
                state.setOuterFrameState(null);
            }
        }
    }

    /**
     * Returns the unique OSR entry marker emitted by graph building.
     */
    private static EntryMarkerNode getEntryMarker(StructuredGraph graph) {
        NodeIterable<EntryMarkerNode> osrNodes = graph.getNodes(EntryMarkerNode.TYPE);
        EntryMarkerNode osr = osrNodes.first();
        if (osr == null) {
            throw new PermanentBailoutException("Ristretto OSR entry point was not parsed.");
        }
        if (osrNodes.count() > 1) {
            throw new PermanentBailoutException("Ristretto OSR graph has multiple entry markers.");
        }
        return osr;
    }
}
