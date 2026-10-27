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

import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.graal.meta.SubstrateForeignCallLinkage;

import jdk.graal.compiler.asm.amd64.AMD64MacroAssembler;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.amd64.AMD64Call;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

final class AMD64SubstrateShenandoahBarrierUtil {
    private AMD64SubstrateShenandoahBarrierUtil() {
    }

    static AllocatableValue callTargetAddress(LIRGeneratorTool tool, ForeignCallLinkage linkage) {
        Value address = ((SubstrateAMD64Backend.SubstrateAMD64LIRGenerator) tool).emitBarrierCallTargetAddress(linkage);
        return Value.ILLEGAL.equals(address) ? Value.ILLEGAL : tool.asAllocatable(address);
    }

    static void callStub(CompilationResultBuilder crb, AMD64MacroAssembler masm, ForeignCallLinkage callTarget, AllocatableValue address) {
        var method = ((SubstrateForeignCallLinkage) callTarget).getMethod();
        if (Value.ILLEGAL.equals(address)) {
            AMD64Call.directCall(crb, masm, method, null, false, null);
        } else {
            AMD64Call.indirectCall(crb, masm, asRegister(address), method, null);
        }
    }
}
