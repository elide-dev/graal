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
package com.oracle.truffle.regex.tregex.test;

import java.util.Map;

import org.graalvm.polyglot.Value;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.regex.tregex.string.Encoding;

public class RE2Tests extends RegexTestBase {

    public static final Map<String, String> ENGINE_OPTIONS = Map.of("regexDummyLang.Flavor", "RE2");

    @Override
    Map<String, String> getEngineOptions() {
        return ENGINE_OPTIONS;
    }

    @Override
    Encoding getTRegexEncoding() {
        return Encoding.UTF_16;
    }

    @Test
    public void leftmostFirst() {
        test("a", "", "xay", 0, true, 1, 2);
        test("(fo|foo)", "", "foo", 0, true, 0, 2, 0, 2);
        test("(foo|fo)", "", "foo", 0, true, 0, 3, 0, 3);
        test("a|ab", "", "ab", 0, true, 0, 1);
        test("(a+)(a*)", "", "aaa", 0, true, 0, 3, 0, 3, 3, 3);
        test("(a+?)(a*)", "", "aaa", 0, true, 0, 3, 0, 1, 1, 3);
        test("(a|ab)(c|bcd)(d*)", "", "abcd", 0, true, 0, 4, 0, 1, 1, 4, 4, 4);
        test("x*", "", "aaa", 0, true, 0, 0);
    }

    @Test
    public void captureGroupsInLoops() {
        // RE2 keeps the last value of a capture group across loop iterations.
        test("(?:(a)|b)+", "", "ab", 0, true, 0, 2, 0, 1);
        test("(?:(a)|(b))+", "", "ab", 0, true, 0, 2, 0, 1, 1, 2);
        test("(a|b)*", "", "abb", 0, true, 0, 3, 2, 3);
    }

    @Test
    public void emptyIterations() {
        // RE2 has no empty checks on x? and on the first iteration of x*
        test("(a*)?", "", "b", 0, true, 0, 0, 0, 0);
        test("(a*)??", "", "b", 0, true, 0, 0, -1, -1);
        test("(a*)*", "", "b", 0, true, 0, 0, 0, 0);
        test("(a*)+", "", "b", 0, true, 0, 0, 0, 0);
        test("(a*)*?", "", "b", 0, true, 0, 0, -1, -1);
        test("(a*)*", "", "aa", 0, true, 0, 2, 0, 2);
        test("(a*)+", "", "aa", 0, true, 0, 2, 0, 2);
        test("(a*)*b", "", "aab", 0, true, 0, 3, 0, 2);
        test("(a|)*", "", "aaa", 0, true, 0, 3, 2, 3);
        test("(a|b*)*", "", "ab", 0, true, 0, 2, 1, 2);
        test("(a*|b)*", "", "ab", 0, true, 0, 2, 1, 2);
        test("(?:(a*)|b)*", "", "ab", 0, true, 0, 2, 0, 1);
        test("((a)|b)*", "", "ab", 0, true, 0, 2, 1, 2, 0, 1);
        test("(a*){2,3}", "", "aa", 0, true, 0, 2, 2, 2);
        test("(?:^)*a", "", "ba", 0, true, 1, 2);
        test("(^)*a", "", "ba", 0, true, 1, 2, -1, -1);
    }

    @Test
    public void anchors() {
        // Without the m flag, $ matches only at the very end of the text, not before a final
        // newline.
        test("a$", "", "a\n", 0, false);
        test("a$", "", "a", 0, true, 0, 1);
        test("a\\z", "", "a\n", 0, false);
        test("^b", "", "a\nb", 0, false);
        test("\\Ab", "", "a\nb", 0, false);
        test("\\Aa", "", "ab", 0, true, 0, 1);
        // multiline
        test("(?m)a$", "", "a\nb", 0, true, 0, 1);
        test("(?m)^b", "", "a\nb", 0, true, 2, 3);
        test("^b", "m", "a\nb", 0, true, 2, 3);
        test("(?m)^$", "", "a\n", 0, true, 2, 2);
        test("(?m)\\Ab", "", "a\nb", 0, false);
        test("(?m)a\\z", "", "a\nb", 0, false);
        test("(?m:^b)|^c", "", "a\nc\nb", 0, true, 4, 5);
        // repeated empty-width assertions
        test("^*a", "", "ba", 0, true, 1, 2);
        test("^+a", "", "ba", 0, false);
        test("^+a", "", "ab", 0, true, 0, 1);
        test("$?a", "", "ba", 0, true, 1, 2);
        test("^^^$$$", "", "", 0, true, 0, 0);
    }

