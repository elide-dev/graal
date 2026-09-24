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
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Runs only in an image built with:
 * <ul>
 * <li>{@code -Dpolyglot.image-build-time.PreinitializeContexts=AOTTierTestLanguage}</li>
 * <li>{@code -Dpolyglot.image-build-time.PreinitializeCodeSharing=true}</li>
 * <li>{@code -Dpolyglot.image-build-time.PreinitializeSources=AOTTierTestLanguage:<add1>,...,AOTTierTestLanguage:<call>}</li>
 * <li>{@code -Dpolyglot.image-build-time.AOTCompile=true -H:+TruffleAOTPrintSummary}</li>
 * </ul>
 * See {@code mx_substratevm.truffle_aot_tier_native_tests}.
 */
public class AOTTierNativeTest {

    /**
     * The shadow of "call" directly calls a live callee that was executed during pre-initialization.
     * Build-time partial evaluation must neither prepare nor inline it; not being prepared implies not
     * being inlined (TruffleTierContext.createInlineContext).
     */
    @Test
    public void liveCalleeNotPreparedAtBuildTime() {
        TruffleTestAssumptions.assumeAOT();
        AOTTestLanguage.CalleeRoot callee = AOTTestLanguage.lastCallee;
        assertNotNull("the \"call\" fixture was parsed during pre-initialization", callee);
        assertFalse("live callee prepared for compilation at build time", callee.preparedAtBuildTime);
    }

    @Test
    public void addStillWorks() {
        TruffleTestAssumptions.assumeAOT();
        try (Engine engine = Engine.create(); Context context = Context.newBuilder().engine(engine).build()) {
            /* parse, not eval: eval would call the root without the argument it reads. */
            assertEquals(3, context.parse(AOTTestLanguage.ID, "add:1").execute(2).asInt());
        }
    }
}
