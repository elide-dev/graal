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

import java.util.function.Consumer;

import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.c.NonmovableArrays;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.guest.staging.core.UnmanagedMemoryUtil;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;


/**
 * A large byte array of the image heap stored in zstd-compressed chunks, inflated lazily at run
 * time. Chunks are independent frames (optionally sharing one dictionary) so that consumers that
 * access the uncompressed data randomly can inflate only the chunks they touch.
 *
 * The blob itself and its arrays live in the image heap and never move, so their addresses can be
 * handed to the decoder without pinning. Inflation into unmanaged memory is uninterruptible, which
 * makes it usable from the code-info decoders; inflation into Java arrays pins the target.
 *
 * Individual chunks can be stored raw (see {@link HeapBlobProfile}): their bytes are kept verbatim
 * in {@link #compressed}, and "inflating" them is a plain copy. A raw chunk is marked by
 * {@link #RAW_CHUNK} in its {@link #chunkCompressedEnds} entry, so blobs without raw chunks have
 * the same layout as before.
 */
public final class CompressedBlob {
    /*
     * Blobs are created after the static analysis (for example from the encoded frame information),
     * so the analysis must not fold their field values; the annotations defer reading the values
     * to the end of compilation.
     */

    /** All compressed chunk frames, concatenated. */
    @UnknownObjectField(availability = AfterCompilation.class) private byte[] compressed;
    /** Uncompressed start offset of each chunk; one extra entry holds the total uncompressed length. */
    @UnknownObjectField(availability = AfterCompilation.class) private int[] chunkStarts;
    /**
     * End offset (exclusive) of each chunk's frame within {@link #compressed}, with
     * {@link #RAW_CHUNK} set for chunks stored uncompressed.
     */
    @UnknownObjectField(availability = AfterCompilation.class) private int[] chunkCompressedEnds;
    /** Optional zstd dictionary shared by all chunks, possibly also by other blobs. */
    @UnknownObjectField(availability = AfterCompilation.class, canBeNull = true) private ZstdDictionary dictionary;

    /** Marks a chunk whose bytes are stored uncompressed. Offsets never use the sign bit. */
    private static final int RAW_CHUNK = 0x80000000;

    @Platforms(Platform.HOSTED_ONLY.class)
    private CompressedBlob(byte[] compressed, int[] chunkStarts, int[] chunkCompressedEnds, ZstdDictionary dictionary) {
        this.compressed = compressed;
        this.chunkStarts = chunkStarts;
        this.chunkCompressedEnds = chunkCompressedEnds;
        this.dictionary = dictionary;
    }

