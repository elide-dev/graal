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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.regex.AbstractRegexObject;
import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.RegexLanguage;
import com.oracle.truffle.regex.RegexRootNode;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.RegexSyntaxException.ErrorCode;
import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.charset.CodePointSetAccumulator;
import com.oracle.truffle.regex.charset.Constants;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.parser.CaseFoldData;
import com.oracle.truffle.regex.tregex.parser.MatchingMode;
import com.oracle.truffle.regex.tregex.parser.RegexASTBuilder;
import com.oracle.truffle.regex.tregex.parser.RegexParser;
import com.oracle.truffle.regex.tregex.parser.RegexValidator;
import com.oracle.truffle.regex.tregex.parser.Token;
import com.oracle.truffle.regex.tregex.parser.ast.RegexAST;
import com.oracle.truffle.regex.tregex.string.Encoding;

/**
 * Parser for the RE2 regex flavor. This is a direct port of the syntax accepted by RE2's
 * {@code Regexp::Parse} with RE2's default ({@code LikePerl}) parse flags, see
 * {@code re2/parse.cc}. The parser builds the TRegex AST directly via {@link RegexASTBuilder}. Case
 * insensitivity is resolved eagerly by expanding all literals and character classes to their
 * simple case folding equivalence classes, exactly like RE2 does.
 * <p>
 * When constructed without an AST builder, the parser only validates the pattern.
 */
public final class RE2RegexParser implements RegexParser, RegexValidator {

    /**
     * Maximum repetition count in counted repetitions, see {@code maximum_repeat_count} in RE2.
     * RE2 also rejects nested counted repetitions whose combined repetition count exceeds this
     * limit.
     */
    public static final int MAX_REPEAT = 1000;

    private static final CodePointSet NEWLINE = CodePointSet.create('\n');
    private static final CodePointSet PERL_SPACE = CodePointSet.createNoDedup('\t', '\n', '\f', '\r', ' ', ' ');
    private static final CodePointSet ALL = Constants.DOT_ALL;

    /**
     * The RE2 Unicode general category names (RE2 does not support long names such as
     * {@code Letter}).
     */
    private static final String[] GENERAL_CATEGORIES = {"C", "Cc", "Cf", "Co", "Cs", "L", "Ll", "Lm", "Lo", "Lt", "Lu", "M", "Mc", "Me", "Mn", "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Pe",
                    "Pf", "Pi", "Po", "Ps", "S", "Sc", "Sk", "Sm", "So", "Z", "Zl", "Zp", "Zs"};

    private static final String[] POSIX_CLASS_NAMES = {"alnum", "alpha", "ascii", "blank", "cntrl", "digit", "graph", "lower", "print", "punct", "space", "upper", "word", "xdigit"};
    private static final CodePointSet[] POSIX_CLASSES = {
                    CodePointSet.createNoDedup('0', '9', 'A', 'Z', 'a', 'z'),
                    CodePointSet.createNoDedup('A', 'Z', 'a', 'z'),
                    CodePointSet.createNoDedup(0x00, 0x7f),
                    CodePointSet.createNoDedup('\t', '\t', ' ', ' '),
                    CodePointSet.createNoDedup(0x00, 0x1f, 0x7f, 0x7f),
                    CodePointSet.createNoDedup('0', '9'),
                    CodePointSet.createNoDedup(0x21, 0x7e),
                    CodePointSet.createNoDedup('a', 'z'),
                    CodePointSet.createNoDedup(0x20, 0x7e),
                    CodePointSet.createNoDedup(0x21, 0x2f, 0x3a, 0x40, 0x5b, 0x60, 0x7b, 0x7e),
                    CodePointSet.createNoDedup(0x09, 0x0d, 0x20, 0x20),
                    CodePointSet.createNoDedup('A', 'Z'),
                    Constants.WORD_CHARS,
                    CodePointSet.createNoDedup('0', '9', 'A', 'F', 'a', 'f'),
    };

    /**
     * Kind of the last item parsed in the current sequence, needed to emulate RE2's handling of
     * repetition operators.
     */
    private enum LastItem {
        /** Start of a group or alternative: repetition operators have no argument. */
        NONE,
        /** A regular term that can be repeated. */
        ATOM,
        /** An empty-width assertion such as {@code ^}, {@code $} or {@code \b}. */
        ASSERTION,
        /** An empty-width assertion that has already been removed by an optional repetition. */
        REMOVED_ASSERTION
    }

