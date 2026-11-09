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
package com.oracle.svm.truffle.test.runtimeinit;

import com.oracle.svm.truffle.test.SimulatedClassInitializerRuntimeCompilationTest;
import com.oracle.truffle.api.CompilerDirectives;

/**
 * Initialized at image run time, and its class initializer cannot be simulated because it reads the
 * time. {@link #add} needs no class initialization check, so it can be inlined into runtime compiled
 * code.
 */
public final class RuntimeInitializedHolder {
    public static final long CREATED = System.nanoTime();

    private final int base;

    public RuntimeInitializedHolder(int base) {
        this.base = base;
    }

    public int add(int delta) {
        int result = SimulatedClassInitializerRuntimeCompilationTest.boundary(base + delta);
        if (result < 0) {
            /* Deoptimizes to the deoptimization target of this method. */
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return -result;
        }
        return result;
    }
}
