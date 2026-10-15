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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.compress.HeapBlobProfile;

/**
 * A heap-blob profile ({@link HeapBlobProfile}) loaded for the build: the union of all
 * {@code -H:HeapBlobProfile} files. It answers which blobs and chunks to store raw and keeps track
 * of the entries that matched nothing in the image, which the build reports.
 */
@Platforms(Platform.HOSTED_ONLY.class)
public final class HostedHeapBlobProfile {
    record Chunk(int index, long hash) {
    }

    record Resource(String module, String name) {
    }

    private final Set<String> wholeKinds = new HashSet<>();
    private final Map<String, Set<Chunk>> chunks = new HashMap<>();
    private final Set<Resource> resources = new HashSet<>();

    /** Chunk hashes, per kind, that matched a chunk of the image. */
    private final Map<String, Set<Long>> matchedChunkHashes = new ConcurrentHashMap<>();
    private final Set<Resource> matchedResources = ConcurrentHashMap.newKeySet();

    /** Loads and merges profile files. */
    public static HostedHeapBlobProfile load(List<Path> files) throws IOException {
        HostedHeapBlobProfile profile = new HostedHeapBlobProfile();
        for (Path file : files) {
            profile.parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString());
        }
        return profile;
    }

    /** Parses one profile, e.g. the output of {@link HeapBlobProfile#record}. */
    public static HostedHeapBlobProfile parse(String contents, String origin) {
        HostedHeapBlobProfile profile = new HostedHeapBlobProfile();
        profile.parse(contents.lines().toList(), origin);
        return profile;
    }

    private void parse(List<String> lines, String origin) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            try {
                switch (fields[0]) {
                    case HeapBlobProfile.WHOLE -> {
                        expectFields(fields, 2);
                        wholeKinds.add(fields[1]);
                    }
                    case HeapBlobProfile.CHUNK -> {
                        expectFields(fields, 4);
                        int index = Integer.parseInt(fields[2]);
                        long hash = Long.parseUnsignedLong(fields[3], 16);
                        chunks.computeIfAbsent(fields[1], _ -> new HashSet<>()).add(new Chunk(index, hash));
                    }
                    case HeapBlobProfile.RESOURCE -> {
                        expectFields(fields, 3);
                        resources.add(new Resource(HeapBlobProfile.unescape(fields[1]), HeapBlobProfile.unescape(fields[2])));
                    }
                    default -> throw new IllegalArgumentException("Unknown entry type '" + fields[0] + "'");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid heap-blob profile entry at " + origin + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
        }
    }

    private static void expectFields(String[] fields, int count) {
        if (fields.length != count) {
            throw new IllegalArgumentException("Expected " + count + " tab-separated fields, found " + fields.length);
        }
    }

    public boolean isEmpty() {
        return wholeKinds.isEmpty() && chunks.isEmpty() && resources.isEmpty();
    }

    /** Whether a profiled run read the blob of the given kind that is only ever read whole. */
    public boolean isHotWhole(String kind) {
        return wholeKinds.contains(kind);
    }

    /** Whether a profiled run read the resource; {@code module} is null for the class path. */
    public boolean isHotResource(String module, String name) {
        Resource resource = new Resource(module == null ? "" : module, name);
        if (resources.contains(resource)) {
            matchedResources.add(resource);
            return true;
        }
        return false;
    }

    /**
     * Returns for every chunk of {@code data} whether a profiled run read it, or null if the
     * profile has no entries for the kind. A chunk is hot if the profile names a chunk with the
     * same contents, at the same index or (for chunks that moved) at another one.
     */
    public boolean[] hotChunks(String kind, byte[] data, int[] chunkStarts) {
        Set<Chunk> entries = chunks.get(kind);
        if (entries == null) {
            return null;
        }
        Set<Long> hashes = new HashSet<>();
        for (Chunk entry : entries) {
            hashes.add(entry.hash());
        }
        int chunkCount = chunkStarts.length - 1;
        boolean[] hot = new boolean[chunkCount];
        Set<Long> matched = matchedChunkHashes.computeIfAbsent(kind, _ -> ConcurrentHashMap.newKeySet());
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            long hash = HeapBlobProfile.hash(data, chunkStarts[chunk], chunkStarts[chunk + 1] - chunkStarts[chunk]);
            if (hashes.contains(hash)) {
                hot[chunk] = true;
                matched.add(hash);
            }
        }
        return hot;
    }

    /**
     * Describes the chunk and resource entries that matched nothing in the image: the blobs they
     * name stayed compressed although the profiled run read them. Empty if everything matched.
     */
    public List<String> describeUnmatched() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Set<Chunk>> entry : new TreeMap<>(chunks).entrySet()) {
            Set<Long> matched = matchedChunkHashes.getOrDefault(entry.getKey(), Set.of());
            long unmatched = entry.getValue().stream().filter(chunk -> !matched.contains(chunk.hash())).count();
            if (unmatched > 0) {
                result.add(String.format("%s: %d of %d chunks", entry.getKey(), unmatched, entry.getValue().size()));
            }
        }
        long unmatchedResources = resources.stream().filter(resource -> !matchedResources.contains(resource)).count();
        if (unmatchedResources > 0) {
            result.add(String.format("%s: %d of %d resources", HeapBlobProfile.EMBEDDED_RESOURCES, unmatchedResources, resources.size()));
        }
        return result;
    }
}
