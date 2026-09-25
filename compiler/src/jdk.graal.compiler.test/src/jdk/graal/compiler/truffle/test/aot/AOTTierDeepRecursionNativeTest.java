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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Recursion in AOT tier code deep enough to overflow the stack. The threshold must not unlink the
 * code while it recurses, so the engine that adopts the pre-initialized engine has the largest
 * {@code engine.AOTTierThreshold}, and this class needs an image run of its own (see
 * {@link AOTTierNativeTestSupport}).
 */
public class AOTTierDeepRecursionNativeTest {

    @Test
    public void deepRecursionInAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Engine engine = AOTTierNativeTestSupport.traceEngine(log, "engine.AOTTierThreshold", String.valueOf(Integer.MAX_VALUE));
                        Context context = Context.newBuilder().engine(engine).build()) {
            Value f = context.parse(fixture("recurse", "recurse"));
            int compiled = AOTTestLanguage.RecurseRoot.compiledExecutions;
            assertEquals(1000, f.execute(1000).asInt());
            assertEquals("all of recurse(1000) runs in AOT code", 1001, AOTTestLanguage.RecurseRoot.compiledExecutions - compiled);
            try {
                f.execute(100_000_000);
                fail();
            } catch (PolyglotException e) {
                assertTrue(e.toString(), e.isResourceExhausted() || e.getMessage().contains("StackOverflow"));
            }
            compiled = AOTTestLanguage.RecurseRoot.compiledExecutions;
            assertEquals(10, f.execute(10).asInt());   // still usable after the overflow
            assertEquals("still in AOT code after the overflow", 11, AOTTestLanguage.RecurseRoot.compiledExecutions - compiled);
        }
        assertEquals(text(log), 1, enters(text(log), "recurse"));
        assertEquals("the overflow does not unlink the code: " + text(log), 0, unlinks(text(log), "recurse"));
    }
}
