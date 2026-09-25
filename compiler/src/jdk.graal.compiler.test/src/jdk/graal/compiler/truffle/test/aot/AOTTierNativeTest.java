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

import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.enters;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.guestFrames;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.unlinks;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Runs only in an image built with:
 * <ul>
 * <li>{@code -Dpolyglot.image-build-time.PreinitializeContexts=AOTTierTestLanguage}</li>
 * <li>{@code -Dpolyglot.image-build-time.PreinitializeCodeSharing=true}</li>
 * <li>{@code -Dpolyglot.image-build-time.PreinitializeSources=AOTTierTestLanguage:<add1>,...,AOTTierTestLanguage:<call>}</li>
 * <li>{@code -Dpolyglot.image-build-time.AOTCompile=true -H:+TruffleAOTPrintSummary}</li>
 * <li>{@code --features=jdk.graal.compiler.truffle.test.aot.AOTTestBuildTimeInvalidationFeature}</li>
 * </ul>
 * See {@code mx_substratevm.truffle_aot_tier_native_tests}.
 * <p>
 * All tests share one explicit engine: only the first engine created in the process adopts the
 * pre-initialized engine, whose shared layer holds the call targets compiled at build time. Tests
 * that need that engine to have other options run in image runs of their own (see
 * {@link AOTTierNativeTestSupport}). Calls of a fixture count towards the AOT tier threshold across
 * tests, so each scenario has a fixture of its own.
 */
public class AOTTierNativeTest {

    static final ByteArrayOutputStream LOG = new ByteArrayOutputStream();

    /**
     * The AOT tier threshold of the engine, set at run time: different from the build-time value
     * (the default), and larger than the number of calls of any fixture in the other tests.
     */
    static final int AOT_TIER_THRESHOLD = 10;

    private static Engine engine;

    private static synchronized Engine engine() {
        if (engine == null) {
            engine = AOTTierNativeTestSupport.traceEngine(LOG, "engine.AOTTierThreshold", String.valueOf(AOT_TIER_THRESHOLD));
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
        return AOTTierNativeTestSupport.fixture(name, code);
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
        AOTTestLanguage.RecurseRoot.captureFrames = true;
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            assertEquals(5, context.parse(fixture("recurse", "recurse")).execute(5).asInt());
        } finally {
            AOTTestLanguage.RecurseRoot.captureFrames = false;
        }
        assertTrue(log(), log().contains("[aot] enter recurse"));
        assertEquals("executions of recurse in compiled code", 6, AOTTestLanguage.RecurseRoot.compiledExecutions - compiledBefore);
        /*
         * The caller frames of calls redirected from the shadow to the live target keep their call
         * nodes (guest stack trace locations): all frames but the innermost one have one. The six
         * recurse frames are followed by the frame of the embedder's call.
         */
        List<Boolean> haveCallNode = AOTTestLanguage.RecurseRoot.capturedFramesHaveCallNode;
        assertNotNull(haveCallNode);
        assertTrue("guest frames at the bottom of recurse(5): " + haveCallNode, haveCallNode.size() >= 6);
        assertFalse("the innermost frame has no call node: " + haveCallNode, haveCallNode.get(0));
        assertFalse("every caller frame has a call node: " + haveCallNode, haveCallNode.subList(1, haveCallNode.size()).contains(false));
    }

    /**
     * A guest exception thrown in AOT code does not unlink it (its exception profiles start
     * generic): the later throws run in AOT code too. The exception has the type, message and guest
     * stack trace it has when the same target runs in the interpreter (once the threshold unlinked
     * its AOT code).
     */
    @Test
    public void exceptionThroughAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value throwing = context.parse(fixture("throw", "throw"));
            int compiledBefore = AOTTestLanguage.ThrowRoot.compiledExecutions;
            List<String> aotFrames = null;
            for (int i = 0; i < 3; i++) {
                PolyglotException e = assertThrows(PolyglotException.class, throwing::execute);
                assertTrue(e.isGuestException());
                assertEquals(AOTTestLanguage.ThrowRoot.MESSAGE, e.getMessage());
                if (aotFrames == null) {
                    aotFrames = guestFrames(e);
                } else {
                    assertEquals(aotFrames, guestFrames(e));
                }
            }
            assertEquals(log(), 1, enters(log(), "throw"));
            assertEquals(log(), 0, unlinks(log(), "throw"));
            assertEquals("throws in AOT code", 3, AOTTestLanguage.ThrowRoot.compiledExecutions - compiledBefore);
            assertEquals(List.of("throw@throw.aot:0-5"), aotFrames);

