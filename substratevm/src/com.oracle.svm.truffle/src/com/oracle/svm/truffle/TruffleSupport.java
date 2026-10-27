/*
 * Copyright (c) 2013, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.truffle;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.truffle.api.SubstrateTruffleCompiler;
import com.oracle.svm.truffle.api.SubstrateTruffleRuntime;
import com.oracle.svm.truffle.api.SubstrateTruffleSupport;
import com.oracle.truffle.compiler.TruffleCompilerRuntime;

/**
 * The Truffle compiler extension point of Native Image before upstream GR-79669 folded it into
 * {@link SubstrateTruffleSupport}, kept for Oracle's prebuilt enterprise Truffle jar.
 * <p>
 * The EE base of the fork (Oracle GraalVM EA {@code jdk-25i5-25.0.4.1.1-ea.05}) ships a
 * {@code truffle-enterprise-svm.jar} whose {@code EnterpriseSupport} extends this class, overrides
 * {@link #createTruffleCompiler} (enterprise compiler configuration, enterprise compiler isolates)
 * and {@link #createBackgroundCompileQueue}, and is registered as the {@code TruffleSupport} image
 * singleton by {@code EnterpriseTruffleFeature.afterRegistration}. Its upstream replacement,
 * {@code EnterpriseTruffleSupport} extending {@link SubstrateTruffleSupport}, is not in that jar.
 * {@link SubstrateTruffleSupport} therefore delegates to a registered {@code TruffleSupport}
 * singleton. Nothing registers one in Native Image CE.
 * <p>
 * Remove this class once the EE base contains GR-79669.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public class TruffleSupport extends SubstrateTruffleSupport {

    public TruffleSupport() {
    }

    public static TruffleSupport singleton() {
        return ImageSingletons.lookup(TruffleSupport.class);
    }

    /**
     * The compiler of a Truffle runtime; the method that subclasses override.
     */
    public SubstrateTruffleCompiler createTruffleCompiler(SubstrateTruffleRuntime runtime) {
        return super.createCompiler(runtime);
    }

    @Override
    @Platforms(Platform.HOSTED_ONLY.class)
    public SubstrateTruffleCompiler createCompiler(TruffleCompilerRuntime compilerRuntime) {
        return createTruffleCompiler((SubstrateTruffleRuntime) compilerRuntime);
    }

    @Override
    public String getCompilerConfigurationName(TruffleCompilerRuntime runtime) {
        /*
         * Only Oracle's enterprise jar subclasses this class, and its compiler uses the enterprise
         * configuration.
         */
        return getClass() == TruffleSupport.class ? super.getCompilerConfigurationName(runtime) : "enterprise";
    }
}
