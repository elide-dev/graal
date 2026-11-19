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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

/**
 * Checks the intrinsic stubs that have run-time CPU feature guarded fast paths in AOT code (Base64
 * encode and decode, CRC32, CRC32C, ChaCha20, AES-GCM) against simple reference implementations,
 * over boundary sizes and unaligned offsets. Which side of a guard runs depends on the image's
 * {@code -H:RuntimeCheckedCPUFeatures} and the CPU executing it, so the gate runs this test in an
 * image with and without AVX-512 features enabled for run time checks.
 */
public class RuntimeCheckedIntrinsicStubsTest {

    private static final int[] OFFSETS = {0, 1, 3, 7};

    private static int[] sizes() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i <= 300; i++) {
            l.add(i);
        }
        for (int base : new int[]{319, 383, 447, 511, 767, 1023, 1535, 2047, 4095, 8191, 16383}) {
            for (int d = 0; d <= 2; d++) {
                l.add(base + d);
            }
        }
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static byte[] data(int n, long seed) {
        byte[] b = new byte[n];
        long x = seed * 0x9E3779B97F4A7C15L + 1;
        for (int i = 0; i < n; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            b[i] = (byte) x;
        }
        return b;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] B64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static byte[] referenceEncode(byte[] s, int off, int len, boolean url) {
        char[] t = url ? B64_URL : B64;
        StringBuilder sb = new StringBuilder();
        int i = off;
        int end = off + len;
        for (; i + 3 <= end; i += 3) {
            int v = (s[i] & 0xff) << 16 | (s[i + 1] & 0xff) << 8 | (s[i + 2] & 0xff);
            sb.append(t[v >>> 18]).append(t[(v >>> 12) & 63]).append(t[(v >>> 6) & 63]).append(t[v & 63]);
        }
        if (end - i == 1) {
            int v = (s[i] & 0xff) << 16;
            sb.append(t[v >>> 18]).append(t[(v >>> 12) & 63]).append("==");
        } else if (end - i == 2) {
            int v = (s[i] & 0xff) << 16 | (s[i + 1] & 0xff) << 8;
            sb.append(t[v >>> 18]).append(t[(v >>> 12) & 63]).append(t[(v >>> 6) & 63]).append('=');
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte SENTINEL = 0x5A;

    /** Index of the last byte of {@code dst} that is not {@link #SENTINEL}, or -1. */
    private static int lastWritten(byte[] dst) {
        for (int i = dst.length - 1; i >= 0; i--) {
            if (dst[i] != SENTINEL) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void testBase64() {
        for (boolean url : new boolean[]{false, true}) {
            Base64.Encoder enc = url ? Base64.getUrlEncoder() : Base64.getEncoder();
            Base64.Decoder dec = url ? Base64.getUrlDecoder() : Base64.getDecoder();
            for (int n : sizes()) {
                for (int off : OFFSETS) {
                    String what = "url=" + url + " n=" + n + " off=" + off;
                    byte[] src = data(n + off + 8, n * 31L + off);
                    byte[] expected = referenceEncode(src, off, n, url);
                    ByteBuffer encoded = enc.encode(ByteBuffer.wrap(src, off, n));
                    byte[] actual = new byte[encoded.remaining()];
                    encoded.get(actual);
                    assertArrayEquals("encode " + what, expected, actual);

                    byte[] in = new byte[expected.length + off + 8];
                    System.arraycopy(expected, 0, in, off, expected.length);
                    ByteBuffer decoded = dec.decode(ByteBuffer.wrap(in, off, expected.length));
                    byte[] plain = new byte[decoded.remaining()];
                    decoded.get(plain);
                    assertArrayEquals("decode " + what, Arrays.copyOfRange(src, off, off + n), plain);

                    // Writes must stay within the array Decoder.decode(byte[]) would allocate.
                    byte[] dst = new byte[n + 128];
                    Arrays.fill(dst, SENTINEL);
                    int written = dec.decode(expected, dst);
                    assertEquals("decode length " + what, n, written);
                    assertTrue("decode overwrite " + what, lastWritten(dst) < 3 * ((expected.length + 3) / 4));
                }
            }
        }
    }

    @Test
    public void testBase64InvalidAndPaddedTails() {
        Base64.Decoder dec = Base64.getDecoder();
        for (int prefix : new int[]{4, 32, 60, 64, 128, 252, 256, 320, 512}) {
            for (String tail : new String[]{"=", "==", "X=", "X==", "XY=", "AAA=", "AA==", "A", "AB", "ABC", "A*==", "*AA="}) {
                byte[] in = ("A".repeat(prefix) + tail).getBytes(StandardCharsets.US_ASCII);
                byte[] dst = new byte[4096];
                Arrays.fill(dst, SENTINEL);
                try {
                    dec.decode(in, dst);
                } catch (IllegalArgumentException e) {
                    // malformed input is rejected, the bound below must still hold
                }
                assertTrue("prefix=" + prefix + " tail=" + tail, lastWritten(dst) < 3 * ((in.length + 3) / 4));
            }
        }
        for (int len : new int[]{64, 128, 256, 512, 1024}) {
            byte[] valid = Base64.getEncoder().encode(data(len, len));
            for (int p : new int[]{0, 1, 31, 32, 63, 64, 65, 127, 255, 256, 257, valid.length - 5}) {
                if (p < 0 || p >= valid.length - 4) {
                    continue;
                }
                byte[] bad = valid.clone();
                bad[p] = '*';
                byte[] dst = new byte[valid.length + 128];
                Arrays.fill(dst, SENTINEL);
                assertThrows(IllegalArgumentException.class, () -> dec.decode(bad, dst));
                assertTrue("len=" + len + " p=" + p, lastWritten(dst) < 3 * ((bad.length + 3) / 4));
            }
        }
    }

    private static int[] crcTable(int poly) {
        int[] t = new int[256];
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? poly ^ (c >>> 1) : c >>> 1;
            }
            t[n] = c;
        }
        return t;
    }

    private static long referenceCrc(int[] t, byte[] b, int off, int len) {
        int c = ~0;
        for (int i = off; i < off + len; i++) {
            c = t[(c ^ b[i]) & 0xff] ^ (c >>> 8);
        }
        return (~c) & 0xffffffffL;
    }

    @Test
    public void testCRC32() {
        int[] table = crcTable(0xEDB88320);
        for (int n : sizes()) {
            for (int off : OFFSETS) {
                byte[] b = data(n + off, n * 7L + off);
                long expected = referenceCrc(table, b, off, n);
                CRC32 c = new CRC32();
                c.update(b, off, n);
                assertEquals("n=" + n + " off=" + off, expected, c.getValue());
                if (n > 2) {
                    CRC32 split = new CRC32();
                    split.update(b, off, n / 3);
                    split.update(b, off + n / 3, n - n / 3);
                    assertEquals("split n=" + n, expected, split.getValue());
                }
                ByteBuffer direct = ByteBuffer.allocateDirect(n + 8);
                direct.position(off);
                direct.put(b, off, n);
                direct.flip();
                direct.position(off);
                CRC32 d = new CRC32();
                d.update(direct);
                assertEquals("direct n=" + n + " off=" + off, expected, d.getValue());
            }
        }
    }

    @Test
    public void testCRC32C() {
        int[] table = crcTable(0x82F63B78);
        for (int n : sizes()) {
            for (int off : OFFSETS) {
                byte[] b = data(n + off, n * 11L + off);
                long expected = referenceCrc(table, b, off, n);
                CRC32C c = new CRC32C();
                c.update(b, off, n);
                assertEquals("n=" + n + " off=" + off, expected, c.getValue());
                if (n > 2) {
                    CRC32C split = new CRC32C();
                    split.update(b, off, n / 3);
                    split.update(b, off + n / 3, n - n / 3);
                    assertEquals("split n=" + n, expected, split.getValue());
                }
            }
        }
    }

    /** RFC 8439 keystream for {@code n} bytes starting at block {@code counter}. */
    private static byte[] referenceChaCha20(byte[] key, byte[] nonce, int counter, int n) {
        int[] s = new int[16];
        s[0] = 0x61707865;
        s[1] = 0x3320646e;
        s[2] = 0x79622d32;
        s[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) {
            s[4 + i] = le32(key, 4 * i);
        }
        for (int i = 0; i < 3; i++) {
            s[13 + i] = le32(nonce, 4 * i);
        }
        byte[] out = new byte[n];
        int[] x = new int[16];
        for (int pos = 0, block = counter; pos < n; pos += 64, block++) {
            s[12] = block;
            System.arraycopy(s, 0, x, 0, 16);
            for (int r = 0; r < 10; r++) {
                quarterRound(x, 0, 4, 8, 12);
                quarterRound(x, 1, 5, 9, 13);
                quarterRound(x, 2, 6, 10, 14);
                quarterRound(x, 3, 7, 11, 15);
                quarterRound(x, 0, 5, 10, 15);
                quarterRound(x, 1, 6, 11, 12);
                quarterRound(x, 2, 7, 8, 13);
                quarterRound(x, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16 && pos + 4 * i < n; i++) {
                int v = x[i] + s[i];
                for (int k = 0; k < 4 && pos + 4 * i + k < n; k++) {
                    out[pos + 4 * i + k] = (byte) (v >>> (8 * k));
                }
            }
        }
        return out;
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    @Test
    public void testChaCha20() throws Exception {
        byte[] key = data(32, 5);
        for (int n : sizes()) {
            for (int off : new int[]{0, 3}) {
                byte[] nonce = data(12, n + off);
                int counter = n % 7;
                byte[] pt = data(n + off, n * 17L);
                Cipher c = Cipher.getInstance("ChaCha20");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new ChaCha20ParameterSpec(nonce, counter));
                byte[] ct = c.doFinal(pt, off, n);
                byte[] ks = referenceChaCha20(key, nonce, counter, n);
                for (int i = 0; i < n; i++) {
                    ks[i] ^= pt[off + i];
                }
                assertArrayEquals("n=" + n + " off=" + off, ks, ct);
            }
        }
        // Streaming: chunk sizes that leave partial keystream blocks between update calls.
        byte[] nonce = data(12, 99);
        byte[] pt = data(10000, 98);
        Cipher c = Cipher.getInstance("ChaCha20");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new ChaCha20ParameterSpec(nonce, 1));
        byte[] ct = new byte[pt.length];
        int pos = 0;
        for (int chunk = 1; pos < pt.length; chunk = chunk * 3 % 1031 + 1) {
            int len = Math.min(chunk, pt.length - pos);
            pos += c.update(pt, pos, len, ct, pos);
        }
        byte[] ks = referenceChaCha20(key, nonce, 1, pt.length);
        for (int i = 0; i < pt.length; i++) {
            ks[i] ^= pt[i];
        }
        assertArrayEquals("streaming", ks, ct);
    }

    /** GCM over AES/ECB and a bitwise GHASH (NIST SP 800-38D), 96-bit IV, 128-bit tag. */
    private static byte[] referenceGCM(byte[] key, byte[] iv, byte[] aad, byte[] pt, int off, int n) throws Exception {
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] h = ecb.doFinal(new byte[16]);
        long hh = be64(h, 0);
        long hl = be64(h, 8);
        byte[] counter = new byte[16];
        System.arraycopy(iv, 0, counter, 0, 12);
        counter[15] = 1;
        byte[] tagMask = ecb.doFinal(counter);
        byte[] out = new byte[n + 16];
        for (int pos = 0; pos < n; pos += 16) {
            for (int i = 15; i >= 12; i--) {
                if (++counter[i] != 0) {
                    break;
                }
            }
            byte[] ks = ecb.doFinal(counter);
            for (int i = 0; i < 16 && pos + i < n; i++) {
                out[pos + i] = (byte) (pt[off + pos + i] ^ ks[i]);
            }
        }
        long[] y = new long[2];
        ghash(y, hh, hl, aad, 0, aad.length);
        ghash(y, hh, hl, out, 0, n);
        byte[] lengths = new byte[16];
        putBe64(lengths, 0, aad.length * 8L);
        putBe64(lengths, 8, n * 8L);
        ghash(y, hh, hl, lengths, 0, 16);
        byte[] s = new byte[16];
        putBe64(s, 0, y[0]);
        putBe64(s, 8, y[1]);
        for (int i = 0; i < 16; i++) {
            out[n + i] = (byte) (s[i] ^ tagMask[i]);
        }
        return out;
    }

    private static void ghash(long[] y, long hh, long hl, byte[] b, int off, int len) {
        for (int pos = 0; pos < len; pos += 16) {
            byte[] block = new byte[16];
            System.arraycopy(b, off + pos, block, 0, Math.min(16, len - pos));
            long xh = y[0] ^ be64(block, 0);
            long xl = y[1] ^ be64(block, 8);
            long zh = 0;
            long zl = 0;
            long vh = hh;
            long vl = hl;
            for (int i = 0; i < 128; i++) {
                long bit = i < 64 ? (xh >>> (63 - i)) & 1 : (xl >>> (127 - i)) & 1;
                if (bit != 0) {
                    zh ^= vh;
                    zl ^= vl;
                }
                boolean lsb = (vl & 1) != 0;
                vl = (vl >>> 1) | (vh << 63);
                vh >>>= 1;
                if (lsb) {
                    vh ^= 0xE100000000000000L;
                }
            }
            y[0] = zh;
            y[1] = zl;
        }
    }

    private static long be64(byte[] b, int i) {
        long v = 0;
        for (int k = 0; k < 8; k++) {
            v = (v << 8) | (b[i + k] & 0xff);
        }
        return v;
    }

    private static void putBe64(byte[] b, int i, long v) {
        for (int k = 7; k >= 0; k--) {
            b[i + k] = (byte) v;
            v >>>= 8;
        }
    }

    @Test
    public void testAESGCM() throws Exception {
        for (int keyLength : new int[]{16, 32}) {
            byte[] key = data(keyLength, keyLength);
            for (int n : sizes()) {
                for (int off : new int[]{0, 5}) {
                    String what = "key=" + keyLength + " n=" + n + " off=" + off;
                    byte[] iv = data(12, n * 3L + off);
                    byte[] aad = data(n % 37, n);
                    byte[] pt = data(n + off, n * 19L);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(aad);
                    byte[] ct = c.doFinal(pt, off, n);
                    assertArrayEquals(what, referenceGCM(key, iv, aad, pt, off, n), ct);

                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(aad);
                    assertArrayEquals("decrypt " + what, Arrays.copyOfRange(pt, off, off + n), c.doFinal(ct));

                    ct[ct.length - 1 - (n % ct.length)] ^= 1;
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(aad);
                    assertThrows("tamper " + what, AEADBadTagException.class, () -> c.doFinal(ct));
                }
            }
        }
    }
}
