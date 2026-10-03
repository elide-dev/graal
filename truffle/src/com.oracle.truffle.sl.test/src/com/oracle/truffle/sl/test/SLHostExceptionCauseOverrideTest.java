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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * Host methods and constructors that throw an exception whose {@link Throwable#getCause()} is
 * overridden. On native image, host executables are invoked reflectively and the exception is
 * unwrapped from the {@code InvocationTargetException}. The unwrapping must stay out of runtime
 * compilation, or compiled host calls reach every {@code getCause()} override in the image. With
 * {@code -H:+RuntimeClassLoading}, the override below then reaches {@link BigInteger}, which is on
 * Truffle's runtime compilation blocklist, and the image fails to build.
 */
public class SLHostExceptionCauseOverrideTest extends AbstractSLTest {

    @SuppressWarnings("serial")
    public static final class CauseOverrideException extends RuntimeException {

        private final long code;

        CauseOverrideException(long code) {
            super("code " + code);
            this.code = code;
        }

        public long getCode() {
            return code;
        }

        @Override
        public synchronized Throwable getCause() {
            return BigInteger.valueOf(code).signum() < 0 ? this : super.getCause();
        }
    }

    public static final class Thrower {

        public Thrower() {
        }

        public Thrower(long code) {
            throw new CauseOverrideException(code);
        }

        public Object fail(long code) {
            throw new CauseOverrideException(code);
        }
    }

    @Test
    public void testMethod() {
        try (Context context = newContextBuilder().allowHostAccess(HostAccess.ALL).build()) {
            context.eval("sl", "function test(thrower, code) {\n" +
                            "  return thrower.fail(code);\n" +
                            "}");
            Value test = context.getBindings("sl").getMember("test");
            for (int i = 0; i < 3; i++) {
                long code = 42 + i;
                assertThrows(code, () -> test.execute(new Thrower(), code));
            }
        }
    }

    @Test
    public void testConstructor() {
        try (Context context = newContextBuilder().allowHostAccess(HostAccess.ALL).build()) {
            Value thrower = context.asValue(Thrower.class);
            assertThrows(43, () -> thrower.newInstance(43L));
        }
    }

    private static void assertThrows(long code, Runnable action) {
        try {
            action.run();
            fail("expected a PolyglotException");
        } catch (PolyglotException e) {
            assertTrue(e.isHostException());
            CauseOverrideException cause = (CauseOverrideException) e.asHostException();
            assertEquals(code, cause.getCode());
            assertNull(cause.getCause());
        }
    }
}
