/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.strings.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.graalvm.nativeimage.ImageInfo;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleString.Encoding;

/**
 * Java string identity across conversions, with the cache that keeps the {@link String} a
 * {@link TruffleString} was created from or converted to (on by default in native images; run on
 * HotSpot with {@code -Dtruffle.strings.CacheJavaStrings=true}).
 */
public class TStringJavaStringIdentityTest {

    @Before
    public void requireCache() {
        Assume.assumeTrue("Java string cache is off", Boolean.parseBoolean(System.getProperty("truffle.strings.CacheJavaStrings", String.valueOf(ImageInfo.inImageCode()))));
    }

    private static String nonInterned(String s) {
        return new String(s.toCharArray());
    }

    @Test
    public void toJavaStringIsStable() {
        TruffleString ts = TruffleString.fromCodePointUncached('x', Encoding.UTF_16).concatUncached(TruffleString.fromJavaStringUncached("yz", Encoding.UTF_16), Encoding.UTF_16, false);
        String first = ts.toJavaStringUncached();
        assertEquals("xyz", first);
        assertSame(first, ts.toJavaStringUncached());
        assertSame(first, TruffleString.ToJavaStringNode.create().execute(ts));
    }

    @Test
    public void roundTripKeepsIdentity() {
        // compact (Latin-1) and UTF-16 strings, with and without copying
        for (String original : new String[]{nonInterned("hello world"), nonInterned("h\u00e9llo \u4e16\u754c"), nonInterned("\ud83d\ude00 emoji")}) {
            for (boolean copy : new boolean[]{false, true}) {
                TruffleString ts = TruffleString.fromJavaStringUncached(original, 0, original.length(), Encoding.UTF_16, copy);
                assertSame(original + " copy=" + copy, original, ts.toJavaStringUncached());
            }
        }
    }

    @Test
    public void literalsKeepIdentity() {
        String literal = "a literal";
        assertSame(literal, TruffleString.fromConstant(literal, Encoding.UTF_16).toJavaStringUncached());
    }

    @Test
    public void substringsAreNotTheirSource() {
        String original = nonInterned("hello world");
        TruffleString ts = TruffleString.fromJavaStringUncached(original, 0, 5, Encoding.UTF_16, true);
        String s = ts.toJavaStringUncached();
        assertEquals("hello", s);
        assertNotSame(original, s);
        assertSame(s, ts.toJavaStringUncached());
    }

    @Test
    public void otherEncodingsAreStable() {
        TruffleString ts = TruffleString.fromJavaStringUncached(nonInterned("h\u00e9llo"), Encoding.UTF_8);
        assertSame(ts.toJavaStringUncached(), ts.toJavaStringUncached());
    }

    @Test
    public void racingConversionsAgree() throws InterruptedException {
        for (int round = 0; round < 100; round++) {
            TruffleString ts = TruffleString.fromCodePointUncached('a', Encoding.UTF_16).repeatUncached(32, Encoding.UTF_16);
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentHashMap<Integer, String> seen = new ConcurrentHashMap<>();
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                Thread w = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    seen.put(id, ts.toJavaStringUncached());
                });
                w.start();
                workers.add(w);
            }
            start.countDown();
            for (Thread w : workers) {
                w.join();
            }
            String first = seen.get(0);
            for (String s : seen.values()) {
                assertSame(first, s);
            }
        }
    }
}