    @Test
    public void wordBoundaries() {
        test("\\bfoo\\b", "", "a foo b", 0, true, 2, 5);
        test("\\bfoo\\b", "", "afoo", 0, false);
        test("\\Boo\\B", "", "foobar", 0, true, 1, 3);
        // \b is ASCII-only
        test("\\b", "", "\u00e9", 0, false);
        test("\\B", "", "\u00e9", 0, true, 0, 0);
        test("a\\b", "", "a\u00e9", 0, true, 0, 1);
        test("(?i)\\b\u212a", "", "a\u212a", 0, true, 1, 2);
    }

    @Test
    public void dot() {
        test(".", "", "\n", 0, false);
        test(".", "", "\r", 0, true, 0, 1);
        test("(?s).", "", "\n", 0, true, 0, 1);
        test("a.c", "s", "a\nc", 0, true, 0, 3);
        test("(?s:.)(.)", "", "\n\n", 0, false);
        test(".", "", "\ud83d\ude00", 0, true, 0, 2);
        test(".", "", Encoding.UTF_8, "\ud83d\ude00", 0, true, 0, 4);
        test("[^a]", "", "\n", 0, true, 0, 1);
    }

    @Test
    public void ungreedyFlag() {
        test("a+", "", "aaa", 0, true, 0, 3);
        test("a+?", "", "aaa", 0, true, 0, 1);
        test("(?U)a+", "", "aaa", 0, true, 0, 1);
        test("(?U)a+?", "", "aaa", 0, true, 0, 3);
        test("a+", "U", "aaa", 0, true, 0, 1);
        test("(?U:(a+))(a*)", "", "aaa", 0, true, 0, 3, 0, 1, 1, 3);
        test("(?U)(a+)(?-U)(a+)", "", "aaa", 0, true, 0, 3, 0, 1, 1, 3);
        test("(?U)(a{1,3})", "", "aaa", 0, true, 0, 1, 0, 1);
        test("(?U)(a{1,3}?)", "", "aaa", 0, true, 0, 3, 0, 3);
        test("(?U)(a??)", "", "a", 0, true, 0, 1, 0, 1);
    }

    @Test
    public void caseInsensitive() {
        test("(?i)abc", "", "xAbC", 0, true, 1, 4);
        test("abc", "i", "xAbC", 0, true, 1, 4);
        test("(?i)[a-z]+", "", "ABC", 0, true, 0, 3);
        // simple case folding orbits
        test("(?i)k", "", "\u212a", 0, true, 0, 1);
        test("(?i)\u212a", "", "k", 0, true, 0, 1);
        test("(?i)K", "", "\u212a", 0, true, 0, 1);
        test("(?i)s", "", "\u017f", 0, true, 0, 1);
        test("(?i)\u03c2", "", "\u03a3", 0, true, 0, 1);
        test("(?i)\u03c3", "", "\u03c2", 0, true, 0, 1);
        test("(?i)\ud801\udc00", "", "\ud801\udc28", 0, true, 0, 2);
        // no multi-character folding
        test("(?i)\u00df", "", "ss", 0, false);
        // Perl and POSIX classes are case folded, too
        test("(?i)\\w", "", "\u212a", 0, true, 0, 1);
        test("(?i)\\W", "", "\u212a", 0, false);
        test("(?i)[^k]", "", "\u212a", 0, false);
        test("(?i)[^k]", "", "K", 0, false);
        test("(?i)[[:upper:]]", "", "a", 0, true, 0, 1);
        test("[[:upper:]]", "", "a", 0, false);
        test("(?i)\\p{Lu}", "", "a", 0, true, 0, 1);
        test("(?i)\\P{Lu}", "", "a", 0, false);
        // scoped flags
        test("a(?i:b)c", "", "aBc", 0, true, 0, 3);
        test("a(?i:b)c", "", "aBC", 0, false);
        test("(a(?i)b)c", "", "aBc", 0, true, 0, 3, 0, 2);
        test("(a(?i)b)c", "", "aBC", 0, false);
        test("(?i)a(?-i)b", "", "Ab", 0, true, 0, 2);
        test("(?i)a(?-i)b", "", "AB", 0, false);
        test("(?i)a(?-i:b)c", "", "AbC", 0, true, 0, 3);
        test("(?i)(?:a|b(?-i)c)d", "", "BcD", 0, true, 0, 3);
        test("(?i)(?:a|b(?-i)c)d", "", "BCD", 0, false);
    }

