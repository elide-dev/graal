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
package com.oracle.truffle.nfi.test;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;

/**
 * Calls native functions in a loop from a compiled root node, the way a guest language calls them
 * from hot code. Checks the results, and reports the time per call when the system property
 * {@code nfi.test.reportCallOverhead} is set. The timing is informational; it never fails the test.
 */
public class NFICallOverheadTest extends NFITest {

    private static final int WARMUP_CALLS = 2000;
    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASURED_ITERATIONS = 1_000_000;
    private static final int MEASURED_ROUNDS = 5;

    /** Calls {@code function} once per iteration with an argument derived from the iteration. */
    private abstract static class CallLoopNode extends NFITestRootNode {

        final Object function;
        volatile boolean compiled;
        @Child InteropLibrary interop;
        @Child InteropLibrary results;

        CallLoopNode(Object function) {
            this.function = function;
            this.interop = getInterop(function);
            this.results = getInterop();
        }

        @Override
        public final Object executeTest(VirtualFrame frame) throws InteropException {
            compiled = CompilerDirectives.inCompiledCode();
            int iterations = (Integer) frame.getArguments()[0];
            long accumulated = 0;
            for (int i = 0; i < iterations; i++) {
                accumulated += call(i);
            }
            return accumulated;
        }

        abstract long call(int i) throws InteropException;
    }

    private static final class IntCallNode extends CallLoopNode {

        IntCallNode() {
            super(lookupAndBind("increment_SINT32", "(sint32):sint32"));
        }

        @Override
        long call(int i) throws InteropException {
            return results.asInt(interop.execute(function, i));
        }
    }

    private static final class DoubleCallNode extends CallLoopNode {

        DoubleCallNode() {
            super(lookupAndBind("increment_DOUBLE", "(double):double"));
        }

        @Override
        long call(int i) throws InteropException {
            return (long) results.asDouble(interop.execute(function, (double) i));
        }
    }

    private static final class ArrayCallNode extends CallLoopNode {

        private static final int LENGTH = 8;
        private final Object array = runWithPolyglot.getTruffleTestEnv().asGuestValue(new int[]{1, 2, 3, 4, 5, 6, 7, 8});

        ArrayCallNode() {
            super(lookupAndBind("sum_SINT32", "([sint32], uint32):sint32"));
        }

        @Override
        long call(int i) throws InteropException {
            return results.asInt(interop.execute(function, array, LENGTH));
        }
    }

    private static final class StringCallNode extends CallLoopNode {

        StringCallNode() {
            super(lookupAndBind("string_arg", "(string):sint32"));
        }

        @Override
        long call(int i) throws InteropException {
            return results.asInt(interop.execute(function, "42"));
        }
    }

    private static void run(String name, CallLoopNode node, long expectedPerIteration, long expectedOffset) {
        CallTarget target = node.getCallTarget();
        for (int i = 0; i < WARMUP_CALLS; i++) {
            target.call(WARMUP_ITERATIONS);
        }
        long n = MEASURED_ITERATIONS;
        long expected = expectedPerIteration == 0 ? expectedOffset * n : n * (n - 1) / 2 + expectedOffset * n;
        // The fastest round is the least disturbed by other load on the machine.
        long best = Long.MAX_VALUE;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long start = System.nanoTime();
            long result = (Long) target.call(MEASURED_ITERATIONS);
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed);
            if (Boolean.getBoolean("nfi.test.reportCallOverhead")) {
                Assert.assertTrue("measured call must execute compiled code", node.compiled);
                // Checkstyle: stop
                System.out.printf("NFI call sample: %s round=%d %.2f ns/call%n", name, round, (double) elapsed / n);
                // Checkstyle: resume
            }
            Assert.assertEquals(name, expected, result);
        }
        // Read at run time: a static final field would be folded when the image is built.
        if (Boolean.getBoolean("nfi.test.reportCallOverhead")) {
            // Checkstyle: stop
            System.out.printf("NFI call overhead: %-8s %8.2f ns/call%n", name, (double) best / n);
            // Checkstyle: resume
        }
    }

    @Test
    public void testIntCall() {
        run("sint32", new IntCallNode(), 1, 1);
    }

    @Test
    public void testDoubleCall() {
        run("double", new DoubleCallNode(), 1, 1);
    }

    @Test
    public void testArrayCall() {
        // Like ArrayNFITest: the Panama backend does not support wrapped arrays.
        Assume.assumeFalse("panama".equals(TEST_BACKEND));
        run("array", new ArrayCallNode(), 0, 36);
    }

    @Test
    public void testStringCall() {
        run("string", new StringCallNode(), 0, 42);
    }
}
