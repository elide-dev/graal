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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayOutputStream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * The pre-initialized context refused at run time: with {@code engine.UsePreInitializedContext=false}
 * a fresh layer parses the fixture again, so its call target has no AOT code. The engine that
 * refuses must be the first engine of the image run, or the refusal is not what keeps it from the
 * AOT code, so this class needs an image run of its own (see {@link AOTTierNativeTestSupport}).
 */
public class AOTTierPreinitRefusedNativeTest {

    /**
     * The refusing engine runs in the interpreter; it leaves the pre-initialized engine to the next
     * engine, which uses the AOT code: so it is the refusal that kept the first engine from it.
     */
    @Test
    public void incompatibleContextDoesNotUseAOT() {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream refusing = new ByteArrayOutputStream();
        try (Engine engine = AOTTierNativeTestSupport.traceEngine(refusing, "engine.UsePreInitializedContext", "false"); Context context = Context.newBuilder().engine(engine).build()) {
            assertEquals(3, context.parse(fixture("add1", "add:1")).execute(2).asInt());
            assertEquals(4, context.parse(fixture("add1", "add:1")).execute(3).asInt());
        }
        assertFalse(text(refusing), text(refusing).contains("[aot] enter"));

        ByteArrayOutputStream adopting = new ByteArrayOutputStream();
        try (Engine engine = AOTTierNativeTestSupport.traceEngine(adopting); Context context = Context.newBuilder().engine(engine).build()) {
            assertEquals(3, context.parse(fixture("add1", "add:1")).execute(2).asInt());
        }
        assertEquals(text(adopting), 1, enters(text(adopting), "add1"));
    }
}
