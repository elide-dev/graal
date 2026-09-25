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

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.thread.JavaVMOperation;

/**
 * Invalidates the AOT tier code of one call target at a safepoint: unlinks it, so that no call
 * enters it any more ({@code SubstrateOptimizedCallTargetInstalledCode.doInvoke} reads the code and
 * calls it without a safepoint in between), then deoptimizes all its activations on all threads.
 * <p>
 * Idempotent: the code may have been unlinked before (e.g., by the AOT tier threshold, which does
 * not deoptimize) while activations are still on a stack, so the activations are deoptimized in any
 * case; frames that are deoptimized already are skipped.
 * <p>
 * No Java synchronization is allowed in a VM operation, so the unlink is not traced here: the
 * requesting thread traces it afterwards if {@link #unlinked} is set.
 */
final class InvalidateAOTCodeOperation extends JavaVMOperation {
    private final AOTInstalledCode code;
    private final CharSequence reason;
    /** Whether this operation unlinked the code (it was linked until now). */
    boolean unlinked;

    InvalidateAOTCodeOperation(AOTInstalledCode code, CharSequence reason) {
        super(VMOperationInfos.get(InvalidateAOTCodeOperation.class, "Invalidate Truffle AOT tier code", SystemEffect.SAFEPOINT));
        this.code = code;
        this.reason = reason;
    }

    @Override
    protected void operate() {
        unlinked = code.live.unlinkAOTQuietly(reason);
        /* Image code is never freed: the metadata of the frames stays intact. */
        CodePointer start = code.entry.entryPoint;
        CodePointer end = Word.pointer(start.rawValue() + code.entry.codeSize);
        Deoptimizer.deoptimizeInRange(start, end, false, CurrentIsolate.getCurrentThread());
        code.deoptimized = true;
    }
}