    private static final class GroupFrame {
        private final int startPosition;
        private final int savedFlags;
        private final long savedMaxRepeatProduct;
        private final boolean savedSequencePrefixNullable;
        private final boolean savedAlternativesNullable;

        GroupFrame(int startPosition, int savedFlags, long savedMaxRepeatProduct, boolean savedSequencePrefixNullable, boolean savedAlternativesNullable) {
            this.startPosition = startPosition;
            this.savedFlags = savedFlags;
            this.savedMaxRepeatProduct = savedMaxRepeatProduct;
            this.savedSequencePrefixNullable = savedSequencePrefixNullable;
            this.savedAlternativesNullable = savedAlternativesNullable;
        }
    }

    private final RegexSource source;
    private final String pattern;
    private final RegexASTBuilder astBuilder;
    private final CompilationBuffer compilationBuffer;
    private final RE2Flags globalFlags;
    private final List<GroupFrame> groupStack = new ArrayList<>();
    private Map<String, List<Integer>> namedCaptureGroups;

    private int position;
    private int flags;
    private int captureGroupCount;
    private LastItem lastItem = LastItem.NONE;
    private boolean lastWasRepeat;
    private int lastRepeatStart;
    /**
     * The product of nested counted repetition counts of the last term, see RE2's
     * {@code RepetitionWalker}.
     */
    private long lastRepeatProduct = 1;
    /**
     * The maximum of {@link #lastRepeatProduct} over all terms in the current group.
     */
    private long maxRepeatProduct = 1;
    /**
     * Whether the last term of the current sequence can match the empty string. {@code true} if
     * the sequence is empty.
     */
    private boolean lastNullable = true;
    /**
     * Whether all terms of the current sequence except the last one can match the empty string.
     */
    private boolean sequencePrefixNullable = true;
    /**
     * Whether any of the already finished alternatives of the current group can match the empty
     * string.
     */
    private boolean alternativesNullable = false;

    private RE2RegexParser(RegexSource source, RegexASTBuilder astBuilder, CompilationBuffer compilationBuffer) throws RegexSyntaxException {
        this.source = source;
        this.pattern = source.getPattern();
        this.astBuilder = astBuilder;
        this.compilationBuffer = compilationBuffer;
        this.globalFlags = RE2Flags.parseFlags(source);
        this.flags = globalFlags.getValue();
    }

    public static RegexParser createParser(RegexLanguage language, RegexSource source, CompilationBuffer compilationBuffer) throws RegexSyntaxException {
        RegexFlags tregexFlags = RegexFlags.builder().unicode(true).sticky(source.getOptions().getMatchingMode() != MatchingMode.search).build();
        return new RE2RegexParser(source, new RegexASTBuilder(language, source, tregexFlags, true, compilationBuffer), compilationBuffer);
    }

    public static RegexValidator createValidator(RegexSource source, CompilationBuffer compilationBuffer) throws RegexSyntaxException {
        return new RE2RegexParser(source, null, compilationBuffer);
    }

    @Override
    public void validate() throws RegexSyntaxException {
        parseInternal();
    }

    @TruffleBoundary
    @Override
    public RegexAST parse() throws RegexSyntaxException {
        assert astBuilder != null;
        boolean fullMatch = source.getOptions().getMatchingMode() == MatchingMode.fullmatch;
        astBuilder.pushRootGroup();
        if (fullMatch) {
            astBuilder.pushGroup();
        }
        parseInternal();
        if (fullMatch) {
            astBuilder.popGroup();
            astBuilder.addDollar();
        }
        return astBuilder.popRootGroup();
    }

    @Override
    public AbstractRegexObject getFlags() {
        return globalFlags;
    }

    @Override
    public AbstractRegexObject getNamedCaptureGroups() {
        return AbstractRegexObject.createNamedCaptureGroupMapInt(namedCaptureGroups);
    }

    // Parser state helpers

    private boolean isFlagSet(int flag) {
        return (flags & flag) != 0;
    }

    private boolean atEnd() {
        return position >= pattern.length();
    }

    private char curChar() {
        return pattern.charAt(position);
    }

    private boolean lookingAt(int offset, char c) {
        return position + offset < pattern.length() && pattern.charAt(position + offset) == c;
    }

    private int consumeCodePoint() {
        int c = pattern.codePointAt(position);
        position += Character.charCount(c);
        return c;
    }

    private RegexSyntaxException syntaxError(String message, String arg, int errorPosition, ErrorCode errorCode) {
        return RegexSyntaxException.createPattern(source, RE2ErrorMessages.withArg(message, arg), errorPosition, errorCode);
    }

