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
package com.oracle.svm.truffle.test;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;

import com.oracle.svm.guest.staging.c.CGlobalData;
import com.oracle.svm.guest.staging.c.CGlobalDataFactory;
import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.Uninterruptible;

/**
 * The C interface bindings that {@link CFunctionRuntimeCompilationTest} calls, written the way the
 * Myna seam generator emits them. They are in their own class because the test class is registered
 * for reflection, which would make the hosted-only {@link AlwaysInline} annotation reachable.
 */
final class CFunctionRuntimeCompilationBindings {

    private CFunctionRuntimeCompilationBindings() {
    }

    /** Shape 1: a non-transitioning leaf that may be inlined into runtime-compiled code. */
    public interface LabsLeaf extends CFunctionPointer {
        @AlwaysInline("Myna binding ABI 2 native leaf")
        @Uninterruptible(reason = "Bounded primitive native leaf; no Java reentry", mayBeInlined = true)
        @InvokeCFunctionPointer(transition = CFunction.Transition.NO_TRANSITION)
        long invoke(long value);
    }

    /** Shape 2: a function pointer call with a Java-to-native transition. */
    public interface LlabsTransition extends CFunctionPointer {
        @InvokeCFunctionPointer
        long invoke(long value);
    }

    /** Shape 3: a C function with a Java-to-native transition. */
    static final class LibC {
        @CFunction
        static native long labs(long value);
    }

    static final CGlobalData<LabsLeaf> LABS_LEAF = CGlobalDataFactory.forSymbol("labs");
    static final CGlobalData<LlabsTransition> LLABS_TRANSITION = CGlobalDataFactory.forSymbol("llabs");

    static long leaf(long value) {
        return LABS_LEAF.get().invoke(value);
    }

    static long transition(long value) {
        return LLABS_TRANSITION.get().invoke(value);
    }

    static long cFunction(long value) {
        return LibC.labs(value);
    }

}
