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
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.SingleLayer;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.InitialLayerOnly;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.truffle.TruffleFeature;

/** Moves Truffle's carrier-local state (context thread local, safepoint state) with virtual threads. */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = SingleLayer.class, layeredInstallationKind = InitialLayerOnly.class)
public final class SubstrateTruffleVirtualThreadSupport extends VirtualThreadMountListener {

    @Override
    public void afterMount(Thread vthread) {
        Target_java_lang_VirtualThread_Truffle t = SubstrateUtil.cast(vthread, Target_java_lang_VirtualThread_Truffle.class);
        SubstrateFastThreadLocal.setCurrentRaw(t.truffleContextThreadLocal);
        SubstrateThreadLocalHandshake.restoreStateAfterMount(t.truffleSafepointState);
    }

    @Override
    public void beforeYield(Thread vthread) {
        Target_java_lang_VirtualThread_Truffle t = SubstrateUtil.cast(vthread, Target_java_lang_VirtualThread_Truffle.class);
        t.truffleContextThreadLocal = SubstrateFastThreadLocal.getCurrentRaw();
        t.truffleSafepointState = SubstrateThreadLocalHandshake.saveStateForYield();
    }

    @Override
    public void afterUnmount(Thread vthread) {
        SubstrateFastThreadLocal.setCurrentRaw(null);
        SubstrateThreadLocalHandshake.clearStateAfterUnmount();
    }
}

@TargetClass(className = "java.lang.VirtualThread", onlyWith = TruffleFeature.IsEnabled.class)
final class Target_java_lang_VirtualThread_Truffle {
    @Alias volatile Thread carrierThread;

    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    Object[] truffleContextThreadLocal;

    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    Object truffleSafepointState;
}
