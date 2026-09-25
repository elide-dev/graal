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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.AfterClass;
import org.junit.Before;
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
 * <p>
 * All tests share one explicit engine: only the first engine created in the process adopts the
 * pre-initialized engine, whose shared layer holds the call targets compiled at build time.
 */
public class AOTTierNativeTest {

    static final ByteArrayOutputStream LOG = new ByteArrayOutputStream();

    private static Engine engine;

    private static synchronized Engine engine() {
        if (engine == null) {
            engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").logHandler(LOG).build();
        }
        return engine;
    }

    @AfterClass
    public static synchronized void closeEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Before
    public void clearLog() {
        synchronized (LOG) {
            LOG.reset();
        }
    }

    private static String log() {
        synchronized (LOG) {
            return LOG.toString();
        }
    }

    /**
     * A source equal to the one pre-initialization parsed for the fixture {@code name} (same
     * content, same name: the fixture file name), so that parsing it hits the pre-initialized call
     * target in the shared layer's source cache.
     */
    private static Source fixture(String name, String code) {
        return Source.newBuilder(AOTTestLanguage.ID, code, name + ".aot").buildLiteral();
    }

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
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            /* parse, not eval: eval would call the root without the argument it reads. */
            assertEquals(3, context.parse(AOTTestLanguage.ID, "add:1").execute(2).asInt());
        }
    }

    @Test
    public void aotCodeRunsFromFirstCall() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            assertEquals(3, context.parse(fixture("add1", "add:1")).execute(2).asInt());
        }
        assertTrue(log(), log().contains("[aot] enter add1"));
    }

    /**
     * "recurse" calls itself through {@code getCallTarget()}, which in the compiled shadow is the
     * shadow target: such calls must be dispatched to the live target, i.e., to its AOT code. So all
     * six calls of {@code recurse(5)} run compiled; if the recursive calls ran the shadow in the
     * interpreter, only the first would.
     */
    @Test
    public void recursionThroughShadowEntersAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        int compiledBefore = AOTTestLanguage.RecurseRoot.compiledExecutions;
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            assertEquals(5, context.parse(fixture("recurse", "recurse")).execute(5).asInt());
        }
        assertTrue(log(), log().contains("[aot] enter recurse"));
        assertEquals("executions of recurse in compiled code", 6, AOTTestLanguage.RecurseRoot.compiledExecutions - compiledBefore);
    }

    @Test
    public void exceptionFromAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value throwing = context.parse(fixture("throw", "throw"));
            PolyglotException e = assertThrows(PolyglotException.class, throwing::execute);
            assertTrue(e.isGuestException());
            assertEquals(AOTTestLanguage.ThrowRoot.MESSAGE, e.getMessage());
        }
        assertTrue(log(), log().contains("[aot] enter throw"));
    }

    /** AOT code of "call" calls its live callee, which runs in the interpreter. */
    @Test
    public void aotCodeCallsLiveCallee() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            assertEquals(42, context.parse(fixture("call", "call")).execute(0).asInt());
        }
        assertTrue(log(), log().contains("[aot] enter call"));
    }

    /**
     * No specialization of the AOT prepared add node accepts a double, so the AOT code transfers to
     * the interpreter and invalidates: the frame is deoptimized (the activation completes in the
     * deoptimization targets, on the shadow's nodes) and the code is unlinked, so the next call
     * runs in the interpreter.
     */
    @Test
    public void invalidatingDeoptimizationUnlinksAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value add2 = context.parse(fixture("add2", "add:2"));
            assertEquals(4, add2.execute(2).asInt());
            assertTrue(log(), log().contains("[aot] enter add2"));
            assertFalse(log(), log().contains("[aot] unlink add2"));
            PolyglotException e = assertThrows(PolyglotException.class, () -> add2.execute(2.5));
            assertTrue(e.toString(), e.isInternalError());
            assertTrue(log(), log().contains("[aot] unlink add2: invalidated"));
            assertEquals(4, add2.execute(2).asInt());
        }
    }
}
