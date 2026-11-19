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
package com.oracle.svm.truffle.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.test.NativeImageBuildArgs;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Intrinsic stubs called from runtime-compiled code. Runtime compilation selects the stub variant
 * from the CPU features of the executing host, so on a host with AVX-512 VBMI the Base64 calls
 * reach the AVX-512 variants, and elsewhere the AVX2 or baseline ones. The results must match a
 * scalar encoding in every case, and decoding must not write past the array that
 * {@link Base64.Decoder#decode(byte[])} would allocate.
 */
@NativeImageBuildArgs({
                "--macro:truffle",
                "-H:MaxRuntimeCompileMethods=3000",
                // Compiling in isolates aborts when installing typed entries (elide-dev/graal#194).
                // Remove these three options with that fix.
                "-H:+UnlockExperimentalVMOptions",
                "-H:-SupportCompileInIsolates",
                "-H:-UnlockExperimentalVMOptions",
})
public class IntrinsicStubRuntimeCompilationTest {

    static volatile boolean lastExecutionCompiled;

    /** Encodes {@code args[0]} into {@code args[1]} and decodes it back into {@code args[2]}. */
    static final class Base64Root extends RootNode {
        private final boolean url;

        Base64Root(boolean url) {
            super(null);
            this.url = url;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            byte[] src = (byte[]) args[0];
            byte[] encoded = (byte[]) args[1];
            byte[] decoded = (byte[]) args[2];
            int encodedLength = (url ? Base64.getUrlEncoder() : Base64.getEncoder()).encode(src, encoded);
            int decodedLength = (url ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(Arrays.copyOf(encoded, encodedLength), decoded);
            lastExecutionCompiled = CompilerDirectives.inCompiledCode();
            return ((long) encodedLength << 32) | decodedLength;
        }

        @Override
        public String getName() {
            return "base64-" + (url ? "url" : "basic");
        }
    }

    /**
     * Decodes {@code args[0]} into {@code args[1]}, returning the decoded length or -1 if the input
     * is rejected.
     */
    static final class DecodeRoot extends RootNode {
        DecodeRoot() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            int result;
            try {
                result = Base64.getDecoder().decode((byte[]) args[0], (byte[]) args[1]);
            } catch (IllegalArgumentException e) {
                result = -1;
            }
            lastExecutionCompiled = CompilerDirectives.inCompiledCode();
            return result;
        }

        @Override
        public String getName() {
            return "base64-decode";
        }
    }

    private Context context;

    @Before
    public void setUp() {
        Assume.assumeTrue("Needs runtime compilation in a native image", ImageInfo.inImageRuntimeCode());
        context = Context.newBuilder().allowExperimentalOptions(true) //
                        .option("engine.CompileImmediately", "true") //
                        .option("engine.BackgroundCompilation", "false") //
                        .option("engine.MultiTier", "false") //
                        .option("engine.CompilationFailureAction", "Throw") //
                        .build();
        context.enter();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    private static final byte SENTINEL = 0x5A;

    @Test
    public void testBase64() {
        for (boolean url : new boolean[]{false, true}) {
            CallTarget target = new Base64Root(url).getCallTarget();
            Base64.Encoder reference = url ? Base64.getUrlEncoder() : Base64.getEncoder();
            boolean compiled = false;
            for (int n = 0; n <= 1100; n += (n < 300 ? 1 : 37)) {
                byte[] src = new byte[n];
                for (int i = 0; i < n; i++) {
                    src[i] = (byte) (i * 131 + n);
                }
                byte[] expected = encodeScalar(src, url);
                byte[] encoded = new byte[expected.length + 64];
                byte[] decoded = new byte[n + 128];
                Arrays.fill(decoded, SENTINEL);
                long r = (long) target.call(src, encoded, decoded);
                compiled |= lastExecutionCompiled;
                assertEquals("encoded length n=" + n, expected.length, (int) (r >>> 32));
                assertArrayEquals("encode n=" + n, expected, Arrays.copyOf(encoded, expected.length));
                assertArrayEquals("encode reference n=" + n, reference.encode(src), expected);
                assertEquals("decoded length n=" + n, n, (int) r);
                assertArrayEquals("decode n=" + n, src, Arrays.copyOf(decoded, n));
                int last = -1;
                for (int i = decoded.length - 1; i >= 0; i--) {
                    if (decoded[i] != SENTINEL) {
                        last = i;
                        break;
                    }
                }
                assertTrue("decode overwrite n=" + n, last < 3 * ((expected.length + 3) / 4));
            }
            assertTrue("never ran compiled", compiled);
        }
    }

    /**
     * Inputs that end in an incomplete padded quantum after at least one 64-byte block
     * (CVE-2026-114938): decoding must not write past the array that
     * {@link Base64.Decoder#decode(byte[])} would allocate, whether the input is accepted or
     * rejected.
     */
    @Test
    public void testBase64PaddedTails() {
        boolean compiled = false;
        for (int prefix : new int[]{4, 32, 64, 128, 256, 320}) {
            for (String tail : new String[]{"=", "==", "X=", "X==", "XY=", "AAA=", "AA==", "A", "AB", "ABC"}) {
                byte[] in = ("A".repeat(prefix) + tail).getBytes(StandardCharsets.US_ASCII);
                // A fresh root per input: rejected inputs throw, and deoptimize, in compiled code.
                CallTarget target = new DecodeRoot().getCallTarget();
                for (int i = 0; i < 3; i++) {
                    byte[] dst = new byte[4096];
                    Arrays.fill(dst, SENTINEL);
                    target.call(in, dst);
                    compiled |= lastExecutionCompiled;
                    int last = -1;
                    for (int j = dst.length - 1; j >= 0; j--) {
                        if (dst[j] != SENTINEL) {
                            last = j;
                            break;
                        }
                    }
                    assertTrue("prefix=" + prefix + " tail=" + tail, last < 3 * ((in.length + 3) / 4));
                }
            }
        }
        assertTrue("never ran compiled", compiled);
    }

    private static byte[] encodeScalar(byte[] s, boolean url) {
        String t = url ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 3 <= s.length; i += 3) {
            int v = (s[i] & 0xff) << 16 | (s[i + 1] & 0xff) << 8 | (s[i + 2] & 0xff);
            sb.append(t.charAt(v >>> 18)).append(t.charAt((v >>> 12) & 63)).append(t.charAt((v >>> 6) & 63)).append(t.charAt(v & 63));
        }
        if (s.length - i == 1) {
            int v = (s[i] & 0xff) << 16;
            sb.append(t.charAt(v >>> 18)).append(t.charAt((v >>> 12) & 63)).append("==");
        } else if (s.length - i == 2) {
            int v = (s[i] & 0xff) << 16 | (s[i + 1] & 0xff) << 8;
            sb.append(t.charAt(v >>> 18)).append(t.charAt((v >>> 12) & 63)).append(t.charAt((v >>> 6) & 63)).append('=');
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
