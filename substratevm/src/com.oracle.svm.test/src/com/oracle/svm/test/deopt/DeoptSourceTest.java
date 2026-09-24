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

import org.graalvm.nativeimage.c.function.CodePointer;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.core.deopt.ImageCodeInstalledCodeLookup;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.test.NativeImageBuildArgs;

import jdk.graal.compiler.api.directives.GraalDirectives;

/**
 * Checks that a builder-compiled method registered as a deoptimization source can deoptimize in a
 * frame inlined into it, and that execution resumes in the deoptimization target of the inlined
 * method. Requires {@link DeoptSourceTestFeature}, which pulls runtime compilation (and with it
 * deoptimization support) into the image. {@code mx native-unittest} therefore builds a dedicated
 * image for this test (see {@link NativeImageBuildArgs}); it runs when the test is selected
 * explicitly and in the {@code all_native_unittests} gate. On the JVM, the test is skipped.
 */
@NativeImageBuildArgs({
                "--features=com.oracle.svm.test.deopt.DeoptSourceTestFeature",
                "--add-exports=org.graalvm.nativeimage.base/com.oracle.svm.common.meta=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.code=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.deopt=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.feature=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.graal.nodes=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.graal.hosted.runtimecompilation=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted.code=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.guest.staging/com.oracle.svm.guest.staging.core.graal=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.shared/com.oracle.svm.shared=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.pointsto/com.oracle.graal.pointsto.meta=ALL-UNNAMED",
                "--add-exports=jdk.graal.compiler/jdk.graal.compiler.core.common=ALL-UNNAMED",
                "--add-exports=jdk.graal.compiler/jdk.graal.compiler.nodes=ALL-UNNAMED",
                "--add-exports=jdk.graal.compiler/jdk.graal.compiler.nodes.graphbuilderconf=ALL-UNNAMED",
                "--add-exports=jdk.graal.compiler/jdk.graal.compiler.phases.util=ALL-UNNAMED",
                "--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED",
})
public class DeoptSourceTest {
    static int deoptCount;
    static int invalidateCount;
    static int helperEntries;
    static boolean resumedInDeoptTarget;

    /**
     * In the copy of {@link #helper} inlined into the deopt source {@link #compute}, this call is
     * replaced by a deoptimization. Everywhere else it is an ordinary call.
     */
    static void deoptHere() {
        deoptCount++;
    }

    /**
     * In the copy of {@link #helper2} inlined into the deopt source {@link #computeInvalidating},
     * this call is replaced by an invalidating deoptimization. Everywhere else it is an ordinary
     * call.
     */
    static void invalidateHere() {
        invalidateCount++;
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

    /** Not inlined: the deopt source has no inlined frame for it. */
    static void thrower(int x) {
        if (x > 0) {
            throw new IllegalStateException();
        }
    }

    static int helperCatching(int x) {
        int unusedInHandler = x + 7;
        int usedOnlyInHandler = x * 3;
        try {
            thrower(unusedInHandler);
        } catch (IllegalStateException e) {
            /*
             * No state split precedes the deopt point in the handler, so the deopt resumes with the
             * exception-dispatch state of the invoke of thrower: it rethrows the exception in the
             * deopt target of helperCatching, whose handler then runs.
             */
            deoptHere(); // deopt source: deoptimizes; deopt target: counts
            resumedInDeoptTarget = isDeoptTarget();
            return usedOnlyInHandler + 1;
        }
        return 0;
    }

    static int computeCatching(int x) {
        int a = helperCatching(x); // inlined into the deopt source by the feature
        return a + GraalDirectives.opaque(1);
    }

    static int helper2(int x) {
        /* The store is a state split: the deoptimization resumes right after it. */
        helperEntries++;
        int y = x + 3;
        invalidateHere(); // deopt source: DeoptimizeNode(InvalidateReprofile, TransferToInterpreter)
        resumedInDeoptTarget = isDeoptTarget();
        return y;
    }

    static int computeInvalidating(int x) {
        return helper2(x) + GraalDirectives.opaque(0); // helper2 is inlined by the feature
    }

    @Before
    public void reset() {
        Assume.assumeTrue("DeoptSourceTestFeature is not part of the image", isFeatureActive());
        deoptCount = 0;
        invalidateCount = 0;
        helperEntries = 0;
        resumedInDeoptTarget = false;
    }

    @Test
    public void deoptimizesInlinedFrameOfSource() {
        Assert.assertEquals(2 * 20 + 1 + 1, compute(20));
        Assert.assertEquals("execution must have resumed in the deopt target of helper", 1, deoptCount);
        Assert.assertTrue("the rest of helper must have run in its deopt target", resumedInDeoptTarget);
        Assert.assertEquals("the deopt must resume inside helper, not re-execute it", 1, helperEntries);
    }

    @Test
    public void deoptimizesInExceptionHandlerOfInlinedFrame() {
        Assert.assertEquals(20 * 3 + 1 + 1, computeCatching(20));
        Assert.assertEquals("execution must have resumed in the deopt target of helperCatching", 1, deoptCount);
        Assert.assertTrue("the handler must have run in the deopt target", resumedInDeoptTarget);
    }

    @Test
    public void invalidatingDeoptInDeoptSourceCallsLookup() {
        DeoptSourceTestLookup.TestInstalledCode.invalidations = 0;
        DeoptSourceTestLookup.TestInstalledCode.reprofiles = 0;
        Assert.assertEquals(10, computeInvalidating(7));
        Assert.assertEquals("execution must have resumed in the deopt target of helper2", 1, invalidateCount);
        Assert.assertTrue("the rest of helper2 must have run in its deopt target", resumedInDeoptTarget);
        Assert.assertEquals("the deopt must resume inside helper2, not re-execute it", 1, helperEntries);
        Assert.assertEquals("image-code lookup must have resolved the frame and invalidated it", 1, DeoptSourceTestLookup.TestInstalledCode.invalidations);
        Assert.assertEquals("the installed code must have been asked to reprofile", 1, DeoptSourceTestLookup.TestInstalledCode.reprofiles);
    }

    @NeverInline("The return address must be in the frame of the caller.")
    static CodePointer callerIP() {
        return KnownIntrinsics.readReturnAddress();
    }

    @Test
    public void lookupIgnoresFramesOfOtherMethods() {
        Assert.assertNull("the test lookup must only own frames of computeInvalidating", ImageCodeInstalledCodeLookup.lookupOrNull(callerIP()));
    }
}
