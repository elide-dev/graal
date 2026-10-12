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
package com.oracle.svm.core.hub;

import java.nio.ByteOrder;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.SubstrateTarget;
import com.oracle.svm.core.code.RuntimeMetadataEncoding;
import com.oracle.svm.core.util.ByteArrayReader;
import com.oracle.svm.shared.singletons.MultiLayeredImageSingleton;
import com.oracle.svm.shared.util.VMError;

/**
 * Packs up to {@value #MAX_BITS} sparsely set {@code int} metadata values of a {@link DynamicHub}
 * into one tagged {@code int}, so that the hub companion does not need a separate object per
 * class.
 * <ul>
 * <li>{@code 0}: no metadata is present.</li>
 * <li>high bit set: exactly one value is stored inline. Bits 30..24 contain the one-hot value mask
 * and bits 23..0 contain the non-negative value.</li>
 * <li>otherwise: the value is {@code offset + 1} into
 * {@link RuntimeMetadataEncoding#getReflectionMetadataEncoding()}.</li>
 * </ul>
 * A side-table entry starts with a one-byte value mask. The values for all set bits then follow as
 * little-endian signed Java {@code int}s in ascending bit order.
 */
final class PackedMetadataEncoding {
    static final int MAX_BITS = 7;

    /** Zero is the default value of the companion fields that hold a packed encoding. */
    static final int NO_METADATA = 0;
    private static final int FIRST_SIDE_TABLE_INDEX = 1;
    private static final int INLINE_ENCODING = 1 << 31;
    private static final int INLINE_MASK_SHIFT = 24;
    private static final int INLINE_MASK = ((1 << MAX_BITS) - 1) << INLINE_MASK_SHIFT;
    private static final int INLINE_VALUE_MASK = (1 << INLINE_MASK_SHIFT) - 1;

    @Platforms(Platform.HOSTED_ONLY.class)
    private PackedMetadataEncoding() {
    }

    /**
     * Encodes the values whose bit is set in {@code mask}. {@code values[i]} is the value for bit
     * {@code i}; values for unset bits are ignored. The side table of the current layer is used
     * when the values do not fit inline.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    static int encode(int mask, int[] values) {
        assert values.length <= MAX_BITS && (mask >>> MAX_BITS) == 0 : mask;
        if (mask == 0) {
            return NO_METADATA;
        }
        if (Integer.bitCount(mask) == 1) {
            int value = values[Integer.numberOfTrailingZeros(mask)];
            if (value >= 0 && value <= INLINE_VALUE_MASK) {
                return INLINE_ENCODING | (mask << INLINE_MASK_SHIFT) | value;
            }
        }

        int index = RuntimeMetadataEncoding.currentLayer().addReflectionMetadata(1 + Integer.BYTES * Integer.bitCount(mask));
        byte[] data = RuntimeMetadataEncoding.currentLayer().getReflectionMetadataEncoding();
        data[index] = (byte) mask;
        int pos = index + 1;
        for (int bit = 0; bit < values.length; bit++) {
            if ((mask & (1 << bit)) != 0) {
                int value = values[bit];
                assert SubstrateTarget.getArchitecture().getByteOrder() == ByteOrder.LITTLE_ENDIAN;
                data[pos] = (byte) value;
                data[pos + 1] = (byte) (value >>> 8);
                data[pos + 2] = (byte) (value >>> 16);
                data[pos + 3] = (byte) (value >>> 24);
                pos += Integer.BYTES;
            }
        }
        return index + FIRST_SIDE_TABLE_INDEX;
    }

    static boolean hasMetadata(int encoded) {
        return encoded != NO_METADATA;
    }

    /**
     * Returns the value stored for {@code bit} (a one-hot mask), or {@code defaultValue} if that
     * value was not set. {@code layerNum} selects the side table.
     */
    static int getValue(int encoded, int bit, int defaultValue, int layerNum) {
        assert Integer.bitCount(bit) == 1 && (bit >>> MAX_BITS) == 0 : bit;
        if (!hasMetadata(encoded)) {
            return defaultValue;
        }
        if ((encoded & INLINE_ENCODING) != 0) {
            int mask = (encoded & INLINE_MASK) >>> INLINE_MASK_SHIFT;
            return mask == bit ? encoded & INLINE_VALUE_MASK : defaultValue;
        }
        byte[] data = MultiLayeredImageSingleton.getForLayer(RuntimeMetadataEncoding.class, layerNum).getReflectionMetadataEncoding();
        int pos = encoded - FIRST_SIDE_TABLE_INDEX;
        int mask = ByteArrayReader.getU1(data, pos++);
        if ((mask & bit) == 0) {
            return defaultValue;
        }
        for (int currentBit = 1; currentBit < bit; currentBit <<= 1) {
            if ((mask & currentBit) != 0) {
                pos += Integer.BYTES;
            }
        }
        VMError.guarantee(pos + Integer.BYTES <= data.length, "Packed metadata side table index out of range");
        return ByteArrayReader.getS4(data, pos);
    }
}
