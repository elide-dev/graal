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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives generational Shenandoah through old-generation cycles (marking, mixed evacuations and
 * coalesce-and-fill) while another thread keeps requesting non-GC safepoints. Native Image
 * safepoints are much more frequent than HotSpot ones (stack walks, code invalidation, Truffle
 * code installation), so concurrent GC workers that are not part of the suspendible thread set
 * regularly run while some unrelated safepoint is in progress (elide-dev/graal#203).
 */
public class GenerationalSafepointStress {
    private static final int SURVIVORS = 16384;

    static volatile Object sink;

    public static void main(String[] args) throws InterruptedException {
        long durationMillis = args.length > 0 ? Long.parseLong(args[0]) : 10_000;
        int garbagePerRound = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        int chunkLength = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
        int pauseMillis = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        int liveChunks = args.length > 4 ? Integer.parseInt(args[4]) : 48;
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong safepoints = new AtomicLong();
        Thread storm = new Thread(() -> {
            ThreadGroup group = Thread.currentThread().getThreadGroup();
            while (!done.get()) {
                /*
                 * Enumerating threads is a safepoint operation that allocates very little on the
                 * VM operation thread, which keeps allocation failures there unlikely.
                 */
                if (group.activeCount() > 0) {
                    safepoints.incrementAndGet();
                }
            }
        }, "SafepointStorm");
        storm.setDaemon(true);
        storm.start();

        /*
         * Chunks stay reachable for liveChunks rounds so that they are promoted. When a chunk is
         * dropped, every eighth element moves to a long-lived ring, which leaves old regions
         * partially live: such regions become coalesce-and-fill candidates.
         */
        byte[][][] chunks = new byte[liveChunks][][];
        byte[][] survivors = new byte[SURVIVORS][];
        int survivorIndex = 0;
        long deadline = System.nanoTime() + durationMillis * 1_000_000L;
        long rounds = 0;
        while (System.nanoTime() < deadline) {
            int slot = (int) (rounds % liveChunks);
            byte[][] dropped = chunks[slot];
            if (dropped != null) {
                for (int i = 0; i < dropped.length; i += 8) {
                    survivors[survivorIndex] = dropped[i];
                    survivorIndex = (survivorIndex + 1) % SURVIVORS;
                }
            }
            byte[][] chunk = new byte[chunkLength][];
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = new byte[512 + (i & 511)];
                chunk[i][0] = (byte) i;
            }
            chunks[slot] = chunk;
            for (int i = 0; i < garbagePerRound; i++) {
                sink = new byte[64];
            }
            rounds++;
            Thread.sleep(pauseMillis);
        }
        done.set(true);
        storm.join();
        for (byte[][] chunk : chunks) {
            for (int i = 0; chunk != null && i < chunk.length; i++) {
                if (chunk[i][0] != (byte) i) {
                    throw new AssertionError("Corrupt chunk element " + i);
                }
            }
        }
        System.out.println("GenerationalSafepointStress passed: " + rounds + " rounds, " + safepoints.get() + " safepoints");
    }
}