    private RegexSyntaxException syntaxError(String message, int start, int end, ErrorCode errorCode) {
        return syntaxError(message, pattern.substring(start, Math.min(end, pattern.length())), start, errorCode);
    }

    // Main loop

    private void parseInternal() throws RegexSyntaxException {
        while (!atEnd()) {
            RegexRootNode.checkThreadInterrupted();
            boolean prevWasRepeat = lastWasRepeat;
            lastWasRepeat = false;
            int start = position;
            char c = curChar();
            switch (c) {
                case '(':
                    if (lookingAt(1, '?')) {
                        parsePerlFlags();
                    } else {
                        position++;
                        openGroup(start, true, null);
                    }
                    break;
                case '|':
                    position++;
                    if (astBuilder != null) {
                        astBuilder.nextSequence();
                    }
                    lastItem = LastItem.NONE;
                    alternativesNullable |= sequencePrefixNullable && lastNullable;
                    sequencePrefixNullable = true;
                    lastNullable = true;
                    break;
                case ')':
                    position++;
                    closeGroup(start);
                    break;
                case '^':
                    position++;
                    if (isFlagSet(RE2Flags.MULTILINE)) {
                        if (astBuilder != null) {
                            astBuilder.addCaretOrLookBehind(NEWLINE);
                        }
                        beginTerm(LastItem.ASSERTION);
                    } else {
                        addCaret();
                    }
                    break;
                case '$':
                    position++;
                    if (isFlagSet(RE2Flags.MULTILINE)) {
                        if (astBuilder != null) {
                            astBuilder.addDollarOrLookAhead(NEWLINE);
                        }
                        beginTerm(LastItem.ASSERTION);
                    } else {
                        // Without the m flag, $ only matches at the very end of the text (like \z),
                        // not before a trailing newline.
                        addDollar();
                    }
                    break;
                case '.':
                    position++;
                    addAtom(isFlagSet(RE2Flags.DOT_ALL) ? ALL : Constants.NO_NEWLINE);
                    break;
                case '[':
                    addAtom(parseCharClass());
                    break;
                case '*':
                    position++;
                    repetition(start, 0, -1, false, prevWasRepeat);
                    break;
                case '+':
                    position++;
                    repetition(start, 1, -1, false, prevWasRepeat);
                    break;
                case '?':
                    position++;
                    repetition(start, 0, 1, false, prevWasRepeat);
                    break;
                case '{':
                    if (!parseCountedRepetition(start, prevWasRepeat)) {
                        // Not a valid repetition: treat as a literal.
                        position++;
                        addLiteral('{');
                    }
                    break;
                case '\\':
                    parseEscape(start);
                    break;
                default:
                    addLiteral(consumeCodePoint());
                    break;
            }
        }
        if (!groupStack.isEmpty()) {
            throw syntaxError(RE2ErrorMessages.MISSING_PAREN, pattern, groupStack.get(groupStack.size() - 1).startPosition, ErrorCode.UnmatchedParenthesis);
        }
    }

    // Terms

    /**
     * Registers the start of a new term in the current sequence.
     */
    private void beginTerm(LastItem kind) {
        sequencePrefixNullable &= lastNullable;
        lastNullable = kind == LastItem.ASSERTION;
        lastItem = kind;
        lastRepeatProduct = 1;
    }

    private void addAtom(CodePointSet codePointSet) {
        if (astBuilder != null) {
            astBuilder.addCharClass(codePointSet);
        }
        beginTerm(LastItem.ATOM);
    }

    private void addLiteral(int codePoint) {
        CodePointSet cps = CodePointSet.create(codePoint);
        addAtom(isFlagSet(RE2Flags.IGNORE_CASE) ? caseFold(cps) : cps);
    }

    private void addCaret() {
        if (astBuilder != null) {
            astBuilder.addCaret();
        }
        beginTerm(LastItem.ASSERTION);
    }

    private void addDollar() {
        if (astBuilder != null) {
            astBuilder.addDollar();
        }
        beginTerm(LastItem.ASSERTION);
    }

    private void addWordBoundary(boolean negated) {
        if (astBuilder != null) {
            if (negated) {
                astBuilder.addWordNonBoundaryAssertion(null, Constants.WORD_CHARS, Constants.NON_WORD_CHARS);
            } else {
                astBuilder.addWordBoundaryAssertion(null, Constants.WORD_CHARS, Constants.NON_WORD_CHARS);
            }
        }
        beginTerm(LastItem.ASSERTION);
    }

