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

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;

import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

/**
 * Records which compressed image-heap blobs a run reads, so that a later build can store them
 * uncompressed and spare startup the inflation ({@link CompressedBlobOptions#HeapBlobProfile}).
 * <p>
 * An image built with {@code -H:+HeapBlobProfiling} (implied by {@code -H:HeapBlobProfile}) and
 * started with {@code -XX:RecordHeapBlobProfile=<file>} writes the profile when the isolate tears
 * down; other images contain none of this code. The profile is a UTF-8 text file with one entry per line and tab-separated fields;
 * lines starting with {@code #} are comments:
 *
 * <pre>
 * whole    &lt;kind&gt;                      a blob that is only ever read whole
 * chunk    &lt;kind&gt; &lt;index&gt; &lt;hash&gt;     one chunk of a chunked blob, with the hash of its contents
 * resource &lt;module&gt; &lt;name&gt;            an embedded resource (empty module for the class path)
 * </pre>
 *
 * Chunk entries name chunks by index and identify them by a hash of their uncompressed contents,
 * so a profile only applies to chunks whose contents did not change since it was recorded; the
 * build warns about entries it cannot match. Blobs that were stored raw because of a profile are
 * recorded as well when read, so re-recording with an image built from a profile yields the same
 * profile.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class HeapBlobProfile {
    public static final String HEADER = "# Native Image heap-blob profile, version 1";

    public static final String WHOLE = "whole";
    public static final String CHUNK = "chunk";
    public static final String RESOURCE = "resource";

    /** Blob kinds, as they appear in profiles and in the build output. */
    public static final String FRAME_INFORMATION = "frame information";
    public static final String GRAPH_ENCODINGS = "graph encodings";
    public static final String REFLECTION_METADATA = "reflection metadata";
    public static final String HEAP_DUMP_METADATA = "heap dump metadata";
    public static final String EMBEDDED_RESOURCES = "embedded resources";

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** Reports the blobs of one owner that the current run has read. */
    public interface Source {
        void record(Recorder recorder);
    }

    /** Collects the entries of a profile at run time. */
    public static final class Recorder {
        private final StringBuilder out = new StringBuilder(HEADER).append('\n');

        public void whole(String kind) {
            out.append(WHOLE).append('\t').append(kind).append('\n');
        }

        public void chunk(String kind, int index, long hash) {
            out.append(CHUNK).append('\t').append(kind).append('\t').append(index).append('\t').append(formatHash(hash)).append('\n');
        }

        public void resource(String module, String name) {
            out.append(RESOURCE).append('\t').append(escape(module == null ? "" : module)).append('\t').append(escape(name)).append('\n');
        }

        public String contents() {
            return out.toString();
        }
    }

    private Source[] sources = new Source[0];

    @Platforms(Platform.HOSTED_ONLY.class)
    public HeapBlobProfile() {
    }

    /** Whether the image can record a profile; folds to a constant in image code. */
    public static boolean isSupported() {
        return ImageSingletons.contains(HeapBlobProfile.class);
    }

    private static HeapBlobProfile singleton() {
        return ImageSingletons.lookup(HeapBlobProfile.class);
    }

    /** Registers a source; sources must be registered before the analysis. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public static void addSource(Source source) {
        if (isSupported()) {
            HeapBlobProfile profile = singleton();
            profile.sources = Arrays.copyOf(profile.sources, profile.sources.length + 1);
            profile.sources[profile.sources.length - 1] = source;
        }
    }

    /** Collects the profile of the current run from all sources; empty if not {@link #isSupported}. */
    public static String record() {
        Recorder recorder = new Recorder();
        if (isSupported()) {
            for (Source source : singleton().sources) {
                source.record(recorder);
            }
        }
        return recorder.contents();
    }

    /** Writes the profile at isolate teardown if {@code -XX:RecordHeapBlobProfile} is set. */
    public static final class TearDownHook implements RuntimeSupport.Hook {
        @Override
        public void execute(boolean isFirstIsolate) {
            String path = CompressedBlobOptions.RecordHeapBlobProfile.getValue();
            if (path == null || path.isEmpty()) {
                return;
            }
            /* A plain stream keeps the file-system support that this pulls into images small. */
            try (FileOutputStream out = new FileOutputStream(path)) {
                out.write(record().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                Log.log().string("Writing the heap-blob profile to ").string(path).string(" failed: ").string(e.toString()).newline();
            }
        }
    }

    /** FNV-1a hash of uncompressed blob contents, identical at build time and at run time. */
    public static long hash(byte[] data, int start, int length) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = start; i < start + length; i++) {
            hash = (hash ^ (data[i] & 0xff)) * FNV_PRIME;
        }
        return hash;
    }

    /** Same as {@link #hash(byte[], int, int)} for data in unmanaged memory. */
    public static long hash(Pointer data, int length) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < length; i++) {
            hash = (hash ^ (data.readByte(i) & 0xff)) * FNV_PRIME;
        }
        return hash;
    }

    public static String formatHash(long hash) {
        String digits = Long.toHexString(hash);
        return "0".repeat(16 - digits.length()) + digits;
    }

    /** Escapes tabs, line breaks and backslashes in a field. */
    public static String escape(String field) {
        if (field.indexOf('\\') < 0 && field.indexOf('\t') < 0 && field.indexOf('\n') < 0 && field.indexOf('\r') < 0) {
            return field;
        }
        StringBuilder sb = new StringBuilder(field.length() + 8);
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reverses {@link #escape}. */
    public static String unescape(String field) {
        if (field.indexOf('\\') < 0) {
            return field;
        }
        StringBuilder sb = new StringBuilder(field.length());
        boolean escaped = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (escaped) {
                switch (c) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        if (escaped) {
            /* A trailing lone backslash is kept as it is. */
            sb.append('\\');
        }
        return sb.toString();
    }
}
