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

import org.graalvm.nativeimage.c.function.CodePointer;

import com.oracle.svm.core.deopt.ImageCodeInstalledCodeLookup;
import com.oracle.svm.core.deopt.SubstrateInstalledCode;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.shared.BuildPhaseProvider;

/**
 * Resolves frames of AOT tier methods to their {@link AOTInstalledCode}, for
 * {@code Deoptimizer.invalidateMethodOfFrame}. The codes are sorted by entry address when the image
 * code is laid out; the order is kept at run time because all entry points are relocated relative
 * to the same code base.
 */
final class AOTCodeTable implements ImageCodeInstalledCodeLookup {
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private AOTInstalledCode[] codes;

    void setCodes(AOTInstalledCode[] sortedCodes) {
        this.codes = sortedCodes;
    }

    @Override
    public SubstrateInstalledCode lookup(CodePointer ip) {
        AOTInstalledCode[] table = codes;
        long pc = ip.rawValue();
        int lo = 0;
        int hi = table.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            AOTInstalledCode code = table[mid];
            long start = code.entry.entryPoint.rawValue();
            if (Long.compareUnsigned(pc, start) < 0) {
                hi = mid - 1;
            } else if (Long.compareUnsigned(pc, start + code.entry.codeSize) >= 0) {
                lo = mid + 1;
            } else {
                return code;
            }
        }
        return null;
    }
}
