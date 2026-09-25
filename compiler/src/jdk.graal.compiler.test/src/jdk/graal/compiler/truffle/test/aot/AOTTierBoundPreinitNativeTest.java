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

import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.enters;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.fixture;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.text;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.unlinks;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Runs in an image pre-initialized without code sharing (see
 * {@code mx_substratevm.truffle_aot_tier_native_tests}): the AOT code belongs to the
 * pre-initialized context, which the first context of the image run adopts and patches. The
 * language stays single-context, and the AOT code folds context state, so it is only linked once
 * patching left that state unchanged:
 * <ul>
 * <li>"ctxstable" folds a context field that patching leaves unchanged: linked after patching, and
 * run from the first call.</li>
 * <li>"patchstate" folds a context field that {@code patchContext} sets: never linked, so the
 * interpreter returns "patched". Linked anyway, it would return "unpatched".</li>
 * <li>"add1" folds no context state: linked in the image.</li>
 * </ul>
 */
public class AOTTierBoundPreinitNativeTest {

    @Test
    public void aotCodeOfPreinitializedContextIsLinkedIfPatchingLeavesFoldedStateUnchanged() {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").logHandler(log).build()) {
            assertEquals("patched", context.parse(fixture("patchstate", "patchstate")).execute().asString());
            assertEquals(42, context.parse(fixture("ctxstable", "ctxstable")).execute().asInt());
            for (int i = 0; i < 20; i++) {
                assertEquals(i + 1, context.parse(fixture("add1", "add:1")).execute(i).asInt());
            }
        }
        String text = text(log);
        assertEquals(text, 1, unlinks(text, "patchstate", "state folded at build time changed when the context was patched: " + AOTTestLanguage.class.getName() + "$AOTTestContext.state"));
        assertEquals(text, 0, enters(text, "patchstate"));
        assertTrue(text, text.contains("[aot] link ctxstable: "));
        assertEquals(text, 1, enters(text, "ctxstable"));
        assertEquals(text, 1, enters(text, "add1"));
        assertFalse("the language of a context pre-initialized without code sharing must stay single-context", AOTTestLanguage.multipleContexts);
    }
}
