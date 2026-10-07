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
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * A scalar-replaced pod (see {@link com.oracle.svm.core.heap.Pod}), created by
 * {@link NewPodInstanceNode#virtualize} in runtime compilation.
 *
 * Its entries are the Java instance fields of the pod class, followed by one {@link PodSlotField}
 * per field in the array part (in the order of the pod's field layout, which is by offset), and
 * finally the {@linkplain PodSlotField#isPodEntry pod entry} whose value is the pod constant. The
 * pod entry is never accessed: it is there so that deoptimization can rematerialize the object
 * from the pod's array length, reference map and field layout (see {@code FrameInfoEncoder} and
 * {@code DeoptState}). Unsafe accesses to the array part are virtualized when they match a field's
 * exact offset and kind; any other access materializes the object.
 */
@NodeInfo
public final class VirtualPodNode extends VirtualInstanceNode {
    public static final NodeClass<VirtualPodNode> TYPE = NodeClass.create(VirtualPodNode.class);

    private final int javaFieldCount;
    private final JavaConstant hub;
    private final JavaConstant arrayLength;
    private final JavaConstant referenceMap;

    /**
     * @param fields the Java instance fields of {@code type}, then the {@link PodSlotField}s of
     *            the array part sorted by offset, then the pod entry
     */
    public VirtualPodNode(ResolvedJavaType type, ResolvedJavaField[] fields, int javaFieldCount, JavaConstant hub, JavaConstant arrayLength, JavaConstant referenceMap) {
        super(TYPE, type, fields, true);
        assert javaFieldCount < fields.length && ((PodSlotField) fields[fields.length - 1]).isPodEntry();
        this.javaFieldCount = javaFieldCount;
        this.hub = hub;
        this.arrayLength = arrayLength;
        this.referenceMap = referenceMap;
    }

    public int getJavaFieldCount() {
        return javaFieldCount;
    }

    /** The index of the entry that holds the pod. */
    public int getPodEntryIndex() {
        return fields.length - 1;
    }

    public JavaConstant getHub() {
        return hub;
    }

    public JavaConstant getArrayLength() {
        return arrayLength;
    }

    public JavaConstant getReferenceMap() {
        return referenceMap;
    }

    @Override
    public int entryIndexForOffset(MetaAccessProvider metaAccess, long constantOffset, JavaKind expectedEntryKind) {
        int index = super.entryIndexForOffset(metaAccess, constantOffset, expectedEntryKind);
        if (index >= 0) {
            return index;
        }
        /* The array part: an exact match of offset and kind, never the pod entry. */
        int low = javaFieldCount;
        int high = fields.length - 2;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int offset = fields[mid].getOffset();
            if (offset < constantOffset) {
                low = mid + 1;
            } else if (offset > constantOffset) {
                high = mid - 1;
            } else {
                return fields[mid].getJavaKind() == expectedEntryKind ? mid : -1;
            }
        }
        return -1;
    }

    @Override
    public VirtualPodNode duplicate() {
        VirtualPodNode node = new VirtualPodNode(type, fields, javaFieldCount, hub, arrayLength, referenceMap);
        node.setNodeSourcePosition(this.getNodeSourcePosition());
        return node;
    }
}
