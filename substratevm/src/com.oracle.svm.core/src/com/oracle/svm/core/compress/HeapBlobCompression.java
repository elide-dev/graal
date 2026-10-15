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

import java.util.List;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

/**
 * Build-time compression service for core code that stores data in the image heap while it is
 * registered (for example resources). The implementation lives in the image builder; core code
 * reaches it through {@link ImageSingletons}.
 */
@Platforms(Platform.HOSTED_ONLY.class)
public interface HeapBlobCompression {

    static boolean isAvailable() {
        return ImageSingletons.contains(HeapBlobCompression.class);
    }

    static HeapBlobCompression singleton() {
        return ImageSingletons.lookup(HeapBlobCompression.class);
    }

    /** Whether a blob of the given size is worth compressing; reports blobs below the size floor. */
    boolean shouldCompress(long length, String kind);

    /** Compresses {@code data} as a single frame that is only ever inflated whole. */
    CompressedBlob compressWhole(byte[] data, String kind);

    /** Compresses {@code data} in fixed-size chunks for random access by uncompressed offset. */
    CompressedBlob compressUniform(byte[] data, String kind);

    /**
     * Trains a dictionary over {@code samples} for blobs that are compressed individually with
     * {@link #compressWhole(byte[], ZstdDictionary, String)}; null if the samples do not yield a
     * useful dictionary.
     */
    ZstdDictionary trainDictionary(List<byte[]> samples, String kind);

    /** Compresses {@code data} as a single frame using a shared dictionary (may be null). */
    CompressedBlob compressWhole(byte[] data, ZstdDictionary dictionary, String kind);

    /**
     * Whether a profiled run read the given embedded resource ({@code module} is null for the
     * class path), so that it should be stored raw.
     */
    boolean isHotResource(String module, String name);

    /** Reports a blob that is stored raw because a profiled run read it, for the build output. */
    void reportStoredRawForProfile(String kind, long length);
}
