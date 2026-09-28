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
package com.oracle.svm.core.thread;

import java.util.Arrays;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.SingleLayer;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.InitialLayerOnly;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;

/**
 * Receives virtual-thread mount transitions, so that subsystems which keep per-thread state in
 * {@link org.graalvm.nativeimage.IsolateThread} fast thread locals (which belong to the carrier)
 * can move that state along with the virtual thread. Listeners are {@linkplain #register
 * registered} at image build time, no later than {@code beforeAnalysis}. Mount callbacks are
 * called in registration order, yield and unmount callbacks in reverse registration order.
 * <p>
 * Callbacks must not allocate, block, yield, or trigger class initialization.
 */
public abstract class VirtualThreadMountListener {

    @Platforms(Platform.HOSTED_ONLY.class)
    public static synchronized void register(VirtualThreadMountListener listener) {
        VMError.guarantee(!BuildPhaseProvider.isAnalysisStarted(), "Virtual thread mount listeners must be registered before the analysis");
        Listeners listeners;
        if (ImageSingletons.contains(Listeners.class)) {
            listeners = ImageSingletons.lookup(Listeners.class);
        } else {
            listeners = new Listeners();
            ImageSingletons.add(Listeners.class, listeners);
        }
        listeners.list = Arrays.copyOf(listeners.list, listeners.list.length + 1);
        listeners.list[listeners.list.length - 1] = listener;
    }

    @Fold
    public static boolean isRegistered() {
        return ImageSingletons.contains(Listeners.class);
    }

    @Fold
    static VirtualThreadMountListener[] listeners() {
        return ImageSingletons.lookup(Listeners.class).list;
    }

    static void notifyAfterMount(Thread vthread) {
        for (VirtualThreadMountListener listener : listeners()) {
            listener.afterMount(vthread);
        }
    }

    static void notifyBeforeYield(Thread vthread) {
        VirtualThreadMountListener[] list = listeners();
        for (int i = list.length - 1; i >= 0; i--) {
            list[i].beforeYield(vthread);
        }
    }

    static void notifyAfterYield(Thread vthread) {
        for (VirtualThreadMountListener listener : listeners()) {
            listener.afterYield(vthread);
        }
    }

    static void notifyAfterUnmount(Thread vthread) {
        VirtualThreadMountListener[] list = listeners();
        for (int i = list.length - 1; i >= 0; i--) {
            list[i].afterUnmount(vthread);
        }
    }

    /** On the carrier thread, after {@code Thread.currentThread()} became {@code vthread}. */
    public abstract void afterMount(Thread vthread);

    /** On {@code vthread}, right before its continuation attempts to yield. */
    public abstract void beforeYield(Thread vthread);

    /**
     * On {@code vthread}, after the yield attempt of {@link #beforeYield} returned: either the yield
     * failed (the virtual thread is pinned and never unmounted), or the virtual thread was mounted
     * again, after {@link #afterMount}.
     */
    public void afterYield(@SuppressWarnings("unused") Thread vthread) {
    }

    /** On the carrier thread, after {@code Thread.currentThread()} became the carrier again. */
    public abstract void afterUnmount(Thread vthread);

    @SingletonTraits(access = AllAccess.class, layeredCallbacks = SingleLayer.class, layeredInstallationKind = InitialLayerOnly.class)
    static final class Listeners {
        VirtualThreadMountListener[] list = new VirtualThreadMountListener[0];
    }
}
