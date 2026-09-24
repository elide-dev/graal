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
package com.oracle.svm.core.deopt;

import java.util.Arrays;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CodePointer;

import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.SingleLayer;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.InitialLayerOnly;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.VMError;

/**
 * The {@link ImageCodeInstalledCodeLookup}s registered for the image. The singleton only exists if
 * at least one lookup was registered. The array is replaced, never modified, on registration, and
 * registration ends before analysis, so the array is immutable in the image.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = SingleLayer.class, layeredInstallationKind = InitialLayerOnly.class)
final class ImageCodeInstalledCodeLookups {

    private ImageCodeInstalledCodeLookup[] lookups = new ImageCodeInstalledCodeLookup[0];

    @Platforms(Platform.HOSTED_ONLY.class)
    private ImageCodeInstalledCodeLookups() {
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    static synchronized void register(ImageCodeInstalledCodeLookup lookup) {
        VMError.guarantee(lookup != null, "lookup must not be null");
        /*
         * ImageSingletons.contains is folded when ImageCodeInstalledCodeLookup.lookupOrNull is
         * parsed, so the set of lookups must be final before any method can be parsed.
         */
        VMError.guarantee(!BuildPhaseProvider.isSetupFinished(), "Image code installed code lookups must be registered before the setup phase finishes.");
        ImageCodeInstalledCodeLookups registry;
        if (ImageSingletons.contains(ImageCodeInstalledCodeLookups.class)) {
            registry = ImageSingletons.lookup(ImageCodeInstalledCodeLookups.class);
        } else {
            registry = new ImageCodeInstalledCodeLookups();
            ImageSingletons.add(ImageCodeInstalledCodeLookups.class, registry);
        }
        ImageCodeInstalledCodeLookup[] old = registry.lookups;
        ImageCodeInstalledCodeLookup[] updated = Arrays.copyOf(old, old.length + 1);
        updated[old.length] = lookup;
        registry.lookups = updated;
    }

    SubstrateInstalledCode lookup(CodePointer ip) {
        for (ImageCodeInstalledCodeLookup lookup : lookups) {
            SubstrateInstalledCode code = lookup.lookup(ip);
            if (code != null) {
                return code;
            }
        }
        return null;
    }
}
