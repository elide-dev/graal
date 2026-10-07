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

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.AbstractUnsafeCompareAndSwapNode;
import jdk.graal.compiler.nodes.java.UnsafeCompareAndExchangeNode;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;

/**
 * A {@link UnsafeCompareAndExchangeNode} that is also virtualized on pods, see
 * {@link SubstrateCompareAndSwapNode}.
 */
@NodeInfo
public final class SubstrateUnsafeCompareAndExchangeNode extends SubstrateCompareAndSwapNode {
    public static final NodeClass<SubstrateUnsafeCompareAndExchangeNode> TYPE = NodeClass.create(SubstrateUnsafeCompareAndExchangeNode.class);

    /**
     * Replaces {@code original}, taking its stamp, which meets those of the inputs. Its location is
     * the location that the original kills, which is any location for an ordered access.
     */
    public SubstrateUnsafeCompareAndExchangeNode(UnsafeCompareAndExchangeNode original) {
        super(TYPE, original.stamp(NodeView.DEFAULT), original.object(), original.offset(), original.expected(), original.newValue(), original.getValueKind(),
                        original.getKilledLocationIdentity(), original.getMemoryOrder());
    }

    @Override
    protected AbstractUnsafeCompareAndSwapNode createCoreNode() {
        return new UnsafeCompareAndExchangeNode(object(), offset(), expected(), newValue(), valueKind, locationIdentity, memoryOrder);
    }

    @Override
    protected void finishVirtualize(VirtualizerTool tool, LogicNode equalsNode, ValueNode currentValue) {
        tool.replaceWith(currentValue);
    }
}
