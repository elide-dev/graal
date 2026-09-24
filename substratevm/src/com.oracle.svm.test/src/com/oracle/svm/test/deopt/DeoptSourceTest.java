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
package com.oracle.svm.test.deopt;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import jdk.graal.compiler.api.directives.GraalDirectives;

/**
 * Checks that a builder-compiled method registered as a deoptimization source can deoptimize in a
 * frame inlined into it, and that execution resumes in the deoptimization target of the inlined
 * method. Requires {@link DeoptSourceTestFeature}, which is passed on the command line because it
 * pulls runtime compilation (and with it deoptimization support) into the image:
 *
 * <pre>
 * mx native-unittest com.oracle.svm.test.deopt.DeoptSourceTest --build-args --features=com.oracle.svm.test.deopt.DeoptSourceTestFeature
 * </pre>
 */
public class DeoptSourceTest {
    static int deoptCount;
    static int helperEntries;
    static boolean resumedInDeoptTarget;

    /**
     * In the copy of {@link #helper} inlined into the deopt source {@link #compute}, this call is
     * replaced by a deoptimization. Everywhere else it is an ordinary call.
     */
    static void deoptHere() {
        deoptCount++;
    }

    /** Folded by the feature: {@code true} only when compiled as part of a deopt target. */
    static boolean isDeoptTarget() {
        return false;
    }

    /** Folded to {@code true} by the feature. */
    static boolean isFeatureActive() {
        return false;
    }

    static int helper(int x) {
        /* The store is a state split: the deoptimization resumes right after it. */
        helperEntries++;
        int y = x * 2;
        deoptHere(); // deopt source: deoptimizes; deopt target: counts
        resumedInDeoptTarget = isDeoptTarget();
        return y + 1;
    }

    static int compute(int x) {
        int a = helper(x); // inlined into the deopt source by the feature
        return a + GraalDirectives.opaque(1);
    }

    @Test
    public void deoptimizesInlinedFrameOfSource() {
        Assume.assumeTrue("DeoptSourceTestFeature is not part of the image", isFeatureActive());
        deoptCount = 0;
        helperEntries = 0;
        resumedInDeoptTarget = false;
        Assert.assertEquals(2 * 20 + 1 + 1, compute(20));
        Assert.assertEquals("execution must have resumed in the deopt target of helper", 1, deoptCount);
        Assert.assertTrue("the rest of helper must have run in its deopt target", resumedInDeoptTarget);
        Assert.assertEquals("the deopt must resume inside helper, not re-execute it", 1, helperEntries);
    }
}
