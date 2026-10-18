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

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;

/**
 * The decompression entry points of the statically linked zstd library (project
 * {@code com.oracle.svm.native.libzstd}). All functions are declared without a thread-state
 * transition so that they can be called from uninterruptible code such as the frame-info decoder;
 * callers must keep the buffers they pass alive and non-moving for the duration of the call.
 */
@CLibrary(value = "zstd", requireStatic = true)
public final class Zstd {
    private Zstd() {
    }

    // Checkstyle: stop

    /** {@code size_t ZSTD_decompress(void* dst, size_t dstCapacity, const void* src, size_t srcSize)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native UnsignedWord ZSTD_decompress(PointerBase dst, UnsignedWord dstCapacity, PointerBase src, UnsignedWord srcSize);

    /** {@code ZSTD_DCtx* ZSTD_createDCtx(void)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native PointerBase ZSTD_createDCtx();

    /** {@code size_t ZSTD_freeDCtx(ZSTD_DCtx* dctx)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native UnsignedWord ZSTD_freeDCtx(PointerBase dctx);

    /** {@code size_t ZSTD_decompressDCtx(ZSTD_DCtx* dctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native UnsignedWord ZSTD_decompressDCtx(PointerBase dctx, PointerBase dst, UnsignedWord dstCapacity, PointerBase src, UnsignedWord srcSize);

    /** {@code ZSTD_dlm_byRef}: a digested dictionary references the dictionary content. */
    public static final int ZSTD_dlm_byRef = 1;
    /** {@code ZSTD_dct_auto}: the dictionary content type is detected. */
    public static final int ZSTD_dct_auto = 0;

    /** {@code size_t ZSTD_estimateDDictSize(size_t dictSize, ZSTD_dictLoadMethod_e dictLoadMethod)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native UnsignedWord ZSTD_estimateDDictSize(UnsignedWord dictSize, int dictLoadMethod);

    /**
     * {@code const ZSTD_DDict* ZSTD_initStaticDDict(void* sBuffer, size_t sBufferSize, const void* dict, size_t dictSize, ZSTD_dictLoadMethod_e dictLoadMethod, ZSTD_dictContentType_e dictContentType)}.
     * Digests a dictionary into a caller-provided, 8-byte aligned buffer; nothing is allocated, and
     * the result must not be passed to {@code ZSTD_freeDDict}.
     */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native PointerBase ZSTD_initStaticDDict(PointerBase sBuffer, UnsignedWord sBufferSize, PointerBase dict, UnsignedWord dictSize, int dictLoadMethod, int dictContentType);

    /**
     * {@code size_t ZSTD_decompress_usingDDict(ZSTD_DCtx* dctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize, const ZSTD_DDict* ddict)}.
     */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native UnsignedWord ZSTD_decompress_usingDDict(PointerBase dctx, PointerBase dst, UnsignedWord dstCapacity, PointerBase src, UnsignedWord srcSize, PointerBase ddict);

    /** {@code unsigned ZSTD_isError(size_t code)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native int ZSTD_isError(UnsignedWord code);

    /** {@code const char* ZSTD_getErrorName(size_t code)}. */
    @CFunction(transition = Transition.NO_TRANSITION)
    public static native CCharPointer ZSTD_getErrorName(UnsignedWord code);

    // Checkstyle: resume
}
