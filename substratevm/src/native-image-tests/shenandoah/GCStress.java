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

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class GCStress {
    private static final int WORKERS = 4;

    private static final class Node {
        final int id;
        final byte[] payload = new byte[256];
        Node next;

        Node(int id, Node next) {
            this.id = id;
            this.next = next;
            payload[0] = (byte) id;
            payload[255] = (byte) (id >>> 8);
        }

        void verify() {
            if (payload[0] != (byte) id || payload[255] != (byte) (id >>> 8)) {
                throw new AssertionError("Corrupt node " + id);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Node[] roots = new Node[8192];
        AtomicReference<Node> atomic = new AtomicReference<>();
        long checksum = 0;
        for (int i = 0; i < 2_000_000; i++) {
            int slot = i & (roots.length - 1);
            Node previous = roots[slot];
            Node node = new Node(i, previous);
            roots[slot] = node;
            if (previous != null) {
                previous.verify();
                previous.next = null;
                checksum += previous.id;
            }
            Node old = atomic.get();
            if (!atomic.compareAndSet(old, node) || atomic.getAndSet(node) != node) {
                throw new AssertionError("Atomic reference mismatch");
            }
            if ((i & 131071) == 0) {
                System.gc();
            }
        }
        for (Node node : roots) {
            node.verify();
            node.next.verify();
        }
        long retained = 2_000_000L - roots.length;
        if (checksum != retained * (retained - 1) / 2) {
            throw new AssertionError("Checksum mismatch: " + checksum);
        }
        System.out.println("GCStress passed: " + checksum);
        runConcurrentStress();
    }

    private static void runConcurrentStress() throws InterruptedException {
        AtomicReference<Node> shared = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(WORKERS);
        Thread[] workers = new Thread[WORKERS];
        for (int worker = 0; worker < WORKERS; worker++) {
            int id = worker;
            workers[worker] = new Thread(() -> {
                try {
                    start.await();
                    Node[] roots = new Node[4096];
                    for (int i = 0; i < 500_000; i++) {
                        int slot = i & (roots.length - 1);
                        Node previous = roots[slot];
                        Node node = new Node(i + id * 500_000, previous);
                        roots[slot] = node;
                        if (previous != null) {
                            previous.verify();
                            previous.next = null;
                        }
                        Node expected;
                        do {
                            expected = shared.get();
                            if (expected != null) {
                                expected.verify();
                            }
                        } while (!shared.compareAndSet(expected, node));
                        Node replaced = shared.getAndSet(node);
                        replaced.verify();
                    }
                    for (Node node : roots) {
                        node.verify();
                        node.next.verify();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    finished.countDown();
                }
            }, "GCStress-" + worker);
            workers[worker].start();
        }
        start.countDown();
        while (finished.getCount() != 0) {
            System.gc();
            Thread.sleep(5);
        }
        for (Thread worker : workers) {
            worker.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("Concurrent mutation failed", failure.get());
        }
        shared.set(null);
        verifyWeakReference();
        System.out.println("Concurrent GCStress passed: " + WORKERS + " workers");
    }

    private static void verifyWeakReference() throws InterruptedException {
        Object referent = new Object();
        WeakReference<Object> reference = new WeakReference<>(referent);
        System.gc();
        if (reference.get() != referent) {
            throw new AssertionError("A strongly reachable weak referent was cleared");
        }
        Reference.reachabilityFence(referent);
        referent = null;
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc();
            Thread.sleep(5);
        }
        if (reference.get() != null) {
            throw new AssertionError("An unreachable weak referent was retained");
        }
    }
}
