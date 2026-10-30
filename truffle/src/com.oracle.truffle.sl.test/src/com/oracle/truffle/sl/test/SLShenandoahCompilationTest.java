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
package com.oracle.truffle.sl.test;

import static org.junit.Assert.assertEquals;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.Assume;
import org.junit.Test;

/** Exercises compiled guest code across moving collections (elide-dev/graal#174). */
public class SLShenandoahCompilationTest extends AbstractSLTest {
    @Test
    public void compiledCodeSurvivesCollections() {
        Assume.assumeTrue(Boolean.getBoolean("com.oracle.truffle.sl.test.shenandoah"));
        assertEquals("runtime", System.getProperty("org.graalvm.nativeimage.imagecode"));
        for (int iteration = 0; iteration < 4; iteration++) {
            try (Context context = newContextBuilder().build()) {
                context.eval("sl", "function fac(n) { if (n <= 1) { return 1; } return n * fac(n - 1); }");
                ProxyExecutable collectNow = arguments -> {
                    System.gc();
                    return 1;
                };
                context.eval("sl", "function facGC(n, collect) { if (n <= 1) { return collect(); } return n * facGC(n - 1, collect); }");
                Value collectingFactorial = context.getBindings("sl").getMember("facGC");
                assertEquals(120, collectingFactorial.execute(5, collectNow).asInt());
                Value factorial = context.getBindings("sl").getMember("fac");
                assertEquals(3628800, factorial.execute(10).asInt());
                for (int collection = 0; collection < 4; collection++) {
                    System.gc();
                    assertEquals(3628800, factorial.execute(10).asInt());
                }
            }
            System.gc();
        }
    }
}