    @Test
    public void namedGroups() {
        Value regex = compileRegex("(?P<first>a)(?<second>b)(c)(?P<\u00e4\u0661_>d)", "");
        Assert.assertEquals(5, regex.getMember("groupCount").asInt());
        Value groups = regex.getMember("groups");
        Assert.assertEquals(1, groups.getMember("first").asInt());
        Assert.assertEquals(2, groups.getMember("second").asInt());
        Assert.assertEquals(4, groups.getMember("\u00e4\u0661_").asInt());
        test("(?P<first>a)(?<second>b)", "", "ab", 0, true, 0, 2, 0, 1, 1, 2);
        // duplicate names are allowed, the name refers to the leftmost group
        Value dup = compileRegex("(?P<n>a)|(?P<n>b)", "");
        Assert.assertEquals(1, dup.getMember("groups").getMember("n").asInt());
        Assert.assertTrue(compileRegex("(a)", "").getMember("groups").isNull());
        expectSyntaxError("(?P<>a)", "", "invalid named capture group: (?P<>");
        expectSyntaxError("(?P<a-b>a)", "", "invalid named capture group: (?P<a-b>");
        expectSyntaxError("(?<a b>a)", "", "invalid named capture group: (?<a b>");
        expectSyntaxError("(?P<name", "", "invalid named capture group: (?P<name");
        expectSyntaxError("(?P=name)", "", "invalid perl operator: (?P");
        expectSyntaxError("(?'name'a)", "", "invalid perl operator: (?'");
    }

    @Test
    public void flags() {
        test("(?ims-U:a.$)", "", "A\n", 0, true, 0, 2);
        test("(?)a", "", "a", 0, true, 0, 1);
        expectSyntaxError("(?x)a", "", "invalid perl operator: (?x");
        expectSyntaxError("(?i-)a", "", "invalid perl operator: (?i-)");
        expectSyntaxError("(?-)a", "", "invalid perl operator: (?-)");
        expectSyntaxError("(?i--s)a", "", "invalid perl operator: (?i--");
        expectSyntaxError("(?i", "", "invalid perl operator: (?i");
        expectSyntaxError("a", "x", "unsupported RE2 flag");
    }

