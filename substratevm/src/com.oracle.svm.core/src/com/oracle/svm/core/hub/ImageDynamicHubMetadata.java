/*
 * Copyright (c) 2024, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.hub;

import static com.oracle.svm.core.reflect.RuntimeMetadataDecoder.NO_DATA;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.reflect.RuntimeMetadataDecoder;

/**
 * Class metadata of image classes (enclosing method, annotations, nest members, ...). The seven
 * indices into the runtime metadata encoding are packed into
 * {@link DynamicHubCompanion#encodedHubMetadata} with {@link PackedMetadataEncoding}, so no object
 * per class is needed. Runtime-loaded classes use {@link RuntimeDynamicHubMetadata} instead.
 */
final class ImageDynamicHubMetadata {
    private static final int ENCLOSING_METHOD_INFO = 1;
    private static final int ANNOTATIONS = 1 << 1;
    private static final int TYPE_ANNOTATIONS = 1 << 2;
    private static final int CLASSES = 1 << 3;
    private static final int PERMITTED_SUBCLASSES = 1 << 4;
    private static final int NEST_MEMBERS = 1 << 5;
    private static final int SIGNERS = 1 << 6;

    private static final Class<?>[] EMPTY_CLASSES = new Class<?>[0];

    @Platforms(Platform.HOSTED_ONLY.class)
    private ImageDynamicHubMetadata() {
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    static int encode(int enclosingMethodInfoIndex, int annotationsIndex, int typeAnnotationsIndex, int classesEncodingIndex, int permittedSubclassesEncodingIndex,
                    int nestMembersEncodingIndex, int signersEncodingIndex) {
        int[] values = {enclosingMethodInfoIndex, annotationsIndex, typeAnnotationsIndex, classesEncodingIndex, permittedSubclassesEncodingIndex, nestMembersEncodingIndex, signersEncodingIndex};
        int mask = 0;
        for (int bit = 0; bit < values.length; bit++) {
            if (values[bit] != NO_DATA) {
                mask |= 1 << bit;
            }
        }
        return PackedMetadataEncoding.encode(mask, values);
    }

    private static int getIndex(int encoded, int bit, DynamicHub declaringClass) {
        return PackedMetadataEncoding.getValue(encoded, bit, NO_DATA, declaringClass.getLayerId());
    }

    private static RuntimeMetadataDecoder decoder() {
        return ImageSingletons.lookup(RuntimeMetadataDecoder.class);
    }

    /**
     * @return null or a value compatible with {@code java.lang.Class.EnclosingMethodInfo(Object[])}
     */
    static Object[] getEnclosingMethod(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, ENCLOSING_METHOD_INFO, declaringClass);
        if (index == NO_DATA) {
            return null;
        }
        Object[] enclosingMethod = decoder().parseEnclosingMethod(index, declaringClass);
        if (enclosingMethod != null) {
            PredefinedClassesSupport.throwIfUnresolvable((Class<?>) enclosingMethod[0], declaringClass.getClassLoader0());
        }
        return enclosingMethod;
    }

    static Object[] getSigners(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, SIGNERS, declaringClass);
        if (index == NO_DATA) {
            return null;
        }
        return decoder().parseObjects(index, declaringClass);
    }

    static byte[] getRawAnnotations(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, ANNOTATIONS, declaringClass);
        if (index == NO_DATA) {
            return null;
        }
        return decoder().parseByteArray(index, declaringClass);
    }

    static byte[] getRawTypeAnnotations(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, TYPE_ANNOTATIONS, declaringClass);
        if (index == NO_DATA) {
            return null;
        }
        return decoder().parseByteArray(index, declaringClass);
    }

    static Class<?>[] getDeclaredClasses(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, CLASSES, declaringClass);
        if (index == NO_DATA) {
            return EMPTY_CLASSES;
        }
        return parseClasses(index, declaringClass);
    }

    static Class<?>[] getNestMembers(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, NEST_MEMBERS, declaringClass);
        if (index == NO_DATA) {
            return new Class<?>[]{DynamicHub.toClass(declaringClass)};
        }
        return parseClasses(index, declaringClass);
    }

    /**
     * Returns the permitted subclasses for {@code declaringClass}. Must only be called when
     * {@code declaringClass} represents a sealed type.
     */
    static Class<?>[] getPermittedSubClasses(int encoded, DynamicHub declaringClass) {
        int index = getIndex(encoded, PERMITTED_SUBCLASSES, declaringClass);
        if (index == NO_DATA) {
            return EMPTY_CLASSES;
        }
        return parseClasses(index, declaringClass);
    }

    private static Class<?>[] parseClasses(int index, DynamicHub declaringClass) {
        Class<?>[] classes = decoder().parseClasses(index, declaringClass);
        for (Class<?> clazz : classes) {
            PredefinedClassesSupport.throwIfUnresolvable(clazz, declaringClass.getClassLoader0());
        }
        return classes;
    }
}
