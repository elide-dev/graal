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
package jdk.graal.compiler.core.amd64.test;

import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Test;

import jdk.graal.compiler.core.amd64.AMD64AddressNode;
import jdk.graal.compiler.core.common.Stride;
import jdk.graal.compiler.core.test.FixReadsAddressOffsetTest;
import jdk.graal.compiler.core.test.GraalCompilerTest;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.vm.ci.amd64.AMD64;

/**
 * Checks that a constant offset of an array index ends up in the displacement of the AMD64 address
 * when {@link FixReadsAddressOffsetTest} shows it can, so {@code a[j + 1]} is addressed as
 * {@code [a + j * 4 + 20]} without computing {@code j + 1}.
 */
public class AMD64ArrayIndexOffsetTest extends GraalCompilerTest {

    @Before
    public void checkAMD64() {
        assumeTrue("skipping AMD64 specific test", getTarget().arch instanceof AMD64);
    }

    private static final int INT_ARRAY_BASE = 16;

    private StructuredGraph compileSnippet(String snippet) {
        compile(getResolvedJavaMethod(FixReadsAddressOffsetTest.class, snippet), null);
        return lastCompiledGraph;
    }

    /**
     * Counts the int-array addresses {@code [base + ZeroExtend(index) * 4 + displacement]} whose
     * index is not an addition.
     */
    private static int countFoldedAddresses(StructuredGraph graph, int displacement) {
        int count = 0;
        for (AMD64AddressNode address : graph.getNodes().filter(AMD64AddressNode.class)) {
            if (address.getScale() == Stride.S4 && address.getDisplacement() == displacement && address.getIndex() instanceof ZeroExtendNode index && !(index.getValue() instanceof AddNode)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void testOffsetBecomesDisplacement() {
        assertTrue("a[x + 1] must be addressed with displacement 20", countFoldedAddresses(compileSnippet("storeNextSnippet"), INT_ARRAY_BASE + 4) == 1);
        assertTrue("a[x - 1] must be addressed with displacement 12", countFoldedAddresses(compileSnippet("storePreviousSnippet"), INT_ARRAY_BASE - 4) == 1);
        assertTrue("a[x + 2] must be addressed with displacement 24", countFoldedAddresses(compileSnippet("storeSumAboveOffsetSnippet"), INT_ARRAY_BASE + 8) == 1);
    }

    @Test
    public void testOffsetStaysInIndexWhenItMayWrap() {
        StructuredGraph graph = compileSnippet("storeGuardedSumSnippet");
        assertTrue("x + 1 may wrap, the offset must stay in the index", countFoldedAddresses(graph, INT_ARRAY_BASE + 4) == 0);
    }

    private static int countUnfoldedAddresses(StructuredGraph graph) {
        int count = 0;
        for (AMD64AddressNode address : graph.getNodes().filter(AMD64AddressNode.class)) {
            if (address.getIndex() instanceof ZeroExtendNode index && index.getValue() instanceof AddNode) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void testInsertionSortStore() {
        StructuredGraph graph = compileSnippet("insertionSortSnippet");
        assertTrue("the a[j + 1] store of the loop must be addressed with displacement 20", countFoldedAddresses(graph, INT_ARRAY_BASE + 4) >= 1);

        graph = compileSnippet("insertionStepSnippet");
        assertTrue("the a[j + 1] store of the loop must be addressed with displacement 20", countFoldedAddresses(graph, INT_ARRAY_BASE + 4) >= 1);
        assertTrue("no index of the loop may keep its offset", countUnfoldedAddresses(graph) == 0);
    }
}
