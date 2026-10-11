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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.code.CompactStringTable;
import com.oracle.svm.shared.util.VMError;

/**
 * Builds a {@link CompactStringTable}: trains a static symbol table over the strings (a simplified
 * FSST: a few rounds of greedy encoding, where the most profitable symbols and merges of adjacent
 * symbols become the next round's table), then encodes every string with the best table found.
 */
@Platforms(Platform.HOSTED_ONLY.class)
public final class CompactStringTableBuilder {
    /** Tables with fewer strings than this keep their String objects. */
    public static final int MIN_STRINGS = 256;
    private static final int MAX_SYMBOLS = CompactStringTable.ESCAPE;
    private static final int MAX_SYMBOL_LENGTH = CompactStringTable.MAX_SYMBOL_LENGTH;
    private static final int TRAINING_ROUNDS = 5;
    private static final int MAX_SAMPLE_BYTES = 4 * 1024 * 1024;

    private CompactStringTableBuilder() {
    }

    /** A symbol table: {@code MAX_SYMBOL_LENGTH} bytes per code plus the code's length. */
    private static final class SymbolTable {
        final byte[] symbols = new byte[MAX_SYMBOLS * MAX_SYMBOL_LENGTH];
        final byte[] lengths = new byte[MAX_SYMBOLS];
        int count;
        /** Codes of all symbols starting with a given byte, longest first. */
        final int[][] byFirstByte = new int[256][];

        void add(byte[] bytes) {
            assert count < MAX_SYMBOLS && bytes.length >= 1 && bytes.length <= MAX_SYMBOL_LENGTH;
            System.arraycopy(bytes, 0, symbols, count * MAX_SYMBOL_LENGTH, bytes.length);
            lengths[count] = (byte) bytes.length;
            count++;
        }

        void seal() {
            List<List<Integer>> buckets = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                buckets.add(new ArrayList<>());
            }
            for (int code = 0; code < count; code++) {
                buckets.get(symbols[code * MAX_SYMBOL_LENGTH] & 0xff).add(code);
            }
            for (int i = 0; i < 256; i++) {
                List<Integer> bucket = buckets.get(i);
                bucket.sort(Comparator.comparingInt((Integer code) -> lengths[code]).reversed());
                byFirstByte[i] = bucket.stream().mapToInt(Integer::intValue).toArray();
            }
        }

        /** Returns the code of the longest symbol matching at {@code pos}, or -1. */
        int match(byte[] input, int pos, int end) {
            int[] bucket = byFirstByte[input[pos] & 0xff];
            if (bucket == null) {
                return -1;
            }
            for (int code : bucket) {
                int length = lengths[code];
                if (pos + length > end) {
                    continue;
                }
                int base = code * MAX_SYMBOL_LENGTH;
                boolean equal = true;
                for (int i = 1; i < length; i++) {
                    if (symbols[base + i] != input[pos + i]) {
                        equal = false;
                        break;
                    }
                }
                if (equal) {
                    return code;
                }
            }
            return -1;
        }

