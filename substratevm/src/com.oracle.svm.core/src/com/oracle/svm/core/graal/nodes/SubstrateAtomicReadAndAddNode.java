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

import static jdk.graal.compiler.nodeinfo.InputType.Memory;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_2;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.java.AtomicReadAndAddNode;
import jdk.graal.compiler.nodes.memory.AbstractMemoryCheckpoint;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.spi.TrackedUnsafeAccess;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;

/**
 * An atomic get-and-add ({@link AtomicReadAndAddNode}) that escape analysis can virtualize: on a
 * virtual object, it returns the entry's value and adds to it. Runtime-compiled graphs use it in
 * place of the core node, which is not virtualizable (see {@code UseVirtualizableAtomicsPhase}).
 * When not virtualized, it is lowered by replacing itself with the core node.
 */
@NodeInfo(allowedUsageTypes = Memory, cycles = CYCLES_8, size = SIZE_2)
public final class SubstrateAtomicReadAndAddNode extends AbstractMemoryCheckpoint implements Lowerable, SingleMemoryKill, TrackedUnsafeAccess, Virtualizable {
    public static final NodeClass<SubstrateAtomicReadAndAddNode> TYPE = NodeClass.create(SubstrateAtomicReadAndAddNode.class);

    @Input ValueNode object;
    @Input ValueNode offset;
    @Input ValueNode delta;
    private final JavaKind valueKind;
    private final LocationIdentity locationIdentity;

    public SubstrateAtomicReadAndAddNode(AtomicReadAndAddNode original) {
        super(TYPE, original.stamp(NodeView.DEFAULT));
        this.object = original.object();
        this.offset = original.offset();
        this.delta = original.delta();
        this.valueKind = original.getValueKind();
        this.locationIdentity = original.getKilledLocationIdentity();
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return locationIdentity;
    }

    @Override
    public void lower(LoweringTool tool) {
        AtomicReadAndAddNode coreNode = graph().add(new AtomicReadAndAddNode(object, offset, delta, valueKind, locationIdentity));
        coreNode.setStateAfter(stateAfter());
        graph().replaceFixedWithFixed(this, coreNode);
        coreNode.lower(tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (valueKind != JavaKind.Int && valueKind != JavaKind.Long) {
            return;
        }
        int index = virtualEntryIndex(tool, object, offset, valueKind);
        if (index < 0) {
            return;
        }
        VirtualObjectNode virtual = (VirtualObjectNode) tool.getAlias(object);
        long constantOffset = tool.getAlias(offset).asJavaConstant().asLong();
        ValueNode currentValue = tool.getEntry(virtual, index);
        ValueNode deltaAlias = tool.getAlias(delta);
        if (currentValue instanceof VirtualObjectNode || deltaAlias instanceof VirtualObjectNode || currentValue.getStackKind() != valueKind) {
            return;
        }
        ValueNode sum = AddNode.create(currentValue, deltaAlias, NodeView.DEFAULT);
        if (!tool.setVirtualEntry(virtual, index, sum, valueKind, constantOffset)) {
            return;
        }
        tool.ensureAdded(sum);
        tool.replaceWith(currentValue);
    }

    /**
     * The index of the entry that an atomic access of {@code kind} at {@code offset} into
     * {@code object} accesses, if {@code object} is virtual and the offset is constant, else -1.
     * For a {@link VirtualPodNode}, this includes its pod fields.
     */
    static int virtualEntryIndex(VirtualizerTool tool, ValueNode object, ValueNode offset, JavaKind kind) {
        ValueNode objectAlias = tool.getAlias(object);
        ValueNode offsetAlias = tool.getAlias(offset);
        if (!(objectAlias instanceof VirtualObjectNode virtual) || !offsetAlias.isJavaConstant()) {
            return -1;
        }
        return virtual.entryIndexForOffset(tool.getMetaAccess(), offsetAlias.asJavaConstant().asLong(), kind);
    }
}
