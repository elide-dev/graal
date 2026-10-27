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

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;

import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.metaspace.Metaspace;

public class MetaspaceAllocationProbe {
    private static volatile Object escaped;
    static final class Box {
        Object value;
    }

    private static WeakReference<Object> installReference(Box box) {
        Object value = new byte[1024];
        box.value = value;
        return new WeakReference<>(value);
    }

    public static void main(String[] args) {
        Metaspace metaspace = Metaspace.singleton();
        if (Runtime.getRuntime().maxMemory() != 64L * 1024 * 1024) {
            throw new AssertionError("Metaspace reservation was counted in the Java heap limit");
        }
        // Ensure this type has an instantiated layout in the image.
        escaped = new Box();
        Box box = metaspace.allocateObject(Box.class);
        WeakReference<Object> reference = installReference(box);
        byte[] bytes = metaspace.allocateByteArray(2_000_003);
        int[] ints = metaspace.allocateIntArray(500_003);
        bytes[bytes.length - 1] = 123;
        ints[ints.length - 1] = 456;
        if (!metaspace.isInAllocatedMemory(box) || !metaspace.isInAllocatedMemory(bytes) || !metaspace.isInAllocatedMemory(ints)) {
            throw new AssertionError("Metaspace membership check failed");
        }
        for (int round = 0; round < 16; round++) {
            System.gc();
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long afterGC = Heap.getHeap().getUsedMemoryAfterLastGC().rawValue();
            if (used < 0 || used > Runtime.getRuntime().maxMemory() || afterGC < 0 || afterGC > Runtime.getRuntime().maxMemory()) {
                throw new AssertionError("Permanent regions were included in collected-heap usage");
            }
            if (reference.get() != box.value || reference.get() == null || bytes[bytes.length - 1] != 123 || ints[ints.length - 1] != 456) {
                throw new AssertionError("Metaspace allocation or outgoing reference corrupted");
            }
        }
        try {
            metaspace.allocateByteArray(Integer.MAX_VALUE);
            throw new AssertionError("Oversized allocation succeeded");
        } catch (OutOfMemoryError expected) {
            if (!expected.getMessage().contains("metaspace")) {
                throw expected;
            }
        }
        int count = 0;
        try {
            while (count < 10_000) {
                metaspace.allocateByteArray(65_536);
                count++;
            }
            throw new AssertionError("Metaspace limit was not enforced");
        } catch (OutOfMemoryError expected) {
            if (!expected.getMessage().contains("metaspace")) {
                throw expected;
            }
        }
        System.gc();
        if (reference.get() != box.value || reference.get() == null || bytes[bytes.length - 1] != 123 || ints[ints.length - 1] != 456) {
            throw new AssertionError("Failed allocation corrupted existing objects");
        }
        box.value = null;
        for (int round = 0; round < 16 && reference.get() != null; round++) {
            System.gc();
        }
        if (reference.get() != null) {
            throw new AssertionError("Removed metaspace reference retained its former target");
        }
        System.out.println("Metaspace allocation probe passed after " + count + " allocations");
    }
}
