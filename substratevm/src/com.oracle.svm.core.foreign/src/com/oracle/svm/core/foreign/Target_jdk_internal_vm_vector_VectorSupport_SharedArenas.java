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
package com.oracle.svm.core.foreign;

import com.oracle.svm.core.annotate.AnnotateOriginal;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.foreign.ForeignAPIPredicates.SharedArenasEnabled;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.util.BasedOnJDKFile;

import jdk.internal.vm.vector.VectorSupport.LoadOperation;
import jdk.internal.vm.vector.VectorSupport.LoadVectorMaskedOperation;
import jdk.internal.vm.vector.VectorSupport.StoreVectorMaskedOperation;
import jdk.internal.vm.vector.VectorSupport.StoreVectorOperation;
import jdk.internal.vm.vector.VectorSupport.Vector;
import jdk.internal.vm.vector.VectorSupport.VectorMask;
import jdk.internal.vm.vector.VectorSupport.VectorPayload;
import jdk.internal.vm.vector.VectorSupport.VectorSpecies;

/**
 * The @Scoped Vector API accessors in {@code ScopedMemoryAccess} (for example
 * {@code loadFromMemorySegmentScopedInternal}) call these {@code VectorSupport} methods. If the call
 * is not intrinsified (for example, because Vector API support is disabled), it must remain a call
 * in the scoped region: the Java implementation calls the {@code defaultImpl} lambda, which
 * accesses the memory segment through other @Scoped accessors that check the session themselves.
 * Calls to these methods are therefore registered as safe in
 * {@code com.oracle.svm.hosted.foreign.ForeignFunctionsFeature#initSafeArenaAccessors}. If the
 * methods were inlined into the scoped region, their remaining calls (for example, to the
 * {@code defaultImpl} lambda) would not be known to be safe.
 * <p>
 * Intrinsification takes precedence over inlining, so this does not affect calls that are
 * intrinsified when Vector API support is enabled.
 */
@BasedOnJDKFile("https://github.com/graalvm/labs-openjdk/blob/jdk-25+13/src/java.base/share/classes/jdk/internal/vm/vector/VectorSupport.java")
@TargetClass(className = "jdk.internal.vm.vector.VectorSupport", onlyWith = SharedArenasEnabled.class)
final class Target_jdk_internal_vm_vector_VectorSupport_SharedArenas {

    @AnnotateOriginal
    @NeverInline("Must remain a call in @Scoped methods, see class Javadoc.")
    static native <C, VM extends VectorPayload, E, S extends VectorSpecies<E>> VM load(Class<? extends VM> vmClass, Class<E> eClass, int length,
                    Object base, long offset, boolean fromSegment,
                    C container, long index, S s,
                    LoadOperation<C, VM, S> defaultImpl);

    @AnnotateOriginal
    @NeverInline("Must remain a call in @Scoped methods, see class Javadoc.")
    static native <C, V extends Vector<?>, E, S extends VectorSpecies<E>, M extends VectorMask<E>> V loadMasked(Class<? extends V> vClass, Class<M> mClass, Class<E> eClass,
                    int length, Object base, long offset, boolean fromSegment,
                    M m, int offsetInRange,
                    C container, long index, S s,
                    LoadVectorMaskedOperation<C, V, S, M> defaultImpl);

    @AnnotateOriginal
    @NeverInline("Must remain a call in @Scoped methods, see class Javadoc.")
    static native <C, V extends VectorPayload> void store(Class<?> vClass, Class<?> eClass,
                    int length,
                    Object base, long offset, boolean fromSegment,
                    V v, C container, long index,
                    StoreVectorOperation<C, V> defaultImpl);

    @AnnotateOriginal
    @NeverInline("Must remain a call in @Scoped methods, see class Javadoc.")
    static native <C, V extends Vector<E>, M extends VectorMask<E>, E> void storeMasked(Class<? extends V> vClass, Class<M> mClass, Class<E> eClass,
                    int length,
                    Object base, long offset, boolean fromSegment,
                    V v, M m, C container, long index,
                    StoreVectorMaskedOperation<C, V, M> defaultImpl);
}
