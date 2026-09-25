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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.BiConsumer;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * The image run with {@code engine.AOTTierThreshold=1} (and {@code engine.TraceAOT}): the results
 * are the same as with any other threshold, and every AOT tier code is entered once and unlinked by
 * the threshold exactly once. The engine adopts the pre-initialized engine, so this class needs an
 * image run of its own (see {@link AOTTierNativeTestSupport}).
 */
public class AOTTierThresholdOneNativeTest {

    private static final int CALLS = 50;

    /** A fixture, the roots its calls enter the AOT code of, and one call with its expected result. */
    private record Case(String fixture, String code, List<String> roots, BiConsumer<Value, Integer> call) {
    }

    private static final List<Case> CASES = List.of(
                    new Case("add2", "add:2", List.of("add2"), (f, i) -> assertEquals(i + 2, f.execute(i).asInt())),
                    new Case("add3", "add:3", List.of("add3"), (f, i) -> assertEquals(i + 3, f.execute(i).asInt())),
                    new Case("add4", "add:4", List.of("add4"), (f, i) -> assertEquals(i + 4, f.execute(i).asInt())),
                    new Case("throw", "throw", List.of("throw"), (f, i) -> assertEquals(AOTTestLanguage.ThrowRoot.MESSAGE, assertThrows(PolyglotException.class, f::execute).getMessage())),
                    new Case("throwcall", "throwcall", List.of("throwcall", "thrower"),
                                    (f, i) -> assertEquals(AOTTestLanguage.ThrowRoot.MESSAGE, assertThrows(PolyglotException.class, f::execute).getMessage())),
                    new Case("recurse", "recurse", List.of("recurse"), (f, i) -> assertEquals(i % 7, f.execute(i % 7).asInt())),
                    new Case("drecurse", "drecurse", List.of("drecurse"), (f, i) -> assertEquals(i % 7, f.execute(i % 7).asInt())),
                    new Case("call", "call", List.of("call"), (f, i) -> assertEquals(42, f.execute(i).asInt())),
                    new Case("deopt", "deopt", List.of("deopt"), (f, i) -> {
                        if (i % 3 == 1) {
                            assertEquals(AOTTestLanguage.DeoptRoot.MESSAGE, assertThrows(PolyglotException.class, () -> f.execute(-1)).getMessage());
                        } else {
                            assertEquals(i % 3 == 2 ? i : i + 1, f.execute(i % 3 == 2 ? -i : i).asInt());
                        }
                    }),
                    new Case("branch", "branch", List.of("branch"), (f, i) -> assertEquals(i % 2 == 0 ? i + 1 : i, f.execute(i % 2 == 0 ? i : -i).asInt())),
                    new Case("assume", "assume", List.of("assume"), (f, i) -> assertEquals(1, f.execute().asInt())),
                    new Case("instr", "instr", List.of("instr"), (f, i) -> assertEquals(i + 100, f.execute(i).asInt())),
                    new Case("assumeloop", "assumeloop", List.of("assumeloop"), (f, i) -> assertEquals((long) i, f.execute(i).asLong())));

    /**
     * One engine, the one that adopts the pre-initialized engine: {@code add1} as in the plan,
     * then the other fixtures.
     */
    @Test
    public void thresholdOneUnlinksOnce() {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Engine engine = AOTTierNativeTestSupport.traceEngine(log, "engine.AOTTierThreshold", "1"); Context context = Context.newBuilder().engine(engine).build()) {
            Value add = context.parse(fixture("add1", "add:1"));
            for (int i = 0; i < CALLS; i++) {
                assertEquals(i + 1, add.execute(i).asInt());
            }
            assertEquals(text(log), 1, enters(text(log), "add1"));
            assertEquals(text(log), 1, unlinks(text(log), "add1"));
            assertEquals(text(log), 1, unlinks(text(log), "add1", "AOT tier threshold reached"));

            for (Case c : CASES) {
                Value f = context.parse(fixture(c.fixture(), c.code()));
                for (int i = 0; i < CALLS; i++) {
                    c.call().accept(f, i);
                }
            }
        }
        String trace = text(log);
        for (Case c : CASES) {
            for (String root : c.roots()) {
                assertEquals(root + ": " + trace, 1, enters(trace, root));
                assertEquals(root + ": " + trace, 1, unlinks(trace, root));
                assertEquals(root + ": " + trace, 1, unlinks(trace, root, "AOT tier threshold reached"));
                int enter = trace.indexOf("[aot] enter " + root + "\n");
                assertTrue(root + " entered before unlinked: " + trace, enter >= 0 && enter < trace.indexOf("[aot] unlink " + root + ":"));
            }
        }
    }
}
