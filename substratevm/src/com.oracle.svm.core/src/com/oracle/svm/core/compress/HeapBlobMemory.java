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
package com.oracle.svm.core.compress;

import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.VMInspectionOptions;
import com.oracle.svm.core.nmt.NativeMemoryTracking;
import com.oracle.svm.core.nmt.NmtCategory;
import com.oracle.svm.core.os.VirtualMemoryProvider;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/**
 * Per-isolate memory for data derived from compressed image-heap blobs, such as inflated frame
 * information and digested zstd dictionaries. It is mapped anonymously instead of taken from
 * {@code malloc}:
 * <ul>
 * <li>The operating system provides the pages zero-filled on first touch, so large buffers cost
 * neither faults nor zeroing at isolate creation, whatever their size.</li>
 * <li>The only references to this memory are in the isolate's image heap, which leak checkers
 * such as LeakSanitizer do not scan; {@code malloc}'d buffers that live as long as the process
 * would be reported as leaked at exit.</li>
 * </ul>
 * Owners release the memory at isolate teardown. It is accounted as image heap in native memory
 * tracking, since it holds image-heap content.
 */
public final class HeapBlobMemory {
    private HeapBlobMemory() {
    }

    /**
     * Maps {@code nbytes} of zero-filled memory that is readable and writable, or returns null if
     * the memory cannot be mapped. The memory is aligned to at least the page size.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static Pointer allocate(UnsignedWord nbytes) {
        Pointer memory = VirtualMemoryProvider.get().commit(Word.nullPointer(), nbytes, VirtualMemoryProvider.Access.READ | VirtualMemoryProvider.Access.WRITE);
        if (memory.isNonNull() && VMInspectionOptions.hasNativeMemoryTrackingSupport()) {
            NativeMemoryTracking.singleton().trackReserve(nbytes, NmtCategory.ImageHeap);
            NativeMemoryTracking.singleton().trackCommit(nbytes, NmtCategory.ImageHeap);
        }
        return memory;
    }

    /** Unmaps memory from {@link #allocate}; {@code nbytes} must be the size it was mapped with. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void free(Pointer memory, UnsignedWord nbytes) {
        if (VMInspectionOptions.hasNativeMemoryTrackingSupport()) {
            NativeMemoryTracking.singleton().trackUncommit(nbytes, NmtCategory.ImageHeap);
            NativeMemoryTracking.singleton().trackFree(nbytes, NmtCategory.ImageHeap);
        }
        int result = VirtualMemoryProvider.get().free(memory, nbytes);
        VMError.guarantee(result == 0, "Error while unmapping memory of inflated image-heap blobs");
    }
}
