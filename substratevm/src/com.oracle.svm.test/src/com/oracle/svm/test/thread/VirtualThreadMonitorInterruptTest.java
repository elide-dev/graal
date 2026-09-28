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
package com.oracle.svm.test.thread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Entering a monitor is not interruptible. An interrupted thread that waits for a monitor must not
 * spin: a spinning virtual thread never unmounts, so enough of them occupy all carrier threads,
 * and the virtual thread that is next in line for the monitor never runs again.
 */
public class VirtualThreadMonitorInterruptTest {

    @Test
    public void interruptedVirtualThreadsWaitingForMonitorDoNotStarveItsSuccessor() throws Exception {
        Object lock = new Object();
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger interruptStatusLost = new AtomicInteger();
        int waiters = 2 * Runtime.getRuntime().availableProcessors();
        List<Thread> threads = new ArrayList<>();
        Thread successor;
        synchronized (lock) {
            /* Queued first, and unmounted, before any interrupted waiter. */
            successor = Thread.ofVirtual().start(() -> {
                synchronized (lock) {
                    acquired.incrementAndGet();
                }
            });
            awaitBlocked(successor);
            for (int i = 0; i < waiters; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    Thread.currentThread().interrupt();
                    synchronized (lock) {
                        acquired.incrementAndGet();
                        // Checkstyle: allow Thread.isInterrupted
                        if (!Thread.currentThread().isInterrupted()) {
                            interruptStatusLost.incrementAndGet();
                        }
                        // Checkstyle: disallow Thread.isInterrupted
                    }
                }));
            }
            for (Thread t : threads) {
                awaitBlocked(t);
            }
        }
        threads.add(successor);
        for (Thread t : threads) {
            assertTrue("virtual thread did not acquire the monitor: " + t, t.join(Duration.ofSeconds(60)));
        }
        assertEquals(waiters + 1, acquired.get());
        assertEquals("the interrupt status must be kept while waiting for a monitor", 0, interruptStatusLost.get());
    }

    private static void awaitBlocked(Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (t.getState() != Thread.State.BLOCKED) {
            assertTrue("thread did not block on the monitor: " + t.getState(), System.nanoTime() < deadline);
            Thread.sleep(1);
        }
    }
}
