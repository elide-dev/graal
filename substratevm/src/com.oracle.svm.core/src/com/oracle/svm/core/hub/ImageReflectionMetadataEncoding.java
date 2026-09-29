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
package com.oracle.svm.core.hub;

import static com.oracle.svm.core.reflect.RuntimeMetadataDecoder.NO_DATA;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.guest.staging.configure.RuntimeDynamicAccessMetadata;
import com.oracle.svm.core.reflect.RuntimeMetadataDecoder;

/**
 * Compact encoding for non-layered image reflection metadata.
 * <p>
 * The value stored in {@link DynamicHubCompanion#encodedReflectionMetadata} is a
 * {@link PackedMetadataEncoding} of the following values in ascending bit order: fields, methods,
 * constructors, record components, dynamic access, unsafe allocation, and class flags.
 */
final class ImageReflectionMetadataEncoding {
    private static final int FIELDS = 1;
    private static final int METHODS = 1 << 1;
    private static final int CONSTRUCTORS = 1 << 2;
    private static final int RECORD_COMPONENTS = 1 << 3;
    private static final int DYNAMIC_ACCESS = 1 << 4;
    private static final int UNSAFE_ALLOCATED = 1 << 5;
    private static final int CLASS_FLAGS = 1 << 6;

    private static final Field[] EMPTY_FIELDS = new Field[0];
    private static final Method[] EMPTY_METHODS = new Method[0];
    private static final Constructor<?>[] EMPTY_CONSTRUCTORS = new Constructor<?>[0];

    @Platforms(Platform.HOSTED_ONLY.class)
    private ImageReflectionMetadataEncoding() {
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static int encode(int fieldsEncodingIndex, int methodsEncodingIndex, int constructorsEncodingIndex, int recordComponentsEncodingIndex, int dynamicAccessIndex,
                    int unsafeAllocationIndex, int classFlags, int defaultClassFlags) {
        int mask = 0;
        if (fieldsEncodingIndex != NO_DATA) {
            mask |= FIELDS;
        }
        if (methodsEncodingIndex != NO_DATA) {
            mask |= METHODS;
        }
        if (constructorsEncodingIndex != NO_DATA) {
            mask |= CONSTRUCTORS;
        }
        if (recordComponentsEncodingIndex != NO_DATA) {
            mask |= RECORD_COMPONENTS;
        }
        if (dynamicAccessIndex != NO_DATA) {
            mask |= DYNAMIC_ACCESS;
        }
        if (unsafeAllocationIndex != NO_DATA) {
            mask |= UNSAFE_ALLOCATED;
        }
        if (classFlags != defaultClassFlags) {
            mask |= CLASS_FLAGS;
        }

        int[] values = {fieldsEncodingIndex, methodsEncodingIndex, constructorsEncodingIndex, recordComponentsEncodingIndex, dynamicAccessIndex, unsafeAllocationIndex, classFlags};
        return PackedMetadataEncoding.encode(mask, values);
    }

    public static boolean hasMetadata(int encodedReflectionMetadata) {
        return PackedMetadataEncoding.hasMetadata(encodedReflectionMetadata);
    }

    public static int getClassFlags(int encodedReflectionMetadata, int defaultClassFlags) {
        return getValue(encodedReflectionMetadata, CLASS_FLAGS, defaultClassFlags);
    }

    public static RuntimeDynamicAccessMetadata getDynamicAccessMetadata(int encodedReflectionMetadata, int layerNum) {
        int index = getValue(encodedReflectionMetadata, DYNAMIC_ACCESS, NO_DATA);
        return index == NO_DATA ? null : RuntimeMetadataDecoder.singleton().parseDynamicAccessMetadata(index, layerNum);
    }

    public static RuntimeDynamicAccessMetadata getUnsafeAllocationMetadata(int encodedReflectionMetadata, int layerNum) {
        int index = getValue(encodedReflectionMetadata, UNSAFE_ALLOCATED, NO_DATA);
        return index == NO_DATA ? null : RuntimeMetadataDecoder.singleton().parseDynamicAccessMetadata(index, layerNum);
    }

    public static Field[] getDeclaredFields(int encodedReflectionMetadata, DynamicHub declaringClass, boolean publicOnly, int layerNum) {
        int index = getValue(encodedReflectionMetadata, FIELDS, NO_DATA);
        return index == NO_DATA ? EMPTY_FIELDS : RuntimeMetadataDecoder.singleton().parseFields(declaringClass, index, publicOnly, layerNum);
    }

    public static Method[] getDeclaredMethods(int encodedReflectionMetadata, DynamicHub declaringClass, boolean publicOnly, int layerNum) {
        int index = getValue(encodedReflectionMetadata, METHODS, NO_DATA);
        return index == NO_DATA ? EMPTY_METHODS : RuntimeMetadataDecoder.singleton().parseMethods(declaringClass, index, publicOnly, layerNum);
    }

    public static Constructor<?>[] getDeclaredConstructors(int encodedReflectionMetadata, DynamicHub declaringClass, boolean publicOnly, int layerNum) {
        int index = getValue(encodedReflectionMetadata, CONSTRUCTORS, NO_DATA);
        return index == NO_DATA ? EMPTY_CONSTRUCTORS : RuntimeMetadataDecoder.singleton().parseConstructors(declaringClass, index, publicOnly, layerNum);
    }

    public static RecordComponent[] getRecordComponents(int encodedReflectionMetadata, DynamicHub declaringClass, int layerNum) {
        int index = getValue(encodedReflectionMetadata, RECORD_COMPONENTS, NO_DATA);
        if (index == NO_DATA) {
            throw DynamicHub.recordsNotAvailable(declaringClass);
        }
        return RuntimeMetadataDecoder.singleton().parseRecordComponents(declaringClass, index, layerNum);
    }

    private static int getValue(int encodedReflectionMetadata, int bit, int defaultValue) {
        /* Non-layered images only, so the side table always lives in layer 0. */
        return PackedMetadataEncoding.getValue(encodedReflectionMetadata, bit, defaultValue, 0);
    }
}
