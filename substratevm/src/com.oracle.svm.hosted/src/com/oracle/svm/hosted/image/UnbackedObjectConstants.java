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
package com.oracle.svm.hosted.image;

import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.hosted.FeatureImpl.DuringSetupAccessImpl;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;

import jdk.graal.compiler.core.common.type.CompressibleConstant;
import jdk.vm.ci.meta.JavaConstant;

/**
 * Object constants of code metadata (e.g., the frame info object constants of the image code) are
 * installed into hosted {@code Object[]} arrays, which the image heap then contains. An
 * {@link ImageHeapConstant} that is not backed by a hosted object (e.g., created by simulated class
 * initialization) has no hosted object to store there, so it used to be stored as {@code null}. It
 * is now stored as a {@link Placeholder}, which the image heap replaces with the constant.
 */
public final class UnbackedObjectConstants {

    private UnbackedObjectConstants() {
    }

    /** Stands for an unbacked constant in a hosted array; never reaches the image heap. */
    public record Placeholder(ImageHeapConstant constant) {
    }

    /** Returns the object to store in a hosted array for {@code constant}, or {@code null}. */
    public static Placeholder placeholderFor(JavaConstant constant) {
        if (constant instanceof ImageHeapConstant heapConstant && !heapConstant.isBackedByHostedObject()) {
            return new Placeholder((ImageHeapConstant) CompressibleConstant.uncompress(heapConstant));
        }
        return null;
    }

    static ImageHeapConstant replace(Object object) {
        return (object instanceof Placeholder placeholder) ? placeholder.constant() : null;
    }
}

@AutomaticallyRegisteredFeature
final class UnbackedObjectConstantsFeature implements InternalFeature {
    @Override
    public void duringSetup(DuringSetupAccess a) {
        ((DuringSetupAccessImpl) a).registerObjectToConstantReplacer(UnbackedObjectConstants::replace);
    }
}
