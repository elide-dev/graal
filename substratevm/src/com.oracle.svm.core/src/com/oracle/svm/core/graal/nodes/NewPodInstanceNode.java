/*
 * Copyright (c) 2022, 2026, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.svm.guest.staging.option.RuntimeOptionKey.RuntimeOptionKeyFlag.RelevantForCompilationIsolates;

import java.util.Collections;

import com.oracle.svm.core.heap.Pod;
import com.oracle.svm.guest.staging.option.RuntimeOptionKey;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.core.gen.DebugInfoBuilder;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.java.MonitorIdNode;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.options.Option;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

@NodeInfo
public final class NewPodInstanceNode extends AbstractNewObjectNode implements VirtualizableAllocation {
    public static final NodeClass<NewPodInstanceNode> TYPE = NodeClass.create(NewPodInstanceNode.class);

    public static class Options {
        @Option(help = "Scalar-replace pods (Truffle static objects with field-based storage) in runtime compilation.") //
        public static final RuntimeOptionKey<Boolean> VirtualizePods = new RuntimeOptionKey<>(true, RelevantForCompilationIsolates);
    }

    private final ResolvedJavaType knownInstanceType;
    @Input ValueNode hub;
    @Input ValueNode arrayLength;
    @Input ValueNode referenceMap;
    /** The pod's {@linkplain Pod#getFieldLayout field layout}, or null if unknown. */
    @OptionalInput ValueNode layout;
    /** The {@link Pod}, or null if unknown. */
    @OptionalInput ValueNode pod;

    public NewPodInstanceNode(ResolvedJavaType knownInstanceType, ValueNode hub, ValueNode arrayLength, ValueNode referenceMap) {
        this(knownInstanceType, hub, arrayLength, referenceMap, null, null);
    }

    /**
     * With the {@code layout} and the {@code pod} that the other values come from, the allocation
     * can be {@linkplain #virtualize scalar-replaced} when they are all constants.
     */
    public NewPodInstanceNode(ResolvedJavaType knownInstanceType, ValueNode hub, ValueNode arrayLength, ValueNode referenceMap, ValueNode layout, ValueNode pod) {
        super(TYPE, StampFactory.objectNonNull(TypeReference.createExactTrusted(knownInstanceType)), true, null);
        this.knownInstanceType = knownInstanceType;
        this.hub = hub;
        this.arrayLength = arrayLength;
        this.referenceMap = referenceMap;
        this.layout = layout;
        this.pod = pod;
    }

    public ResolvedJavaType getKnownInstanceType() {
        return knownInstanceType;
    }

    public ValueNode getHub() {
        return hub;
    }

    public ValueNode getArrayLength() {
        return arrayLength;
    }

    public ValueNode getReferenceMap() {
        return referenceMap;
    }

    /**
     * Scalar-replaces the pod with a {@link VirtualPodNode} when it is allocated in runtime
     * compilation from a constant pod, for example by the factory of a Truffle static shape that
     * partial evaluation made constant. Ahead-of-time compiled code never sees constant pods
     * because pods are created at image run time.
     */
    @Override
    public void virtualize(VirtualizerTool tool) {
        if (SubstrateUtil.HOSTED || !Options.VirtualizePods.getValue() || knownInstanceType == null || layout == null || pod == null) {
            return;
        }
        if (DebugInfoBuilder.class.desiredAssertionStatus()) {
            /*
             * The assertions of DebugInfoBuilder.checkValues expect virtual objects to have only
             * Java fields, so images with those assertions enabled do not scalar-replace pods.
             */
            return;
        }
        if (!hub.isConstant() || !arrayLength.isConstant() || !referenceMap.isConstant() || !layout.isConstant() || !pod.isConstant()) {
            return;
        }
        JavaConstant layoutConstant = layout.asJavaConstant();
        if (layoutConstant == null || layoutConstant.isNull()) {
            return;
        }
        ConstantReflectionProvider constantReflection = tool.getConstantReflection();
        Integer slotCount = constantReflection.readArrayLength(layoutConstant);
        if (slotCount == null) {
            return;
        }
        ResolvedJavaField[] javaFields = knownInstanceType.getInstanceFields(true);
        int entryCount = javaFields.length + slotCount + 1;
        if (entryCount > tool.getMaximumEntryCount()) {
            return;
        }

        MetaAccessProvider metaAccess = tool.getMetaAccess();
        /* Not Arrays.copyOf: the Java fields may come in an array of a more specific type. */
        ResolvedJavaField[] fields = new ResolvedJavaField[entryCount];
        System.arraycopy(javaFields, 0, fields, 0, javaFields.length);
        for (int i = 0; i < slotCount; i++) {
            int element = constantReflection.readArrayElement(layoutConstant, i).asInt();
            JavaKind kind = Pod.fieldLayoutKind(element);
            /* JavaKind.toJavaClass is null for Object. */
            ResolvedJavaType slotType = metaAccess.lookupJavaType(kind.isObject() ? Object.class : kind.toJavaClass());
            fields[javaFields.length + i] = new PodSlotField(knownInstanceType, slotType, Pod.fieldLayoutOffset(element));
        }
        fields[entryCount - 1] = new PodSlotField(knownInstanceType, metaAccess.lookupJavaType(Object.class), PodSlotField.POD_ENTRY_OFFSET);

        VirtualPodNode virtualObject = new VirtualPodNode(knownInstanceType, fields, javaFields.length, hub.asJavaConstant(), arrayLength.asJavaConstant(), referenceMap.asJavaConstant());
        ValueNode[] state = new ValueNode[entryCount];
        for (int i = 0; i < entryCount - 1; i++) {
            state[i] = ConstantNode.defaultForKind(tool.getMetaAccessExtensionProvider().getStorageKind(fields[i].getType()), graph());
        }
        state[entryCount - 1] = pod;
        tool.createVirtualObject(virtualObject, state, Collections.<MonitorIdNode> emptyList(), getNodeSourcePosition(), false);
        tool.replaceWithVirtual(virtualObject);
    }

    @NodeIntrinsic
    public static native Object newPodInstance(@ConstantNodeParameter Class<?> knownInstanceClass, Class<?> runtimeClass, int arrayLength, byte[] referenceMap);
}
