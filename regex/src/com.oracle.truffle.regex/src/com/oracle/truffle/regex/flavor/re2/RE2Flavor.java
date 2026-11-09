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
import com.oracle.truffle.regex.RegexLanguage;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.charset.UnicodeProperties;
import com.oracle.truffle.regex.charset.UnicodePropertyDataVersion;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.parser.CaseFoldData;
import com.oracle.truffle.regex.tregex.parser.RegexFlavor;
import com.oracle.truffle.regex.tregex.parser.RegexLexer;
import com.oracle.truffle.regex.tregex.parser.RegexParser;
import com.oracle.truffle.regex.tregex.parser.RegexValidator;
import com.oracle.truffle.regex.tregex.parser.ast.RegexAST;

/**
 * An implementation of the <a href="https://github.com/google/re2/wiki/Syntax">RE2</a> regex
 * flavor.
 * <p>
 * Matching uses RE2's default semantics: leftmost-first (Perl-like) alternation priority,
 * Unicode-aware character classes on code points, ASCII-only Perl classes ({@code \d \s \w}) and
 * word boundaries, and simple Unicode case folding. RE2 guarantees linear-time matching, so this
 * flavor always implies {@code ForceLinearExecution}: TRegex never falls back to its backtracking
 * engine for RE2 regexes.
 * <p>
 * Not supported: {@code \C} (any byte), which is rejected as a syntax error, and RE2's
 * leftmost-longest (POSIX) matching mode.
 */
public final class RE2Flavor extends RegexFlavor {

    public static final RE2Flavor INSTANCE = new RE2Flavor();
    public static final UnicodeProperties UNICODE = new UnicodeProperties(UnicodePropertyDataVersion.UNICODE_17_0_0, 0, UnicodeProperties.NameMatchingMode.exact);

    private RE2Flavor() {
        super(NESTED_CAPTURE_GROUPS_KEPT_ON_LOOP_REENTRY | RE2_EMPTY_CHECKS | FROM_INDEX_INSIDE_SURROGATE_PAIR_MOVES_FORWARD);
    }

    @Override
    public String getName() {
        return "RE2";
    }

    @Override
    public RegexLexer createLexer(RegexSource source, CompilationBuffer compilationBuffer) {
        return null;
    }

    @Override
    public RegexParser createParser(RegexLanguage language, RegexSource source, CompilationBuffer compilationBuffer) {
        return RE2RegexParser.createParser(language, source, compilationBuffer);
    }

    @Override
    public RegexValidator createValidator(RegexLanguage language, RegexSource source, CompilationBuffer compilationBuffer) {
        return RE2RegexParser.createValidator(source, compilationBuffer);
    }

    @Override
    public EqualsIgnoreCasePredicate getEqualsIgnoreCasePredicate(RegexAST ast) {
        // Case-insensitive matching is fully resolved into character classes by the parser.
        return (a, b, altMode) -> CaseFoldData.CaseFoldUnfoldAlgorithm.ECMAScriptUnicode.getEqualsPredicate().test(a, b);
    }

    @Override
    public CaseFoldData.CaseFoldAlgorithm getCaseFoldAlgorithm(RegexAST ast) {
        throw CompilerDirectives.shouldNotReachHere();
    }
}
