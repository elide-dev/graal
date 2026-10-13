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

import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.callStub;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.heapBaseRegister;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.linkRegisterValue;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.loadThreadLocal;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.threadRegister;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ShiftType;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.aarch64.AArch64AtomicMove.CompareAndSwapOp;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AArch64 self-healing load-reference barrier applied to the <em>location</em> of an
 * oop field that is about to be updated by a compare-and-swap (the AArch64 counterpart of
 * {@code AMD64SubstrateShenandoahCASHealOp}, implementing the "fix up early" Shenandoah atomic
 * barrier model of JDK-8384080 / JDK-8383810): the field is healed to its to-space value
 * <em>before</em> a plain CAS, so the CAS cannot suffer a concurrent-evacuation false negative.
 *
 * Inline path (out of line once the heap has forwarded objects): load the field; skip null; decode
 * the object; skip it unless it is in the collection set; if its mark word holds a forwardee,
 * re-encode the forwardee and CAS-heal the field (a lost race just means another thread already
 * updated the field). If the object is in the collection set but not yet evacuated, call the
 * runtime stub {@code svm_gc_load_reference_barrier_heal}, which evacuates, resolves and heals.
 *
 * Unlike AMD64 ({@code cmpxchg} hardwires {@code rax}) the heal CAS uses ordinary allocated
 * temporaries, via LSE {@code casl} or an exclusive load/store loop depending on the CPU features.
 */
@Opcode("SHENANDOAH_CAS_HEAL")
public class AArch64SubstrateShenandoahCASHealOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahCASHealOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahCASHealOp.class);

    /** Register holding the address of the reference field that is about to be updated. */
    @Alive({REG}) private AllocatableValue address;

    /** Scratch: gc_state / cset map base / mark word / forwardee / new narrow reference. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: the loaded field value (also the expected value of the heal CAS). */
    @Temp({REG}) private AllocatableValue tmp2;

    /** Scratch: the decoded object address. */
    @Temp({REG}) private AllocatableValue tmp3;

    /** Scratch: cset region index, then the value fetched by the heal CAS. */
    @Temp({REG}) private AllocatableValue tmp4;

    /** Argument register of the slow-path stub call. */
    @Temp({REG}) private AllocatableValue callArg;

    /** The {@code bl} of the slow path overwrites the link register. */
    @Temp({REG}) private AllocatableValue linkReg;

    private final ForeignCallLinkage callTarget;

    /** Whether the reference field is a full machine word (8 bytes) vs. 32 bits. */
    private final boolean loadWordSized;

    /** Compression shift of the narrow reference. */
    private final int narrowShift;

    /** Whether the reference encoding is heap-base relative, which the inline decode requires. */
    private final boolean canInlineCsetCheck;

    public AArch64SubstrateShenandoahCASHealOp(AllocatableValue address, AllocatableValue tmp, AllocatableValue tmp2, AllocatableValue tmp3, AllocatableValue tmp4,
                    ForeignCallLinkage callTarget, boolean loadWordSized, int narrowShift, boolean canInlineCsetCheck) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.tmp2 = tmp2;
        this.tmp3 = tmp3;
        this.tmp4 = tmp4;
        this.callArg = callTarget.getOutgoingCallingConvention().getArgument(0);
        this.linkReg = linkRegisterValue();
        this.callTarget = callTarget;
        this.loadWordSized = loadWordSized;
        this.narrowShift = narrowShift;
        this.canInlineCsetCheck = canInlineCsetCheck;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = threadRegister();
        Register heapBase = heapBaseRegister();
        Register addr = asRegister(address);
        Register rtmp = asRegister(tmp);
        Register rold = asRegister(tmp2);
        Register robj = asRegister(tmp3);
        Register ridx = asRegister(tmp4);
        int bits = loadWordSized ? 64 : 32;

        Label done = new Label();
        Label heal = new Label();
        Label slowPath = new Label();

        // Skip if the thread register is not yet set up (very early isolate-creation code).
        masm.cbz(64, thread, done);

        // Fast path: only heal while the heap has forwarded objects (evacuation / update-refs).
        loadThreadLocal(masm, 8, rtmp, ShenandoahConstants.gcStateOffset());
        masm.tst(32, rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED);
        masm.branchConditionally(ConditionFlag.NE, heal);
        masm.bind(done);

        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(heal);
            if (canInlineCsetCheck) {
                // Load the current field value (old narrow reference). Null needs no healing.
                masm.ldr(bits, rold, AArch64Address.createBaseRegisterOnlyAddress(bits, addr));
                masm.cbz(64, rold, done);

                // Decode the object address: obj = heapBase + (value << shift).
                masm.add(64, robj, heapBase, rold, ShiftType.LSL, narrowShift);

                // Collection-set fast test through the biased per-region byte map.
                masm.lsr(64, ridx, robj, ShenandoahConstants.logOfHeapRegionGrainBytes());
                loadThreadLocal(masm, 64, rtmp, ShenandoahConstants.csetMapAddressOffset());
                masm.ldr(8, rtmp, AArch64Address.createRegisterOffsetAddress(8, rtmp, ridx, false));
                masm.cbz(32, rtmp, done);

                // In the collection set: if the mark word holds a forwardee, heal inline; otherwise
                // (not yet evacuated, or self-forwarded with a null forwardee) call the runtime.
                masm.ldr(64, rtmp, masm.makeAddress(64, robj, ShenandoahConstants.markOffset()));
                masm.tst(64, rtmp, ShenandoahConstants.MARK_FORWARDED_MASK);
                masm.branchConditionally(ConditionFlag.EQ, slowPath);
                masm.and(64, rtmp, rtmp, ~((long) ShenandoahConstants.MARK_FORWARDED_MASK));
                masm.cbz(64, rtmp, slowPath);

                // Re-encode the forwardee: newNarrow = (forwardee - heapBase) >> shift.
                masm.sub(64, rtmp, rtmp, heapBase);
                if (narrowShift != 0) {
                    masm.lsr(64, rtmp, rtmp, narrowShift);
                }

                // Best-effort CAS-heal from the old (from-space) to the to-space value. Release
                // semantics like the C++ ShenandoahHeap::atomic_update_oop.
                AArch64Kind kind = loadWordSized ? AArch64Kind.QWORD : AArch64Kind.DWORD;
                CompareAndSwapOp.emitCompareAndSwap(masm, kind, addr, ridx, rold, rtmp, MemoryOrderMode.RELEASE, false);
                masm.jmp(done);
            } else {
                masm.jmp(slowPath);
            }
        });

        // Out-of-line slow path: the stub evacuates/resolves and CAS-heals the field in place.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(slowPath);
            masm.mov(64, asRegister(callArg), addr);
            callStub(crb, masm, callTarget);
            masm.jmp(done);
        });
    }
}
