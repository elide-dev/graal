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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;

public class RuntimeMetaspaceProbe {
    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        checkHeapAccounting();
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        Object marker = new byte[]{42};
        Class<?> type = new Loader().define(bytes);
        Object instance = type.getConstructor(Object.class, Runnable.class).newInstance(marker, (Runnable) System::gc);
        IntUnaryOperator operator = (IntUnaryOperator) instance;
        if (operator.applyAsInt(-1) != 42) {
            throw new AssertionError("Initial runtime-defined callback failed");
        }
        AtomicReference<Throwable> loadingFailure = new AtomicReference<>();
        Thread loading = new Thread(() -> {
            try {
                for (int i = 0; i < 128; i++) {
                    new Loader().define(bytes);
                }
            } catch (Throwable failure) {
                loadingFailure.set(failure);
            }
        });
        loading.start();
        for (int round = 0; round < 32; round++) {
            for (int i = 0; i < 20_000; i++) {
                if (operator.applyAsInt(i) != i * 31 + 7) {
                    throw new AssertionError("Runtime-defined interface dispatch failed");
                }
            }
            byte[][] pressure = new byte[128][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[16_384];
            }
            System.gc();
            checkHeapAccounting();
            if (operator.applyAsInt(-1) != 42) {
                throw new AssertionError("Collection with an active runtime-defined frame failed");
            }
            if (type.getField("marker").get(instance) != marker) {
                throw new AssertionError("Runtime-defined object reference was lost");
            }
            if (!type.getName().equals("RuntimeLoadedPayload") || type.getClassLoader() == null) {
                throw new AssertionError("Runtime class metadata was lost");
            }
        }
        loading.join();
        if (loadingFailure.get() != null) {
            throw new AssertionError("Concurrent class definition failed", loadingFailure.get());
        }
        if (args.length > 1 && args[1].equals("pressure")) {
            exerciseAllocationPressure(operator);
        }
        System.out.println("Runtime metaspace probe passed");
    }

    private static void checkHeapAccounting() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        if (total > runtime.maxMemory() || free < 0 || free > runtime.maxMemory()) {
            throw new AssertionError("Invalid Java heap accounting: total=" + total + " free=" + free + " max=" + runtime.maxMemory());
        }
    }

    private static void exerciseAllocationPressure(IntUnaryOperator operator) {
        byte[][] survivors = new byte[2048][];
        for (int i = 0; i < 16_384; i++) {
            byte[] value = new byte[32_768];
            value[0] = (byte) i;
            survivors[i % survivors.length] = value;
            if (i % 512 == 0) {
                checkHeapAccounting();
            }
            if (operator.applyAsInt(i) != i * 31 + 7) {
                throw new AssertionError("Runtime-compiled dispatch failed under allocation pressure");
            }
        }
        for (int i = 0; i < survivors.length; i++) {
            if (survivors[i][0] != (byte) (14_336 + i)) {
                throw new AssertionError("A surviving allocation was corrupted");
            }
        }
    }

}
