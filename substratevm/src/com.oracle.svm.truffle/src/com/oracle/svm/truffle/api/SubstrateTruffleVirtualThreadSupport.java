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
package com.oracle.svm.truffle.api;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.thread.VirtualThreadMountListener;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.truffle.TruffleFeature;

/**
 * Moves Truffle's carrier-local state (context thread local, safepoint state) with virtual
 * threads. While a virtual thread is mounted, the carrier's own state is saved in the virtual
 * thread, because the carrier may itself have entered a context (for example, a custom scheduler
 * that runs virtual threads on a thread that uses polyglot contexts).
 */
public final class SubstrateTruffleVirtualThreadSupport extends VirtualThreadMountListener {

    @Override
    public void afterMount(Thread vthread) {
        Target_java_lang_VirtualThread_Truffle t = SubstrateUtil.cast(vthread, Target_java_lang_VirtualThread_Truffle.class);
        t.carrierContextThreadLocal = SubstrateFastThreadLocal.getCurrentRaw();
        t.carrierSafepointState = SubstrateThreadLocalHandshake.saveState();
        SubstrateFastThreadLocal.setCurrentRaw(t.truffleContextThreadLocal);
        SubstrateThreadLocalHandshake.restoreState(t.truffleSafepointState);
    }

    @Override
    public void beforeYield(Thread vthread) {
        Target_java_lang_VirtualThread_Truffle t = SubstrateUtil.cast(vthread, Target_java_lang_VirtualThread_Truffle.class);
        t.truffleContextThreadLocal = SubstrateFastThreadLocal.getCurrentRaw();
        t.truffleSafepointState = SubstrateThreadLocalHandshake.saveState();
    }

    @Override
    public void afterYield(Thread vthread) {
        /* Consumed by afterMount, or unused if the yield failed; do not keep the context alive. */
        Target_java_lang_VirtualThread_Truffle t = SubstrateUtil.cast(vthread, Target_java_lang_VirtualThread_Truffle.class);
        t.truffleContextThreadLocal = null;
        t.truffleSafepointState = null;
    }

    @Override
    public void afterUnmount(Thread vthread) {
        Target_java_lang_VirtualThread_Truffle t = SubstrateUtil.cast(vthread, Target_java_lang_VirtualThread_Truffle.class);
        SubstrateFastThreadLocal.setCurrentRaw(t.carrierContextThreadLocal);
        SubstrateThreadLocalHandshake.restoreState(t.carrierSafepointState);
        t.carrierContextThreadLocal = null;
        t.carrierSafepointState = null;
    }
}

@TargetClass(className = "java.lang.VirtualThread", onlyWith = TruffleFeature.IsEnabled.class)
final class Target_java_lang_VirtualThread_Truffle {
    @Alias volatile Thread carrierThread;

    /** The virtual thread's own state, from beforeYield until it is mounted again. */
    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    Object[] truffleContextThreadLocal;

    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    Object truffleSafepointState;

    /** The carrier's own state while the virtual thread is mounted on it. */
    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    Object[] carrierContextThreadLocal;

    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    Object carrierSafepointState;
}
