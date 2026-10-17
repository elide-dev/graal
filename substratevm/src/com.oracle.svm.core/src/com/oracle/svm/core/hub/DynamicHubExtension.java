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

import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.security.ProtectionDomain;

import com.oracle.svm.core.configure.RuntimeDynamicAccessMetadata;

import jdk.internal.vm.annotation.Stable;
import sun.reflect.annotation.AnnotationType;
import sun.reflect.generics.repository.ClassRepository;

/**
 * Rarely used, mostly lazily computed data of a {@link DynamicHub}. Most classes never need any of
 * these values, so they live in a side object that {@link DynamicHubCompanion#ensureExtension()}
 * allocates on first use instead of taking a slot in every companion.
 *
 * All fields are written at most a few times, either during image building or lazily at run time
 * with the same visibility rules as the mutable {@link DynamicHubCompanion} fields.
 */
public final class DynamicHubExtension {
    String packageName;
    ProtectionDomain protectionDomain;
    ClassRepository genericInfo;
    SoftReference<Target_java_lang_Class_ReflectionData<?>> reflectionData;
    AnnotationType annotationType;
    Object annotationData;
    Constructor<?> cachedConstructor;
    /** Set during image building for JFR event classes; see {@code JfrEventFeature}. */
    Object jfrEventConfiguration;
    @Stable RuntimeDynamicAccessMetadata dynamicAccess;
    @Stable RuntimeDynamicAccessMetadata canUnsafeAllocate;
    Object classData;

    /** Class metadata of runtime-loaded classes; null for image classes. */
    @Stable RuntimeDynamicHubMetadata hubMetadata;

    /** Reflection metadata of runtime-loaded classes; null for image classes. */
    @Stable ReflectionMetadata reflectionMetadata;

    DynamicHubExtension() {
    }
}
