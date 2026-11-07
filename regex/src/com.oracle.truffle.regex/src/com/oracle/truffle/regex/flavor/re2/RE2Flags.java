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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.regex.AbstractConstantKeysObject;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.util.TruffleReadOnlyKeysArray;

/**
 * An immutable set of RE2 flags. The flags string of a TRegex source ({@code /pattern/flags}) may
 * contain the RE2 inline flag letters {@code i}, {@code m}, {@code s} and {@code U}; they have the
 * same effect as a leading {@code (?flags)} group in the pattern.
 */
@ExportLibrary(InteropLibrary.class)
public final class RE2Flags extends AbstractConstantKeysObject {

    static final int IGNORE_CASE = 1;
    static final int MULTILINE = 1 << 1;
    static final int DOT_ALL = 1 << 2;
    static final int UNGREEDY = 1 << 3;

    private static final String PROP_IGNORE_CASE = "i";
    private static final String PROP_MULTILINE = "m";
    private static final String PROP_DOT_ALL = "s";
    private static final String PROP_UNGREEDY = "U";
    private static final TruffleReadOnlyKeysArray KEYS = new TruffleReadOnlyKeysArray(PROP_IGNORE_CASE, PROP_MULTILINE, PROP_DOT_ALL, PROP_UNGREEDY);
    private static final char[] ALL_FLAGS = {'i', 'm', 's', 'U'};

    public static final RE2Flags EMPTY_INSTANCE = new RE2Flags(0);

    private final int value;

    private RE2Flags(int value) {
        this.value = value;
    }

    public static RE2Flags parseFlags(RegexSource source) throws RegexSyntaxException {
        String flagsStr = source.getFlags();
        int flags = 0;
        for (int i = 0; i < flagsStr.length(); i++) {
            int bit = flagBit(flagsStr.charAt(i));
            if (bit == 0) {
                throw RegexSyntaxException.createFlags(source, RE2ErrorMessages.UNSUPPORTED_FLAG, i);
            }
            flags |= bit;
        }
        return flags == 0 ? EMPTY_INSTANCE : new RE2Flags(flags);
    }

    /**
     * Returns the bit corresponding to the given RE2 flag letter, or {@code 0} if the letter is
     * not a valid RE2 flag.
     */
    static int flagBit(int c) {
        switch (c) {
            case 'i':
                return IGNORE_CASE;
            case 'm':
                return MULTILINE;
            case 's':
                return DOT_ALL;
            case 'U':
                return UNGREEDY;
            default:
                return 0;
        }
    }

    int getValue() {
        return value;
    }

    private boolean hasFlag(int flag) {
        return (value & flag) != 0;
    }

    public boolean isIgnoreCase() {
        return hasFlag(IGNORE_CASE);
    }

    public boolean isMultiline() {
        return hasFlag(MULTILINE);
    }

    public boolean isDotAll() {
        return hasFlag(DOT_ALL);
    }

    public boolean isUngreedy() {
        return hasFlag(UNGREEDY);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RE2Flags && this.value == ((RE2Flags) other).value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @TruffleBoundary
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(ALL_FLAGS.length);
        for (int i = 0; i < ALL_FLAGS.length; i++) {
            if (hasFlag(1 << i)) {
                sb.append(ALL_FLAGS[i]);
            }
        }
        return sb.toString();
    }

    @TruffleBoundary
    @ExportMessage
    @Override
    public Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "TRegexRE2Flags{flags=" + this + '}';
    }

    @Override
    public TruffleReadOnlyKeysArray getKeys() {
        return KEYS;
    }

    @Override
    public boolean isMemberReadableImpl(String symbol) {
        switch (symbol) {
            case PROP_IGNORE_CASE:
            case PROP_MULTILINE:
            case PROP_DOT_ALL:
            case PROP_UNGREEDY:
                return true;
            default:
                return false;
        }
    }

    @Override
    public Object readMemberImpl(String symbol) throws UnknownIdentifierException {
        switch (symbol) {
            case PROP_IGNORE_CASE:
                return isIgnoreCase();
            case PROP_MULTILINE:
                return isMultiline();
            case PROP_DOT_ALL:
                return isDotAll();
            case PROP_UNGREEDY:
                return isUngreedy();
            default:
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw UnknownIdentifierException.create(symbol);
        }
    }
}
