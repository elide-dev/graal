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
package com.oracle.svm.core.graal.nodes;

import java.util.function.Function;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.annotation.AnnotationsInfo;

/**
 * A field in the array part of a pod (see {@link com.oracle.svm.core.heap.Pod}), or the entry of a
 * {@link VirtualPodNode} that holds its pod. These are not Java fields: they exist only so that a
 * {@link VirtualPodNode} can describe all its entries as fields, which escape analysis code expects
 * of {@link jdk.graal.compiler.nodes.virtual.VirtualInstanceNode virtual instances}. Their
 * {@linkplain #getOffset offsets} are the absolute offsets in the object.
 */
public final class PodSlotField implements ResolvedJavaField {
    /** The offset of the {@linkplain #isPodEntry pod entry}, which is not part of the object. */
    public static final int POD_ENTRY_OFFSET = -1;

    private final ResolvedJavaType declaringClass;
    private final ResolvedJavaType type;
    private final int offset;

    public PodSlotField(ResolvedJavaType declaringClass, ResolvedJavaType type, int offset) {
        assert offset >= 0 || offset == POD_ENTRY_OFFSET : offset;
        this.declaringClass = declaringClass;
        this.type = type;
        this.offset = offset;
    }

    /** Whether this is the entry that holds the pod rather than a field of the object. */
    public boolean isPodEntry() {
        return offset == POD_ENTRY_OFFSET;
    }

    @Override
    public int getModifiers() {
        return 0;
    }

    @Override
    public int getOffset() {
        return offset;
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }

    @Override
    public String getName() {
        return isPodEntry() ? "pod" : "pod+" + offset;
    }

    @Override
    public JavaType getType() {
        return type;
    }

    @Override
    public JavaKind getJavaKind() {
        return type.getJavaKind();
    }

    @Override
    public ResolvedJavaType getDeclaringClass() {
        return declaringClass;
    }

    @Override
    public <T> T getDeclaredAnnotationInfo(Function<AnnotationsInfo, T> parser) {
        return null;
    }

    @Override
    public AnnotationsInfo getTypeAnnotationInfo() {
        return null;
    }

    @Override
    public String toString() {
        return "PodSlotField<" + getName() + ": " + type.getJavaKind() + ">";
    }
}
