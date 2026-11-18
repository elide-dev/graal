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
package com.oracle.svm.core.methodhandles;

import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.shared.BuildPhaseProvider.AfterAnalysis;

/**
 * The link information of a method handle that a {@link RuntimeMethodHandleLinker} can invoke
 * directly: the index of the linker's entry method that implements the handle, and the value that
 * {@link java.lang.invoke.MethodHandle#bindTo} bound as the handle's first argument, or null.
 * <p>
 * The class is concrete and final because the static analysis sees it as the type of
 * {@link Target_java_lang_invoke_MethodHandle#linkInfo}, whose value only becomes available after
 * the analysis. A runtime compiler reads the fields through its constant reflection, which also
 * works when it compiles in a separate isolate, so the entry method is referenced by its index
 * rather than directly.
 */
public final class MethodHandleLinkInfo {
    /**
     * Read by {@link java.lang.invoke.MethodHandle#bindTo}: the instances that the image builder
     * creates are only scanned after the analysis, so the analysis must not fold the field.
     */
    @UnknownPrimitiveField(availability = AfterAnalysis.class) //
    private final int entryIndex;
    private final Object boundValue;

    public MethodHandleLinkInfo(int entryIndex, Object boundValue) {
        this.entryIndex = entryIndex;
        this.boundValue = boundValue;
    }

    public int getEntryIndex() {
        return entryIndex;
    }

    public Object getBoundValue() {
        return boundValue;
    }
}