    // Groups

    private void openGroup(int start, boolean capture, String name) {
        // The group is a new term in the enclosing sequence.
        sequencePrefixNullable &= lastNullable;
        groupStack.add(new GroupFrame(start, flags, maxRepeatProduct, sequencePrefixNullable, alternativesNullable));
        maxRepeatProduct = 1;
        lastItem = LastItem.NONE;
        sequencePrefixNullable = true;
        lastNullable = true;
        alternativesNullable = false;
        if (capture) {
            captureGroupCount++;
            if (name != null) {
                if (namedCaptureGroups == null) {
                    namedCaptureGroups = new LinkedHashMap<>();
                }
                // RE2 allows duplicate names; the name refers to the leftmost group.
                namedCaptureGroups.putIfAbsent(name, List.of(captureGroupCount));
            }
            if (astBuilder != null) {
                astBuilder.pushCaptureGroup();
            }
        } else if (astBuilder != null) {
            astBuilder.pushGroup();
        }
    }

    private void closeGroup(int start) {
        if (groupStack.isEmpty()) {
            throw syntaxError(RE2ErrorMessages.UNEXPECTED_PAREN, pattern, start, ErrorCode.UnmatchedParenthesis);
        }
        GroupFrame frame = groupStack.remove(groupStack.size() - 1);
        flags = frame.savedFlags;
        lastRepeatProduct = maxRepeatProduct;
        maxRepeatProduct = Math.max(frame.savedMaxRepeatProduct, lastRepeatProduct);
        lastItem = LastItem.ATOM;
        lastNullable = alternativesNullable || (sequencePrefixNullable && lastNullable);
        sequencePrefixNullable = frame.savedSequencePrefixNullable;
        alternativesNullable = frame.savedAlternativesNullable;
        if (astBuilder != null) {
            astBuilder.popGroup();
        }
    }

    /**
     * Parses a group starting with {@code (?}: flag changes, non-capturing groups and named
     * capture groups. See {@code ParseState::ParsePerlFlags} in RE2.
     */
    private void parsePerlFlags() {
        int start = position;
        int len = pattern.length();
        // Look-around assertions are not supported.
        if (start + 3 < len && (pattern.charAt(start + 2) == '=' || pattern.charAt(start + 2) == '!')) {
            throw syntaxError(RE2ErrorMessages.INVALID_PERL_OP, start, start + 3, ErrorCode.InvalidGroup);
        }
        if (start + 4 < len && pattern.charAt(start + 2) == '<' && (pattern.charAt(start + 3) == '=' || pattern.charAt(start + 3) == '!')) {
            throw syntaxError(RE2ErrorMessages.INVALID_PERL_OP, start, start + 4, ErrorCode.InvalidGroup);
        }
        // Named capture groups: (?P<name>re) and (?<name>re).
        boolean startsWithP = start + 4 < len && pattern.charAt(start + 2) == 'P' && pattern.charAt(start + 3) == '<';
        boolean startsWithName = start + 3 < len && pattern.charAt(start + 2) == '<';
        if (startsWithP || startsWithName) {
            int nameStart = start + (startsWithP ? 4 : 3);
            int end = pattern.indexOf('>', nameStart);
            if (end < 0) {
                throw syntaxError(RE2ErrorMessages.INVALID_NAMED_CAPTURE, start, len, ErrorCode.InvalidNamedGroup);
            }
            String name = pattern.substring(nameStart, end);
            if (!isValidCaptureName(name)) {
                throw syntaxError(RE2ErrorMessages.INVALID_NAMED_CAPTURE, start, end + 1, ErrorCode.InvalidNamedGroup);
            }
            position = end + 1;
            openGroup(start, true, name);
            return;
        }
        int i = start + 2;
        boolean negated = false;
        boolean sawFlags = false;
        int newFlags = flags;
        while (true) {
            if (i >= len) {
                throw syntaxError(RE2ErrorMessages.INVALID_PERL_OP, start, i, ErrorCode.InvalidInlineFlag);
            }
            int c = pattern.codePointAt(i);
            i += Character.charCount(c);
            if (c == '-') {
                if (negated) {
                    throw syntaxError(RE2ErrorMessages.INVALID_PERL_OP, start, i, ErrorCode.InvalidInlineFlag);
                }
                negated = true;
                sawFlags = false;
            } else if (c == ':' || c == ')') {
                if (negated && !sawFlags) {
                    throw syntaxError(RE2ErrorMessages.INVALID_PERL_OP, start, i, ErrorCode.InvalidInlineFlag);
                }
                position = i;
                if (c == ':') {
                    // The group restores the flags that were active before it was opened.
                    openGroup(start, false, null);
                }
                flags = newFlags;
                return;
            } else {
                int bit = RE2Flags.flagBit(c);
                if (bit == 0) {
                    throw syntaxError(RE2ErrorMessages.INVALID_PERL_OP, start, i, ErrorCode.InvalidInlineFlag);
                }
                sawFlags = true;
                if (negated) {
                    newFlags &= ~bit;
                } else {
                    newFlags |= bit;
                }
            }
        }
    }

