/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
#include <stdint.h>
#include <trufflenfi.h>

#include "common.h"

#define GEN_NUMERIC_TEST(name, type)                                                                                                                 \
                                                                                                                                                     \
    EXPORT type increment_##name(type arg) {                                                                                                         \
        return arg + 1;                                                                                                                              \
    }                                                                                                                                                \
                                                                                                                                                     \
    EXPORT type decrement_##name(type arg) {                                                                                                         \
        return arg - 1;                                                                                                                              \
    }                                                                                                                                                \
                                                                                                                                                     \
    EXPORT type call_closure_##name(type (*fn)(type), type arg) {                                                                                    \
        return fn(arg);                                                                                                                              \
    }                                                                                                                                                \
                                                                                                                                                     \
    EXPORT type callback_##name(type (*fn)(type), type arg) {                                                                                        \
        return fn(arg + 1) * 2;                                                                                                                      \
    }                                                                                                                                                \
                                                                                                                                                     \
    typedef type (*fnptr_##name)(type);                                                                                                              \
                                                                                                                                                     \
    EXPORT fnptr_##name callback_ret_##name() {                                                                                                      \
        return increment_##name;                                                                                                                     \
    }                                                                                                                                                \
                                                                                                                                                     \
    EXPORT type pingpong_##name(TruffleEnv *env, fnptr_##name (*wrapFn)(TruffleEnv * env, fnptr_##name), type arg) {                                 \
        fnptr_##name wrapped = wrapFn(env, increment_##name);                                                                                        \
        int ret = wrapped(arg + 1) * 2;                                                                                                              \
        (*env)->releaseClosureRef(env, wrapped);                                                                                                     \
        return ret;                                                                                                                                  \
    }

GEN_NUMERIC_TEST(SINT8, int8_t)
GEN_NUMERIC_TEST(UINT8, uint8_t)
GEN_NUMERIC_TEST(SINT16, int16_t)
GEN_NUMERIC_TEST(UINT16, uint16_t)
GEN_NUMERIC_TEST(SINT32, int32_t)
GEN_NUMERIC_TEST(UINT32, uint32_t)
GEN_NUMERIC_TEST(SINT64, int64_t)
GEN_NUMERIC_TEST(UINT64, uint64_t)
GEN_NUMERIC_TEST(FLOAT, float)
GEN_NUMERIC_TEST(DOUBLE, double)
GEN_NUMERIC_TEST(POINTER, intptr_t)
#if defined(__x86_64__)
/*
 * Note that this is only defined on the GNU toolchain. The equivalent macro for the Visual Studio
 * compiler would be _M_AMD64. This is on purpose not checked here, since Visual Studio does not
 * support FP80, it treats the `long double` type as double precision.
 */
GEN_NUMERIC_TEST(FP80, long double)
#endif

#if defined(__aarch64__) && !defined(__MACH__) && !defined(__APPLE__)
GEN_NUMERIC_TEST(FP128, long double)
#endif

/* Argument counts on both sides of the SVM backend's stack buffer for argument pointers (16). */
EXPORT int64_t sum_16_SINT64(int64_t a1, int64_t a2, int64_t a3, int64_t a4, int64_t a5, int64_t a6, int64_t a7, int64_t a8, int64_t a9, int64_t a10, int64_t a11,
                             int64_t a12, int64_t a13, int64_t a14, int64_t a15, int64_t a16) {
    return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16;
}

EXPORT int64_t sum_17_SINT64(int64_t a1, int64_t a2, int64_t a3, int64_t a4, int64_t a5, int64_t a6, int64_t a7, int64_t a8, int64_t a9, int64_t a10, int64_t a11,
                             int64_t a12, int64_t a13, int64_t a14, int64_t a15, int64_t a16, int64_t a17) {
    return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16 + a17;
}

EXPORT int64_t sum_32_SINT64(int64_t a1, int64_t a2, int64_t a3, int64_t a4, int64_t a5, int64_t a6,
                int64_t a7, int64_t a8, int64_t a9, int64_t a10, int64_t a11, int64_t a12,
                int64_t a13, int64_t a14, int64_t a15, int64_t a16, int64_t a17, int64_t a18,
                int64_t a19, int64_t a20, int64_t a21, int64_t a22, int64_t a23, int64_t a24,
                int64_t a25, int64_t a26, int64_t a27, int64_t a28, int64_t a29, int64_t a30,
                int64_t a31, int64_t a32) {
    return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 +
           a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19 + a20 +
           a21 + a22 + a23 + a24 + a25 + a26 + a27 + a28 + a29 + a30 +
           a31 + a32;
}

EXPORT int64_t sum_33_SINT64(int64_t a1, int64_t a2, int64_t a3, int64_t a4, int64_t a5, int64_t a6,
                int64_t a7, int64_t a8, int64_t a9, int64_t a10, int64_t a11, int64_t a12,
                int64_t a13, int64_t a14, int64_t a15, int64_t a16, int64_t a17, int64_t a18,
                int64_t a19, int64_t a20, int64_t a21, int64_t a22, int64_t a23, int64_t a24,
                int64_t a25, int64_t a26, int64_t a27, int64_t a28, int64_t a29, int64_t a30,
                int64_t a31, int64_t a32, int64_t a33) {
    return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 +
           a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19 + a20 +
           a21 + a22 + a23 + a24 + a25 + a26 + a27 + a28 + a29 + a30 +
           a31 + a32 + a33;
}
