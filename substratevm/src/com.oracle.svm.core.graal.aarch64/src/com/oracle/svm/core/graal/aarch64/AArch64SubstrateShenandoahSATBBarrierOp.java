/*
 * Copyright (c) 2025, 2026, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.callStub;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.heapBaseRegister;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.linkRegisterValue;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.loadThreadLocal;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.storeThreadLocal;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.threadRegister;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ShiftType;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

/**
 * SubstrateVM AArch64 implementation of the Shenandoah SATB pre-write barrier (the AArch64
 * counterpart of {@code AMD64SubstrateShenandoahSATBBarrierOp}; the enqueue sequence follows the
 * Graal HotSpot backend op {@code AArch64HotSpotShenandoahSATBBarrierOp}).
 *
 * The inlined fast path only tests the {@code MARKING} bit of the per-thread {@code gc_state}. The
 * out-of-line mid path obtains the previous value, skips null and enqueues it into the thread's
 * SATB buffer inline (decrement the queue index, store the uncompressed oop). Only when the buffer
 * is full does it call the runtime stub, which flushes/refills the buffer and enqueues the value.
 */
@Opcode("SHENANDOAH_SATB_BARRIER")
public class AArch64SubstrateShenandoahSATBBarrierOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahSATBBarrierOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahSATBBarrierOp.class);

    /** The address of the reference field that is about to be overwritten. */
    @Alive({COMPOSITE}) private AArch64AddressValue address;

    /** Scratch: gc_state byte, then the SATB queue index / slot address. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: the previous value (and, for narrow refs, its decoded oop). */
    @Temp({REG}) private AllocatableValue preval;

    /** Scratch: the SATB queue buffer pointer. */
    @Temp({REG}) private AllocatableValue buf;

    /**
     * The (already uncompressed) pre-value to enqueue when it is supplied by the caller (the expected
     * value of a CAS, or a reference-get referent); {@link Value#ILLEGAL} for a plain store, in which
     * case the current field value is loaded from {@link #address}.
     */
    @Alive({REG, ILLEGAL}) private AllocatableValue preValue;

    /** Argument register of the slow-path stub call. */
    @Temp({REG}) private AllocatableValue callArg;

    /** The {@code bl} of the slow path overwrites the link register. */
    @Temp({REG}) private AllocatableValue linkReg;

    @Alive({REG, ILLEGAL}) private AllocatableValue callAddress;

    private final ForeignCallLinkage callTarget;

    /** Whether the field value is a full 64-bit word (vs. a 32-bit compressed reference). */
    private final boolean loadWordSized;

    /** Whether the field holds a compressed (narrow) reference that must be uncompressed. */
    private final boolean narrow;

    /** Compression shift for narrow references. */
    private final int narrowShift;

    /** If true, the previous value is known to be non-null. */
    private final boolean nonNull;

    public AArch64SubstrateShenandoahSATBBarrierOp(AArch64AddressValue address, AllocatableValue tmp, AllocatableValue preval, AllocatableValue buf,
                    AllocatableValue preValue, ForeignCallLinkage callTarget, AllocatableValue callAddress, boolean loadWordSized, boolean narrow, int narrowShift, boolean nonNull) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.preval = preval;
        this.buf = buf;
        this.preValue = preValue;
        this.callArg = callTarget.getOutgoingCallingConvention().getArgument(0);
        this.linkReg = linkRegisterValue();
        this.callTarget = callTarget;
        this.callAddress = callAddress;
        this.loadWordSized = loadWordSized;
        this.narrow = narrow;
        this.narrowShift = narrowShift;
        this.nonNull = nonNull;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = threadRegister();
        Register rtmp = asRegister(tmp);
        Register rpre = asRegister(preval);
        Register rbuf = asRegister(buf);
        AArch64Address fieldAddr = address.toAddress();
        boolean valuePassed = !Value.ILLEGAL.equals(preValue);
        int valueBits = loadWordSized ? 64 : 32;

        Label done = new Label();
        Label midPath = new Label();
        Label runtime = new Label();

        // Skip the barrier if the thread register is not yet set up (very early isolate creation).
        masm.cbz(64, thread, done);

        // Fast path: nothing to do unless concurrent marking is in progress.
        loadThreadLocal(masm, 8, rtmp, ShenandoahConstants.gcStateOffset());
        masm.tst(32, rtmp, ShenandoahConstants.GC_STATE_MARKING);
        masm.branchConditionally(ConditionFlag.NE, midPath);
        masm.bind(done);

        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(midPath);
            // Obtain the previous value.
            if (valuePassed) {
                masm.mov(64, rpre, asRegister(preValue));
            } else {
                masm.loadAddress(rtmp, fieldAddr);
                masm.ldr(valueBits, rpre, AArch64Address.createBaseRegisterOnlyAddress(valueBits, rtmp));
            }

            // Skip null. A value-passed (uncompressed) null is the heap base (or 0); a narrow value
            // loaded from the field uses 0.
            if (!nonNull) {
                if (valuePassed) {
                    masm.cmp(64, rpre, heapBaseRegister());
                    masm.branchConditionally(ConditionFlag.EQ, done);
                }
                masm.cbz(64, rpre, done);
            }

            // Inline SATB enqueue: tmp := *index; if tmp == 0 the buffer is full -> runtime.
            loadThreadLocal(masm, 64, rtmp, ShenandoahConstants.satbIndexOffset());
            masm.cbz(64, rtmp, runtime);
            // tmp := tmp - wordSize; *index := tmp
            masm.sub(64, rtmp, rtmp, 8);
            storeThreadLocal(masm, 64, rtmp, ShenandoahConstants.satbIndexOffset(), rbuf);
            // tmp := *buffer + tmp
            loadThreadLocal(masm, 64, rbuf, ShenandoahConstants.satbBufferOffset());
            masm.add(64, rtmp, rtmp, rbuf);
            // The SATB buffer holds uncompressed oops: decode a narrow reference first. Only this
            // (non-overflow) path decodes, so the runtime path still sees the raw value.
            if (narrow) {
                masm.add(64, rpre, heapBaseRegister(), rpre, ShiftType.LSL, narrowShift);
            }
            masm.str(64, rpre, AArch64Address.createBaseRegisterOnlyAddress(64, rtmp));
            masm.jmp(done);
        });

        // Out-of-line slow path: buffer full. Pass the raw previous value to the stub.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(runtime);
            masm.mov(valueBits, asRegister(callArg), rpre);
            callStub(crb, masm, callTarget, callAddress);
            masm.jmp(done);
        });
    }
}
