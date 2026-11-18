# TRegex - Truffle Regular Expression Language

This Truffle language represents classic regular expressions. It treats a given regular expression as a "program" that
you can execute to obtain a regular expression matcher object, which in turn can be used to perform regex searches via
Truffle interop.

The expected syntax is `options/regex/flags`, where `options` is a comma-separated list of key-value pairs which affect
how the regex is interpreted, and `/regex/flags` is equivalent to the popular regular expression literal format found in
e.g. JavaScript or Ruby.

### Parsing

When parsing a regular expression, TRegex will return a Truffle CallTarget, which, when called, will yield one of the
following results:

* a (Truffle) null value, indicating that TRegex cannot handle the given regex.
* a "compiled regex" (`RegexObject`) object, which can be used to match the given regex.
* a Truffle `PARSE_ERROR` exception may be thrown to indicate a syntax error.

An example of how to parse a regular expression:

```java
Source source = Source.newBuilder("regex", "Flavor=ECMAScript/(a|(b))c/i", "myRegex").mimeType("application/tregex").internal(true).build();
Object regex;
try {
    regex = getContext().getEnv().parseInternal(source).call();
} catch (AbstractTruffleException e) {
    if (InteropLibrary.getUncached().getExceptionType(e) == ExceptionType.PARSE_ERROR) {
        // handle parser error
    } else {
        // fatal error, this should never happen
    }
}
if (InteropLibrary.getUncached().isNull(regex)) {
    // regex is not supported by TRegex, fall back to a different regex engine
}
```

### The compiled regex object

A `RegexObject` represents a compiled regular expression that can be used to match against input strings. It exposes the
following three properties:

* `pattern`: the source string of the compiled regular expression.
* `flags`: an object representing the set of flags passed to the regular expression compiler, depending on the flavor of
  regular expressions used.
* `groupCount`: the number of capture groups present in the regular expression, including group 0.
* `groups`: a map of all named capture groups to their respective group number, or a null value if the expression does
  not contain named capture groups.
* `exec`: an executable method that matches the compiled regular expression against a string. The method accepts two
  parameters:
    * `input`: the character sequence to search in. This may either be a Java String, or a Truffle Object that behaves
      like a `char`-array.
    * `fromIndex`: the position to start searching from.
    * The return value is a `RegexResult` object.

### The result object

A `RegexResult` object represents the result of matching a regular expression against a string. It can be obtained as
the result of a `RegexObject`'s
`exec`-method and has the following properties:

* `boolean isMatch`: `true` if a match was found, `false` otherwise.
* `int getStart(int groupNumber)`: returns the position where the beginning of the capture group with the given number
  was found. If the result is no match, the returned value is undefined. Capture group number `0` denotes the boundaries
  of the entire expression. If no match was found for a particular capture group, the returned value is `-1`.
* `int getEnd(int groupNumber)`: returns the position where the end of the capture group with the given number was
  found. If the result is no match, the returned value is undefined. Capture group number `0` denotes the boundaries of
  the entire expression. If no match was found for a particular capture group, the returned value is `-1`.

Compiled regex usage example in pseudocode:

```java
regex = <matcher from previous example>
assert(regex.pattern == "(a|(b))c")
assert(regex.flags.ignoreCase == true)
assert(regex.groupCount == 3)

result = regex.exec("xacy", 0)
assert(result.isMatch == true)
assertEquals([result.getStart(0), result.getEnd(0)], [ 1,  3])
assertEquals([result.getStart(1), result.getEnd(1)], [ 1,  2])
assertEquals([result.getStart(2), result.getEnd(2)], [-1, -1])

result2 = regex.exec("xxx", 0)
assert(result2.isMatch == false)
// result2.getStart(...) and result2.getEnd(...) are undefined

```

### Available options

These options define how TRegex should interpret a given regular expression:

