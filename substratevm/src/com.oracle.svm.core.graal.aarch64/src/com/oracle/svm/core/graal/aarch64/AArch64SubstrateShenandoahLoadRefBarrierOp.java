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

import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.callStub;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.heapBaseRegister;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.linkRegisterValue;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.loadThreadLocal;
import static com.oracle.svm.core.graal.aarch64.AArch64SubstrateShenandoahBarrierUtil.threadRegister;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadRefBarrierNode.ReferenceStrength;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AArch64 implementation of the Shenandoah load-reference barrier (the AArch64
 * counterpart of {@code AMD64SubstrateShenandoahLoadRefBarrierOp}, structurally based on the Graal
 * HotSpot backend op {@code AArch64HotSpotShenandoahLoadRefBarrierOp}):
 * <ol>
 * <li>Inlined fast path: if the loaded reference is null, or the per-thread {@code gc_state} shows a
 * stable heap (no forwarded objects; for non-strong references additionally no weak-roots
 * processing), the reference is returned unchanged.</li>
 * <li>Out-of-line mid path (strong references only): the collection-set fast test through the
 * biased per-region byte map (thread-local {@code csetMapAddress}, indexed by
 * {@code address >> regionSizeShift}).</li>
 * <li>Out-of-line slow path: call the runtime stub with the reference and the address it was loaded
 * from. The stub returns the canonical (to-space) reference and self-heals the load location.</li>
 * </ol>
 *
 * The result register is only written once all temporaries are dead (at the join points), so the
 * register allocator is free to assign it any register that does not hold an {@code Alive} input.
 */
@Opcode("SHENANDOAH_LRB")
public class AArch64SubstrateShenandoahLoadRefBarrierOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahLoadRefBarrierOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahLoadRefBarrierOp.class);

    /** The canonicalized reference (output). */
    @Def({REG}) private AllocatableValue result;

    /** The reference that was just loaded (input). */
    @Alive({REG}) private AllocatableValue object;

    /** The address of the memory location the reference was loaded from. */
    @Alive({COMPOSITE}) private AArch64AddressValue loadAddress;

    /** Scratch: gc_state byte / cset map base / cset map byte. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: region index for the cset fast test. */
    @Temp({REG}) private AllocatableValue tmp2;

    @Temp({REG}) private AllocatableValue callArg0;
    @Temp({REG}) private AllocatableValue callArg1;
    @Temp({REG}) private AllocatableValue callRet;
    /** The {@code bl} of the slow path overwrites the link register. */
    @Temp({REG}) private AllocatableValue linkReg;

    @Alive({REG, ILLEGAL}) private AllocatableValue callAddress;

    private final ForeignCallLinkage callTarget;
    private final ReferenceStrength strength;
    private final boolean notNull;

    public AArch64SubstrateShenandoahLoadRefBarrierOp(AllocatableValue result, AllocatableValue object, AArch64AddressValue loadAddress, AllocatableValue tmp, AllocatableValue tmp2,
                    ForeignCallLinkage callTarget, AllocatableValue callAddress, ReferenceStrength strength, boolean notNull) {
        super(TYPE);
        this.result = result;
        this.object = object;
        this.loadAddress = loadAddress;
        this.tmp = tmp;
        this.tmp2 = tmp2;
        this.callArg0 = callTarget.getOutgoingCallingConvention().getArgument(0);
        this.callArg1 = callTarget.getOutgoingCallingConvention().getArgument(1);
        this.callRet = callTarget.getOutgoingCallingConvention().getReturn();
        this.linkReg = linkRegisterValue();
        this.callTarget = callTarget;
        this.callAddress = callAddress;
        this.strength = strength;
        this.notNull = notNull;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = threadRegister();
        Register rtmp = asRegister(tmp);
        Register rtmp2 = asRegister(tmp2);
        Register objReg = asRegister(object);
        Register resReg = asRegister(result);
        AArch64Address loadAddr = loadAddress.toAddress();

        Label unchanged = new Label();
        Label done = new Label();
        Label csetCheck = new Label();
        Label slowPath = new Label();

        if (!notNull) {
            // Null comes in two encodings: a reference loaded in compressed form decompresses null
            // to the heap base (SubstrateVM always uncompresses with "base + (value << shift)"),
            // while a value-passed reference uses plain 0. A decompressed null must not reach the
            // cset fast test (it would index the biased map below region 0).
            masm.cmp(64, objReg, heapBaseRegister());
            masm.branchConditionally(ConditionFlag.EQ, unchanged);
            masm.cbz(64, objReg, unchanged);
        }

        // Skip the barrier if the thread register is not yet set up (very early isolate-creation
        // code). No objects are forwarded then.
        masm.cbz(64, thread, unchanged);

        // Check for heap stability.
        loadThreadLocal(masm, 8, rtmp, ShenandoahConstants.gcStateOffset());
        if (strength == ReferenceStrength.STRONG) {
            masm.tst(32, rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED);
            masm.branchConditionally(ConditionFlag.NE, csetCheck);
        } else {
            // HAS_FORWARDED | WEAK_ROOTS is not encodable as a logical immediate: two tests.
            masm.tst(32, rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED);
            masm.branchConditionally(ConditionFlag.NE, slowPath);
            masm.tst(32, rtmp, ShenandoahConstants.GC_STATE_WEAK_ROOTS);
            masm.branchConditionally(ConditionFlag.NE, slowPath);
        }
        masm.bind(unchanged);
        masm.mov(64, resReg, objReg);
        masm.bind(done);

        if (strength == ReferenceStrength.STRONG) {
            // Out-of-line mid path: collection-set fast test.
            crb.getLIR().addSlowPath(this, () -> {
                masm.bind(csetCheck);
                masm.lsr(64, rtmp2, objReg, ShenandoahConstants.logOfHeapRegionGrainBytes());
                loadThreadLocal(masm, 64, rtmp, ShenandoahConstants.csetMapAddressOffset());
                masm.ldr(8, rtmp, AArch64Address.createRegisterOffsetAddress(8, rtmp, rtmp2, false));
                masm.cbnz(32, rtmp, slowPath);
                masm.jmp(unchanged);
            });
        }

        // Out-of-line slow path: call the runtime LRB with the loaded reference and its address.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(slowPath);
            Register arg0 = asRegister(callArg0);
            Register arg1 = asRegister(callArg1);
            // object and the address components are Alive, so they cannot live in arg0/arg1.
            masm.loadAddress(arg1, loadAddr);
            masm.mov(64, arg0, objReg);
            callStub(crb, masm, callTarget, callAddress);
            masm.mov(64, resReg, asRegister(callRet));
            masm.jmp(done);
        });
    }
}
