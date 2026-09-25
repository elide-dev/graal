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

import java.io.ByteArrayOutputStream;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * A context without an explicit engine (a bound engine), the first of the image run, against the
 * shared pre-initialized layer: the results are correct. The bound engine adopts the
 * pre-initialized engine and its context, pre-initialized in the shared layer, so it uses the AOT
 * code too (the one context of a bound engine does not share the layer with others). It is the
 * first engine of the image run, so this class needs an image run of its own (see
 * {@link AOTTierNativeTestSupport}).
 */
public class AOTTierBoundEngineNativeTest {

    @Test
    public void boundEngineRunsCorrectly() {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").logHandler(log).build()) {
            for (int i = 0; i < 20; i++) {
                assertEquals(i + 1, context.parse(fixture("add1", "add:1")).execute(i).asInt());
            }
            assertEquals(42, context.parse(fixture("call", "call")).execute(0).asInt());
            assertEquals(5, context.parse(fixture("recurse", "recurse")).execute(5).asInt());
        }
        assertEquals(text(log), 1, enters(text(log), "add1"));
        assertEquals(text(log), 1, enters(text(log), "call"));
        assertEquals(text(log), 1, enters(text(log), "recurse"));
    }
}