            assertEquals(interpretedAfterThreshold(throwing, "throw", 3), aotFrames);
            assertEquals(log(), 1, unlinks(log(), "throw"));
        }
    }

    /**
     * Calls {@code f} (which throws) until the AOT tier threshold unlinks the AOT code of
     * {@code root}, once {@code callsBefore} calls were made; then once more, in the interpreter,
     * and returns the guest frames of that exception.
     */
    private static List<String> interpretedAfterThreshold(Value f, String root, int callsBefore, Object... args) {
        for (int i = callsBefore; i < AOT_TIER_THRESHOLD; i++) {
            assertThrows(PolyglotException.class, () -> f.execute(args));
        }
        assertEquals(log(), 1, unlinks(log(), root, "AOT tier threshold reached"));
        int compiled = AOTTestLanguage.ThrowRoot.compiledExecutions + AOTTestLanguage.ThrowCallRoot.compiledExecutions + AOTTestLanguage.DeoptRoot.compiledExecutions;
        PolyglotException e = assertThrows(PolyglotException.class, () -> f.execute(args));
        assertTrue(e.isGuestException());
        assertEquals("the interpreter ran", compiled, AOTTestLanguage.ThrowRoot.compiledExecutions + AOTTestLanguage.ThrowCallRoot.compiledExecutions + AOTTestLanguage.DeoptRoot.compiledExecutions);
        return guestFrames(e);
    }

    /**
     * An exception thrown by the AOT code of a callee crosses a call site in the AOT code of its
     * caller. Neither code is unlinked by it, and the guest stack trace is the interpreter's.
     */
    @Test
    public void exceptionCrossesCallSiteInAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value throwcall = context.parse(fixture("throwcall", "throwcall"));
            int callerBefore = AOTTestLanguage.ThrowCallRoot.compiledExecutions;
            int calleeBefore = AOTTestLanguage.ThrowRoot.compiledExecutions;
            List<String> aotFrames = null;
            for (int i = 0; i < 3; i++) {
                PolyglotException e = assertThrows(PolyglotException.class, throwcall::execute);
                assertTrue(e.isGuestException());
                assertEquals(AOTTestLanguage.ThrowRoot.MESSAGE, e.getMessage());
                if (aotFrames == null) {
                    aotFrames = guestFrames(e);
                } else {
                    assertEquals(aotFrames, guestFrames(e));
                }
            }
            assertEquals(log(), 1, enters(log(), "throwcall"));
            assertEquals(log(), 1, enters(log(), "thrower"));
            assertEquals(log(), 0, unlinks(log(), "throwcall"));
            assertEquals(log(), 0, unlinks(log(), "thrower"));
            assertEquals("caller runs in AOT code", 3, AOTTestLanguage.ThrowCallRoot.compiledExecutions - callerBefore);
            assertEquals("callee runs in AOT code", 3, AOTTestLanguage.ThrowRoot.compiledExecutions - calleeBefore);
            assertEquals(List.of("thrower@throwcall.aot:0-5", "throwcall@throwcall.aot:0-9"), aotFrames);

            assertEquals(interpretedAfterThreshold(throwcall, "throwcall", 3), aotFrames);
            assertEquals(log(), 1, unlinks(log(), "thrower", "AOT tier threshold reached"));
        }
    }

    /**
     * A transfer to the interpreter that does not invalidate deoptimizes the activation of AOT code
     * (its virtual frame is rematerialized), which completes in the deoptimization target; the code
     * stays linked. An exception thrown right after such a deoptimization has the interpreter's
     * message and guest stack trace.
     */
    @Test
    public void nonInvalidatingDeoptimizationKeepsAOTCodeLinked() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value deopt = context.parse(fixture("deopt", "deopt"));
            int compiledBefore = AOTTestLanguage.DeoptRoot.compiledExecutions;
            assertEquals(2, deopt.execute(1).asInt());
            assertEquals(3, deopt.execute(-3).asInt());
            assertEquals(4, deopt.execute(3).asInt());
            PolyglotException e = assertThrows(PolyglotException.class, () -> deopt.execute(-1));
            assertTrue(e.isGuestException());
            assertEquals(AOTTestLanguage.DeoptRoot.MESSAGE, e.getMessage());
            List<String> afterDeoptFrames = guestFrames(e);
            assertEquals(6, deopt.execute(5).asInt());
            assertEquals(log(), 1, enters(log(), "deopt"));
            assertEquals(log(), 0, unlinks(log(), "deopt"));
            assertEquals("every call entered AOT code", 5, AOTTestLanguage.DeoptRoot.compiledExecutions - compiledBefore);
            assertEquals(List.of("deopt@deopt.aot:0-5"), afterDeoptFrames);

            assertEquals(interpretedAfterThreshold(deopt, "deopt", 5, -1), afterDeoptFrames);
        }
    }

    /**
     * The profiles of "branch" are its own (not the DSL's), uninitialized when the AOT code was
     * compiled: taking any profiled path does not unlink the code, because the shadow's profiles
     * start disabled.
     */
    @Test
    public void uninitializedProfilesDoNotUnlinkAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        int compiledBefore = AOTTestLanguage.BranchRoot.compiledExecutions;
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value branch = context.parse(fixture("branch", "branch"));
            assertEquals(2, branch.execute(1).asInt());
            assertEquals(3, branch.execute(-3).asInt());
            assertEquals(2000, branch.execute(2000).asInt());
            assertEquals(8, branch.execute(7).asInt());
        }
        assertEquals(log(), 1, enters(log(), "branch"));
        assertEquals(log(), 0, unlinks(log(), "branch"));
        assertEquals("executions of branch in compiled code", 4, AOTTestLanguage.BranchRoot.compiledExecutions - compiledBefore);
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

    /**
     * The AOT tier threshold is the engine's option at run time, not the value when the image was
     * built: the AOT code of "add3" is unlinked by the threshold-th call.
     */
    @Test
    public void runTimeAOTTierThresholdUnlinks() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value add3 = context.parse(fixture("add3", "add:3"));
            for (int i = 1; i < AOT_TIER_THRESHOLD; i++) {
                assertEquals(i + 3, add3.execute(i).asInt());
            }
            assertTrue(log(), log().contains("[aot] enter add3"));
            assertFalse(log(), log().contains("[aot] unlink add3"));
            assertEquals(AOT_TIER_THRESHOLD + 3, add3.execute(AOT_TIER_THRESHOLD).asInt());
            assertTrue(log(), log().contains("[aot] unlink add3: AOT tier threshold reached"));
            /* Now in the interpreter, on the live target's AST. */
            assertEquals(4, add3.execute(1).asInt());
        }
    }

    /**
     * The AOT code of "assume" folds the check of {@link AOTTestLanguage#STABLE}: the assumption was
     * registered with the code when it was compiled, at build time, and that dependency is in the
     * image heap. Invalidating the assumption at run time unlinks the code (and deoptimizes its
     * activations), so the next call runs in the interpreter and sees the invalid assumption.
     */
    @Test
    public void assumptionRegisteredAtBuildTimeInvalidatesAOTCode() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value f = context.parse(fixture("assume", "assume"));
            assertEquals(1, f.execute().asInt());
            assertTrue(log(), log().contains("[aot] enter assume"));
            assertFalse(log(), log().contains("[aot] unlink assume"));
            AOTTestLanguage.invalidateStable();
            assertTrue(log(), log().contains("[aot] unlink assume: aot-test"));
            assertEquals(2, f.execute().asInt());
        }
    }

    /**
     * An int overflow in the AOT code of "add4" (doInt, rewriteOn ArithmeticException) is a
     * deoptimization out of the AOT code: the activation completes in the deoptimization targets
     * with the next specialization, doIntOverflow, and the code is unlinked. The results stay
     * correct, also for the calls after it, in the interpreter.
     */
    @Test
    public void exclusionDeoptKeepsCorrectResult() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value add4 = context.parse(fixture("add4", "add:4"));
            assertEquals(5, add4.execute(1).asInt());
            assertTrue(log(), log().contains("[aot] enter add4"));
            assertEquals(Integer.MAX_VALUE + 4L, add4.execute(Integer.MAX_VALUE).asLong());
            /* The overflow was an invalidating deoptimization out of the AOT code. */
            assertTrue(log(), log().contains("[aot] unlink add4: invalidated"));
            assertEquals(6, add4.execute(2).asInt());
            assertEquals("x4", add4.execute("x").asString());
            assertEquals(Integer.MAX_VALUE + 4L, add4.execute(Integer.MAX_VALUE).asLong());
        }
    }

    /**
     * The AOT code of "buildinval" was compiled against {@link AOTTestLanguage#BUILD_STABLE}, which
     * the image build invalidated afterwards (AOTTestBuildTimeInvalidationFeature, after the
     * analysis): the build dropped the code, so the root runs in the interpreter.
     */
    @Test
    public void droppedAtBuildTimeRunsInterpreted() {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            assertEquals(7, context.parse(fixture("buildinval", "buildinval")).execute().asInt());
        }
        assertFalse(log(), log().contains("[aot] enter buildinval"));
        /* The image heap has the assumption as the build left it. */
        assertFalse(AOTTestLanguage.BUILD_STABLE.isValid());
    }

    /**
     * Invalidating an assumption deoptimizes the activations of the AOT code on all threads: the
     * loop of "spin", in AOT code on another thread, stopped at a safepoint in the loop, continues in
     * the deoptimization target, which sees the invalid assumption.
     */
    @Test
    public void invalidationDeoptimizesActivationsOnOtherThreads() throws InterruptedException {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value spin = context.parse(fixture("spin", "spin"));
            AtomicReference<Object> result = new AtomicReference<>();
            Thread spinner = new Thread(() -> {
                try {
                    result.set(spin.execute().asLong());
                } catch (Throwable t) {
                    result.set(t);
                }
            });
            spinner.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!AOTTestLanguage.SpinRoot.started && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue("spin started", AOTTestLanguage.SpinRoot.started);
            AOTTestLanguage.invalidateSpinStable();
            spinner.join(TimeUnit.SECONDS.toMillis(30));
            /* Not deoptimized: end the loop, so that the test fails rather than hangs. */
            AOTTestLanguage.SpinRoot.stop = true;
            spinner.join();
            assertEquals("result of spin (-1: deoptimized; otherwise iterations until stopped)", -1L, result.get());
        }
        assertTrue(log(), log().contains("[aot] enter spin"));
        assertTrue(log(), log().contains("[aot] unlink spin: aot-spin-test"));
    }

    /**
     * Invalidation deoptimizes an activation of AOT code whose thread is stopped at a Truffle
     * safepoint poll in it: a thread-local action holds the thread of "pollspin" at the poll in its
     * loop while the assumption the code folds is invalidated.
     */
    @Test
    public void invalidationDeoptimizesActivationParkedAtTruffleSafepoint() throws InterruptedException {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value spin = context.parse(fixture("pollspin", "pollspin"));
            AtomicReference<Object> result = new AtomicReference<>();
            Thread spinner = new Thread(() -> {
                try {
                    result.set(spin.execute().asLong());
                } catch (Throwable t) {
                    result.set(t);
                }
            });
            spinner.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!AOTTestLanguage.PollSpinRoot.started && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue("pollspin started", AOTTestLanguage.PollSpinRoot.started);
            assertTrue("pollspin runs in AOT code", AOTTestLanguage.PollSpinRoot.startedInCompiledCode);
            CountDownLatch parked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            context.enter();
            try {
                AOTTestLanguage.submitThreadLocal(spinner, new ThreadLocalAction(false, false) {
                    @Override
                    protected void perform(Access access) {
                        parked.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                    }
                });
            } finally {
                context.leave();
            }
            try {
                assertTrue("pollspin stopped at the safepoint poll", parked.await(30, TimeUnit.SECONDS));
                AOTTestLanguage.invalidatePollStable();
            } finally {
                release.countDown();
            }
            spinner.join(TimeUnit.SECONDS.toMillis(30));
            /* Not deoptimized: end the loop, so that the test fails rather than hangs. */
            AOTTestLanguage.PollSpinRoot.stop = true;
            spinner.join(TimeUnit.SECONDS.toMillis(30));
            assertEquals("result of pollspin (-1: deoptimized; otherwise iterations until stopped)", -1L, result.get());
        }
        assertEquals(log(), 1, enters(log(), "pollspin"));
        assertEquals(log(), 1, unlinks(log(), "pollspin", "aot-poll-test"));
    }

    /**
     * Interrupting a context stops its guest code running in a loop of AOT tier code: the code
     * polls Truffle safepoints like runtime compiled code.
     */
    @Test
    public void interruptStopsLoopInAOTCode() throws Exception {
        TruffleTestAssumptions.assumeAOT();
        try (Context context = Context.newBuilder().engine(engine()).build()) {
            Value loop = context.parse(fixture("loop", "loop"));
            AtomicReference<Object> result = new AtomicReference<>();
            Thread looper = new Thread(() -> {
                try {
                    result.set(loop.execute().asLong());
                } catch (Throwable t) {
                    result.set(t);
                }
            });
            looper.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!AOTTestLanguage.LoopRoot.started && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue("loop started", AOTTestLanguage.LoopRoot.started);
            assertTrue("loop runs in AOT code", AOTTestLanguage.LoopRoot.startedInCompiledCode);
            try {
                context.interrupt(Duration.ofSeconds(30));
            } finally {
                /* Not interrupted: end the loop, so that the test fails rather than hangs. */
                AOTTestLanguage.LoopRoot.stop = true;
                looper.join(TimeUnit.SECONDS.toMillis(30));
            }
            assertTrue(String.valueOf(result.get()), result.get() instanceof PolyglotException e && e.isInterrupted());
        }
        assertEquals(log(), 1, enters(log(), "loop"));
    }

}
