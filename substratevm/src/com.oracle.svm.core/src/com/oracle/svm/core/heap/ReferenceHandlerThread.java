/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.heap;

import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.core.thread.RecurringCallbackSupport;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.RuntimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.SingleLayer;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.InitialLayerOnly;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;

@SingletonTraits(access = RuntimeAccessOnly.class, layeredCallbacks = SingleLayer.class, layeredInstallationKind = InitialLayerOnly.class)
public final class ReferenceHandlerThread implements Runnable {
    private final Thread thread;
    private volatile IsolateThread isolateThread;
    private volatile boolean stopped;

    @Platforms(Platform.HOSTED_ONLY.class)
    ReferenceHandlerThread() {
        thread = new Thread(this, "Reference Handler");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.setDaemon(true);
    }

    /**
     * Starts the thread without waiting for it to run: waiting would put the thread's creation and
     * attachment on the startup path of every isolate. Until the thread publishes
     * {@link #isolateThread}, {@link #isReferenceHandlerThread(IsolateThread)} identifies it by its
     * {@link Thread} object. Teardown calls {@link #waitUntilAttached()} first.
     */
    public static void start() {
        if (!isSupported()) {
            return;
        }

        singleton().thread.start();
    }

    /**
     * Waits until the thread has attached and published {@link #isolateThread}. Teardown must call
     * this before {@code PlatformThreads.tearDownOtherThreads()}: a thread that attaches after that
     * interrupts itself, which this thread must not be. The thread was started when the isolate was
     * created, so this rarely has to wait.
     */
    public static void waitUntilAttached() {
        if (!isSupported()) {
            return;
        }

        while (singleton().isolateThread.isNull()) {
            Thread.yield();
        }
    }

    public static void initiateStop() {
        if (!isSupported()) {
            return;
        }

        assert singleton().isolateThread.isNonNull() : "waitUntilAttached() must be called first";
        singleton().stopped = true;
        Heap.getHeap().wakeUpReferencePendingListWaiters();
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static boolean isStopping() {
        assert isSupported();
        return singleton().stopped;
    }

    @Uninterruptible(reason = "Executed during teardown after VMThreads#threadExit")
    public static void waitInNativeUntilDetached() {
        if (!isSupported()) {
            return;
        }

        VMThreads.waitInNativeUntilDetached(singleton().isolateThread);
        singleton().isolateThread = Word.nullPointer();
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static boolean isReferenceHandlerThread() {
        return isReferenceHandlerThread(CurrentIsolate.getCurrentThread());
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static boolean isReferenceHandlerThread(IsolateThread other) {
        if (!isSupported() || other.isNull()) {
            return false;
        }
        IsolateThread handler = singleton().isolateThread;
        if (handler.isNonNull()) {
            return other == handler;
        }
        /*
         * The thread was started but has not published its isolate thread yet (see start()). The
         * caller keeps {@code other} alive, and the handler's Thread object never changes.
         */
        return PlatformThreads.fromVMThreadUnsafe(other) == singleton().thread;
    }

    public static boolean isReferenceHandlerThread(Thread other) {
        return isSupported() && other == singleton().thread;
    }

    @Override
    public void run() {
        RecurringCallbackSupport.suspendCallbackTimer("An exception in a recurring callback must not interrupt pending reference processing because it could result in a memory leak.");

        this.isolateThread = CurrentIsolate.getCurrentThread();
        try {
            while (!stopped) {
                ReferenceInternals.waitForPendingReferences();
                ReferenceInternals.processPendingReferences();
                ReferenceHandler.processCleaners();
            }
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere("Reference processing and cleaners must handle all potential exceptions", t);
        }
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static ReferenceHandlerThread singleton() {
        return ImageSingletons.lookup(ReferenceHandlerThread.class);
    }

    @Fold
    static boolean isSupported() {
        return SubstrateOptions.AllowVMInternalThreads.getValue();
    }
}

@AutomaticallyRegisteredFeature
class ReferenceHandlerThreadFeature implements InternalFeature {
    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return ReferenceHandlerThread.isSupported() && ImageLayerBuildingSupport.firstImageBuild();
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        ImageSingletons.add(ReferenceHandlerThread.class, new ReferenceHandlerThread());
    }
}
