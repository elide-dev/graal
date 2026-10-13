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
package com.oracle.svm.interpreter.ristretto;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.threadlocal.VMThreadLocalInfo;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.interpreter.InterpreterFrame;
import com.oracle.svm.shared.BuildPhaseProvider.ReadyForCompilation;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

/**
 * How Ristretto OSR code reads the transferred interpreter frame: the thread local that
 * {@link RistrettoOSRSupport} fills in before entering the compiled code, and the offsets of the
 * {@link InterpreterFrame} fields that hold its locals and locks. Filled in at image build time by
 * {@link RistrettoFeature}, and used by
 * {@link com.oracle.svm.interpreter.ristretto.compile.RistrettoOSRLocalLoweringPhase}.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class RistrettoOSRFrameLayout {
    @UnknownObjectField(availability = ReadyForCompilation.class) //
    private VMThreadLocalInfo currentFrame;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class) //
    private int referencesOffset;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class) //
    private int primitivesOffset;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class) //
    private int locksOffset;

    @Platforms(Platform.HOSTED_ONLY.class)
    RistrettoOSRFrameLayout() {
    }

    public static RistrettoOSRFrameLayout singleton() {
        return ImageSingletons.lookup(RistrettoOSRFrameLayout.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    void setCurrentFrame(VMThreadLocalInfo info) {
        this.currentFrame = info;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    void setFieldOffsets(int references, int primitives, int locks) {
        this.referencesOffset = references;
        this.primitivesOffset = primitives;
        this.locksOffset = locks;
    }

    /** The thread local holding the interpreter frame that is entering OSR code. */
    public VMThreadLocalInfo currentFrame() {
        return currentFrame;
    }

    /** Offset of the {@code Object[]} that holds the reference slots of the frame. */
    public int referencesOffset() {
        return referencesOffset;
    }

    /** Offset of the {@code long[]} that holds the primitive slots of the frame. */
    public int primitivesOffset() {
        return primitivesOffset;
    }

    /** Offset of the {@code Object[]} that holds the objects of the monitors the frame owns. */
    public int locksOffset() {
        return locksOffset;
    }
}
