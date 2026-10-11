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
package com.oracle.svm.core.code;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.SubstrateDiagnostics;
import com.oracle.svm.guest.staging.core.heap.RestrictHeapAccess;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;
import com.oracle.svm.shared.Uninterruptible;

/**
 * A table of strings stored as one byte pool with an offset index instead of one {@link String}
 * object per entry. The pool is coded with a static symbol table (a simplified form of FSST, "Fast
 * Static Symbol Table"): every code byte either names one of up to 255 symbols of 1 to 8 bytes or,
 * as {@link #ESCAPE}, introduces one literal byte. Each string decodes independently, so entries
 * are random-access; decoded strings are cached in a run-time array.
 *
 * The table holds the method and field names and the other strings of the code metadata, which
 * are read when stack traces are built or reflection is used. A fixed 40 bytes of object headers
 * per string, plus padding, are saved, and the symbol coding typically halves the content bytes.
 */
public final class CompactStringTable {
    public static final int ESCAPE = 255;
    public static final int MAX_SYMBOL_LENGTH = 8;
    private static final String UNAVAILABLE = "<name unavailable during fatal error handling>";

    /* Created after the static analysis; see CompressedBlob for why the fields are "unknown". */
    @UnknownObjectField(availability = AfterCompilation.class) private byte[] data;
    /** Start offset of every string in {@link #data}, plus one entry holding the total length. */
    @UnknownObjectField(availability = AfterCompilation.class) private int[] offsets;
    /** {@link #MAX_SYMBOL_LENGTH} bytes per symbol code. */
    @UnknownObjectField(availability = AfterCompilation.class) private byte[] symbols;
    /** Length of every symbol code. */
    @UnknownObjectField(availability = AfterCompilation.class) private byte[] symbolLengths;
    /** Whether the decoded bytes are Latin-1 (otherwise UTF-8). */
    @UnknownPrimitiveField(availability = AfterCompilation.class) private boolean latin1;

    /** Decoded strings, created on first access at run time. */
    private volatile String[] cache;

    @Platforms(Platform.HOSTED_ONLY.class)
    public CompactStringTable(byte[] data, int[] offsets, byte[] symbols, byte[] symbolLengths, boolean latin1) {
        this.data = data;
        this.offsets = offsets;
        this.symbols = symbols;
        this.symbolLengths = symbolLengths;
        this.latin1 = latin1;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int length() {
        return offsets.length - 1;
    }

    /** The byte arrays this table stores in the image heap, for build-time size reporting. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public void forEachImageHeapArray(Consumer<byte[]> consumer) {
        consumer.accept(data);
        consumer.accept(symbols);
        consumer.accept(symbolLengths);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerAsImmutable(Consumer<Object> registerAsImmutable) {
        /* Not the table itself: its cache is filled at run time. */
        registerAsImmutable.accept(data);
        registerAsImmutable.accept(offsets);
        registerAsImmutable.accept(symbols);
        registerAsImmutable.accept(symbolLengths);
    }

    /**
     * Returns the string at {@code index}, decoding it on first access. Decoding allocates, so this
     * must only be reached from interruptible contexts (which all stack trace and reflection
     * consumers are); during fatal error handling an uncached name is reported as unavailable
     * instead.
     */
    @RestrictHeapAccess(access = RestrictHeapAccess.Access.UNRESTRICTED, reason = "Allocates only outside fatal error handling; restricted callers such as log backtraces get a placeholder for uncached names.")
    @Uninterruptible(reason = "Called from uninterruptible code; allocates only outside fatal error handling.", calleeMustBe = false, mayBeInlined = true)
    public String get(int index) {
        String[] strings = cache;
        if (strings != null) {
            String cached = strings[index];
            if (cached != null) {
                return cached;
            }
        }
        if (SubstrateDiagnostics.isFatalErrorHandlingInProgress()) {
            return UNAVAILABLE;
        }
        return decodeAndCache(index);
    }

    private String decodeAndCache(int index) {
        String[] strings = cache;
        if (strings == null) {
            synchronized (this) {
                strings = cache;
                if (strings == null) {
                    strings = new String[length()];
                    cache = strings;
                }
            }
        }
        String result = decode(index);
        /* Benign race: concurrent decoders produce equal strings, any of them may win. */
        strings[index] = result;
        return result;
    }

    /** Decodes without touching the run-time cache, for build-time verification. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public String decodeAtBuildTime(int index) {
        return decode(index);
    }

    private String decode(int index) {
        int start = offsets[index];
        int end = offsets[index + 1];
        if (isNull(start, end)) {
            return null;
        }
        byte[] decoded = new byte[decodedLength(start, end)];
        int out = 0;
        for (int in = start; in < end;) {
            int code = data[in++] & 0xff;
            if (code == ESCAPE) {
                decoded[out++] = data[in++];
            } else {
                int length = symbolLengths[code];
                System.arraycopy(symbols, code * MAX_SYMBOL_LENGTH, decoded, out, length);
                out += length;
            }
        }
        return new String(decoded, 0, out, latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    /** A null entry is stored as a lone escape code, which no string can produce. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private boolean isNull(int start, int end) {
        return end - start == 1 && (data[start] & 0xff) == ESCAPE;
    }

    private int decodedLength(int start, int end) {
        int length = 0;
        for (int in = start; in < end;) {
            int code = data[in++] & 0xff;
            if (code == ESCAPE) {
                in++;
                length++;
            } else {
                length += symbolLengths[code];
            }
        }
        return length;
    }

    /** Prints the string at {@code index} without allocating, for diagnostics. */
    @Uninterruptible(reason = "Only writes to the log, which is safe from uninterruptible diagnostics code.", calleeMustBe = false, mayBeInlined = true)
    public void print(Log log, int index) {
        int end = offsets[index + 1];
        if (isNull(offsets[index], end)) {
            log.string("null");
            return;
        }
        for (int in = offsets[index]; in < end;) {
            int code = data[in++] & 0xff;
            if (code == ESCAPE) {
                log.character((char) (data[in++] & 0xff));
            } else {
                int length = symbolLengths[code];
                for (int i = 0; i < length; i++) {
                    log.character((char) (symbols[code * MAX_SYMBOL_LENGTH + i] & 0xff));
                }
            }
        }
    }
}
