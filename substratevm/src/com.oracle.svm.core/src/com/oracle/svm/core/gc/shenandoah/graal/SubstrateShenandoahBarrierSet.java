/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.gc.shenandoah.graal;

import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.gc.shenandoah.ShenandoahOptions;
import com.oracle.svm.core.StaticFieldsSupport;
import com.oracle.svm.core.graal.nodes.SubstrateCompressionNode;
import com.oracle.svm.core.heap.ReferenceAccess;

import jdk.graal.compiler.core.common.CompressEncoding;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocal;

import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahBarrierSet;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * SubstrateVM specialization of the shared {@link ShenandoahBarrierSet}. It reuses the barrier
 * insertion logic of the base class (SATB pre-write barriers, load-reference barriers and, for
 * generational mode, card-marking barriers) and only adapts the Native Image specific details:
 *
 * <ul>
 * <li>Static object fields are represented as elements of the native image heap "static object
 * fields" array (see {@link StaticFieldsSupport}), so they require array write barriers.</li>
 *
 * <li>Native Image always uses a heap base and may use compressed references. The load-reference
 * barrier therefore has to (un)compress references using the SubstrateVM compression nodes.</li>
 * </ul>
 */
public class SubstrateShenandoahBarrierSet extends ShenandoahBarrierSet {

    private final CompressEncoding oopEncoding;

    public SubstrateShenandoahBarrierSet(ResolvedJavaType objectArrayType, ResolvedJavaField referentField) {
        super(objectArrayType, referentField);
        this.oopEncoding = ReferenceAccess.singleton().getCompressEncoding();
        /*
         * The GC mode is fixed at image build time (-H:ShenandoahGCMode), so each mode only pays
         * for the barriers it needs: passive (stop-the-world collections only) needs no barriers
         * at all, satb needs load-reference/SATB/CAS barriers for its concurrent phases, and
         * generational additionally needs the card-marking (post-write) barrier that maintains
         * the remembered set.
         */
        boolean passive = ShenandoahOptions.isPassive();
        this.useLoadRefBarrier = !passive;
        this.useSATBBarrier = !passive;
        this.useCASBarrier = !passive;
        this.useCardBarrier = ShenandoahOptions.isGenerational();
    }

    /**
     * Static fields in SVM are represented as two arrays in the native image heap: one for Object
     * fields and one for all primitive fields (see {@link StaticFieldsSupport}). Therefore, we must
     * emit array write barriers for static fields.
     */
    @Override
    public BarrierType fieldWriteBarrierType(ResolvedJavaField field, JavaKind storageKind) {
        if (field.isStatic() && storageKind == JavaKind.Object) {
            return arrayWriteBarrierType(storageKind);
        }
        return super.fieldWriteBarrierType(field, storageKind);
    }

    /**
     * SubstrateVM issues {@code Word}-plugin reads for which no {@code loadStamp} is available
     * (see {@code WordOperationPlugin.readOp}). The plugin only requests a barrier type for
     * OBJECT reads ({@code readKind.isObject()}, i.e. {@code BarrieredAccess.readObject}), so a
     * null stamp means "object read that wants all barriers" and requires the load-reference
     * barrier. Returning {@code NONE} here would elide the LRB on such reads - e.g. the monitor
     * slot reads in {@code MultiThreadedMonitorSupport.monitorEnter/monitorExit} - letting a
     * mutator obtain (and lock / write to) the from-space copy of a {@code JavaMonitor} during
     * concurrent evacuation, which breaks mutual exclusion and loses lock-state updates.
     */
    @Override
    public BarrierType readBarrierType(LocationIdentity location, ValueNode address, Stamp loadStamp) {
        if (loadStamp == null) {
            return BarrierType.READ;
        }
        if (!loadStamp.isObjectStamp()) {
            return BarrierType.NONE;
        }
        return super.readBarrierType(location, address, loadStamp);
    }

    /**
     * SubstrateVM writes object references into off-heap slots: an object-typed VM thread local lives
     * inside the (non-heap) {@code IsolateThread} and carries a {@link BarrierType#FIELD} barrier so
     * that the SATB pre barrier remembers the overwritten value. The card table is indexed by heap
     * address, so marking a card for such a store would dirty an arbitrary byte outside the table.
     */
    @Override
    protected boolean isInHeap(LocationIdentity location) {
        return !(location instanceof FastThreadLocal.FastThreadLocalLocationIdentity);
    }

    @Override
    protected ValueNode maybeUncompressReference(ValueNode value, boolean narrow) {
        if (value != null && narrow) {
            return SubstrateCompressionNode.uncompressWithoutUnique(value.graph(), value, oopEncoding);
        }
        return value;
    }

    @Override
    protected ValueNode maybeCompressReference(ValueNode value, boolean narrow) {
        if (value != null && narrow) {
            return SubstrateCompressionNode.compressWithoutUnique(value.graph(), value, oopEncoding);
        }
        return value;
    }

    /**
     * SubstrateVM emits a plain load-reference ({@link BarrierType#READ}) barrier for object reads
     * that the HotSpot barrier set would either leave barrier-free or tag as {@link
     * BarrierType#FIELD}:
     * <ul>
     * <li>Object-typed VM thread locals live inside the (non-heap) {@code IsolateThread} but hold a
     * reference to a movable heap object, so their read carries a READ barrier even though the
     * address is not object-based.</li>
     * <li>Generic snippet reads (e.g. the arraycopy and clone snippets) access array elements
     * through {@code ANY_LOCATION} with a READ barrier rather than the array/FIELD barrier the
     * verifier expects for a heap read.</li>
     * </ul>
     * For a read, READ and FIELD are equivalent - both insert the load-reference barrier (the
     * FIELD/READ distinction only affects the card barrier on writes) - so accepting READ here does
     * not mask a missing read barrier. {@link BarrierType#NONE} is additionally accepted for heap
     * reads that provably load a non-moving reference (most notably a {@code DynamicHub}/
     * {@code java.lang.Class} read), for which SubstrateVM emits no barrier. Note that these hooks
     * only govern reads whose location is not a field or object-array location; ordinary field
     * reads are still checked exactly against {@link #fieldReadBarrierType} via
     * {@code barrierForLocation}.
     */
    @Override
    protected boolean isValidNonHeapReadBarrier(BarrierType barrierType) {
        return isNoneOrLoadReferenceBarrier(barrierType);
    }

    @Override
    protected boolean isValidHeapReadBarrier(BarrierType barrierType) {
        return isNoneOrLoadReferenceBarrier(barrierType);
    }

    /**
     * An object read is well-formed for SubstrateVM if it carries either no barrier (SubstrateVM
     * elides the barrier for reads that provably load a non-moving reference, e.g. a
     * {@code DynamicHub}) or one of the barrier types that {@code addReadNodeBarriers} turns into a
     * load-reference barrier. This is only consulted for reads whose location is neither a field
     * nor the object-array location; those are still checked exactly via {@code barrierForLocation}.
     */
    private static boolean isNoneOrLoadReferenceBarrier(BarrierType barrierType) {
        switch (barrierType) {
            case NONE:
            case READ:
            case FIELD:
            case ARRAY:
            case UNKNOWN:
                return true;
            default:
                return false;
        }
    }
}
