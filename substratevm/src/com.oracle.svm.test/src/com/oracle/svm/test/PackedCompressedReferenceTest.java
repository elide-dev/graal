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
package com.oracle.svm.test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import org.graalvm.word.impl.Word;
import org.junit.Test;

import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.config.ObjectLayout;

public class PackedCompressedReferenceTest {
    // Image-heap constants remain valid while their references are held in a raw packed word.
    private static final String FIRST = "first compressed reference";
    private static final String SECOND = "second compressed reference";

    @NeverInline("Keep the packed input unknown when compiling the reference conversion.")
    private static String unpackLowReference(long packed) {
        return (String) ReferenceAccess.singleton().uncompressReference(Word.unsigned(packed & 0xffff_ffffL));
    }

    @Test
    public void lowReferenceIgnoresUpperPackedBits() {
        assumeTrue(ObjectLayout.singleton().getReferenceSize() == Integer.BYTES);
        long first = ReferenceAccess.singleton().getCompressedRepresentation(FIRST).rawValue();
        long second = ReferenceAccess.singleton().getCompressedRepresentation(SECOND).rawValue();
        assertSame(FIRST, unpackLowReference(first));
        assertSame(FIRST, unpackLowReference((second << 32) | first));
        assertSame(SECOND, unpackLowReference((first << 32) | second));
        assertSame(FIRST, unpackLowReference(0xffff_ffff_0000_0000L | first));
        assertNull(unpackLowReference(0xffff_ffff_0000_0000L));
    }
}
