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
package com.oracle.svm.hosted.compress;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.stream.Stream;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.shared.util.VMError;

/**
 * The handful of zstd-jni entry points the image builder uses. zstd-jni ships as a plain jar next
 * to the builder jars ({@code lib/svm/builder/lib/zstd-jni-*.jar}) and is loaded through its own
 * class loader: it is not a module of the builder, and its method signatures reference
 * {@code org.jetbrains.annotations.NotNull}, which is not on the builder's compile path, so the
 * methods are bound through method handles. Images never contain zstd-jni; they link the static
 * zstd decoder instead.
 */
@Platforms(Platform.HOSTED_ONLY.class)
final class ZstdJni {
    /** Overrides the location of the zstd-jni jar, mainly for tests and unusual layouts. */
    private static final String JAR_PROPERTY = "svm.zstd.jar";

    private static final MethodHandle COMPRESS_BYTE_ARRAY;
    private static final MethodHandle COMPRESS_USING_DICT;
    private static final MethodHandle COMPRESS_BOUND;
    private static final MethodHandle IS_ERROR;
    private static final MethodHandle GET_ERROR_NAME;
    private static final MethodHandle TRAIN_FROM_BUFFER;
    private static final String UNAVAILABLE_REASON;

    static {
        MethodHandle compressByteArray = null;
        MethodHandle compressUsingDict = null;
        MethodHandle compressBound = null;
        MethodHandle isError = null;
        MethodHandle getErrorName = null;
        MethodHandle trainFromBuffer = null;
        String unavailableReason = null;
        try {
            Class<?> zstd = loadZstdClass();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            compressByteArray = lookup.findStatic(zstd, "compressByteArray",
                            MethodType.methodType(long.class, byte[].class, int.class, int.class, byte[].class, int.class, int.class, int.class));
            compressUsingDict = lookup.findStatic(zstd, "compressUsingDict",
                            MethodType.methodType(long.class, byte[].class, int.class, byte[].class, int.class, int.class, byte[].class, int.class));
            compressBound = lookup.findStatic(zstd, "compressBound", MethodType.methodType(long.class, long.class));
            isError = lookup.findStatic(zstd, "isError", MethodType.methodType(boolean.class, long.class));
            getErrorName = lookup.findStatic(zstd, "getErrorName", MethodType.methodType(String.class, long.class));
            trainFromBuffer = lookup.findStatic(zstd, "trainFromBuffer", MethodType.methodType(long.class, byte[][].class, byte[].class, boolean.class));
            /* Force loading of the JNI library now so that failures surface here. */
            long unused = (long) compressBound.invokeExact(0L);
            assert unused >= 0;
        } catch (Throwable t) {
            unavailableReason = t.toString();
        }
        COMPRESS_BYTE_ARRAY = compressByteArray;
        COMPRESS_USING_DICT = compressUsingDict;
        COMPRESS_BOUND = compressBound;
        IS_ERROR = isError;
        GET_ERROR_NAME = getErrorName;
        TRAIN_FROM_BUFFER = trainFromBuffer;
        UNAVAILABLE_REASON = unavailableReason;
    }

    private ZstdJni() {
    }

    private static Class<?> loadZstdClass() throws ReflectiveOperationException, IOException {
        try {
            /* Already visible, e.g. when running tests with zstd-jni on the class path. */
            return Class.forName("com.github.luben.zstd.Zstd");
        } catch (ClassNotFoundException ignored) {
            /* Expected in the image builder: zstd-jni is not on its module path. */
        }
        Path jar = locateJar();
        if (jar == null) {
            throw new ClassNotFoundException("The zstd-jni jar was not found next to the image builder; set -D" + JAR_PROPERTY + " to its location");
        }
        @SuppressWarnings("resource")
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ZstdJni.class.getClassLoader());
        return Class.forName("com.github.luben.zstd.Zstd", true, loader);
    }

    private static Path locateJar() throws IOException {
        String override = System.getProperty(JAR_PROPERTY);
        if (override != null) {
            return Path.of(override);
        }
        CodeSource codeSource = ZstdJni.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        Path builderJar;
        try {
            builderJar = Path.of(codeSource.getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
        Path libDir = builderJar.getParent() == null ? null : builderJar.getParent().resolve("lib");
        if (libDir == null || !Files.isDirectory(libDir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(libDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("zstd-jni") && p.getFileName().toString().endsWith(".jar")).findFirst().orElse(null);
        }
    }

    static boolean isAvailable() {
        return UNAVAILABLE_REASON == null;
    }

    static String getUnavailableReason() {
        return UNAVAILABLE_REASON;
    }

    static long compress(byte[] dst, byte[] src, int level) {
        try {
            return (long) COMPRESS_BYTE_ARRAY.invokeExact(dst, 0, dst.length, src, 0, src.length, level);
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere(t);
        }
    }

    static long compressUsingDict(byte[] dst, byte[] src, byte[] dict, int level) {
        try {
            return (long) COMPRESS_USING_DICT.invokeExact(dst, 0, src, 0, src.length, dict, level);
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere(t);
        }
    }

    static long compressBound(long srcSize) {
        try {
            return (long) COMPRESS_BOUND.invokeExact(srcSize);
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere(t);
        }
    }

    static boolean isError(long code) {
        try {
            return (boolean) IS_ERROR.invokeExact(code);
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere(t);
        }
    }

    static String getErrorName(long code) {
        try {
            return (String) GET_ERROR_NAME.invokeExact(code);
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere(t);
        }
    }

    /** Returns the dictionary size written into {@code dictBuffer}, or a zstd error code. */
    static long trainFromBuffer(byte[][] samples, byte[] dictBuffer) {
        try {
            return (long) TRAIN_FROM_BUFFER.invokeExact(samples, dictBuffer, false);
        } catch (Throwable t) {
            throw VMError.shouldNotReachHere(t);
        }
    }
}
