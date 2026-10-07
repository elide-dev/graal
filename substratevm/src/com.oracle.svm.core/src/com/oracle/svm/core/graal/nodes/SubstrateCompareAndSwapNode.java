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
package com.oracle.svm.core.graal.nodes;

import static jdk.graal.compiler.core.common.calc.CanonicalCondition.EQ;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.FloatStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.calc.ReinterpretNode;
import jdk.graal.compiler.nodes.java.AbstractUnsafeCompareAndSwapNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;

/**
 * An unsafe compare-and-swap or compare-and-exchange that escape analysis can also virtualize on a
 * {@link VirtualPodNode}, whose pod fields are not Java fields. Runtime-compiled graphs use these
 * nodes in place of the core compiler's nodes (see {@code UseVirtualizableAtomicsPhase}); when not
 * virtualized, they are lowered by replacing themselves with the core node.
 *
 * Virtualizing an atomic access on a virtual object is sound: no other thread can reach the
 * object, so its atomicity and memory ordering cannot be observed.
 */
@NodeInfo
public abstract class SubstrateCompareAndSwapNode extends AbstractUnsafeCompareAndSwapNode {
    public static final NodeClass<SubstrateCompareAndSwapNode> TYPE = NodeClass.create(SubstrateCompareAndSwapNode.class);

    protected SubstrateCompareAndSwapNode(NodeClass<? extends SubstrateCompareAndSwapNode> c, Stamp stamp, ValueNode object, ValueNode offset, ValueNode expected, ValueNode newValue,
                    JavaKind valueKind, LocationIdentity locationIdentity, MemoryOrderMode memoryOrder) {
        super(c, stamp, object, offset, expected, newValue, valueKind, locationIdentity, memoryOrder);
    }

    public LocationIdentity getLocationIdentity() {
        return locationIdentity;
    }

    /** The core compiler node that this node stands for. */
    protected abstract AbstractUnsafeCompareAndSwapNode createCoreNode();

    @Override
    public void lower(LoweringTool tool) {
        AbstractUnsafeCompareAndSwapNode coreNode = graph().add(createCoreNode());
        coreNode.setStateAfter(stateAfter());
        graph().replaceFixedWithFixed(this, coreNode);
        coreNode.lower(tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode objectAlias = tool.getAlias(object());
        if (!(objectAlias instanceof VirtualPodNode virtualPod)) {
            super.virtualize(tool);
            return;
        }
        /* As AbstractUnsafeCompareAndSwapNode.virtualize, but finding pod fields by offset. */
        ValueNode offsetAlias = tool.getAlias(offset());
        if (!offsetAlias.isJavaConstant()) {
            return;
        }
        long constantOffset = offsetAlias.asJavaConstant().asLong();
        int index = virtualPod.entryIndexForOffset(tool.getMetaAccess(), constantOffset, valueKind);
        JavaKind entryKind = valueKind;
        if (index < 0 && (valueKind == JavaKind.Int || valueKind == JavaKind.Long)) {
            /*
             * A float or double field is compared and swapped as the bits of its value, see
             * StaticProperty.compareAndSwapFloat and compareAndSwapDouble.
             */
            entryKind = valueKind == JavaKind.Int ? JavaKind.Float : JavaKind.Double;
            index = virtualPod.entryIndexForOffset(tool.getMetaAccess(), constantOffset, entryKind);
        }
        if (index < 0) {
            return;
        }
        ValueNode currentEntry = tool.getEntry(virtualPod, index);
        ValueNode currentValue = entryKind == valueKind ? currentEntry : ReinterpretNode.create(valueKind, currentEntry, NodeView.DEFAULT);
        ValueNode expectedAlias = tool.getAlias(expected());

        LogicNode equalsNode = null;
        if (valueKind.isObject()) {
            equalsNode = ObjectEqualsNode.virtualizeComparison(expectedAlias, currentValue, graph(), tool);
        }
        if (equalsNode == null && !(expectedAlias instanceof VirtualObjectNode) && !(currentValue instanceof VirtualObjectNode)) {
            if (expectedAlias.getStackKind().isNumericFloat()) {
                /* Compare the bits, as the access does: NaNs and signed zeros must compare as such. */
                JavaKind bitsKind = ((FloatStamp) expectedAlias.stamp(NodeView.DEFAULT)).getBits() == 32 ? JavaKind.Int : JavaKind.Long;
                ValueNode expectedBits = ReinterpretNode.create(bitsKind, expectedAlias, NodeView.DEFAULT);
                ValueNode currentBits = ReinterpretNode.create(bitsKind, currentValue, NodeView.DEFAULT);
                equalsNode = CompareNode.createCompareNode(EQ, expectedBits, currentBits, tool.getConstantReflection(), NodeView.DEFAULT);
            } else {
                equalsNode = CompareNode.createCompareNode(EQ, expectedAlias, currentValue, tool.getConstantReflection(), NodeView.DEFAULT);
            }
        }
        if (equalsNode == null) {
            return;
        }

        ValueNode newValueAlias = tool.getAlias(newValue());
        ValueNode fieldValue;
        if (equalsNode instanceof LogicConstantNode constant) {
            fieldValue = constant.getValue() ? newValue() : currentValue;
        } else {
            if (currentValue instanceof VirtualObjectNode || newValueAlias instanceof VirtualObjectNode) {
                return;
            }
            fieldValue = ConditionalNode.create(equalsNode, newValueAlias, currentValue, NodeView.DEFAULT);
        }
        ValueNode entryValue = entryKind == valueKind ? fieldValue : ReinterpretNode.create(entryKind, fieldValue, NodeView.DEFAULT);
        if (!tool.setVirtualEntry(virtualPod, index, entryValue, entryKind, constantOffset)) {
            return;
        }
        tool.ensureAdded(equalsNode);
        tool.ensureAdded(currentValue);
        tool.ensureAdded(fieldValue);
        tool.ensureAdded(entryValue);
        finishVirtualize(tool, equalsNode, currentValue);
    }
}
