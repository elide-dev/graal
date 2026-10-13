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

import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.loadThreadLocal;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.threadRegister;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.aarch64.AArch64.zr;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler.ScratchRegister;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AArch64 implementation of the Shenandoah card-marking (post-write) barrier of
 * generational mode (the AArch64 counterpart of {@code AMD64SubstrateShenandoahCardBarrierOp}): after
 * a reference store, the card covering the written address is dirtied if it is still clean.
 *
 * The per-thread (biased) card-table base can be zero very early during thread attach, before the
 * C++ side has published it, and the thread register can be zero in very early isolate-creation
 * code; the barrier skips itself in both cases.
 */
@Opcode("SHENANDOAH_CARD_BARRIER")
public class AArch64SubstrateShenandoahCardBarrierOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahCardBarrierOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahCardBarrierOp.class);

    /** The address that was written to. */
    @Alive({COMPOSITE}) private AArch64AddressValue address;

    /** Scratch: card index. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: per-thread card-table base. */
    @Temp({REG}) private AllocatableValue tmp2;

    public AArch64SubstrateShenandoahCardBarrierOp(AArch64AddressValue address, AllocatableValue tmp, AllocatableValue tmp2) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.tmp2 = tmp2;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = threadRegister();
        Register rindex = asRegister(tmp);
        Register rbase = asRegister(tmp2);
        byte dirty = ShenandoahConstants.dirtyCardValue();

        Label done = new Label();

        masm.cbz(64, thread, done);

        loadThreadLocal(masm, 64, rbase, ShenandoahConstants.cardTableAddressOffset());
        masm.cbz(64, rbase, done);

        // cardAddress = cardTableBase + (writtenAddress >> cardShift); the base is pre-biased.
        masm.loadAddress(rindex, address.toAddress());
        masm.lsr(64, rindex, rindex, ShenandoahConstants.cardTableShift());
        AArch64Address cardAddr = AArch64Address.createRegisterOffsetAddress(8, rbase, rindex, false);

        // Conditional card marking: only dirty a card that is still clean.
        try (ScratchRegister sc = masm.getScratchRegister()) {
            Register rcard = sc.getRegister();
            masm.ldr(8, rcard, cardAddr);
            if (dirty == 0) {
                masm.cbz(32, rcard, done);
                masm.str(8, zr, cardAddr);
            } else {
                masm.compare(32, rcard, dirty & 0xFF);
                masm.branchConditionally(ConditionFlag.EQ, done);
                masm.mov(rcard, dirty & 0xFF);
                masm.str(8, rcard, cardAddr);
            }
        }
        masm.bind(done);
    }
}
