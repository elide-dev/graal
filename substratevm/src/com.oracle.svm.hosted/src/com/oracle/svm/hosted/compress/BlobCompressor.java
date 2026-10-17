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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.compress.CompressedBlob;
import com.oracle.svm.core.compress.CompressedBlobOptions;
import com.oracle.svm.core.compress.HeapBlobCompression;
import com.oracle.svm.core.compress.ZstdDictionary;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.LogUtils;
import com.oracle.svm.shared.util.VMError;

/**
 * Build-time side of {@link CompressedBlob}: splits a byte array into chunks, optionally trains a
 * zstd dictionary over them, and compresses every chunk as an independent frame. Compression runs
 * in the image builder through zstd-jni; images only link the zstd decoder.
 */
@Platforms(Platform.HOSTED_ONLY.class)
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public final class BlobCompressor implements HeapBlobCompression {
    /** A blob is stored compressed only if that saves at least this fraction of its size. */
    private static final double MIN_SAVINGS = 0.1;
    /** Minimum number of chunks before dictionary training is attempted. */
    private static final int MIN_CHUNKS_FOR_DICTIONARY = 8;
    private static final int MIN_DICTIONARY_SIZE = 4 * 1024;
    private static final int MAX_DICTIONARY_SIZE = 110 * 1024;

    /** Per-kind statistics, reported in the build output. */
    public record Stats(AtomicLong uncompressed, AtomicLong compressed, AtomicLong dictionaries, AtomicLong blobs) {
        Stats() {
            this(new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
    }

    private static final Map<String, Stats> STATS = new ConcurrentHashMap<>();

    private static volatile boolean unavailabilityReported;

    BlobCompressor() {
    }

    public static boolean isEnabled() {
        if (!CompressedBlobOptions.CompressHeapBlobs.getValue()) {
            return false;
        }
        if (!ZstdJni.isAvailable()) {
            if (!unavailabilityReported) {
                unavailabilityReported = true;
                LogUtils.warning("Image-heap blobs are stored uncompressed because zstd-jni could not be loaded: " + ZstdJni.getUnavailableReason());
            }
            return false;
        }
        return true;
    }

    /** Whether a blob of the given size is worth compressing at all. */
    public static boolean shouldCompressBlob(long length) {
        return isEnabled() && length >= CompressedBlobOptions.HeapBlobMinimumSize.getValue();
    }

    @Override
    public boolean shouldCompress(long length) {
        return shouldCompressBlob(length);
    }

    @Override
    public CompressedBlob compressWhole(byte[] data, String kind) {
        return compressWholeBlob(data, kind);
    }

    @Override
    public CompressedBlob compressUniform(byte[] data, String kind) {
        return compressUniformBlob(data, kind);
    }

    @Override
    public ZstdDictionary trainDictionary(List<byte[]> samples, String kind) {
        if (!isEnabled() || !CompressedBlobOptions.HeapBlobDictionaries.getValue() || samples.size() < MIN_CHUNKS_FOR_DICTIONARY) {
            return null;
        }
        long total = 0;
        for (byte[] sample : samples) {
            total += sample.length;
        }
        byte[] dictionary = trainDictionary(samples.toArray(new byte[0][]), total);
        if (dictionary == null) {
            return null;
        }
        Stats stats = STATS.computeIfAbsent(kind, _ -> new Stats());
        stats.compressed.addAndGet(dictionary.length);
        stats.dictionaries.addAndGet(dictionary.length);
        return new ZstdDictionary(dictionary);
    }

    @Override
    public CompressedBlob compressWhole(byte[] data, ZstdDictionary dictionary, String kind) {
        return compress(data, new int[]{0, data.length}, dictionary, kind);
    }

    public static Map<String, Stats> getStats() {
        return STATS;
    }

    /** Compresses {@code data} in fixed-size chunks (random access by uncompressed offset). */
    public static CompressedBlob compressUniformBlob(byte[] data, String kind) {
        int chunkSize = chunkSize();
        int chunkCount = Math.max(1, (data.length + chunkSize - 1) / chunkSize);
        int[] chunkStarts = new int[chunkCount + 1];
        for (int i = 0; i < chunkCount; i++) {
            chunkStarts[i] = i * chunkSize;
        }
        chunkStarts[chunkCount] = data.length;
        return compress(data, chunkStarts, true, kind);
    }

    /**
     * Compresses {@code data} in chunks that never split a record. {@code recordStarts} holds the
     * ascending start offsets of all records and must begin with 0; records are grouped until a
     * chunk would exceed the configured chunk size, so a record larger than a chunk gets a chunk of
     * its own.
     */
    public static CompressedBlob compressAligned(byte[] data, int[] recordStarts, String kind) {
        VMError.guarantee(recordStarts.length > 0 && recordStarts[0] == 0, "Record starts must begin at offset 0");
        int chunkSize = chunkSize();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int currentStart = 0;
        for (int i = 1; i < recordStarts.length; i++) {
            int recordStart = recordStarts[i];
            VMError.guarantee(recordStart >= recordStarts[i - 1] && recordStart <= data.length, "Record starts must be sorted and within the data");
            int nextEnd = i + 1 < recordStarts.length ? recordStarts[i + 1] : data.length;
            if (nextEnd - currentStart > chunkSize && recordStart > currentStart) {
                starts.add(recordStart);
                currentStart = recordStart;
            }
        }
        int[] chunkStarts = new int[starts.size() + 1];
        for (int i = 0; i < starts.size(); i++) {
            chunkStarts[i] = starts.get(i);
        }
        chunkStarts[starts.size()] = data.length;
        return compress(data, chunkStarts, true, kind);
    }

    /** Compresses {@code data} as a single frame, for blobs that are only ever inflated whole. */
    public static CompressedBlob compressWholeBlob(byte[] data, String kind) {
        return compress(data, new int[]{0, data.length}, false, kind);
    }

    private static int chunkSize() {
        int chunkSize = CompressedBlobOptions.HeapBlobChunkSize.getValue();
        VMError.guarantee(chunkSize > 0 && Integer.bitCount(chunkSize) == 1, "HeapBlobChunkSize must be a power of 2");
        return chunkSize;
    }

    private static CompressedBlob compress(byte[] data, int[] chunkStarts, boolean allowDictionary, String kind) {
        int chunkCount = chunkStarts.length - 1;
        ZstdDictionary dictionary = null;
        if (allowDictionary && CompressedBlobOptions.HeapBlobDictionaries.getValue() && chunkCount >= MIN_CHUNKS_FOR_DICTIONARY) {
            byte[][] samples = new byte[chunkCount][];
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                samples[chunk] = Arrays.copyOfRange(data, chunkStarts[chunk], chunkStarts[chunk + 1]);
            }
            byte[] trained = trainDictionary(samples, data.length);
            if (trained != null) {
                dictionary = new ZstdDictionary(trained);
                Stats stats = STATS.computeIfAbsent(kind, _ -> new Stats());
                stats.compressed.addAndGet(trained.length);
                stats.dictionaries.addAndGet(trained.length);
            }
        }
        return compress(data, chunkStarts, dictionary, kind);
    }

    private static CompressedBlob compress(byte[] data, int[] chunkStarts, ZstdDictionary dictionary, String kind) {
        int chunkCount = chunkStarts.length - 1;
        int level = CompressedBlobOptions.HeapBlobCompressionLevel.getValue();
        final byte[] dict = dictionary == null ? null : dictionary.getBytes();

        byte[][] frames = new byte[chunkCount][];
        IntStream.range(0, chunkCount).parallel().forEach(chunk -> {
            byte[] input = Arrays.copyOfRange(data, chunkStarts[chunk], chunkStarts[chunk + 1]);
            byte[] output = new byte[Math.toIntExact(ZstdJni.compressBound(input.length))];
            long written = dict == null ? ZstdJni.compress(output, input, level) : ZstdJni.compressUsingDict(output, input, dict, level);
            if (ZstdJni.isError(written)) {
                throw VMError.shouldNotReachHere("zstd compression of " + kind + " failed: " + ZstdJni.getErrorName(written));
            }
            frames[chunk] = Arrays.copyOf(output, (int) written);
        });

        int[] chunkCompressedEnds = new int[chunkCount];
        long total = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            total += frames[chunk].length;
            chunkCompressedEnds[chunk] = Math.toIntExact(total);
        }
        byte[] compressed = new byte[Math.toIntExact(total)];
        int position = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, compressed, position, frame.length);
            position += frame.length;
        }

        Stats stats = STATS.computeIfAbsent(kind, _ -> new Stats());
        stats.uncompressed.addAndGet(data.length);
        if (compressed.length > data.length * (1 - MIN_SAVINGS)) {
            /* Already compressed or incompressible data: not worth the inflation at run time. */
            stats.compressed.addAndGet(data.length);
            return null;
        }
        stats.compressed.addAndGet(compressed.length);
        stats.blobs.incrementAndGet();
        return CompressedBlob.create(compressed, chunkStarts, chunkCompressedEnds, dictionary);
    }

    /** Prints the per-kind statistics when {@code -H:+PrintHeapBlobCompression} is set. */
    public static void printStats() {
        if (!CompressedBlobOptions.PrintHeapBlobCompression.getValue()) {
            return;
        }
        for (Map.Entry<String, Stats> entry : new TreeMap<>(STATS).entrySet()) {
            Stats stats = entry.getValue();
            long uncompressed = stats.uncompressed.get();
            long compressed = stats.compressed.get();
            double ratio = uncompressed == 0 ? 100.0 : 100.0 * compressed / uncompressed;
            LogUtils.info(String.format("Heap blob compression: %s: %d blobs, %,d -> %,d bytes (%.1f%%), dictionaries %,d bytes", entry.getKey(), stats.blobs.get(),
                            uncompressed, compressed, ratio, stats.dictionaries.get()));
        }
    }

    private static byte[] trainDictionary(byte[][] samples, long totalLength) {
        int dictionarySize = (int) Math.max(MIN_DICTIONARY_SIZE, Math.min(MAX_DICTIONARY_SIZE, totalLength / 32));
        byte[] dictionary = new byte[dictionarySize];
        long size = ZstdJni.trainFromBuffer(samples, dictionary);
        if (ZstdJni.isError(size) || size <= 0) {
            /* Not enough or too uniform data for a useful dictionary: compress without one. */
            return null;
        }
        return size == dictionary.length ? dictionary : Arrays.copyOf(dictionary, Math.toIntExact(size));
    }
}
