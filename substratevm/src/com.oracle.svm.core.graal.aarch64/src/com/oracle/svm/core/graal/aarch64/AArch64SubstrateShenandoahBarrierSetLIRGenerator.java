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
package com.oracle.svm.core.graal.aarch64;

import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.callTargetAddress;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.gc.shenandoah.graal.ShenandoahBarrierSupport;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;

import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.core.aarch64.AArch64LIRGenerator;
import jdk.graal.compiler.core.aarch64.AArch64ReadBarrierSetLIRGenerator;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryExtendKind;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.lir.LIRFrameState;
import jdk.graal.compiler.lir.Variable;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64AtomicMove.CompareAndSwapOp;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadRefBarrierNode;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

/**
 * AArch64 LIR generation for the Shenandoah barriers on SubstrateVM (Native Image / AOT).
 *
 * This is the AArch64 counterpart of
 * {@code AMD64SubstrateShenandoahBarrierSetLIRGenerator}. The
 * shared {@link jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahBarrierSet} inserts the barrier
 * nodes into the graph and this class lowers them to LIR. As on AMD64 (and as in the Graal HotSpot
 * backend
 * {@code AArch64HotSpotShenandoahBarrierSetLIRGenerator}),
 * every barrier is an inlined fast path that tests the per-thread {@code gc_state} byte and only
 * calls into the Shenandoah C++ library (through an uninterruptible, register-preserving stub) on
 * the slow path.
 *
 * AArch64 specifics compared to AMD64:
 * <ul>
 * <li>The compare-and-swap of an oop field is not bound to a fixed register (AMD64 {@code cmpxchg}
 * hardwires {@code rax}), so no comparand has to be preserved around the location heal.</li>
 * <li>Address operands are materialized with {@code loadAddress} inside the ops (only on the paths
 * that need them), because AArch64 immediate offsets are scaled by the access size.</li>
 * </ul>
 */
public class AArch64SubstrateShenandoahBarrierSetLIRGenerator implements ShenandoahBarrierSetLIRGeneratorTool, AArch64ReadBarrierSetLIRGenerator {

    private static AArch64AddressValue asAddress(LIRGeneratorTool tool, Value address) {
        return ((AArch64LIRGenerator) tool).asAddressValue(address, AArch64Address.ANY_SIZE);
    }

    @Override
    public Value emitLoadReferenceBarrier(LIRGeneratorTool tool, Value obj, Value address, ShenandoahLoadRefBarrierNode.ReferenceStrength strength, boolean narrow, boolean notNull) {
        /*
         * Select the runtime stub by reference strength: the WEAK/PHANTOM variants additionally
         * return null for an unreachable (unmarked) referent during the concurrent weak-roots phase.
         */
        SubstrateForeignCallDescriptor descriptor = switch (strength) {
            case STRONG -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER;
            case WEAK -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_WEAK;
            case PHANTOM -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_PHANTOM;
        };
        ForeignCallLinkage linkage = tool.getForeignCalls().lookupForeignCall(descriptor);
        Variable result = tool.newVariable(obj.getValueKind());
        AArch64AddressValue loadAddress = asAddress(tool, address);
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp2 = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        tool.getResult().getFrameMapBuilder().callsMethod(linkage.getOutgoingCallingConvention());
        tool.append(new AArch64SubstrateShenandoahLoadRefBarrierOp(result, tool.asAllocatable(obj), loadAddress, tmp, tmp2, linkage, callTargetAddress(tool, linkage), strength, notNull));
        return result;
    }

    @Override
    public void emitPreWriteBarrier(LIRGeneratorTool tool, Value address, AllocatableValue expectedObject, boolean narrow, boolean nonNull) {
        /*
         * If a pre-value is provided (the expected value of a compare-and-swap, or the loaded
         * referent of a reference-get) we must enqueue exactly that (already uncompressed) value.
         * Only for a plain store (expectedObject == ILLEGAL) is the current field value loaded
         * inside the barrier op. See AMD64SubstrateShenandoahBarrierSetLIRGenerator for details.
         */
        boolean valuePassed = !Value.ILLEGAL.equals(expectedObject);
        boolean useNarrow = narrow && !valuePassed;
        ForeignCallLinkage linkage = tool.getForeignCalls().lookupForeignCall(
                        useNarrow ? ShenandoahBarrierSupport.PRE_WRITE_BARRIER_NARROW : ShenandoahBarrierSupport.PRE_WRITE_BARRIER_WIDE);
        AArch64AddressValue addr = asAddress(tool, address);
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue preval = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue buf = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue preValue = valuePassed ? expectedObject : Value.ILLEGAL;
        // A narrow reference occupies 'referenceSize' bytes: 4 with size-reducing compressed
        // references, 8 otherwise (a heap-base-relative 8-byte offset). Wide references and
        // value-passed pre-values are always full words.
        boolean loadWordSized = !useNarrow || ObjectLayout.singleton().getReferenceSize() == 8;
        int narrowShift = ImageSingletons.lookup(CompressEncoding.class).getShift();
        tool.getResult().getFrameMapBuilder().callsMethod(linkage.getOutgoingCallingConvention());
        tool.append(new AArch64SubstrateShenandoahSATBBarrierOp(addr, tmp, preval, buf, preValue, linkage, callTargetAddress(tool, linkage), loadWordSized, useNarrow, narrowShift, nonNull));
    }

