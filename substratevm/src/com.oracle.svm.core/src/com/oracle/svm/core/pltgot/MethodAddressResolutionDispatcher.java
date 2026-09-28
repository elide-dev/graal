/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.pltgot;

import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;

import com.oracle.svm.core.thread.NativeSpinLockUtils;
import com.oracle.svm.guest.staging.c.CGlobalData;
import com.oracle.svm.guest.staging.c.CGlobalDataFactory;
import com.oracle.svm.shared.Uninterruptible;

public class MethodAddressResolutionDispatcher {
    /*
     * The GOT and its memory protection are shared by all isolates of the process (see
     * GOTHeapSupport). The lock and the number of active resolvers must therefore also be
     * process-wide, otherwise one isolate could make the GOT read-only while another isolate is
     * still resolving a method, e.g., the isolates used for runtime compilation.
     */
    private static final CGlobalData<CIntPointer> LOCK = CGlobalDataFactory.createBytes(() -> SizeOf.get(CIntPointer.class));
    private static final CGlobalData<CIntPointer> ACTIVE_RESOLVER_INSTANCES = CGlobalDataFactory.createBytes(() -> SizeOf.get(CIntPointer.class));

    @Uninterruptible(reason = "PLT/GOT method address resolution doesn't support interruptible code paths.")
    protected static long resolveMethodAddress(long gotEntry) {
        CIntPointer lock = LOCK.get();
        CIntPointer activeResolverInstances = ACTIVE_RESOLVER_INSTANCES.get();
        NativeSpinLockUtils.lockNoTransition(lock);
        try {
            if (activeResolverInstances.read() == 0) {
                GOTHeapSupport.get().makeGOTWritable();
            }
            activeResolverInstances.write(activeResolverInstances.read() + 1);
        } finally {
            NativeSpinLockUtils.unlock(lock);
        }

        long resolvedMethodAddress = PLTGOTConfiguration.singleton().getMethodAddressResolver().resolveMethodWithGOTEntry(gotEntry);

        NativeSpinLockUtils.lockNoTransition(lock);
        try {
            if (activeResolverInstances.read() == 1) {
                GOTHeapSupport.get().makeGOTReadOnly();
            }
            activeResolverInstances.write(activeResolverInstances.read() - 1);
        } finally {
            NativeSpinLockUtils.unlock(lock);
        }
        return resolvedMethodAddress;
    }
}
