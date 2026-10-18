/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.aarch64.test;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.jtt.LIRTest;
import jdk.graal.compiler.lir.jtt.LIRTestSpecification;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.meta.Value;

public class AArch64SnippetStateTest extends LIRTest {
    private static final SnippetSpec FINISHED = new SnippetSpec(0);
    private static final SnippetSpec UNFINISHED = new SnippetSpec(1);
    private static final SnippetSpec CONSERVATIVE = new SnippetSpec(2);

    @Before
    public void requireAArch64() {
        Assume.assumeTrue("AArch64-specific test", getTarget().arch instanceof AArch64);
    }

    private static final class SnippetSpec extends LIRTestSpecification {
        final int operation;

        SnippetSpec(int operation) {
            this.operation = operation;
        }

        @Override
        public void generate(LIRGeneratorTool gen, Value value) {
            gen.append(new SnippetStateOp(operation));
            setResult(value);
        }
    }

    private static final class SnippetStateOp extends AArch64LIRInstruction {
        static final LIRInstructionClass<SnippetStateOp> TYPE = LIRInstructionClass.create(SnippetStateOp.class);
        private final int operation;

        SnippetStateOp(int operation) {
            super(TYPE);
            this.operation = operation;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            // Use the live builder for its site bookkeeping, but keep this assembler isolated.
            AArch64MacroAssembler recorded = new AArch64TestMacroAssembler(crb.target);
            int start = masm.position() + 4;
            while (recorded.position() < start) {
                recorded.nop();
            }
            recorded.startRecordingCodeSnippet(crb);
            recorded.nop();
            if (operation == 1) {
                recorded.reset();
                while (recorded.position() <= start) {
                    recorded.nop();
                }
                recorded.stopRecordingCodeSnippet(crb);
                Assert.assertFalse("reset must discard unfinished recording", recorded.replayCodeSnippetAt(crb, start));
            } else {
                recorded.stopRecordingCodeSnippet(crb);
                Assert.assertTrue("ordinary snippet replay is available", recorded.replayCodeSnippetAt(crb, start));
                if (operation == 0) {
                    recorded.reset();
                    Assert.assertFalse("reset must discard completed recording", recorded.replayCodeSnippetAt(crb, start));
                } else {
                    crb.setConservativeLabelRanges();
                    Assert.assertFalse("conservative emission must use ordinary control flow", recorded.replayCodeSnippetAt(crb, start));
                }
            }
        }
    }

    @LIRIntrinsic
    public static int checkState(@SuppressWarnings("unused") SnippetSpec specification, int value) {
        return value;
    }

    public static int finishedSnippet(int value) {
        return checkState(FINISHED, value);
    }

    public static int unfinishedSnippet(int value) {
        return checkState(UNFINISHED, value);
    }

    public static int conservativeSnippet(int value) {
        return checkState(CONSERVATIVE, value);
    }

    @Test
    public void finishedRecordingIsDiscardedOnReset() {
        test("finishedSnippet", 17);
    }

    @Test
    public void unfinishedRecordingIsDiscardedOnReset() {
        test("unfinishedSnippet", 17);
    }

    @Test
    public void conservativeEmissionDeclinesRecordedSnippet() {
        test("conservativeSnippet", 17);
    }
}
