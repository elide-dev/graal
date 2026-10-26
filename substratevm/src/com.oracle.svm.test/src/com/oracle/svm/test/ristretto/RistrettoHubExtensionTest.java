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
package com.oracle.svm.test.ristretto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.graalvm.nativeimage.hosted.Feature;
import org.junit.Test;

import com.oracle.svm.core.hub.DynamicHubCompanion;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.meta.HostedConstantFieldProvider;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.shared.util.ReflectionUtil;

/**
 * The lazily allocated {@code DynamicHubCompanion.extension} holds the reflection caches of a
 * class ({@code Class.reflectionData}, {@code annotationData}, ...). It is published at run time
 * with Unsafe, a write that the static analysis cannot attribute to the field. If the analysis
 * then considers the field never written, the hosted compiler treats it as final and folds reads
 * of it through a constant class to the build-time value null. The CAS retry loops of
 * {@code Class.newReflectionData()} and {@code Class.annotationData()} then never terminate once
 * the extension exists, e.g. for {@code SpeculationLog.class.getSimpleName()} in
 * {@code SpeculativeStoreChecksPhase.notApplicableTo()} of a Ristretto compilation, which made
 * {@code RistrettoCompilationManager.shutDown()} wait forever (elide-dev/graal#162).
 *
 * The folding depends on inlining into the constant site (observed with {@code -O3}), so the
 * regression check runs in the builder (see {@link #beforeCompilation}), where the hosted field
 * must not qualify as final.
 */
public class RistrettoHubExtensionTest {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {
    }

    @Marker
    static final class Probe {
    }

    /** Called by {@code RistrettoDeoptimizationTest.TestFeature#beforeCompilation}. */
    static void beforeCompilation(Feature.BeforeCompilationAccess access) {
        var metaAccess = ((FeatureImpl.BeforeCompilationAccessImpl) access).getMetaAccess();
        HostedField extension = metaAccess.lookupJavaField(ReflectionUtil.lookupField(DynamicHubCompanion.class, "extension"));
        if (HostedConstantFieldProvider.isFinalField(extension)) {
            throw new AssertionError("DynamicHubCompanion.extension is published at run time but would be constant folded: " + extension);
        }
    }

    @Test
    public void testReflectionCachesOfConstantClass() {
        // Checkstyle: allow direct annotation access
        for (int i = 0; i < 3; i++) {
            assertEquals("Probe", Probe.class.getSimpleName());
            assertNotNull(Probe.class.getAnnotation(Marker.class));
        }
        assertSame(Probe.class.getAnnotation(Marker.class), Probe.class.getAnnotation(Marker.class));
        assertSame(Probe.class.getProtectionDomain(), Probe.class.getProtectionDomain());
        assertFalse(Probe.class.isAnnotationPresent(Test.class));
        // Checkstyle: disallow direct annotation access
    }
}
