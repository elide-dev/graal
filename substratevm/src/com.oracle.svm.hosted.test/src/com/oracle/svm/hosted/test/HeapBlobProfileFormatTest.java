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
package com.oracle.svm.hosted.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.oracle.svm.core.compress.HeapBlobProfile;
import com.oracle.svm.hosted.compress.HostedHeapBlobProfile;

/**
 * Round trip of heap-blob profiles: what the run-time recorder writes is what the build reads, and
 * chunk entries match chunks by contents.
 */
public class HeapBlobProfileFormatTest {

    private static final int CHUNK = 16;

    private static byte[] data(int chunks) {
        byte[] data = new byte[chunks * CHUNK];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + i / CHUNK);
        }
        return data;
    }

    private static int[] chunkStarts(byte[] data) {
        int[] starts = new int[data.length / CHUNK + 1];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = i * CHUNK;
        }
        return starts;
    }

    private static long chunkHash(byte[] data, int chunk) {
        return HeapBlobProfile.hash(data, chunk * CHUNK, CHUNK);
    }

    @Test
    public void escapingRoundTrips() {
        for (String field : List.of("", "plain/name.txt", "tab\there", "line\nbreak", "cr\rlf", "back\\slash", "\\t literally")) {
            String escaped = HeapBlobProfile.escape(field);
            assertFalse(escaped.contains("\t") || escaped.contains("\n") || escaped.contains("\r"));
            assertEquals(field, HeapBlobProfile.unescape(escaped));
        }
    }

    @Test
    public void recordedProfileParses() {
        byte[] data = data(6);
        HeapBlobProfile.Recorder recorder = new HeapBlobProfile.Recorder();
        recorder.whole(HeapBlobProfile.REFLECTION_METADATA);
        recorder.chunk(HeapBlobProfile.FRAME_INFORMATION, 1, chunkHash(data, 1));
        recorder.chunk(HeapBlobProfile.FRAME_INFORMATION, 4, chunkHash(data, 4));
        recorder.resource(null, "on/the/class path.txt");
        recorder.resource("some.module", "odd\tname\n.bin");

        String contents = recorder.contents();
        assertTrue(contents.startsWith(HeapBlobProfile.HEADER + "\n"));
        HostedHeapBlobProfile profile = HostedHeapBlobProfile.parse(contents, "test");

        assertTrue(profile.isHotWhole(HeapBlobProfile.REFLECTION_METADATA));
        assertFalse(profile.isHotWhole(HeapBlobProfile.HEAP_DUMP_METADATA));
        assertTrue(profile.isHotResource(null, "on/the/class path.txt"));
        assertTrue(profile.isHotResource("", "on/the/class path.txt"));
        assertTrue(profile.isHotResource("some.module", "odd\tname\n.bin"));
        assertFalse(profile.isHotResource("other.module", "odd\tname\n.bin"));

        assertArrayEquals(new boolean[]{false, true, false, false, true, false}, profile.hotChunks(HeapBlobProfile.FRAME_INFORMATION, data, chunkStarts(data)));
        assertNull("No entries for the kind", profile.hotChunks(HeapBlobProfile.GRAPH_ENCODINGS, data, chunkStarts(data)));
        assertTrue(profile.describeUnmatched().isEmpty());
    }

    @Test
    public void chunksMatchByContents() {
        byte[] data = data(6);
        HeapBlobProfile.Recorder recorder = new HeapBlobProfile.Recorder();
        /* Recorded at index 0, now at index 3: matches by contents. */
        recorder.chunk(HeapBlobProfile.GRAPH_ENCODINGS, 0, chunkHash(data, 3));
        /* Contents that no chunk has any more: stays unmatched. */
        recorder.chunk(HeapBlobProfile.GRAPH_ENCODINGS, 2, 0x1234L);
        recorder.resource(null, "missing.txt");
        HostedHeapBlobProfile profile = HostedHeapBlobProfile.parse(recorder.contents(), "test");

        assertArrayEquals(new boolean[]{false, false, false, true, false, false}, profile.hotChunks(HeapBlobProfile.GRAPH_ENCODINGS, data, chunkStarts(data)));
        assertEquals(List.of("graph encodings: 1 of 2 chunks", "embedded resources: 1 of 1 resources"), profile.describeUnmatched());
    }

    @Test
    public void malformedEntriesAreRejected() {
        assertTrue(HostedHeapBlobProfile.parse("# comment only\n\n", "test").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HostedHeapBlobProfile.parse("bogus\tentry\n", "test"));
        assertThrows(IllegalArgumentException.class, () -> HostedHeapBlobProfile.parse("chunk\tframe information\t1\n", "test"));
        assertThrows(IllegalArgumentException.class, () -> HostedHeapBlobProfile.parse("chunk\tframe information\tx\t00\n", "test"));
        assertThrows(IllegalArgumentException.class, () -> HostedHeapBlobProfile.parse("whole\n", "test"));
    }
}
