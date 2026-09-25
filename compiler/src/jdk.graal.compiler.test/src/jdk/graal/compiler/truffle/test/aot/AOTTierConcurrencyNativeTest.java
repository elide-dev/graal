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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Many threads call AOT tier code while it is unlinked: by the threshold, and by an invalidation.
 * Every call returns the correct result; no call runs stale code. The engine adopts the
 * pre-initialized engine with {@code engine.AOTTierThreshold=100}, so this class needs an image run
 * of its own (see {@link AOTTierNativeTestSupport}).
 */
public class AOTTierConcurrencyNativeTest {

    private static final int THREADS = 8;

    private static final ByteArrayOutputStream LOG = new ByteArrayOutputStream();

    private static Engine engine;

    private static synchronized Engine engine() {
        if (engine == null) {
            engine = AOTTierNativeTestSupport.traceEngine(LOG, "engine.AOTTierThreshold", "100");
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

    private interface Body {
        void run(int thread) throws Exception;
    }

    private static void runThreads(Body body) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < THREADS; t++) {
            int index = t;
            threads.add(new Thread(() -> {
                try {
                    body.run(index);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread th : threads) {
            th.join(TimeUnit.MINUTES.toMillis(2));
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    /** Eight contexts on eight threads call "add1" while the threshold unlinks its AOT code. */
    @Test
    public void concurrentCallsDuringUnlink() throws Exception {
        TruffleTestAssumptions.assumeAOT();
        runThreads(t -> {
            try (Context c = Context.newBuilder().engine(engine()).build()) {
                Value add = c.parse(fixture("add1", "add:1"));
                for (int i = 0; i < 5000; i++) {
                    if (add.execute(i).asInt() != i + 1) {
                        throw new AssertionError("wrong result at " + i);
                    }
                }
            }
        });
        String log = text(LOG);
        assertEquals(log, 1, enters(log, "add1"));
        assertEquals(log, 1, unlinks(log, "add1"));
        assertEquals(log, 1, unlinks(log, "add1", "AOT tier threshold reached"));
    }

    /**
     * Eight contexts on eight threads call "assumeloop", whose calls take long in its loop, while
     * the assumption its AOT code folds is invalidated (while the first call of every thread runs),
     * well before the threshold. A call returns
     * between N and 2N (2N if all of its loop ran after the invalidation); a call that starts
     * after the invalidation returned must return 2N: it must not enter the unlinked code.
     */
    @Test
    public void concurrentCallsDuringInvalidation() throws Exception {
        TruffleTestAssumptions.assumeAOT();
        final int n = 50_000_000;
        final int calls = 4;
        AtomicInteger started = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        Thread invalidator = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (started.get() < THREADS && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            try {
                /* Every thread is in the loop of its first call, which takes much longer. */
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            AOTTestLanguage.invalidateLoopStable();
            Invalidated.done = true;
        });
        invalidator.start();
        runThreads(t -> {
            try (Context c = Context.newBuilder().engine(engine()).build()) {
                Value f = c.parse(fixture("assumeloop", "assumeloop"));
                for (int i = 0; i < calls; i++) {
                    boolean after = Invalidated.done;
                    if (i == 0) {
                        started.incrementAndGet();
                    }
                    long sum = f.execute(n).asLong();
                    if (sum < n || sum > 2L * n) {
                        throw new AssertionError("wrong result " + sum);
                    }
                    if (after && sum != 2L * n) {
                        throw new AssertionError("stale code after the invalidation: " + sum);
                    }
                    if (sum != n && sum != 2L * n) {
                        inFlight.incrementAndGet();
                    }
                }
            }
        });
        invalidator.join();
        String log = text(LOG);
        /*
         * Informational: the loop has no VM safepoint in image code, so the invalidation waits for
         * the loops in flight to end (see spin and pollspin in AOTTierNativeTest for activations
         * deoptimized in their loop).
         */
        System.out.println("assumeloop activations deoptimized in their loop: " + inFlight.get());
        assertEquals(log, 1, enters(log, "assumeloop"));
        assertEquals(log, 1, unlinks(log, "assumeloop"));
        assertEquals(log, 1, unlinks(log, "assumeloop", "aot-loop-test"));
    }

    static final class Invalidated {
        static volatile boolean done;
    }
}
