/*
 * Copyright (c) 2015, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.amd64.test;

import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Test;

import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.core.test.MatchRuleTest;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.amd64.AMD64Binary;
import jdk.graal.compiler.lir.amd64.AMD64BinaryConsumer.ConstOp;
import jdk.graal.compiler.lir.amd64.AMD64BinaryConsumer.MemoryConstOp;
import jdk.graal.compiler.lir.amd64.AMD64ControlFlow.CmpBranchOp;
import jdk.graal.compiler.lir.amd64.AMD64ControlFlow.CmpConstBranchOp;
import jdk.graal.compiler.lir.amd64.AMD64ControlFlow.CmpDataBranchOp;
import jdk.graal.compiler.lir.amd64.AMD64Move.LeaOp;
import jdk.graal.compiler.lir.amd64.AMD64Unary;
import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.amd64.AMD64Kind;

public class AMD64MatchRuleTest extends MatchRuleTest {
    @Before
    public void checkAMD64() {
        assumeTrue("skipping AMD64 specific test", getTarget().arch instanceof AMD64);
    }

    public static int test1Snippet(TestClass o, TestClass b, TestClass c) {
        if (o.x == 42) {
            return b.z;
        } else {
            return c.y;
        }
    }

    /**
     * Verifies, if the match rules in AMD64NodeMatchRules do work on the graphs by compiling and
     * checking if the expected LIR instruction show up.
     */
    @Test
    public void test1() {
        compile(getResolvedJavaMethod("test1Snippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof MemoryConstOp && ((MemoryConstOp) ins).getOpcode().toString().equals("CMP")) {
                assertFalse("MemoryConstOp expected only once in first block", found);
                found = true;
            }
            if (ins instanceof CmpConstBranchOp || ins instanceof CmpBranchOp || ins instanceof CmpDataBranchOp) {
                assertFalse("CMP expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("Memory compare must be in the LIR", found);
    }

    public static int leaTimes3Snippet(int x) {
        return x * 3;
    }

    public static int leaScaledDisplacementSnippet(int x) {
        return x * 2 + 7;
    }

    public static int leaScaledSumDisplacementSnippet(int x, int y) {
        return x * 4 + y + 11;
    }

    public static long leaLongTimes9Snippet(long x) {
        return x * 9;
    }

    public static int noLeaShift4Snippet(int x, int y) {
        return (x << 4) + y;
    }

    private int countLea(String snippet) {
        compile(getResolvedJavaMethod(snippet), null);
        LIR lir = getLIR();
        int count = 0;
        for (int blockId : lir.codeEmittingOrder()) {
            if (blockId == AbstractControlFlowGraph.INVALID_BLOCK_ID) {
                continue;
            }
            for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[blockId])) {
                if (ins instanceof LeaOp) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Checks that a shift by 1, 2 or 3 and an addition become one {@code lea}, and that the
     * results stay correct when the arithmetic overflows.
     */
    @Test
    public void testLea() {
        assertTrue("x * 3 must use lea", countLea("leaTimes3Snippet") == 1);
        assertTrue("x * 2 + 7 must use lea", countLea("leaScaledDisplacementSnippet") == 1);
        assertTrue("x * 4 + y + 11 must use lea", countLea("leaScaledSumDisplacementSnippet") == 1);
        assertTrue("x * 9 (long) must use lea", countLea("leaLongTimes9Snippet") == 1);
        assertTrue("a shift by 4 cannot use lea", countLea("noLeaShift4Snippet") == 0);

        int[] ints = {0, 1, -1, 12345, -98765, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE / 3 + 1};
        for (int x : ints) {
            test("leaTimes3Snippet", x);
            test("leaScaledDisplacementSnippet", x);
            for (int y : ints) {
                test("leaScaledSumDisplacementSnippet", x, y);
                test("noLeaShift4Snippet", x, y);
            }
        }
        long[] longs = {0L, 1L, -1L, 1234567890123L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE / 9 + 1};
        for (long x : longs) {
            test("leaLongTimes9Snippet", x);
        }
    }

    public static class TestClass {
        public int x;
        public int y;
        public int z;

        public TestClass(int x) {
            super();
            this.x = x;
        }
    }

    static volatile short shortValue;

    public static long testVolatileExtensionSnippet() {
        return shortValue;
    }

    @Test
    public void testVolatileExtension() {
        compile(getResolvedJavaMethod("testVolatileExtensionSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof AMD64Unary.MemoryOp) {
                ins.visitEachOutput((value, _, _) -> assertTrue(value.getPlatformKind().toString(), value.getPlatformKind().equals(AMD64Kind.QWORD)));
                assertFalse("MemoryOp expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("sign extending load must be in the LIR", found);
    }

    static int intValue;
    static volatile int volatileIntValue;

    /**
     * Can't match test and load of input because of volatile store in between.
     */
    public static short testLoadTestNoMatchSnippet() {
        int v = intValue;
        volatileIntValue = 42;
        if (v == 42) {
            return shortValue;
        }
        return 0;
    }

    @Test
    public void testLoadTestNoMatch() {
        compile(getResolvedJavaMethod("testLoadTestNoMatchSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof ConstOp && ((ConstOp) ins).getOpcode().toString().equals("CMP")) {
                assertFalse("CMP expected only once in first block", found);
                found = true;
            }
            if (ins instanceof CmpConstBranchOp || ins instanceof CmpBranchOp || ins instanceof CmpDataBranchOp) {
                assertFalse("CMP expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("CMP must be in the LIR", found);
    }

    /**
     * Should match as an add with a memory operand.
     */
    public static int testAddLoadSnippet() {
        int v1 = volatileIntValue;
        int v2 = intValue;
        return v2 + (2 * v1);
    }

    @Test
    public void testAddLoad() {
        compile(getResolvedJavaMethod("testAddLoadSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof AMD64Binary.MemoryTwoOp && ((AMD64Binary.MemoryTwoOp) ins).getOpcode().toString().equals("ADD")) {
                assertFalse("MemoryTwoOp expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("ADD with memory argument must be in the LIR", found);
    }

    /**
     * Can't match as an add with a memory operand because the other add input is too late.
     */
    public static int testAddLoadNoMatchSnippet() {
        int v1 = volatileIntValue;
        int v2 = intValue;
        return v1 + (2 * v2);
    }

    @Test
    public void testAddLoadNoMatch() {
        compile(getResolvedJavaMethod("testAddLoadNoMatchSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof AMD64Binary.CommutativeTwoOp && ((AMD64Binary.CommutativeTwoOp) ins).getOpcode().toString().equals("ADD")) {
                assertFalse("CommutativeTwoOp expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("ADD with memory argument must not be in the LIR", found);
    }

    /**
     * sign extension and load are in different blocks but can still be matched as a single
     * instruction.
     */
    public static long testVolatileExtensionDifferentBlocksSnippet(boolean flag) {
        short v = shortValue;
        if (flag) {
            return v;
        }
        return 0;
    }

    @Test
    public void testVolatileExtensionDifferentBlocks() {
        compile(getResolvedJavaMethod("testVolatileExtensionDifferentBlocksSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof AMD64Unary.MemoryOp) {
                ins.visitEachOutput((value, _, _) -> assertTrue(value.getPlatformKind().toString(), value.getPlatformKind().equals(AMD64Kind.QWORD)));
                assertFalse("MemoryOp expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("sign extending load must be in the LIR", found);
    }

    /**
     * Add and load are not in the same block and one input is too late: can't match.
     */
    public static int testAddLoadDifferentBlocksNoMatchSnippet(boolean flag) {
        int v1 = volatileIntValue;
        if (flag) {
            int v2 = intValue;
            return v1 + (2 * v2);
        }
        return 0;
    }

    @Test
    public void testAddLoadDifferentBlocksNoMatch() {
        compile(getResolvedJavaMethod("testAddLoadDifferentBlocksNoMatchSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (int blockId : lir.codeEmittingOrder()) {
            BasicBlock<?> b = lir.getBlockById(blockId);
            for (LIRInstruction ins : lir.getLIRforBlock(b)) {
                if (ins instanceof AMD64Binary.CommutativeTwoOp && ((AMD64Binary.CommutativeTwoOp) ins).getOpcode().toString().equals("ADD")) {
                    assertFalse("CommutativeTwoOp expected only once in first block", found);
                    found = true;
                }
            }
        }
        assertTrue("ADD with memory argument must not be in the LIR", found);
    }

    /**
     * Add and load are in different blocks but can still match.
     */
    public static int testAddLoadDifferentBlocksSnippet(boolean flag) {
        int v2 = intValue;
        int v1 = volatileIntValue;
        if (flag) {
            return v1 + v2;
        }
        return 0;
    }

    @Test
    public void testAddLoadDifferentBlocks() {
        compile(getResolvedJavaMethod("testAddLoadDifferentBlocksSnippet"), null);
        LIR lir = getLIR();
        boolean found = false;
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (ins instanceof AMD64Binary.MemoryTwoOp && ((AMD64Binary.MemoryTwoOp) ins).getOpcode().toString().equals("ADD")) {
                assertFalse("MemoryTwoOp expected only once in first block", found);
                found = true;
            }
        }
        assertTrue("ADD with memory argument must be in the LIR", found);
    }

}
