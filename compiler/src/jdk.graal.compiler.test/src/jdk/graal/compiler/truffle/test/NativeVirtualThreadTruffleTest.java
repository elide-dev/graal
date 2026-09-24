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
package jdk.graal.compiler.truffle.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextPolicy;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;

/**
 * Native Image only: Truffle guest code with runtime-compiled frames on continuation-backed virtual
 * threads. Build with -H:+VMContinuationsWithRuntimeCompilation.
 */
public class NativeVirtualThreadTruffleTest extends TestWithSynchronousCompiling {

    static final String ID = "NativeVirtualThreadTruffleTestLang";

    @Registration(id = ID, name = ID, contextPolicy = ContextPolicy.EXCLUSIVE)
    public static class VTLang extends TruffleLanguage<VTLang.LangContext> {
        static final ContextReference<LangContext> CONTEXT = ContextReference.create(VTLang.class);
        static final LanguageReference<VTLang> LANGUAGE = LanguageReference.create(VTLang.class);

        static final class LangContext {
            final int id;

            LangContext(int id) {
                this.id = id;
            }
        }

        private static int nextId;

        @Override
        protected synchronized LangContext createContext(Env env) {
            return new LangContext(nextId++);
        }

        @Override
        protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
            return true;
        }
    }

    /** Returns 1 if the context seen after a carrier-migrating yield is the one seen before it. */
    static final class ContextAcrossYieldRoot extends RootNode {
        ContextAcrossYieldRoot(VTLang lang) {
            super(lang);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            VTLang.LangContext before = VTLang.CONTEXT.get(this);
            yieldNow();
            VTLang.LangContext after = VTLang.CONTEXT.get(this);
            return before == after ? after.id : -1;
        }
    }

    /**
     * Parks once, then spins with safepoint polls and periodic yields until interrupted, or until
     * {@code stop[0]} is set (used only for the warmup call before compilation).
     */
    static final class SpinRoot extends RootNode {
        SpinRoot(VTLang lang) {
            super(lang);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Semaphore gate = (Semaphore) frame.getArguments()[0];
            CountDownLatch parked = (CountDownLatch) frame.getArguments()[1];
            boolean[] stop = (boolean[]) frame.getArguments()[2];
            parkOnce(gate, parked);
            for (int i = 0; !stop[0]; i++) {
                TruffleSafepoint.poll(this);
                if ((i & 0xFF) == 0) {
                    yieldNow();
                }
            }
            return 0;
        }
    }

    @TruffleBoundary
    static void yieldNow() {
        Thread.yield();
    }

    @TruffleBoundary
    static void parkOnce(Semaphore gate, CountDownLatch parked) {
        parked.countDown();
        gate.acquireUninterruptibly();
    }

    @Before
    public void assumeRealVirtualThreads() {
        Assume.assumeTrue("Native Image only", ImageInfo.inImageRuntimeCode());
        assertEquals("continuations must be enabled (-H:+VMContinuationsWithRuntimeCompilation)",
                        "java.lang.VirtualThread", Thread.ofVirtual().unstarted(() -> {
                        }).getClass().getName());
    }

    /** Uses TestWithSynchronousCompiling's builder (synchronous compilation, low thresholds). */
    Context newContext() {
        Context c = newContextBuilder().option("engine.WarnVirtualThreadSupport", "false").build();
        c.initialize(ID);
        return c;
    }

    static OptimizedCallTarget compiled(Context ctx, java.util.function.Function<VTLang, RootNode> factory, Object... warmupArgs) {
        ctx.enter();
        try {
            OptimizedCallTarget target = (OptimizedCallTarget) factory.apply(VTLang.LANGUAGE.get(null)).getCallTarget();
            target.call(warmupArgs);
            target.compile(true);
            awaitCompiled(target, true);
            return target;
        } finally {
            ctx.leave();
        }
    }

    /** Waits for a (possibly background) compilation to be installed. */
    static void awaitCompiled(OptimizedCallTarget target, boolean lastTier) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (lastTier ? !target.isValidLastTier() : !target.isValid()) {
            assertTrue("must be compiled", System.nanoTime() < deadline);
            Thread.onSpinWait();
        }
    }

    static List<Throwable> runVirtualThreads(int n, IntConsumer body) throws InterruptedException {
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            int index = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    body.accept(index);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        return failures;
    }

    @Test
    public void contextLookupSurvivesCarrierMigration() throws Exception {
        Context a = newContext();
        Context b = newContext();
        OptimizedCallTarget ta = compiled(a, ContextAcrossYieldRoot::new);
        OptimizedCallTarget tb = compiled(b, ContextAcrossYieldRoot::new);
        int expectedA = contextId(a);
        int expectedB = contextId(b);
        int n = 8 * Runtime.getRuntime().availableProcessors();
        List<Throwable> failures = runVirtualThreads(n, i -> {
            Context ctx = (i & 1) == 0 ? a : b;
            OptimizedCallTarget target = (i & 1) == 0 ? ta : tb;
            int expected = (i & 1) == 0 ? expectedA : expectedB;
            ctx.enter();
            try {
                for (int k = 0; k < 200; k++) {
                    assertEquals(expected, target.call());
                }
            } finally {
                ctx.leave();
            }
        });
        assertTrue(failures.toString(), failures.isEmpty());
        assertTrue(ta.isValidLastTier() && tb.isValidLastTier());
        a.close();
        b.close();
    }

    static int contextId(Context ctx) {
        ctx.enter();
        try {
            return VTLang.CONTEXT.get(null).id;
        } finally {
            ctx.leave();
        }
    }

    @Test
    public void interruptRequestedWhileUnmountedIsDeliveredOnRemount() throws Exception {
        Context ctx = newContext();
        OptimizedCallTarget spin = compiled(ctx, SpinRoot::new, new Semaphore(Integer.MAX_VALUE), new CountDownLatch(0), new boolean[]{true});
        int n = 4 * Runtime.getRuntime().availableProcessors();
        Semaphore gate = new Semaphore(0);
        CountDownLatch parked = new CountDownLatch(n);
        boolean[] neverStop = {false};
        AtomicReference<Throwable> interruptFailure = new AtomicReference<>();
        AtomicReference<List<Throwable>> vtFailures = new AtomicReference<>();
        Thread interrupter = Thread.ofPlatform().unstarted(() -> {
            try {
                ctx.interrupt(Duration.ofSeconds(60));
            } catch (Throwable t) {
                interruptFailure.set(t);
            }
        });
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                vtFailures.set(runVirtualThreads(n, i -> {
                    ctx.enter();
                    try {
                        spin.call(gate, parked, neverStop);
                    } finally {
                        ctx.leave();
                    }
                }));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        parked.await(); // all virtual threads are parked (unmounted) inside compiled code
        interrupter.start(); // sets fast-pending while carrierThread == null
        Thread.sleep(100);
        gate.release(n); // remount: afterMount must recompute PENDING, then the compiled poll must fire
        interrupter.join();
        neverStop[0] = true; // if the interrupt was lost, let the spinning threads finish instead of hanging
        starter.join();
        assertEquals(null, interruptFailure.get());
        List<Throwable> failures = vtFailures.get();
        assertEquals("every virtual thread must be interrupted", n, failures.size());
        for (Throwable t : failures) {
            // Thrown directly out of CallTarget.call (no polyglot boundary), so this is Truffle's interrupt exception.
            assertTrue(String.valueOf(t), String.valueOf(t.getMessage()).contains("interrupted"));
        }
        ctx.close();
    }
    /** Returns 1 while the assumption holds at the point of return, else 2. Parks in between. */
    static final class ParkThenCheckRoot extends RootNode {
        final com.oracle.truffle.api.Assumption assumption;

        ParkThenCheckRoot(VTLang lang, com.oracle.truffle.api.Assumption assumption) {
            super(lang);
            this.assumption = assumption;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            parkOnce((Semaphore) frame.getArguments()[0], (CountDownLatch) frame.getArguments()[1]);
            return assumption.isValid() ? 1 : 2;
        }
    }

    @Test
    public void assumptionInvalidatedWhileParkedDeoptimizesOnResume() throws Exception {
        Context ctx = newContext();
        com.oracle.truffle.api.Assumption assumption = com.oracle.truffle.api.Truffle.getRuntime().createAssumption("vt");
        OptimizedCallTarget target = compiled(ctx, l -> new ParkThenCheckRoot(l, assumption), new Semaphore(Integer.MAX_VALUE), new CountDownLatch(0));
        int n = 4 * Runtime.getRuntime().availableProcessors();
        Semaphore gate = new Semaphore(0);
        CountDownLatch parked = new CountDownLatch(n);
        int[] results = new int[n];
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                List<Throwable> f = runVirtualThreads(n, i -> {
                    ctx.enter();
                    try {
                        results[i] = (Integer) target.call(gate, parked);
                    } finally {
                        ctx.leave();
                    }
                });
                assertTrue(f.toString(), f.isEmpty());
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        parked.await();
        assertTrue("still compiled while parked", target.isValid());
        assumption.invalidate(); // compiled code folded isValid() == true; parked frames must deopt on resume
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        gate.release(n);
        starter.join();
        for (int i = 0; i < n; i++) {
            assertEquals("thread " + i, 2, results[i]);
        }
        ctx.close();
    }

    @Test
    public void tierUpWhileParkedKeepsOldCodeRunnable() throws Exception {
        Context ctx = newContext();
        com.oracle.truffle.api.Assumption assumption = com.oracle.truffle.api.Truffle.getRuntime().createAssumption("never-invalidated");
        ctx.enter();
        OptimizedCallTarget target;
        try {
            target = (OptimizedCallTarget) new ParkThenCheckRoot(VTLang.LANGUAGE.get(null), assumption).getCallTarget();
            target.call(new Semaphore(Integer.MAX_VALUE), new CountDownLatch(0));
            target.compile(false); // tier 1
            awaitCompiled(target, false);
            assertTrue(target.isValid() && !target.isValidLastTier());
        } finally {
            ctx.leave();
        }
        Semaphore gate = new Semaphore(0);
        CountDownLatch parked = new CountDownLatch(1);
        int[] result = new int[1];
        OptimizedCallTarget t = target;
        Thread vt = Thread.ofVirtual().start(() -> {
            ctx.enter();
            try {
                result[0] = (Integer) t.call(gate, parked);
            } finally {
                ctx.leave();
            }
        });
        parked.await();
        ctx.enter();
        try {
            target.compile(true); // installs last tier; tier-1 code becomes non-entrant but stays alive
            awaitCompiled(target, true);
        } finally {
            ctx.leave();
        }
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        gate.release();
        vt.join();
        assertEquals(1, result[0]);
        assertTrue(target.isValidLastTier());
        ctx.close();
    }

    /** Yields (unmounting, usually migrating) inside compiled code, then reads an assumption. */
    static final class YieldThenCheckRoot extends RootNode {
        final com.oracle.truffle.api.Assumption assumption;

        YieldThenCheckRoot(VTLang lang, com.oracle.truffle.api.Assumption assumption) {
            super(lang);
            this.assumption = assumption;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            yieldNow();
            return assumption.isValid() ? 1 : 2;
        }
    }

    @Test
    public void invalidationStressWithManyVirtualThreads() throws Exception {
        Context ctx = newContext();
        com.oracle.truffle.api.Assumption assumption = com.oracle.truffle.api.Truffle.getRuntime().createAssumption("stress");
        OptimizedCallTarget target = compiled(ctx, l -> new YieldThenCheckRoot(l, assumption));
        java.util.concurrent.atomic.AtomicBoolean invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        // Churn: invalidate the target every millisecond (the next calls recompile it synchronously, per
        // TestWithSynchronousCompiling thresholds) while thousands of virtual threads freeze and thaw in it.
        // Halfway through, invalidate the assumption for good.
        Thread churn = Thread.ofPlatform().start(() -> {
            long half = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!stop.get()) {
                target.invalidate("stress");
                if (!invalidated.get() && System.nanoTime() > half) {
                    assumption.invalidate();
                    invalidated.set(true);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        List<Throwable> failures = runVirtualThreads(2000, i -> {
            ctx.enter();
            try {
                while (System.nanoTime() < deadline) {
                    boolean invalidatedBeforeCall = invalidated.get();
                    int r = (Integer) target.call();
                    if (r != 1 && r != 2 || invalidatedBeforeCall && r != 2) {
                        throw new AssertionError("stale result " + r + " (assumption invalidated before call: " + invalidatedBeforeCall + ")");
                    }
                }
            } finally {
                ctx.leave();
            }
        });
        stop.set(true);
        churn.join();
        assertTrue("assumption must have been invalidated during the run", invalidated.get());
        assertTrue(failures.size() + " failures, first: " + (failures.isEmpty() ? "" : failures.get(0)), failures.isEmpty());
        ctx.close();
    }
}