    /**
     * Capture names may consist of characters in the Unicode categories Lu, Ll, Lt, Lm, Lo, Nl,
     * Mn, Mc, Nd and Pc, see {@code IsValidCaptureName} in RE2.
     */
    private static boolean isValidCaptureName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length();) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            switch (Character.getType(c)) {
                case Character.UPPERCASE_LETTER:
                case Character.LOWERCASE_LETTER:
                case Character.TITLECASE_LETTER:
                case Character.MODIFIER_LETTER:
                case Character.OTHER_LETTER:
                case Character.LETTER_NUMBER:
                case Character.NON_SPACING_MARK:
                case Character.COMBINING_SPACING_MARK:
                case Character.DECIMAL_DIGIT_NUMBER:
                case Character.CONNECTOR_PUNCTUATION:
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    // Repetitions

    /**
     * Tries to parse a counted repetition {@code {n}}, {@code {n,}} or {@code {n,m}} at the
     * current position. Returns {@code false} without consuming anything if the text is not a
     * well-formed counted repetition, in which case RE2 treats the brace as a literal.
     */
    private boolean parseCountedRepetition(int start, boolean prevWasRepeat) {
        int i = start + 1;
        int len = pattern.length();
        int lo = parseInteger(i);
        if (lo < 0) {
            return false;
        }
        i = skipDigits(i);
        int hi;
        if (i >= len) {
            return false;
        }
        if (pattern.charAt(i) == ',') {
            i++;
            if (i >= len) {
                return false;
            }
            if (pattern.charAt(i) == '}') {
                hi = -1;
            } else {
                hi = parseInteger(i);
                if (hi < 0) {
                    return false;
                }
                i = skipDigits(i);
            }
        } else {
            hi = lo;
        }
        if (i >= len || pattern.charAt(i) != '}') {
            return false;
        }
        position = i + 1;
        repetition(start, lo, hi, true, prevWasRepeat);
        return true;
    }

    private int skipDigits(int fromIndex) {
        int i = fromIndex;
        while (i < pattern.length() && isAsciiDigit(pattern.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Parses a non-negative decimal integer without leading zeros, see {@code ParseInteger} in
     * RE2. Returns {@code -1} if there is no valid integer at {@code fromIndex}.
     */
    private int parseInteger(int fromIndex) {
        int len = pattern.length();
        if (fromIndex >= len || !isAsciiDigit(pattern.charAt(fromIndex))) {
            return -1;
        }
        if (fromIndex + 1 < len && pattern.charAt(fromIndex) == '0' && isAsciiDigit(pattern.charAt(fromIndex + 1))) {
            return -1;
        }
        int n = 0;
        for (int i = fromIndex; i < len && isAsciiDigit(pattern.charAt(i)); i++) {
            if (n >= 100000000) {
                return -1;
            }
            n = n * 10 + pattern.charAt(i) - '0';
        }
        return n;
    }

    private static boolean isAsciiDigit(char c) {
        return '0' <= c && c <= '9';
    }

    private void repetition(int start, int min, int max, boolean counted, boolean prevWasRepeat) {
        boolean nonGreedy = false;
        if (!atEnd() && curChar() == '?') {
            nonGreedy = true;
            position++;
        }
        if (prevWasRepeat) {
            // RE2 does not allow stacking repetition operators, e.g. a** or a{2}*.
            throw syntaxError(RE2ErrorMessages.BAD_REPEAT_OP, lastRepeatStart, position, ErrorCode.InvalidQuantifier);
        }
        if (counted && ((max != -1 && max < min) || min > MAX_REPEAT || max > MAX_REPEAT)) {
            throw syntaxError(RE2ErrorMessages.INVALID_REPEAT_SIZE, start, position, ErrorCode.InvalidQuantifier);
        }
        if (lastItem == LastItem.NONE) {
            throw syntaxError(RE2ErrorMessages.MISSING_REPEAT_ARGUMENT, start, position, ErrorCode.InvalidQuantifier);
        }
        if (counted && (min >= 2 || max >= 2)) {
            long count = max == -1 ? min : max;
            long product = count > 0 ? lastRepeatProduct * count : lastRepeatProduct;
            if (product > MAX_REPEAT) {
                throw syntaxError(RE2ErrorMessages.INVALID_REPEAT_SIZE, start, position, ErrorCode.InvalidQuantifier);
            }
            lastRepeatProduct = product;
            maxRepeatProduct = Math.max(maxRepeatProduct, product);
        }
        lastWasRepeat = true;
        lastRepeatStart = start;
        boolean greedy = nonGreedy == isFlagSet(RE2Flags.UNGREEDY);
        switch (lastItem) {
            case ATOM:
                if (astBuilder != null) {
                    if (lastNullable && min == 0 && (max == 1 || max == -1)) {
                        // TRegex follows ECMAScript in rejecting optional loop iterations that
                        // match the empty string. RE2 does not have such empty checks: it compiles
                        // x? to an alternation (x|) and x* for a nullable x to (x+)?, see
                        // Compiler::Quest and Compiler::Star in re2/compile.cc. In x+, only the
                        // second and later iterations are subject to an empty check, which
                        // matches RE2 for all but the first iteration.
                        astBuilder.wrapCurTermInOptionalGroup(max == -1 ? Token.createQuantifier(1, -1, greedy, false, !counted) : null, greedy);
                    } else {
                        astBuilder.addQuantifier(Token.createQuantifier(min, max, greedy, false, !counted));
                    }
                }
                if (min == 0) {
                    lastNullable = true;
                }
                break;
            case ASSERTION:
                // Repeating an empty-width assertion: x{0,...} always matches the empty string,
                // x{n,...} with n > 0 is equivalent to x.
                if (min == 0) {
                    if (astBuilder != null) {
                        astBuilder.removeCurTerm();
                    }
                    lastItem = LastItem.REMOVED_ASSERTION;
                    lastNullable = true;
                }
                break;
            default:
                break;
        }
    }

    // Escapes

    private void parseEscape(int start) {
        if (position + 1 >= pattern.length()) {
            throw RegexSyntaxException.createPattern(source, RE2ErrorMessages.TRAILING_BACKSLASH, start, ErrorCode.InvalidEscape);
        }
        char c = pattern.charAt(position + 1);
        switch (c) {
            case 'b':
            case 'B':
                position += 2;
                addWordBoundary(c == 'B');
                return;
            case 'A':
                position += 2;
                addCaret();
                return;
            case 'z':
                position += 2;
                addDollar();
                return;
            case 'C':
                throw RegexSyntaxException.createPattern(source, RE2ErrorMessages.UNSUPPORTED_ANY_BYTE, start, ErrorCode.InvalidEscape);
            case 'Q':
                position += 2;
                while (!atEnd()) {
                    if (curChar() == '\\' && lookingAt(1, 'E')) {
                        position += 2;
                        break;
                    }
                    addLiteral(consumeCodePoint());
                }
                return;
            case 'p':
            case 'P':
                addAtom(parseUnicodeGroup());
                return;
            case 'd':
            case 'D':
            case 's':
            case 'S':
            case 'w':
            case 'W':
                position += 2;
                addAtom(perlClass(c));
                return;
            default:
                addLiteral(parseEscapeChar());
        }
    }

    /**
     * Parses an escape sequence denoting a single character, see {@code ParseEscape} in RE2. The
     * current position must point to the backslash.
     */
    private int parseEscapeChar() {
        int start = position;
        position++; // backslash
        if (atEnd()) {
            throw RegexSyntaxException.createPattern(source, RE2ErrorMessages.TRAILING_BACKSLASH, start, ErrorCode.InvalidEscape);
        }
        int c = consumeCodePoint();
        switch (c) {
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
                // A single non-zero digit would be a backreference, which is not supported.
                if (atEnd() || !isOctalDigit(curChar())) {
                    throw badEscape(start);
                }
                return parseOctal(c);
            case '0':
                return parseOctal(c);
            case 'x':
                return parseHexEscape(start);
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'a':
                return 0x07;
            case 'f':
                return '\f';
            case 'v':
                return 0x0b;
            default:
                if (c < 0x80 && !Character.isLetterOrDigit(c)) {
                    // Escaped ASCII punctuation (including '_') is always a literal.
                    return c;
                }
                throw badEscape(start);
        }
    }

    private RegexSyntaxException badEscape(int start) {
        return syntaxError(RE2ErrorMessages.INVALID_ESCAPE, start, position, ErrorCode.InvalidEscape);
    }

    private static boolean isOctalDigit(char c) {
        return '0' <= c && c <= '7';
    }

    private int parseOctal(int firstDigit) {
        int code = firstDigit - '0';
        for (int i = 0; i < 2 && !atEnd() && isOctalDigit(curChar()); i++) {
            code = code * 8 + curChar() - '0';
            position++;
        }
        return code;
    }

    private int parseHexEscape(int start) {
        if (atEnd()) {
            throw badEscape(start);
        }
        int c = consumeCodePoint();
        if (c == '{') {
            int nHex = 0;
            int code = 0;
            while (true) {
                if (atEnd()) {
                    throw badEscape(start);
                }
                c = consumeCodePoint();
                int digit = Character.digit(c, 16);
                if (c >= 0x80 || digit < 0) {
                    break;
                }
                nHex++;
                code = code * 16 + digit;
                if (code > Character.MAX_CODE_POINT) {
                    throw badEscape(start);
                }
            }
            if (c != '}' || nHex == 0) {
                throw badEscape(start);
            }
            return code;
        }
        if (atEnd()) {
            throw badEscape(start);
        }
        int c1 = consumeCodePoint();
        int hi = c < 0x80 ? Character.digit(c, 16) : -1;
        int lo = c1 < 0x80 ? Character.digit(c1, 16) : -1;
        if (hi < 0 || lo < 0) {
            throw badEscape(start);
        }
        return hi * 16 + lo;
    }

    // Character classes

    private CodePointSet perlClass(char c) {
        switch (c) {
            case 'd':
            case 'D':
                return applyCaseFoldAndSign(Constants.DIGITS, c == 'D');
            case 's':
            case 'S':
                return applyCaseFoldAndSign(PERL_SPACE, c == 'S');
            default:
                assert c == 'w' || c == 'W';
                return applyCaseFoldAndSign(Constants.WORD_CHARS, c == 'W');
        }
    }

    /**
     * Applies case folding (if enabled) to a predefined character class and then negates it if
     * requested. This mirrors RE2's {@code AddUGroup}: a negated class under case folding excludes
     * everything that is case-equivalent to a member of the class.
     */
    private CodePointSet applyCaseFoldAndSign(CodePointSet set, boolean negate) {
        CodePointSet result = isFlagSet(RE2Flags.IGNORE_CASE) ? caseFold(set) : set;
        return negate ? result.createInverse(Encoding.UTF_32) : result;
    }

    private CodePointSet caseFold(CodePointSet set) {
        CodePointSetAccumulator acc = new CodePointSetAccumulator();
        acc.addSet(set);
        CaseFoldData.applyCaseFoldUnfold(acc, compilationBuffer.getCodePointSetAccumulator1(), CaseFoldData.CaseFoldUnfoldAlgorithm.ECMAScriptUnicode);
        return acc.toCodePointSet();
    }

    /**
     * Parses a Unicode class escape {@code \pN}, {@code \p{Name}}, {@code \PN}, {@code \P{Name}}
     * or {@code \p{^Name}}. The current position must point to the backslash.
     */
    private CodePointSet parseUnicodeGroup() {
        int start = position;
        boolean negate = pattern.charAt(position + 1) == 'P';
        position += 2;
        if (atEnd()) {
            throw syntaxError(RE2ErrorMessages.INVALID_CHAR_CLASS_RANGE, start, position, ErrorCode.InvalidCharacterClass);
        }
        String name;
        if (curChar() != '{') {
            int c = consumeCodePoint();
            name = new String(Character.toChars(c));
        } else {
            int end = pattern.indexOf('}', position);
            if (end < 0) {
                throw syntaxError(RE2ErrorMessages.INVALID_CHAR_CLASS_RANGE, start, pattern.length(), ErrorCode.InvalidCharacterClass);
            }
            name = pattern.substring(position + 1, end);
            position = end + 1;
        }
        if (name.startsWith("^")) {
            negate = !negate;
            name = name.substring(1);
        }
        CodePointSet set = lookupUnicodeGroup(name);
        if (set == null) {
            throw syntaxError(RE2ErrorMessages.INVALID_CHAR_CLASS_RANGE, start, position, ErrorCode.InvalidCharacterClass);
        }
        return applyCaseFoldAndSign(set, negate);
    }

    private static CodePointSet lookupUnicodeGroup(String name) {
        if (name.equals("Any")) {
            return ALL;
        }
        if (name.equals("C")) {
            // RE2's C category does not include unassigned code points (Cn).
            return RE2Flavor.UNICODE.unionOfProperties("gc=Cc", "gc=Cf", "gc=Co", "gc=Cs");
        }
        for (String gc : GENERAL_CATEGORIES) {
            if (gc.equals(name)) {
                return RE2Flavor.UNICODE.getProperty("gc=" + name);
            }
        }
        if (name.isEmpty() || !RE2Flavor.UNICODE.isSupportedScript(name)) {
            return null;
        }
        return RE2Flavor.UNICODE.getProperty("sc=" + name);
    }

    /**
     * Parses a bracketed character class like {@code [^a-z[:digit:]\pL]}, see
     * {@code ParseState::ParseCharClass} in RE2.
     */
    private CodePointSet parseCharClass() {
        int start = position;
        position++; // '['
        boolean negated = false;
        if (!atEnd() && curChar() == '^') {
            negated = true;
            position++;
        }
        CodePointSetAccumulator acc = new CodePointSetAccumulator();
        boolean first = true;
        while (!atEnd() && (curChar() != ']' || first)) {
            first = false;
            // POSIX classes like [:alnum:] and [:^alnum:].
            if (curChar() == '[' && lookingAt(1, ':') && position + 2 < pattern.length()) {
                int end = pattern.indexOf(":]", position + 2);
                if (end >= 0) {
                    acc.addSet(parsePosixClass(pattern.substring(position, end + 2)));
                    position = end + 2;
                    continue;
                }
            }
            if (curChar() == '\\' && position + 2 < pattern.length() && (lookingAt(1, 'p') || lookingAt(1, 'P'))) {
                acc.addSet(parseUnicodeGroup());
                continue;
            }
            if (curChar() == '\\' && position + 1 < pattern.length()) {
                char c = pattern.charAt(position + 1);
                if (c == 'd' || c == 'D' || c == 's' || c == 'S' || c == 'w' || c == 'W') {
                    position += 2;
                    acc.addSet(perlClass(c));
                    continue;
                }
            }
            int rangeStart = position;
            int lo = parseClassChar(start);
            int hi = lo;
            if (position + 1 < pattern.length() && curChar() == '-' && pattern.charAt(position + 1) != ']') {
                position++; // '-'
                hi = parseClassChar(start);
                if (hi < lo) {
                    throw syntaxError(RE2ErrorMessages.INVALID_CHAR_CLASS_RANGE, rangeStart, position, ErrorCode.InvalidCharacterClass);
                }
            }
            acc.addRange(lo, hi);
        }
        if (atEnd()) {
            throw syntaxError(RE2ErrorMessages.MISSING_BRACKET, start, pattern.length(), ErrorCode.UnmatchedBracket);
        }
        position++; // ']'
        if (isFlagSet(RE2Flags.IGNORE_CASE)) {
            // All class components are closed under case folding at this point except for plain
            // ranges, so folding the union is the same as folding each range individually.
            CaseFoldData.applyCaseFoldUnfold(acc, compilationBuffer.getCodePointSetAccumulator1(), CaseFoldData.CaseFoldUnfoldAlgorithm.ECMAScriptUnicode);
        }
        CodePointSet set = acc.toCodePointSet();
        return negated ? set.createInverse(Encoding.UTF_32) : set;
    }

    private int parseClassChar(int classStart) {
        if (atEnd()) {
            throw syntaxError(RE2ErrorMessages.MISSING_BRACKET, classStart, pattern.length(), ErrorCode.UnmatchedBracket);
        }
        if (curChar() == '\\') {
            return parseEscapeChar();
        }
        return consumeCodePoint();
    }

    private CodePointSet parsePosixClass(String name) {
        // name has the form [:alpha:] or [:^alpha:]
        boolean negate = name.charAt(2) == '^';
        String className = name.substring(negate ? 3 : 2, name.length() - 2);
        for (int i = 0; i < POSIX_CLASS_NAMES.length; i++) {
            if (POSIX_CLASS_NAMES[i].equals(className)) {
                return applyCaseFoldAndSign(POSIX_CLASSES[i], negate);
            }
        }
        throw syntaxError(RE2ErrorMessages.INVALID_CHAR_CLASS_RANGE, name, position, ErrorCode.InvalidCharacterClass);
    }
}
