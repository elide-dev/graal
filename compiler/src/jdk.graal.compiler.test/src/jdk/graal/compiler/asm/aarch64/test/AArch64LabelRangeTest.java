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
package jdk.graal.compiler.asm.aarch64.test;

import java.util.EnumSet;

import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.asm.BranchTargetOutOfBoundsException;
import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.core.aarch64.test.AArch64TestMacroAssembler;
import jdk.graal.compiler.test.GraalTest;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.code.TargetDescription;

/**
 * Assembler-level checks for AArch64 label range handling. Only machine code is emitted and
 * inspected, so this uses a synthetic AArch64 target and runs on every host architecture.
 */
public class AArch64LabelRangeTest extends GraalTest {
    private final TargetDescription target = new TargetDescription(new AArch64(EnumSet.of(AArch64.CPUFeature.FP)), true, 16, 4096, true);

    private enum Kind {
        ADR(21),
        CONDITIONAL(21),
        COMPARE_ZERO(21),
        TEST_BIT(16),
        UNCONDITIONAL(28);

        final int bits;

        Kind(int bits) {
            this.bits = bits;
        }
    }

    private static final class TestAssembler extends AArch64TestMacroAssembler {
        TestAssembler(TargetDescription target) {
            super(target);
        }

        void emit(Kind kind, int offset) {
            switch (kind) {
                case ADR -> adr(AArch64.r11, offset);
                case CONDITIONAL -> b(ConditionFlag.EQ, offset);
                case COMPARE_ZERO -> cbz(64, AArch64.r11, offset);
                case TEST_BIT -> tbz(AArch64.r11, 5, offset);
                case UNCONDITIONAL -> b(offset);
            }
        }

        void relocate(int offset) {
            patchRelativeJumpTarget(0, offset);
        }
    }

    @Test
    public void forwardAdrOverflowRequestsConservativeRetry() {
        TestAssembler asm = new TestAssembler(target);
        Label label = new Label();
        asm.adr(AArch64.r11, label);
        while (asm.position() < (1 << 20)) {
            asm.nop();
        }
        Assert.assertThrows(BranchTargetOutOfBoundsException.class, () -> asm.bind(label));
    }

    @Test
    public void backwardAdrOverflowRequestsConservativeRetry() {
        TestAssembler asm = new TestAssembler(target);
        Label label = new Label();
        asm.bind(label);
        while (asm.position() < (1 << 20) + 4) {
            asm.nop();
        }
        Assert.assertThrows(BranchTargetOutOfBoundsException.class, () -> asm.adr(AArch64.r11, label));
    }

    @Test
    public void replayPreservesSignedOffsetsAtBothLimits() {
        for (Kind kind : Kind.values()) {
            int limit = 1 << (kind.bits - 1);
            for (int offset : new int[]{limit - 4, -limit}) {
                TestAssembler actual = new TestAssembler(target);
                actual.emit(kind, -4);
                actual.relocate(offset + 4);
                TestAssembler expected = new TestAssembler(target);
                expected.emit(kind, offset);
                Assert.assertEquals(kind + " offset " + offset, expected.getInt(0), actual.getInt(0));
            }
        }
    }

    private void assertReplayOverflow(Kind kind) {
        int limit = 1 << (kind.bits - 1);
        for (int offset : new int[]{limit, -limit - 4}) {
            TestAssembler asm = new TestAssembler(target);
            asm.emit(kind, -4);
            int original = asm.getInt(0);
            Assert.assertThrows(kind + " offset " + offset, BranchTargetOutOfBoundsException.class, () -> asm.relocate(offset + 4));
            Assert.assertEquals("failed relocation must preserve the instruction", original, asm.getInt(0));
        }
    }

    @Test
    public void replayAdrOverflowIsRejectedBeforeWriting() {
        assertReplayOverflow(Kind.ADR);
    }

    @Test
    public void replayConditionalOverflowIsRejectedBeforeWriting() {
        assertReplayOverflow(Kind.CONDITIONAL);
    }

    @Test
    public void replayCompareZeroOverflowIsRejectedBeforeWriting() {
        assertReplayOverflow(Kind.COMPARE_ZERO);
    }

    @Test
    public void replayTestBitOverflowIsRejectedBeforeWriting() {
        assertReplayOverflow(Kind.TEST_BIT);
    }

    @Test
    public void replayUnconditionalOverflowIsRejectedBeforeWriting() {
        assertReplayOverflow(Kind.UNCONDITIONAL);
    }
}
