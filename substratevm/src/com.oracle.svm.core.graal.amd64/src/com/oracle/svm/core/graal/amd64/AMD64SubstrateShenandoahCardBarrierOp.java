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
package com.oracle.svm.core.graal.amd64;

import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.amd64.AMD64Address;
import jdk.graal.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.amd64.AMD64MacroAssembler;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.amd64.AMD64AddressValue;
import jdk.graal.compiler.lir.amd64.AMD64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AMD64 implementation of the Shenandoah card-marking (post-write) barrier, which
 * maintains the remembered set of generational mode: after a reference store, the card covering the
 * stored-to address is marked dirty, so that a young collection can find old-to-young references
 * without scanning the whole old generation.
 *
 * The sequence mirrors the HotSpot backend op (AMD64HotSpotShenandoahCardBarrierOp): compute the card
 * index from the written address, add the per-thread (biased) card-table base, and store the dirty
 * value, with a conditional check that skips the store when the card is already dirty (which keeps
 * the cache line clean and shared in the common case).
 *
 * SubstrateVM specifics:
 * <ul>
 * <li>Whether this barrier is emitted at all is an image-build-time decision
 * ({@code -H:+ShenandoahGenerational}), because the GC mode is only chosen at run time. In an image
 * built with generational support that then runs in satb or passive mode there is no remembered set;
 * the per-thread card-table base is zero in that case and the barrier skips itself.</li>
 * <li>The thread register can be zero in very early isolate-creation code, which is skipped too.</li>
 * </ul>
 */
@Opcode("SHENANDOAH_CARD_BARRIER")
public class AMD64SubstrateShenandoahCardBarrierOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64SubstrateShenandoahCardBarrierOp> TYPE = LIRInstructionClass.create(AMD64SubstrateShenandoahCardBarrierOp.class);

    /** The address that was written to. */
    @Alive({COMPOSITE}) private AMD64AddressValue address;

    /** Scratch: card index, then card address. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: per-thread card-table base. */
    @Temp({REG}) private AllocatableValue tmp2;

    public AMD64SubstrateShenandoahCardBarrierOp(AMD64AddressValue address, AllocatableValue tmp, AllocatableValue tmp2) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.tmp2 = tmp2;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register rindex = asRegister(tmp);
        Register rbase = asRegister(tmp2);

        Label done = new Label();

        // Skip if the thread register is not set up yet (very early isolate-creation code): no
        // collection can be in progress then, so there is no remembered set to maintain.
        masm.testq(thread, thread);
        masm.jcc(ConditionFlag.Zero, done);

        // The (biased) card-table base is zero unless the current GC mode maintains a remembered set.
        // This is how satb/passive mode skip this barrier in a generational-capable image.
        masm.movq(rbase, new AMD64Address(thread, ShenandoahConstants.cardTableAddressOffset()));
        masm.testqAndJcc(rbase, rbase, ConditionFlag.Zero, done, false);

        // cardAddress = cardTableBase + (writtenAddress >> cardShift). The base is pre-biased by
        // heapBase >> cardShift, so the shifted address serves directly as the index.
        masm.leaq(rindex, address.toAddress(masm));
        masm.shrq(rindex, ShenandoahConstants.cardTableShift());
        masm.addq(rindex, rbase);

        // Conditional card marking: only dirty a card that is still clean.
        AMD64Address cardAddr = new AMD64Address(rindex);
        masm.cmpb(cardAddr, ShenandoahConstants.dirtyCardValue());
        masm.jccb(ConditionFlag.Equal, done);
        masm.movb(cardAddr, ShenandoahConstants.dirtyCardValue());

        masm.bind(done);
    }
}
