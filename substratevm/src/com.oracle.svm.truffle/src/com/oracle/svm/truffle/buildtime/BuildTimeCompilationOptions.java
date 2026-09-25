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
package com.oracle.svm.truffle.buildtime;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.hosted.image.NativeImageCodeCache;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;

/** Hosted options of the Truffle AOT tier (build-time compilation of pre-initialized roots). */
public final class BuildTimeCompilationOptions {
    @Option(help = "Print a per-root summary of Truffle build-time (AOT tier) compilation.")//
    public static final HostedOptionKey<Boolean> TruffleAOTPrintSummary = new HostedOptionKey<>(false);

    /**
     * Also enables {@code VerifyDeoptSourceEntryPoints}: a failed verification fails the build,
     * which only this option allows the AOT tier to do.
     */
    @Option(help = "Fail the image build if a root of the pre-initialized engine is not compiled at build time, unless polyglot.image-build-time.AOTCompileFilter excludes it " +
                    "or it was executed at build time. Enables VerifyDeoptSourceEntryPoints.")//
    public static final HostedOptionKey<Boolean> TruffleAOTFailOnError = new HostedOptionKey<>(false) {
        @Override
        protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, Boolean oldValue, Boolean newValue) {
            super.onValueUpdate(values, oldValue, newValue);
            if (newValue) {
                NativeImageCodeCache.Options.VerifyDeoptSourceEntryPoints.update(values, true);
            }
        }
    };

    private BuildTimeCompilationOptions() {
    }
}