    @Test
    public void repetition() {
        test("a{2}", "", "aaa", 0, true, 0, 2);
        test("a{2,}", "", "aaaa", 0, true, 0, 4);
        test("a{2,3}", "", "aaaa", 0, true, 0, 3);
        test("a{2,3}?", "", "aaaa", 0, true, 0, 2);
        test("a{0}b", "", "ab", 0, true, 1, 2);
        test("(a){0}b", "", "ab", 0, true, 1, 2, -1, -1);
        test("(ab){1,2}", "", "ababab", 0, true, 0, 4, 2, 4);
        // invalid counted repetitions are literals
        test("a{,2}", "", "a{,2}", 0, true, 0, 5);
        test("a{", "", "a{", 0, true, 0, 2);
        test("a{1", "", "a{1", 0, true, 0, 3);
        test("a{1,2", "", "a{1,2", 0, true, 0, 5);
        test("a{01}", "", "a{01}", 0, true, 0, 5);
        test("a{x}", "", "a{x}", 0, true, 0, 4);
        // the maximum repetition count is 1000
        String a1000 = "a".repeat(1000);
        test("a{1000}", "", a1000 + "a", 0, true, 0, 1000);
        test("^a{1000}$", "", a1000, 0, true, 0, 1000);
        test("(?:a{10}){100}", "", a1000, 0, true, 0, 1000);
        expectSyntaxError("a{1001}", "", "invalid repetition size: {1001}");
        expectSyntaxError("a{1001,}", "", "invalid repetition size: {1001,}");
        expectSyntaxError("a{0,1001}", "", "invalid repetition size: {0,1001}");
        expectSyntaxError("a{2,1}", "", "invalid repetition size: {2,1}");
        expectSyntaxError("(a{2}){501}", "", "invalid repetition size: {501}");
        expectSyntaxError("((a{10}){10}){11}", "", "invalid repetition size: {11}");
        // the limit applies to nested repetitions only, not to sequences
        test("a{2}b{1000}", "", "aa" + "b".repeat(1000), 0, true, 0, 1002);
        expectSyntaxError("(?:a{2}b{11}){100}", "", "invalid repetition size: {100}");
        // stacked repetition operators are not allowed
        expectSyntaxError("a**", "", "bad repetition operator: **");
        expectSyntaxError("a*+", "", "bad repetition operator: *+");
        expectSyntaxError("a??*", "", "bad repetition operator: ??*");
        expectSyntaxError("a{2}{3}", "", "bad repetition operator: {2}{3}");
        expectSyntaxError("a{2}*", "", "bad repetition operator: {2}*");
        expectSyntaxError("a*{2}", "", "bad repetition operator: *{2}");
        // missing argument
        expectSyntaxError("*", "", "missing argument to repetition operator: *");
        expectSyntaxError("(*)", "", "missing argument to repetition operator: *");
        expectSyntaxError("a|*", "", "missing argument to repetition operator: *");
        expectSyntaxError("(?i)+", "", "missing argument to repetition operator: +");
        expectSyntaxError("{2}", "", "missing argument to repetition operator: {2}");
        expectSyntaxError("{2}?", "", "missing argument to repetition operator: {2}?");
    }

    @Test
    public void largeRepetitions() {
        test("\\w{1,64}@\\w{1,255}\\.com", "", "x" + "a".repeat(70) + "@example.com", 0, true, 7, 83);
        test(".{0,500}x", "", "a".repeat(600) + "x", 0, true, 100, 601);
        test("(abc){300}", "", "abc".repeat(300), 0, true, 0, 900, 897, 900);
        test("\\pL{1000}", "", "\u00e9".repeat(1000), 0, true, 0, 1000);
        // Repetitions that exceed TRegex's automaton size limits cannot be executed in linear time
        // and are reported as unsupported, since RE2 never falls back to backtracking.
        expectUnsupported(".{0,1000}x");
        expectUnsupported("[a-z]{1,1000}");
    }

    @Test
    public void noBacktrackingFeatures() {
        expectSyntaxError("(a)\\1", "", "invalid escape sequence: \\1");
        expectSyntaxError("(?=a)", "", "invalid perl operator: (?=");
        expectSyntaxError("(?!a)", "", "invalid perl operator: (?!");
        expectSyntaxError("(?<=a)b", "", "invalid perl operator: (?<=");
        expectSyntaxError("(?<!a)b", "", "invalid perl operator: (?<!");
        expectSyntaxError("(?>a)", "", "invalid perl operator: (?>");
        expectSyntaxError("a++", "", "bad repetition operator: ++");
        expectSyntaxError("\\C", "", "\\C");
        expectSyntaxError("a\\C*b", "", "\\C");
    }

    @Test
    public void syntaxErrors() {
        expectSyntaxError("a)", "", "unexpected ): a)");
        expectSyntaxError("(a", "", "missing ): (a");
        expectSyntaxError("((a)", "", "missing )");
        expectSyntaxError("[a", "", "missing ]: [a");
        expectSyntaxError("[a-", "", "missing ]: [a-");
        expectSyntaxError("[]", "", "missing ]: []");
        expectSyntaxError("[z-a]", "", "invalid character class range: z-a");
        expectSyntaxError("[[:foo:]]", "", "invalid character class range: [:foo:]");
        expectSyntaxError("\\p{Foo}", "", "invalid character class range: \\p{Foo}");
        expectSyntaxError("\\p{Letter}", "", "invalid character class range: \\p{Letter}");
        expectSyntaxError("\\pX", "", "invalid character class range: \\pX");
        expectSyntaxError("\\p{L", "", "invalid character class range: \\p{L");
        expectSyntaxError("a\\", "", "trailing \\");
        expectSyntaxError("\\q", "", "invalid escape sequence: \\q");
        expectSyntaxError("\\Z", "", "invalid escape sequence: \\Z");
        expectSyntaxError("\\8", "", "invalid escape sequence: \\8");
        expectSyntaxError("\\xZ1", "", "invalid escape sequence: \\xZ1");
        expectSyntaxError("\\x1", "", "invalid escape sequence");
        expectSyntaxError("\\x{}", "", "invalid escape sequence: \\x{}");
        expectSyntaxError("\\x{110000}", "", "invalid escape sequence");
        expectSyntaxError("\\x{12", "", "invalid escape sequence");
        expectSyntaxError("[\\b]", "", "invalid escape sequence: \\b");
        expectSyntaxError("[\\Qa\\E]", "", "invalid escape sequence: \\Q");
    }

