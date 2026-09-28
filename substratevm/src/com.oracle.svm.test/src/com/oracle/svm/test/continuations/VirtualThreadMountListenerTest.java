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
package com.oracle.svm.test.continuations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicIntegerArray;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.junit.Test;

import com.oracle.svm.core.thread.VirtualThreadMountListener;
import com.oracle.svm.shared.util.ModuleSupport;
import com.oracle.svm.test.NativeImageBuildArgs;

@NativeImageBuildArgs({"--features=com.oracle.svm.test.continuations.VirtualThreadMountListenerTest$ListenerFeature"})
public class VirtualThreadMountListenerTest {
    static final int LISTENER_COUNT = 2;
    /* Indexed by listener id - 1. Kept here, so that the test does not refer to the listener class. */
    static final AtomicIntegerArray MOUNTS = new AtomicIntegerArray(LISTENER_COUNT);
    static final AtomicIntegerArray YIELDS = new AtomicIntegerArray(LISTENER_COUNT);
    static final AtomicIntegerArray AFTER_YIELDS = new AtomicIntegerArray(LISTENER_COUNT);
    static final AtomicIntegerArray UNMOUNTS = new AtomicIntegerArray(LISTENER_COUNT);
    static volatile boolean wrongThreadSeen;
    static volatile boolean wrongOrderSeen;
    /** Id of the listener that was called last, to check the order of the callbacks. */
    static volatile int lastCalled;

    public static final class CountingListener extends VirtualThreadMountListener {
        final int id;

        CountingListener(int id) {
            this.id = id;
        }

        /*
         * Returns the super type so that verifying ListenerFeature does not load this class before
         * the feature exports the package of its super class.
         */
        static VirtualThreadMountListener create(int id) {
            return new CountingListener(id);
        }

        /** Mount callbacks run in registration order, yield and unmount callbacks in reverse. */
        private void called(boolean registrationOrder) {
            int expectedPrevious = registrationOrder ? id - 1 : id + 1;
            int first = registrationOrder ? 1 : LISTENER_COUNT;
            wrongOrderSeen |= id != first && lastCalled != expectedPrevious;
            lastCalled = id;
        }

        @Override
        public void afterMount(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() != vthread;
            called(true);
            MOUNTS.incrementAndGet(id - 1);
        }

        @Override
        public void beforeYield(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() != vthread;
            called(false);
            YIELDS.incrementAndGet(id - 1);
        }

        @Override
        public void afterYield(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() != vthread;
            called(true);
            AFTER_YIELDS.incrementAndGet(id - 1);
        }

        @Override
        public void afterUnmount(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() == vthread || Thread.currentThread().isVirtual();
            called(false);
            UNMOUNTS.incrementAndGet(id - 1);
        }
    }

    public static final class ListenerFeature implements Feature {
        public ListenerFeature() {
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, ListenerFeature.class, false, "org.graalvm.nativeimage.builder", "com.oracle.svm.core.thread");
        }

        @Override
        public void beforeAnalysis(BeforeAnalysisAccess access) {
            RuntimeClassInitialization.initializeAtBuildTime(CountingListener.class);
            VirtualThreadMountListener.register(CountingListener.create(1));
            VirtualThreadMountListener.register(CountingListener.create(2));
        }
    }

    @Test
    public void listenersSeeEveryMountYieldAndUnmountInOrder() throws Exception {
        int yields = 10;
        Thread vt = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < yields; i++) {
                Thread.yield();
            }
        });
        vt.join();
        assertTrue(!wrongThreadSeen);
        assertTrue(!wrongOrderSeen);
        for (int i = 0; i < LISTENER_COUNT; i++) {
            assertEquals(yields, YIELDS.get(i));
            assertEquals(yields, AFTER_YIELDS.get(i));
            assertEquals(yields + 1, MOUNTS.get(i));
            assertEquals(yields + 1, UNMOUNTS.get(i));
        }
    }
}
