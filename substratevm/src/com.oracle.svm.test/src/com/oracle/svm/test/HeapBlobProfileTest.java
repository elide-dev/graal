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
package com.oracle.svm.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.svm.core.compress.CompressedBlob;
import com.oracle.svm.core.compress.HeapBlobProfile;
import com.oracle.svm.hosted.compress.BlobCompressor;
import com.oracle.svm.hosted.compress.HostedHeapBlobProfile;
import com.oracle.svm.shared.util.ModuleSupport;

/**
 * Checks that blobs with chunks kept raw by a heap-blob profile read back correctly at run time:
 * the chunks a profile names are stored raw, all others compressed, and both inflate to the
 * original bytes. The profile is recorded with the run-time recorder's format and parsed by the
 * builder, so this also covers the round trip of the format.
 */
public class HeapBlobProfileTest {
    static final String KIND = "test blob";
    static final int CHUNK_SIZE = 4096;
    static final int CHUNK_COUNT = 24;
    static final Set<Integer> HOT_CHUNKS = Set.of(0, 5, 6, CHUNK_COUNT - 1);

    /** The blobs built by {@link TestFeature}. */
    public static final class Blobs {
        final byte[] data;
        final CompressedBlob mixed;
        final CompressedBlob compressed;

        Blobs(byte[] data, CompressedBlob mixed, CompressedBlob compressed) {
            this.data = data;
            this.mixed = mixed;
            this.compressed = compressed;
        }
    }

    public static final class TestFeature implements Feature {
        public TestFeature() {
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false, "org.graalvm.nativeimage.builder",
                            "com.oracle.svm.core.compress", "com.oracle.svm.hosted.compress");
        }

        @Override
        public void beforeAnalysis(BeforeAnalysisAccess access) {
            RuntimeClassInitialization.initializeAtBuildTime(Blobs.class);
            if (!BlobCompressor.isEnabled()) {
                /* zstd-jni is not available on this platform: nothing is compressed. */
                ImageSingletons.add(Blobs.class, new Blobs(null, null, null));
                return;
            }
            byte[] data = createData();
            int[] chunkStarts = new int[CHUNK_COUNT + 1];
            for (int chunk = 0; chunk <= CHUNK_COUNT; chunk++) {
                chunkStarts[chunk] = chunk * CHUNK_SIZE;
            }
            /* What a run that read the hot chunks records, plus an entry for chunk contents that do not exist. */
            HeapBlobProfile.Recorder recorder = new HeapBlobProfile.Recorder();
            for (int chunk : HOT_CHUNKS) {
                recorder.chunk(KIND, chunk, HeapBlobProfile.hash(data, chunkStarts[chunk], CHUNK_SIZE));
            }
            recorder.chunk(KIND, 1, 0x0123456789abcdefL);
            HostedHeapBlobProfile profile = HostedHeapBlobProfile.parse(recorder.contents(), "HeapBlobProfileTest");

            CompressedBlob mixed = BlobCompressor.compress(data, chunkStarts, true, KIND, profile);
            CompressedBlob compressed = BlobCompressor.compress(data, chunkStarts, true, KIND, HostedHeapBlobProfile.parse("", "none"));
            ImageSingletons.add(Blobs.class, new Blobs(data, mixed, compressed));
        }
    }

    /** Text-like data that compresses well but differs from chunk to chunk. */
    static byte[] createData() {
        String[] words = {"frame", "chunk", "blob", "heap", "image", "zstd", "raw", "inflate", "profile", "startup"};
        StringBuilder sb = new StringBuilder();
        long seed = 42;
        while (sb.length() < CHUNK_COUNT * CHUNK_SIZE) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            sb.append(words[(int) ((seed >>> 33) % words.length)]).append(' ').append(sb.length() / CHUNK_SIZE).append(' ');
        }
        return Arrays.copyOf(sb.toString().getBytes(StandardCharsets.US_ASCII), CHUNK_COUNT * CHUNK_SIZE);
    }

    private static Blobs blobs() {
        Blobs blobs = ImageSingletons.lookup(Blobs.class);
        Assume.assumeTrue("Requires heap-blob compression", blobs.data != null);
        return blobs;
    }

    @Test
    public void hotChunksAreStoredRaw() {
        Blobs blobs = blobs();
        assertNotNull("The test data must compress", blobs.mixed);
        assertEquals(CHUNK_COUNT, blobs.mixed.getChunkCount());
        for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) {
            assertEquals("chunk " + chunk, HOT_CHUNKS.contains(chunk), blobs.mixed.isRawChunk(chunk));
            assertFalse("chunk " + chunk, blobs.compressed.isRawChunk(chunk));
        }
        /* Raw chunks are stored verbatim, so the mixed blob is larger, but still compressed. */
        assertTrue(blobs.mixed.getCompressedLength() >= blobs.compressed.getCompressedLength() + (HOT_CHUNKS.size() - 1) * CHUNK_SIZE);
        assertTrue(blobs.mixed.getCompressedLength() < blobs.data.length);
    }

    @Test
    public void chunksInflateToTheOriginalBytes() {
        Blobs blobs = blobs();
        for (CompressedBlob blob : new CompressedBlob[]{blobs.mixed, blobs.compressed}) {
            for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) {
                byte[] expected = Arrays.copyOfRange(blobs.data, chunk * CHUNK_SIZE, (chunk + 1) * CHUNK_SIZE);
                assertArrayEquals("chunk " + chunk, expected, blob.inflateChunk(chunk));
            }
            assertArrayEquals(blobs.data, blob.inflateAll());
        }
    }

    @Test
    public void recordsBlobsReadAtRunTime() {
        Assume.assumeTrue("Requires an image built with -H:+HeapBlobProfiling", HeapBlobProfile.isSupported());
        /* Decodes frame information, which is compressed in images this large. */
        assertTrue(new Throwable().getStackTrace().length > 0);
        String profile = HeapBlobProfile.record();
        assertTrue(profile, profile.startsWith(HeapBlobProfile.HEADER + "\n"));
        assertTrue(profile, profile.contains("\n" + HeapBlobProfile.CHUNK + "\t" + HeapBlobProfile.FRAME_INFORMATION + "\t"));
        /* The test runner reads annotations reflectively. */
        assertTrue(profile, profile.contains("\n" + HeapBlobProfile.WHOLE + "\t" + HeapBlobProfile.REFLECTION_METADATA + "\n"));
    }
}