    @Test
    public void escapes() {
        test("\\a\\f\\t\\n\\r\\v", "", "\u0007\f\t\n\r\u000b", 0, true, 0, 6);
        test("\\x41\\x{1F600}", "", "A\ud83d\ude00", 0, true, 0, 3);
        test("\\x{000041}", "", "A", 0, true, 0, 1);
        test("\\101", "", "A", 0, true, 0, 1);
        test("\\0", "", "\u0000", 0, true, 0, 1);
        test("\\608", "", "08", 0, true, 0, 2);
        test("\\1234", "", "S4", 0, true, 0, 2);
        test("\\.\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\^\\$\\\\\\_\\-\\/", "", ".*+?()[]{}|^$\\_-/", 0, true, 0, 17);
        test("\\Qa.b*\\E+", "", "a.b**", 0, true, 0, 5);
        test("\\Qa.b", "", "xa.b", 0, true, 1, 4);
        test("a\\Q\\E*", "", "aaa", 0, true, 0, 3);
        test("(?i)\\QaB\\E", "", "Ab", 0, true, 0, 2);
    }

    @Test
    public void characterClasses() {
        // Perl classes are ASCII-only
        test("\\d+", "", "x0123456789", 0, true, 1, 11);
        test("\\d", "", "\u0663", 0, false);
        test("\\D", "", "\u0663", 0, true, 0, 1);
        test("\\w+", "", "aZ_9\u00e9", 0, true, 0, 4);
        test("\\W", "", "\u00e9", 0, true, 0, 1);
        test("\\s+", "", "\t\n\f\r x", 0, true, 0, 5);
        test("\\s", "", "\u000b", 0, false);
        test("\\s", "", "\u00a0", 0, false);
        test("\\S", "", "\u000b", 0, true, 0, 1);
        test("[\\d\\s]+", "", "1 2x", 0, true, 0, 3);
        test("[^\\d\\s]+", "", "1 2xy", 0, true, 3, 5);
        test("[\\D]", "", "1a", 0, true, 1, 2);
        // POSIX classes
        test("[[:alpha:]]+", "", "ab\u00e9", 0, true, 0, 2);
        test("[[:^alpha:]]", "", "a\u00e9", 0, true, 1, 2);
        test("[[:space:]]", "", "\u000b", 0, true, 0, 1);
        test("[[:word:][:punct:]]+", "", "a_!.b c", 0, true, 0, 5);
        test("[[:xdigit:]]+", "", "0aFg", 0, true, 0, 3);
        test("[:alpha:]+", "", "lap", 0, true, 0, 3);
        // Unicode classes
        test("\\pL+", "", "h\u00e9llo1", 0, true, 0, 5);
        test("\\p{L}+", "", "h\u00e9llo1", 0, true, 0, 5);
        test("\\PL", "", "a1", 0, true, 1, 2);
        test("\\p{^L}", "", "a1", 0, true, 1, 2);
        test("\\P{^L}", "", "1a", 0, true, 1, 2);
        test("\\p{Greek}+", "", "a\u03b1\u03b2\u03b3b", 0, true, 1, 4);
        test("\\p{Lu}", "", "a\u00c9", 0, true, 1, 2);
        test("[\\p{Lu}\\d]+", "", "aAB1c", 0, true, 1, 4);
        test("[^\\p{Lu}]", "", "Aa", 0, true, 1, 2);
        test("\\p{Any}", "", "\n", 0, true, 0, 1);
        test("\\pN", "", "\u0663", 0, true, 0, 1);
        test("\\p{Han}", "", "a\u672c", 0, true, 1, 2);
        test("\\p{Han}", "", "\ud840\udc00", 0, true, 0, 2);
        test("\\p{Cs}", "", "a", 0, false);
        // ranges and special characters in classes
        test("[]a]+", "", "]a]", 0, true, 0, 3);
        test("[^]a]", "", "]ab", 0, true, 2, 3);
        test("[a-]+", "", "a-", 0, true, 0, 2);
        test("[-a]+", "", "a-", 0, true, 0, 2);
        test("[a-c-e]+", "", "b-e", 0, true, 0, 3);
        test("[\\x00-\\x{10FFFF}]", "", "\n", 0, true, 0, 1);
        test("[\u00e0-\u00e5]", "", "\u00e3", 0, true, 0, 1);
        test("[\\]]", "", "]", 0, true, 0, 1);
        test("[\\n]", "", "\n", 0, true, 0, 1);
        test("[^\\n]", "", "\n", 0, false);
    }