        byte[] symbolBytes(int code) {
            return Arrays.copyOfRange(symbols, code * MAX_SYMBOL_LENGTH, code * MAX_SYMBOL_LENGTH + lengths[code]);
        }
    }

    private record Candidate(long packed, int length) {
        static Candidate of(byte[] bytes, int from, int length) {
            long packed = 0;
            for (int i = 0; i < length; i++) {
                packed = (packed << 8) | (bytes[from + i] & 0xff);
            }
            return new Candidate(packed, length);
        }

        byte[] bytes() {
            byte[] result = new byte[length];
            long value = packed;
            for (int i = length - 1; i >= 0; i--) {
                result[i] = (byte) value;
                value >>>= 8;
            }
            return result;
        }
    }

    /** Builds a table for the strings, or returns null if there are too few strings to pay off. */
    public static CompactStringTable build(String[] strings) {
        if (strings.length < MIN_STRINGS) {
            return null;
        }
        boolean latin1 = true;
        for (String s : strings) {
            for (int i = 0; s != null && latin1 && i < s.length(); i++) {
                if (s.charAt(i) > 0xff) {
                    latin1 = false;
                }
            }
        }
        byte[][] inputs = new byte[strings.length][];
        long totalLength = 0;
        for (int i = 0; i < strings.length; i++) {
            /* A null entry is stored as a lone escape code, which no string can produce. */
            inputs[i] = strings[i] == null ? null : strings[i].getBytes(latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            totalLength += inputs[i] == null ? 1 : inputs[i].length;
        }

        SymbolTable table = train(inputs, totalLength);

        int[] offsets = new int[strings.length + 1];
        byte[] data = new byte[Math.toIntExact(2 * totalLength)];
        int position = 0;
        for (int i = 0; i < inputs.length; i++) {
            offsets[i] = position;
            if (inputs[i] == null) {
                data[position++] = (byte) CompactStringTable.ESCAPE;
            } else {
                position = encode(table, inputs[i], data, position);
            }
        }
        offsets[strings.length] = position;
        VMError.guarantee(position <= 2 * totalLength, "Symbol coding can at most double the size");
        return new CompactStringTable(Arrays.copyOf(data, position), offsets, table.symbols, table.lengths, latin1);
    }

    private static int encode(SymbolTable table, byte[] input, byte[] output, int position) {
        int out = position;
        for (int in = 0; in < input.length;) {
            int code = table.match(input, in, input.length);
            if (code >= 0) {
                output[out++] = (byte) code;
                in += table.lengths[code];
            } else {
                output[out++] = (byte) CompactStringTable.ESCAPE;
                output[out++] = input[in++];
            }
        }
        return out;
    }

    private static SymbolTable train(byte[][] inputs, long totalLength) {
        /* Train on a sample: the strings concatenated, in their original order. */
        byte[] sample = new byte[(int) Math.min(totalLength, MAX_SAMPLE_BYTES)];
        int[] sampleEnds = new int[inputs.length];
        int filled = 0;
        int sampleCount = 0;
        for (byte[] input : inputs) {
            if (input == null) {
                continue;
            }
            if (filled + input.length > sample.length) {
                break;
            }
            System.arraycopy(input, 0, sample, filled, input.length);
            filled += input.length;
            sampleEnds[sampleCount++] = filled;
        }
        sampleEnds = Arrays.copyOf(sampleEnds, sampleCount);

        SymbolTable best = new SymbolTable();
        best.seal();
        long bestSize = Long.MAX_VALUE;
        SymbolTable current = best;
        for (int round = 0; round < TRAINING_ROUNDS; round++) {
            Map<Candidate, int[]> gains = new HashMap<>();
            long encodedSize = 0;
            int start = 0;
            for (int end : sampleEnds) {
                encodedSize += countCandidates(current, sample, start, end, gains);
                start = end;
            }
            if (encodedSize < bestSize) {
                bestSize = encodedSize;
                best = current;
            }
            List<Map.Entry<Candidate, int[]>> ranked = new ArrayList<>(gains.entrySet());
            ranked.sort(Comparator.comparingLong((Map.Entry<Candidate, int[]> e) -> gain(e)).reversed());
            SymbolTable next = new SymbolTable();
            for (Map.Entry<Candidate, int[]> entry : ranked) {
                if (next.count == MAX_SYMBOLS) {
                    break;
                }
                next.add(entry.getKey().bytes());
            }
            next.seal();
            current = next;
        }
        /* The last table was built but never measured. */
        long lastSize = 0;
        int start = 0;
        for (int end : sampleEnds) {
            lastSize += countCandidates(current, sample, start, end, new HashMap<>());
            start = end;
        }
        return lastSize < bestSize ? current : best;
    }

    private static long gain(Map.Entry<Candidate, int[]> entry) {
        /* Every occurrence costs one code instead of 2 * length escaped bytes. */
        return (long) entry.getValue()[0] * (2L * entry.getKey().length() - 1);
    }

    /**
     * Greedily encodes {@code [start, end)} with {@code table}, records the symbols used and the
     * merges of adjacent tokens as candidates for the next table, and returns the encoded size.
     */
    private static long countCandidates(SymbolTable table, byte[] input, int start, int end, Map<Candidate, int[]> gains) {
        long size = 0;
        int previousStart = -1;
        int previousLength = 0;
        for (int in = start; in < end;) {
            int code = table.match(input, in, end);
            int length = code >= 0 ? table.lengths[code] : 1;
            size += code >= 0 ? 1 : 2;
            count(gains, Candidate.of(input, in, length));
            if (previousStart >= 0 && previousLength + length <= MAX_SYMBOL_LENGTH) {
                count(gains, Candidate.of(input, previousStart, previousLength + length));
            }
            previousStart = in;
            previousLength = length;
            in += length;
        }
        return size;
    }

    private static void count(Map<Candidate, int[]> gains, Candidate candidate) {
        gains.computeIfAbsent(candidate, _ -> new int[1])[0]++;
    }

    /** Verifies that every string decodes back to itself. */
    public static boolean verify(CompactStringTable table, String[] strings) {
        for (int i = 0; i < strings.length; i++) {
            VMError.guarantee(Objects.equals(strings[i], table.decodeAtBuildTime(i)), "Compact string table round trip failed for entry %d", i);
        }
        return true;
    }
}
