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

import java.util.Optional;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.graal.nodes.ReadReservedRegisterFixedNode;
import com.oracle.svm.core.graal.nodes.ReadReservedRegisterFloatingNode;
import com.oracle.svm.core.graal.thread.LoadVMThreadLocalNode;
import com.oracle.svm.interpreter.ristretto.RistrettoOSRFrameLayout;

import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.NarrowNode;
import jdk.graal.compiler.nodes.calc.ReinterpretNode;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.MidTierContext;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import org.graalvm.word.LocationIdentity;

/**
 * Replaces the {@link RistrettoOSRLocalNode}s of an OSR graph with loads from the interpreter
 * frame that entered the compiled code, at the front of the graph.
 *
 * The loads are plain memory reads without frame states, inserted right after the start node once
 * every deoptimization point has been fixed and given its frame state. So no deoptimization can
 * come before them, and every frame state that names an OSR local (the entry state of the start
 * node included) refers to a value that was loaded already, and the interpreter resumes with the
 * locals it transferred. This mirrors how HotSpot lowers its {@code OSRStartNode}: the reads from
 * the OSR buffer are placed before everything else in the compiled code.
 */
public final class RistrettoOSRLocalLoweringPhase extends BasePhase<MidTierContext> {
    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return NotApplicable.when(!graphState.getGuardsStage().areFrameStatesAtDeopts(), "Ristretto OSR locals are loaded once all deoptimization points have their frame state");
    }

    @Override
    public boolean shouldApply(StructuredGraph graph) {
        return graph.hasNode(RistrettoOSRLocalNode.TYPE);
    }

    @Override
    protected void run(StructuredGraph graph, MidTierContext context) {
        GraalError.guarantee(graph.isOSR(), "Ristretto OSR locals in a graph that is not an OSR compilation: %s", graph);
        RistrettoOSRFrameLayout layout = RistrettoOSRFrameLayout.singleton();
        MetaAccessProvider metaAccess = context.getMetaAccess();
        FixedWithNextNode insertionPoint = graph.start();

        ReservedRegisters reservedRegisters = ReservedRegisters.singleton();
        ValueNode thread;
        if (reservedRegisters.mustUseFixedRead(graph)) {
            ReadReservedRegisterFixedNode fixedThread = graph.add(new ReadReservedRegisterFixedNode(reservedRegisters.getThreadRegister()));
            graph.addAfterFixed(insertionPoint, fixedThread);
            insertionPoint = fixedThread;
            thread = fixedThread;
        } else {
            thread = graph.unique(new ReadReservedRegisterFloatingNode(reservedRegisters.getThreadRegister()));
        }
        LoadVMThreadLocalNode frame = graph.add(new LoadVMThreadLocalNode(metaAccess, layout.currentFrame(), thread, BarrierType.NONE, MemoryOrderMode.PLAIN));
        graph.addAfterFixed(insertionPoint, frame);
        insertionPoint = frame;

        ValueNode references = null;
        ValueNode primitives = null;
        ValueNode locks = null;
        for (RistrettoOSRLocalNode local : graph.getNodes(RistrettoOSRLocalNode.TYPE).snapshot()) {
            if (local.hasNoUsages()) {
                local.safeDelete();
                continue;
            }
            ValueNode value;
            if (local.isLock()) {
                if (locks == null) {
                    locks = appendLoad(graph, insertionPoint, frame, layout.locksOffset(), JavaKind.Object, LocationIdentity.any());
                    insertionPoint = (FixedWithNextNode) locks;
                }
                value = appendArrayLoad(graph, insertionPoint, metaAccess, locks, JavaKind.Object, local.index());
                insertionPoint = (FixedWithNextNode) value;
            } else if (local.kind() == JavaKind.Object) {
                if (references == null) {
                    references = appendLoad(graph, insertionPoint, frame, layout.referencesOffset(), JavaKind.Object, LocationIdentity.any());
                    insertionPoint = (FixedWithNextNode) references;
                }
                value = appendArrayLoad(graph, insertionPoint, metaAccess, references, JavaKind.Object, local.index());
                insertionPoint = (FixedWithNextNode) value;
            } else {
                if (primitives == null) {
                    primitives = appendLoad(graph, insertionPoint, frame, layout.primitivesOffset(), JavaKind.Object, LocationIdentity.any());
                    insertionPoint = (FixedWithNextNode) primitives;
                }
                /* The interpreter keeps every primitive local in a long slot (see InterpreterFrame). */
                ValueNode slot = appendArrayLoad(graph, insertionPoint, metaAccess, primitives, JavaKind.Long, local.index());
                insertionPoint = (FixedWithNextNode) slot;
                value = switch (local.kind()) {
                    case Int -> graph.addOrUniqueWithInputs(NarrowNode.create(slot, Integer.SIZE, NodeView.DEFAULT));
                    case Float -> graph.addOrUniqueWithInputs(ReinterpretNode.create(JavaKind.Float, NarrowNode.create(slot, Integer.SIZE, NodeView.DEFAULT), NodeView.DEFAULT));
                    case Long -> slot;
                    case Double -> graph.addOrUniqueWithInputs(ReinterpretNode.create(JavaKind.Double, slot, NodeView.DEFAULT));
                    default -> throw GraalError.shouldNotReachHere("Unsupported Ristretto OSR local kind: " + local.kind());
                };
            }
            local.replaceAndDelete(value);
        }
    }

    private static ValueNode appendArrayLoad(StructuredGraph graph, FixedWithNextNode insertionPoint, MetaAccessProvider metaAccess, ValueNode array, JavaKind elementKind, int index) {
        long offset = metaAccess.getArrayBaseOffset(elementKind) + (long) index * metaAccess.getArrayIndexScale(elementKind);
        return appendLoad(graph, insertionPoint, array, offset, elementKind, NamedLocationIdentity.getArrayLocation(elementKind));
    }

    /**
     * Appends a read of {@code kind} at {@code offset} in {@code object}. The location is forced so
     * that the read is never canonicalized to a field or array access, which could introduce a
     * null or bounds check after deoptimization points are fixed.
     */
    private static ValueNode appendLoad(StructuredGraph graph, FixedWithNextNode insertionPoint, ValueNode object, long offset, JavaKind kind, LocationIdentity location) {
        RawLoadNode load = graph.add(new RawLoadNode(object, ConstantNode.forLong(offset, graph), kind, location, true, MemoryOrderMode.PLAIN));
        graph.addAfterFixed(insertionPoint, load);
        return load;
    }
}