    @Test
    public void encodings() {
        test("(\u00e9+)(x)", "", Encoding.UTF_8, "a\u00e9\u00e9x", 0, true, 1, 6, 1, 5, 5, 6);
        test("(\u00e9+)(x)", "", Encoding.UTF_16, "a\u00e9\u00e9x", 0, true, 1, 4, 1, 3, 3, 4);
        test("(\u00e9+)(x)", "", Encoding.LATIN_1, "a\u00e9\u00e9x", 0, true, 1, 4, 1, 3, 3, 4);
        test("\\p{Greek}", "", Encoding.UTF_8, "a\u03b1", 0, true, 1, 3);
        test("(?i)\u212a", "", Encoding.UTF_8, "xk", 0, true, 1, 2);
    }

    @Test
    public void validate() {
        Map<String, String> validate = Map.of("regexDummyLang.Flavor", "RE2", "regexDummyLang.Validate", "true");
        Assert.assertTrue(compileRegex(context, "(?P<n>a)\\pL{2,5}[[:alpha:]]", "", validate, getTRegexEncoding()).isNull());
        expectSyntaxError(context, "(a", "", validate, getTRegexEncoding(), "missing ): (a", 0);
        expectSyntaxError(context, "a**", "", validate, getTRegexEncoding(), "bad repetition operator: **", 1);
        expectSyntaxError(context, "(?<=a)", "", validate, getTRegexEncoding(), "invalid perl operator: (?<=", 0);
    }

    @Test
    public void matchingModes() {
        test("a|ab", "", OPT_MATCHING_MODE_FULLMATCH, "ab", 0, true, 0, 2);
        test("a+", "", OPT_MATCHING_MODE_FULLMATCH, "aab", 0, false);
        test("a+", "", OPT_MATCHING_MODE_MATCH, "aab", 0, true, 0, 2);
        test("b", "", OPT_MATCHING_MODE_MATCH, "ab", 0, false);
    }

    @Test
    public void linearTime() {
        // These patterns take exponential time in backtracking engines.
        String as = "a".repeat(100_000);
        for (String pattern : new String[]{"(a*)*b", "(a|a)*b", "(a+)+$b", "(?:a|aa)+c", "(\\w+\\s?)+x", "(.*)*z", "^(a?){50}a{50}$"}) {
            // RegressionTestMode would cross-check the result with the backtracking engine, which
            // takes exponential time on these inputs, so it must not be enabled here.
            Value regex = compileRegex(context, pattern, "", ENGINE_OPTIONS, getTRegexEncoding());
            Assert.assertFalse(pattern, regex.isNull());
            Assert.assertFalse(pattern, regex.getMember("isBacktracking").asBoolean());
            long start = System.nanoTime();
            Assert.assertFalse(pattern, execRegex(regex, getTRegexEncoding(), as, 0).getMember("isMatch").asBoolean());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            Assert.assertTrue(pattern + " took " + elapsedMillis + "ms", elapsedMillis < 60_000);
        }
        test("^(a?){25}a{25}$", "", "a".repeat(25), 0, true, 0, 25, 0, 0);
    }
}
