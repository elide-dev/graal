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

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CodePointer;

import com.oracle.svm.core.code.CodeInfoTable;

/**
 * Maps a program counter inside image (AOT) code of a deoptimization-source method to the
 * {@link SubstrateInstalledCode} that owns it, so that invalidating deoptimizations work for image
 * code. {@link CodeInfoTable#lookupInstalledCode} only knows runtime-compiled code; for image code,
 * {@link Deoptimizer#invalidateMethodOfFrame} consults the registered lookups instead.
 * <p>
 * Lookups are registered with {@link #register} by whoever owns such code. Several lookups can be
 * registered in one image; each must return {@code null} for every frame it does not own, so that
 * the error for invalidating a frame that nobody owns is preserved.
 * <p>
 * A lookup runs on the thread that deoptimizes, in {@link Deoptimizer#invalidateMethodOfFrame}
 * outside of a VM operation, and also inside the VM operations that deoptimize frames, where
 * {@link Deoptimizer} records the owner of a deoptimized image-code frame as its source. So a
 * lookup must not lock or block. It is only consulted for frames that are not runtime-compiled
 * code, i.e., never on the path of runtime-compiled code.
 */
public interface ImageCodeInstalledCodeLookup {

    /**
     * Returns the installed code owning the image-code deopt-source frame at {@code ip}, or
     * {@code null} if this lookup does not own the frame.
     * <p>
     * The caller invalidates the returned code with {@link SubstrateInstalledCode#invalidate()} and
     * then deoptimizes the requesting frame itself, because image code is never removed and so
     * invalidating it need not deoptimize that frame.
     */
    SubstrateInstalledCode lookup(CodePointer ip);

    /**
     * Registers a lookup for the image being built. Must be called before the setup phase
     * finishes, e.g., in {@code Feature.afterRegistration} or {@code Feature.duringSetup}.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    static void register(ImageCodeInstalledCodeLookup lookup) {
        ImageCodeInstalledCodeLookups.register(lookup);
    }

    /**
     * Returns the result of the first registered lookup that owns the frame at {@code ip}, or
     * {@code null} if no lookup owns it or none is registered.
     */
    static SubstrateInstalledCode lookupOrNull(CodePointer ip) {
        if (!ImageSingletons.contains(ImageCodeInstalledCodeLookups.class)) {
            return null;
        }
        return ImageSingletons.lookup(ImageCodeInstalledCodeLookups.class).lookup(ip);
    }
}