#### User options
* `Flavor`: specifies the regex dialect to use. Possible values:
  * `ECMAScript`: ECMAScript/JavaScript syntax (default).
  * `Python`: Python 3 syntax.
  * `Ruby`: Ruby syntax.
  * `JavaUtilPattern`: `java.util.regex.Pattern` syntax.
  * `OracleDB`: Oracle Database syntax.
  * `RE2`: [RE2](https://github.com/google/re2/wiki/Syntax) syntax, see [RE2 Flavor](#re2-flavor).
* `Encoding`: specifies the string encoding to match against. Possible values:
  * `UTF-8`
  * `UTF-16` (default)
  * `UTF-32`
  * `LATIN-1`
  * `BYTES` (equivalent to `LATIN-1`)
* `Validate`: don't generate a regex matcher object, just check the regex for syntax errors.
* `U180EWhitespace`: treat `0x180E MONGOLIAN VOWEL SEPARATOR` as part of `\s`. This is a legacy feature for languages
  using a Unicode standard older than 6.3, such as ECMAScript 6 and older.

#### Performance tuning options
* `UTF16ExplodeAstralSymbols`: generate one DFA states per (16 bit) `char` instead of per-codepoint. This may
  improve performance in certain scenarios, but increases the likelihood of DFA state explosion.
* `AlwaysEager`: do not generate any lazy regex matchers (lazy in the sense that they may lazily compute properties of a
  {@link RegexResult}).

#### Debugging options
* `RegressionTestMode`: exercise all supported regex matcher variants, and check if they produce the same results.
* `DumpAutomata`: dump all generated parser trees, NFA, and DFA to disk. This will generate debugging dumps of most
  relevant data structures in JSON, GraphViz and LaTex format.
* `StepExecution`: dump tracing information about all DFA matcher runs.

All options except `Flavor` and `Encoding` are boolean and `false` by default.

### RE2 Flavor

The `RE2` flavor implements the syntax and the default matching semantics of [RE2](https://github.com/google/re2/wiki/Syntax).
Select it with the `Flavor=RE2` option, either in the source string or as a Truffle source option (`regex.Flavor`), for example:

```java
Source source = Source.newBuilder("regex", "Flavor=RE2,Encoding=UTF-16/(?i)^(?P<user>\\w+)@example\\.com$/", "myRegex").mimeType("application/tregex").internal(true).build();
```

RE2 guarantees that matching runs in time linear in the size of the input.
The `RE2` flavor therefore always implies `ForceLinearExecution`: TRegex never uses its backtracking engine for RE2 regexes.
If TRegex cannot execute a regex with its automaton-based engines, parsing returns a null value instead of a compiled regex.
This happens when a regex exceeds the automaton size limits, typically because of large counted repetitions with many optional iterations, such as `.{0,1000}`.
Counted repetitions of moderate size, for example `\w{1,255}` or `.{0,500}`, are supported.

The flags part of the source string may contain the RE2 flag letters `i`, `m`, `s`, and `U`.
They have the same effect as a leading `(?flags)` group in the pattern.
The `flags` property of the compiled regex exposes them as boolean members `i`, `m`, `s`, and `U`.

The following features of RE2's default (`LikePerl`) syntax are supported:

* Leftmost-first (Perl-like) alternation and repetition priority, with greedy, lazy (`*?`, `+?`, `??`, `{n,m}?`), and counted (`{n}`, `{n,}`, `{n,m}`) repetitions. The maximum repetition count is 1000, and nested counted repetitions may not exceed a combined count of 1000. Stacked repetition operators such as `a**` are syntax errors. An invalid counted repetition such as `a{,2}` is a literal.
* Capture groups, non-capturing groups `(?:re)`, and named capture groups `(?P<name>re)` and `(?<name>re)`. Duplicate names are allowed; a name refers to its leftmost group.
* Flags `i` (case-insensitive), `m` (multi-line), `s` (`.` matches `\n`), and `U` (swap greedy and lazy repetitions), set with `(?flags)` until the end of the enclosing group, or scoped with `(?flags:re)`. Flags can be cleared with `-`, for example `(?i-s)`.
* Empty-width assertions `^`, `$`, `\A`, `\z`, `\b`, and `\B`. Without the `m` flag, `^` and `$` match only at the beginning and the end of the text. In particular, `$` does not match before a trailing newline.
* `.`, which matches any character except `\n` unless the `s` flag is set.
* The ASCII-only Perl classes `\d`, `\s` (`[\t\n\f\r ]`), `\w`, and their negations, and ASCII word boundaries.
* Unicode general categories and scripts, for example `\pN`, `\p{Lu}`, `\p{Greek}`, `\PN`, and `\p{^Greek}`, as well as `\p{Any}`.
* Bracket expressions with ranges, negation, Perl and Unicode classes, and the ASCII POSIX classes such as `[[:alpha:]]` and `[[:^alpha:]]`.
* Escapes `\a`, `\f`, `\t`, `\n`, `\r`, `\v`, octal escapes such as `\123`, hexadecimal escapes `\x7F` and `\x{10FFFF}`, escaped punctuation, and literal text `\Q...\E`.
* Case-insensitive matching based on simple Unicode case folding, for example `(?i)k` matches `K` and `\u212A` (KELVIN SIGN).

The following features are not supported:

* `\C` (match a single byte). Like Go's `regexp` package, TRegex rejects `\C` with a syntax error.
* RE2's leftmost-longest (POSIX) matching mode (`longest_match`).

Backreferences, look-around assertions, possessive quantifiers, and atomic groups are not part of the RE2 syntax and are rejected with a syntax error, as in RE2.
Error messages follow RE2, for example `invalid escape sequence: \1` or `invalid perl operator: (?=`.

TRegex reports match positions in units of the input encoding.
Use `Encoding=UTF-8` to retrieve byte offsets like RE2, or `Encoding=UTF-16` (the default) to retrieve offsets into Java strings.
Like Go's `regexp` package, `\B` only considers positions between code points, while RE2 also considers positions inside multi-byte UTF-8 sequences.
With `Encoding=UTF-16`, the RE2 flavor never reports a match that starts before `fromIndex`.
If `fromIndex` points to the low surrogate of a valid surrogate pair, the search starts at the next code point boundary, after the pair.
For example, searching `😀x` (`"\uD83D\uDE00x"`) for `.` from index 1 matches `x` at `[2,3)`, and the empty regex matches at `[2,2)`.
Other flavors, for example `ECMAScript` with the `u` flag, start the search at the pair's high surrogate instead.

The search tests of RE2 (`re2-search.txt`, taken from the Go project) are part of the TRegex test suite, see _RE2ConformanceTest.java_.
All leftmost-first cases pass.

Like RE2, the `RE2` flavor handles loop iterations that match the empty string differently from the other flavors:

* If the first iteration of `x*` or `x+`, or iteration `n` of `x{n,}`, matches the empty string, the loop ends there.
  For example, `(|a)*` matches the empty string at the start of `aaa`, not the whole string.
* Later iterations of such a loop must not match the empty string.
* Iterations of bounded quantifiers such as `x?` and `x{n,m}` may always match the empty string.

TRegex unrolls most bounded quantifiers.
If a bounded quantifier is too large to unroll and its body can match the empty string, TRegex ends the loop after the first empty iteration instead, which matches RE2 unless the remaining iterations would have matched differently at the same position.
