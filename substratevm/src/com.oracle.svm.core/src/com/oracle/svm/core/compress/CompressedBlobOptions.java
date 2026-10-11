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

import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.options.Option;

/**
 * Options for the build-time compression of large, cold image-heap byte arrays (encoded graphs for
 * runtime compilation, frame information, embedded resources, reflection metadata, heap-dump
 * metadata). Compressed blobs are inflated lazily at run time by the statically linked zstd decoder.
 */
public final class CompressedBlobOptions {
    @Option(help = "Compress large cold image-heap blobs (encoded graphs, frame information, embedded resources, reflection and heap-dump metadata) with zstd at build time and inflate them lazily at run time.")//
    public static final HostedOptionKey<Boolean> CompressHeapBlobs = new HostedOptionKey<>(true);

    @Option(help = "Zstd compression level used for image-heap blobs. Only affects build time.")//
    public static final HostedOptionKey<Integer> HeapBlobCompressionLevel = new HostedOptionKey<>(19);

    @Option(help = "Uncompressed chunk size in bytes for image-heap blobs that are accessed randomly (frame information, encoded graphs). Must be a power of 2.")//
    public static final HostedOptionKey<Integer> HeapBlobChunkSize = new HostedOptionKey<>(64 * 1024);

    @Option(help = "Train a zstd dictionary per blob so that small chunks keep the compression ratio of the whole blob.")//
    public static final HostedOptionKey<Boolean> HeapBlobDictionaries = new HostedOptionKey<>(true);

    @Option(help = "Image-heap blobs smaller than this many bytes are stored uncompressed.")//
    public static final HostedOptionKey<Integer> HeapBlobMinimumSize = new HostedOptionKey<>(32 * 1024);

    @Option(help = "Print the uncompressed and compressed size of every kind of image-heap blob at the end of the build.")//
    public static final HostedOptionKey<Boolean> PrintHeapBlobCompression = new HostedOptionKey<>(false);

    private CompressedBlobOptions() {
    }
}