    /**
     * Creates a blob from already compressed chunks.
     *
     * @param chunkStarts uncompressed start offset of every chunk, followed by the total
     *            uncompressed length
     * @param chunkCompressedEnds exclusive end offset of every chunk frame in {@code compressed}
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public static CompressedBlob create(byte[] compressed, int[] chunkStarts, int[] chunkCompressedEnds, ZstdDictionary dictionary) {
        return create(compressed, chunkStarts, chunkCompressedEnds, null, dictionary);
    }

    /**
     * Creates a blob from already compressed chunks, some of which may be stored raw.
     *
     * @param rawChunks for every chunk whether its bytes are stored uncompressed in
     *            {@code compressed}; null if all chunks are compressed
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public static CompressedBlob create(byte[] compressed, int[] chunkStarts, int[] chunkCompressedEnds, boolean[] rawChunks, ZstdDictionary dictionary) {
        VMError.guarantee(chunkStarts.length == chunkCompressedEnds.length + 1, "Chunk tables must describe the same number of chunks");
        VMError.guarantee(chunkCompressedEnds.length == 0 || chunkCompressedEnds[chunkCompressedEnds.length - 1] == compressed.length, "Chunk frames must cover the compressed data");
        VMError.guarantee(rawChunks == null || rawChunks.length == chunkCompressedEnds.length, "Raw chunk flags must describe every chunk");
        int[] ends = chunkCompressedEnds.clone();
        for (int chunk = 0; rawChunks != null && chunk < ends.length; chunk++) {
            if (rawChunks[chunk]) {
                int frameLength = ends[chunk] - (chunk == 0 ? 0 : chunkCompressedEnds[chunk - 1]);
                VMError.guarantee(frameLength == chunkStarts[chunk + 1] - chunkStarts[chunk], "A raw chunk must be stored with its uncompressed length");
                ends[chunk] |= RAW_CHUNK;
            }
        }
        return new CompressedBlob(compressed, chunkStarts, ends, dictionary);
    }

    /** The byte arrays this blob stores in the image heap, for build-time size reporting. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public void forEachImageHeapArray(Consumer<byte[]> consumer) {
        consumer.accept(compressed);
    }

    /** Registers the blob and its arrays as immutable, e.g. with {@code access::registerAsImmutable}. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerAsImmutable(Consumer<Object> registerAsImmutable) {
        registerAsImmutable.accept(this);
        registerAsImmutable.accept(compressed);
        registerAsImmutable.accept(chunkStarts);
        registerAsImmutable.accept(chunkCompressedEnds);
        if (dictionary != null) {
            dictionary.registerAsImmutable(registerAsImmutable);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getUncompressedLength() {
        return chunkStarts[chunkStarts.length - 1];
    }

    public int getCompressedLength() {
        return compressed.length;
    }

    public int getDictionaryLength() {
        return dictionary == null ? 0 : dictionary.getLength();
    }

    public ZstdDictionary getDictionary() {
        return dictionary;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getChunkCount() {
        return chunkCompressedEnds.length;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getChunkStart(int chunk) {
        return chunkStarts[chunk];
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getChunkLength(int chunk) {
        return chunkStarts[chunk + 1] - chunkStarts[chunk];
    }

    /** Whether the chunk is stored uncompressed, so that "inflating" it is a plain copy. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isRawChunk(int chunk) {
        return (chunkCompressedEnds[chunk] & RAW_CHUNK) != 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private int getFrameEnd(int chunk) {
        return chunkCompressedEnds[chunk] & ~RAW_CHUNK;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private int getFrameStart(int chunk) {
        return chunk == 0 ? 0 : getFrameEnd(chunk - 1);
    }

    /** Returns the index of the chunk that contains the given uncompressed offset. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getChunkIndex(int uncompressedOffset) {
        int low = 0;
        int high = chunkCompressedEnds.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (chunkStarts[mid] <= uncompressedOffset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * Inflates one chunk into unmanaged memory. {@code dst} must provide at least
     * {@link #getChunkLength} bytes.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void inflateChunkTo(int chunk, Pointer dst, UnsignedWord dstCapacity) {
        assert Heap.getHeap().isInImageHeap(compressed) : "Compressed blobs must live in the image heap";
        int srcStart = getFrameStart(chunk);
        int srcLength = getFrameEnd(chunk) - srcStart;
        Pointer src = arrayData(compressed).add(srcStart);
        if (isRawChunk(chunk)) {
            assert dstCapacity.aboveOrEqual(srcLength) : "Destination too small for a raw chunk";
            UnmanagedMemoryUtil.copy(src, dst, Word.unsigned(srcLength));
            return;
        }

        PointerBase dctx = Zstd.ZSTD_createDCtx();
        if (dctx.isNull()) {
            throw VMError.shouldNotReachHere("zstd: cannot allocate decompression context");
        }
        UnsignedWord result;
        if (dictionary == null) {
            result = Zstd.ZSTD_decompressDCtx(dctx, dst, dstCapacity, src, Word.unsigned(srcLength));
        } else {
            result = Zstd.ZSTD_decompress_usingDDict(dctx, dst, dstCapacity, src, Word.unsigned(srcLength), dictionary.getDDict());
        }
        Zstd.ZSTD_freeDCtx(dctx);
        if (Zstd.ZSTD_isError(result) != 0 || result.notEqual(Word.unsigned(getChunkLength(chunk)))) {
            reportInflationFailure(chunk, result, srcStart, srcLength);
        }
    }

    @Uninterruptible(reason = "Fatal error reporting, called from uninterruptible code.", calleeMustBe = false, mayBeInlined = true)
    private void reportInflationFailure(int chunk, UnsignedWord result, int srcStart, int srcLength) {
        Log log = Log.log();
        log.string("zstd: inflating image-heap blob chunk ").signed(chunk).string(" of ").signed(getChunkCount()).string(" failed: ");
        if (Zstd.ZSTD_isError(result) != 0) {
            log.string(Zstd.ZSTD_getErrorName(result));
        } else {
            log.string("inflated ").unsigned(result).string(" bytes, expected ").signed(getChunkLength(chunk));
        }
        log.string(" (compressed ").signed(srcLength).string(" bytes at ").signed(srcStart).string(", dictionary ").signed(getDictionaryLength()).string(" bytes)").newline();
        throw VMError.shouldNotReachHere("zstd: inflating an image-heap blob chunk failed");
    }

    /** Inflates one chunk into a new Java array. */
    public byte[] inflateChunk(int chunk) {
        byte[] result = new byte[getChunkLength(chunk)];
        try (PinnedObject pin = PinnedObject.create(result)) {
            inflateChunkTo(chunk, pin.addressOfArrayElement(0), Word.unsigned(result.length));
        }
        return result;
    }

    /** Inflates the whole blob into a new Java array. */
    public byte[] inflateAll() {
        byte[] result = new byte[getUncompressedLength()];
        try (PinnedObject pin = PinnedObject.create(result)) {
            for (int chunk = 0; chunk < getChunkCount(); chunk++) {
                inflateChunkTo(chunk, pin.addressOfArrayElement(getChunkStart(chunk)), Word.unsigned(getChunkLength(chunk)));
            }
        }
        return result;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer arrayData(byte[] array) {
        return NonmovableArrays.getArrayBase(NonmovableArrays.fromImageHeap(array));
    }
}
