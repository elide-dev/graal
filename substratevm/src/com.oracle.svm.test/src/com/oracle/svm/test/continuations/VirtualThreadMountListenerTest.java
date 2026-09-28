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

import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.junit.Test;

import com.oracle.svm.core.thread.VirtualThreadMountListener;
import com.oracle.svm.shared.util.ModuleSupport;
import com.oracle.svm.test.NativeImageBuildArgs;

@NativeImageBuildArgs({"--features=com.oracle.svm.test.continuations.VirtualThreadMountListenerTest$ListenerFeature"})
public class VirtualThreadMountListenerTest {
    static final AtomicInteger MOUNTS = new AtomicInteger();
    static final AtomicInteger YIELDS = new AtomicInteger();
    static final AtomicInteger UNMOUNTS = new AtomicInteger();
    static volatile boolean wrongThreadSeen;

    public static final class CountingListener extends VirtualThreadMountListener {
        @Override
        public void afterMount(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() != vthread;
            MOUNTS.incrementAndGet();
        }

        @Override
        public void beforeYield(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() != vthread;
            YIELDS.incrementAndGet();
        }

        @Override
        public void afterUnmount(Thread vthread) {
            wrongThreadSeen |= Thread.currentThread() == vthread || Thread.currentThread().isVirtual();
            UNMOUNTS.incrementAndGet();
        }
    }

    public static final class ListenerFeature implements Feature {
        public ListenerFeature() {
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, ListenerFeature.class, false, "org.graalvm.nativeimage.builder", "com.oracle.svm.core.thread");
        }

        @Override
        public void beforeAnalysis(BeforeAnalysisAccess access) {
            RuntimeClassInitialization.initializeAtBuildTime(CountingListener.class);
            ImageSingletons.add(VirtualThreadMountListener.class, new CountingListener());
        }
    }

    @Test
    public void listenerSeesEveryMountYieldAndUnmount() throws Exception {
        int yields = 10;
        Thread vt = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < yields; i++) {
                Thread.yield();
            }
        });
        vt.join();
        assertTrue(!wrongThreadSeen);
        assertEquals(yields, YIELDS.get());
        assertEquals(yields + 1, MOUNTS.get());
        assertEquals(yields + 1, UNMOUNTS.get());
    }
}
