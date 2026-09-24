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
package com.oracle.svm.test.deopt;

import org.graalvm.nativeimage.c.function.CodePointer;

import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.FrameInfoQueryResult;
import com.oracle.svm.core.deopt.ImageCodeInstalledCodeLookup;
import com.oracle.svm.core.deopt.SubstrateInstalledCode;
import com.oracle.svm.core.deopt.SubstrateSpeculationLog;
import com.oracle.svm.shared.Uninterruptible;

import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Resolves only frames of the image code of {@link DeoptSourceTest#computeInvalidating}, i.e.,
 * IPs whose outermost frame is that method, and returns null for every other IP.
 */
public final class DeoptSourceTestLookup implements ImageCodeInstalledCodeLookup {
    private final TestInstalledCode code;

    DeoptSourceTestLookup(TestInstalledCode code) {
        this.code = code;
    }

    @Override
    public SubstrateInstalledCode lookup(CodePointer ip) {
        CodeInfo info = CodeInfoTable.lookupImageCodeInfo(ip);
        if (info.isNull()) {
            return null;
        }
        FrameInfoQueryResult frame = CodeInfoTable.lookupCodeInfoQueryResult(info, ip).getFrameInfo();
        if (frame == null) {
            return null;
        }
        while (frame.getCaller() != null) {
            frame = frame.getCaller();
        }
        if (frame.getSourceClass() == DeoptSourceTest.class && "computeInvalidating".equals(frame.getSourceMethodName())) {
            return code;
        }
        return null;
    }

    /** Stands in for the installed code that owns the image code of a deopt source. */
    public static final class TestInstalledCode implements SubstrateInstalledCode {
        public static int invalidations;
        public static int reprofiles;

        @Override
        public String getName() {
            return "DeoptSourceTest";
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public long getEntryPoint() {
            return 0;
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public long getAddress() {
            return 0;
        }

        @Override
        public ResolvedJavaMethod getMethod() {
            return null;
        }

        @Override
        public void setAddress(long address, long entryPoint, ResolvedJavaMethod method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void invalidate() {
            /* Deliberately does not deoptimize: the requesting frame is deoptimized by the caller. */
            invalidations++;
        }

        @Override
        public void reprofile() {
            reprofiles++;
        }

        @Override
        public void recordDeoptimization(DeoptimizationReason reason) {
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public boolean isAlive() {
            return true;
        }

        @Override
        public void makeNonEntrant() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubstrateSpeculationLog getSpeculationLog() {
            return null;
        }

        @Override
        public void setCompilationId(CompilationIdentifier id) {
        }
    }
}
