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
 * The pre-initialized context rejected because the first context's options are incompatible with
 * it ({@code AOTTierTestLanguage.Mode}): that context parses the fixture in a fresh layer, so it
 * runs without AOT code, and it leaves the pre-initialized context to the next compatible context.
 * Its engine must be the one that adopts the pre-initialized engine, so this class needs an image
 * run of its own (see {@link AOTTierNativeTestSupport}).
 */
public class AOTTierIncompatibleOptionsNativeTest {

    @Test
    public void incompatibleOptionsDoNotUseAOT() {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Engine engine = AOTTierNativeTestSupport.traceEngine(log)) {
            try (Context context = Context.newBuilder().engine(engine).allowExperimentalOptions(true).option(AOTTestLanguage.ID + ".Mode", "other").build()) {
                assertEquals(3, context.parse(fixture("add1", "add:1")).execute(2).asInt());
            }
            assertFalse(text(log), text(log).contains("[aot] enter"));
            /* The incompatible context left the pre-initialized context to a compatible one. */
            try (Context context = Context.newBuilder().engine(engine).build()) {
                assertEquals(3, context.parse(fixture("add1", "add:1")).execute(2).asInt());
            }
            assertEquals(text(log), 1, enters(text(log), "add1"));
        }
    }
}
