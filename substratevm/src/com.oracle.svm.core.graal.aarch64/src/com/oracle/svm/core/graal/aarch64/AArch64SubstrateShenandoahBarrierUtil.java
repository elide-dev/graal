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

import static jdk.vm.ci.aarch64.AArch64.lr;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallLinkage;

import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.aarch64.AArch64Call;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * Helpers shared by the AArch64 SubstrateVM Shenandoah barrier LIR ops.
 */
final class AArch64SubstrateShenandoahBarrierUtil {

    private AArch64SubstrateShenandoahBarrierUtil() {
    }

    static Register threadRegister() {
        return ReservedRegisters.singleton().getThreadRegister();
    }

    static Register heapBaseRegister() {
        return ReservedRegisters.singleton().getHeapBaseRegister();
    }

    /** The link register, which every {@code bl} overwrites; ops that call must declare it Temp. */
    static AllocatableValue linkRegisterValue() {
        return lr.asValue(LIRKind.value(AArch64Kind.QWORD));
    }

    /**
     * Loads {@code bits} bits (zero-extended) from {@code thread + offset} into {@code dst}. The
     * offset of a VM thread local can exceed the range of a scaled immediate, so {@code dst} doubles
     * as the scratch register for the address computation.
     */
    static void loadThreadLocal(AArch64MacroAssembler masm, int bits, Register dst, int offset) {
        AArch64Address addr = masm.makeAddress(bits, threadRegister(), offset, dst);
        masm.ldr(bits, dst, addr);
    }

    /** Stores {@code bits} bits of {@code src} to {@code thread + offset}, using {@code scratch} if needed. */
    static void storeThreadLocal(AArch64MacroAssembler masm, int bits, Register src, int offset, Register scratch) {
        AArch64Address addr = masm.makeAddress(bits, threadRegister(), offset, scratch);
        masm.str(bits, src, addr);
    }

    /**
     * Calls the (uninterruptible, register-preserving) runtime stub. All SubstrateVM direct calls
     * are within +-128 MB, so a plain {@code bl} suffices (see SubstrateAArch64DirectCallOp).
     */
    static void callStub(CompilationResultBuilder crb, AArch64MacroAssembler masm, ForeignCallLinkage callTarget) {
        AArch64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, null);
    }
}