    @Override
    public void emitCardBarrier(LIRGeneratorTool lirTool, Value address) {
        /*
         * Card-marking (post-write) barrier of generational mode. Only emitted when the image was
         * built with -H:ShenandoahGCMode=generational (see SubstrateShenandoahBarrierSet).
         */
        AArch64AddressValue addr = asAddress(lirTool, address);
        AllocatableValue tmp = lirTool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp2 = lirTool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        lirTool.append(new AArch64SubstrateShenandoahCardBarrierOp(addr, tmp, tmp2));
    }

    @Override
    public Value emitAtomicReadAndWrite(LIRGeneratorTool tool, LIRKind readKind, Value address, Value newValue, BarrierType barrierType) {
        /*
         * getAndSet needs no location heal: the swap unconditionally overwrites the field with a
         * to-space reference provided by the mutator, and the load-reference barrier inserted as a
         * separate node canonicalizes the returned old value.
         */
        return tool.emitAtomicReadAndWrite(readKind, address, newValue, BarrierType.NONE);
    }

    @Override
    public void emitCompareAndSwapOp(LIRGeneratorTool tool, boolean isLogic, Value address, MemoryOrderMode memoryOrder, AArch64Kind memKind, Variable result,
                    AllocatableValue allocatableExpectedValue, AllocatableValue allocatableNewValue, BarrierType barrierType) {
        AllocatableValue addressValue = tool.asAllocatable(address);
        if (barrierType != BarrierType.NONE) {
            /*
             * For an oop CAS, first self-heal the field location so that a concurrent evacuation
             * cannot cause a false negative ("fix up early" model, JDK-8384080 / JDK-8383810): after
             * the heal the field holds the canonical to-space reference, so the plain CAS below
             * compares to-space against to-space.
             */
            GraalError.guarantee(memKind == AArch64Kind.DWORD || memKind == AArch64Kind.QWORD, "unexpected oop CAS kind %s", memKind);
            emitLocationHeal(tool, addressValue, memKind == AArch64Kind.QWORD);
        }
        tool.append(new CompareAndSwapOp(memKind, memoryOrder, isLogic, result, allocatableExpectedValue, allocatableNewValue, addressValue));
    }

    /**
     * Emit a self-healing load-reference barrier on the location {@code address}: if the field
     * holds a from-space pointer, heal it to its to-space value before the following plain atomic.
     * See {@link AArch64SubstrateShenandoahCASHealOp}.
     */
    private static void emitLocationHeal(LIRGeneratorTool tool, AllocatableValue address, boolean wordSized) {
        ForeignCallLinkage healLinkage = tool.getForeignCalls().lookupForeignCall(ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_HEAL);
        CompressEncoding oopEncoding = ImageSingletons.lookup(CompressEncoding.class);
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp2 = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp3 = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp4 = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        tool.getResult().getFrameMapBuilder().callsMethod(healLinkage.getOutgoingCallingConvention());
        tool.append(new AArch64SubstrateShenandoahCASHealOp(address, tmp, tmp2, tmp3, tmp4, healLinkage, callTargetAddress(tool, healLinkage), wordSized, oopEncoding.getShift(), oopEncoding
                        .hasBase()));
    }

    @Override
    public Variable emitBarrieredLoad(LIRGeneratorTool tool, LIRKind kind, Value address, LIRFrameState state, MemoryOrderMode memoryOrder, BarrierType barrierType) {
        /* The load-reference barrier is inserted as a separate node, so emit a plain load here. */
        return tool.getArithmetic().emitLoad(kind, address, state, memoryOrder, MemoryExtendKind.DEFAULT);
    }
}
