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
package jdk.graal.compiler.core.test;

import java.util.Random;

import org.junit.Test;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.phases.common.FixReadsPhase;

/**
 * Tests that {@link FixReadsPhase} rewrites the address index {@code ZeroExtend(x + c)} into
 * {@code ZeroExtend(x) + c} exactly when the stamps it knows at the access prove that the narrow
 * addition cannot wrap, and that the compiled code stays correct at the boundaries either way.
 */
public class FixReadsAddressOffsetTest extends GraalCompilerTest {

    /**
     * The index {@code j + 1} of the store is zero-extended; the bounds checks of the loop are
     * hoisted by loop predication, so {@code j >= 0} is only known through the pi node of the
     * {@code a[j]} load. After the loop, {@code j} may be {@code -1}, so the stores there must
     * keep {@code ZeroExtend(j + 1)}.
     */
    public static void insertionSortSnippet(int[] a) {
        for (int i = 1; i < a.length; i++) {
            int v = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    /**
     * The inner loop of {@link #insertionSortSnippet} alone: every access is in the loop, so every
     * index offset must be folded, including the {@code a[j - 1]} of an unrolled copy.
     */
    public static void insertionStepSnippet(int[] a, int v) {
        int j = a.length - 2;
        while (j >= 0 && a[j] > v) {
            a[j + 1] = a[j];
            j--;
        }
    }

    /** {@code x >= 0} comes from the branch, so {@code x + 1} cannot wrap. */
    public static void storeNextSnippet(int[] a, int x) {
        if (x >= 0 && x + 1 < a.length) {
            a[x + 1] = 1;
        }
    }

    /** {@code x - 1 >= 0} alone proves that the subtraction cannot wrap. */
    public static void storePreviousSnippet(int[] a, int x) {
        int k = x - 1;
        if (k >= 0 && k < a.length) {
            a[k] = 1;
        }
    }

    /**
     * {@code x + 1 >= 0} does not exclude {@code x == -1}, for which {@code ZeroExtend(x + 1)} is
     * {@code 0} but {@code ZeroExtend(x) + 1} is {@code 2^32}. The index must not be rewritten.
     */
    public static void storeGuardedSumSnippet(int[] a, int x) {
        int k = x + 1;
        if (k >= 0 && k < a.length) {
            a[k] = 1;
        }
    }

    /**
     * {@code x + 2 >= 2} excludes the wrapped results {@code 0} and {@code 1}, so the index can be
     * rewritten without knowing anything about {@code x} itself.
     */
    public static void storeSumAboveOffsetSnippet(int[] a, int x) {
        int k = x + 2;
        if (k >= 2 && k < a.length) {
            a[k] = 1;
        }
    }

    private int countExtendedAdds(String snippet) {
        compile(getResolvedJavaMethod(snippet), null);
        StructuredGraph graph = lastCompiledGraph;
        int count = 0;
        for (ZeroExtendNode zeroExtend : graph.getNodes().filter(ZeroExtendNode.class)) {
            if (zeroExtend.getValue() instanceof AddNode) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void testRewrites() {
        assertTrue("x >= 0: ZeroExtend(x + 1) must become ZeroExtend(x) + 1", countExtendedAdds("storeNextSnippet") == 0);
        assertTrue("x - 1 >= 0: ZeroExtend(x - 1) must become ZeroExtend(x) - 1", countExtendedAdds("storePreviousSnippet") == 0);
        assertTrue("x + 2 >= 2: ZeroExtend(x + 2) must become ZeroExtend(x) + 2", countExtendedAdds("storeSumAboveOffsetSnippet") == 0);
        assertTrue("j >= 0 is known from the a[j] load: ZeroExtend(j + 1) must become ZeroExtend(j) + 1", countExtendedAdds("insertionStepSnippet") == 0);
    }

    @Test
    public void testNoRewriteWhenIndexMayWrap() {
        assertTrue("x + 1 >= 0 does not exclude x == -1", countExtendedAdds("storeGuardedSumSnippet") >= 1);
    }

    @Test
    public void testBoundaries() {
        int[] xs = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -2, -1, 0, 1, 2, 6, 7, 8, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int x : xs) {
            test("storeNextSnippet", new int[8], x);
            test("storePreviousSnippet", new int[8], x);
            test("storeGuardedSumSnippet", new int[8], x);
            test("storeSumAboveOffsetSnippet", new int[8], x);
        }
    }

    @Test
    public void testInsertionSort() {
        Random random = new Random(42);
        for (int n : new int[]{0, 1, 2, 3, 17, 100, 5000}) {
            int[] a = new int[n];
            for (int i = 0; i < n; i++) {
                a[i] = random.nextInt();
            }
            if (n > 2) {
                a[0] = Integer.MAX_VALUE;
                a[1] = Integer.MIN_VALUE;
                a[n - 1] = Integer.MIN_VALUE;
            }
            test("insertionSortSnippet", a);
            for (int v : new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}) {
                test("insertionStepSnippet", a.clone(), v);
            }
        }
    }
}
