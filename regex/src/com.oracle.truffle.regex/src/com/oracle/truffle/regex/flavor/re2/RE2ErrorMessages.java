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
package com.oracle.truffle.regex.flavor.re2;

/**
 * Error messages of the RE2 flavor. These mirror the error strings of RE2's
 * {@code RegexpStatus::CodeText}, so that users see the same messages as they would from RE2.
 */
public interface RE2ErrorMessages {
    String INVALID_ESCAPE = "invalid escape sequence";
    String INVALID_CHAR_CLASS_RANGE = "invalid character class range";
    String MISSING_BRACKET = "missing ]";
    String MISSING_PAREN = "missing )";
    String UNEXPECTED_PAREN = "unexpected )";
    String TRAILING_BACKSLASH = "trailing \\";
    String MISSING_REPEAT_ARGUMENT = "missing argument to repetition operator";
    String INVALID_REPEAT_SIZE = "invalid repetition size";
    String BAD_REPEAT_OP = "bad repetition operator";
    String INVALID_PERL_OP = "invalid perl operator";
    String INVALID_NAMED_CAPTURE = "invalid named capture group";
    String UNSUPPORTED_ANY_BYTE = "invalid escape sequence: \\C (matching single bytes is not supported)";
    String UNSUPPORTED_FLAG = "unsupported RE2 flag";

    static String withArg(String message, String arg) {
        return message + ": " + arg;
    }
}
