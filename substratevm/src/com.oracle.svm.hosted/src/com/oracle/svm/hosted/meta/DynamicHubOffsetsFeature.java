/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.meta;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.util.ReflectionUtil;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.DynamicHubOffsets;
import com.oracle.svm.core.hub.DynamicHubCompanion;
import com.oracle.svm.hosted.FeatureImpl;

@AutomaticallyRegisteredFeature
public final class DynamicHubOffsetsFeature implements InternalFeature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(DynamicHubOffsets.class, new DynamicHubOffsets());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        /*
         * DynamicHubCompanion.extension is published at run time with Unsafe, at the offset that
         * initializeOffsets computes below. The analysis cannot attribute that write to the field,
         * so it must be registered as unsafe accessed: otherwise the analysis considers the field
         * never written, HostedConstantFieldProvider treats it as final, and reads through a
         * constant class (e.g. SomeClass.class.getSimpleName() or getAnnotation() inlined into a
         * caller) fold to the build-time value null. Class.newReflectionData() and
         * Class.annotationData() then retry their CAS forever once the extension exists.
         */
        access.registerAsUnsafeAccessed(ReflectionUtil.lookupField(DynamicHubCompanion.class, "extension"));
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess a) {
        DynamicHubOffsets.singleton().initializeOffsets(((FeatureImpl.BeforeCompilationAccessImpl) a).getMetaAccess());
    }
}
