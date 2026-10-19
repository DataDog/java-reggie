# Prerequisite bug fixes for JDK_STRICT

This plan fixes the bugs listed in `doc/plans/jdk-strict-option.md` (*Prerequisite bug
fixes*). The fixes apply in default mode. Each fix gets a regression test. Each fixed construct
then joins the M2 vetted set.

## Rules for every fix

- **Bug policy.** A fix that changes documented or test-asserted reggie behaviour goes to the
  user before work starts. Such fixes are marked **sign-off** below. The user approved the
  sign-off items (decisions 2, 3, 4, 6, 7 and 11), the B12 ASCII-only fold (decision 5) and
  the B38 surrogate refusal: where reggie behaviour differs from the JDK, the JDK behaviour is
  correct, and breaking changes are acceptable in 0.x releases. The user approved the
  recommendations and helpers H1–H10 on condition that performance and correctness are
  maintained:
  - correctness: `./gradlew build` and `./gradlew :reggie-integration-tests:test` pass after
    every fix;
  - performance: a JMH baseline (`saveBaseline`) is taken on the Wave 0 commit. After each
    fix, the benchmarks for the affected routes are rerun against it. A regression is fixed
    before the next fix starts. A full `benchmarkAndReport` runs at the end of each wave.
- **Tests.** Write each test first and confirm that it fails. Rows marked as guard rows pass
  today and protect against a regression in the rewrite. Expected values come live from
  `java.util.regex`, as in `GroupCountCharClassRegressionTest`. Each row also goes into the
  phase-1 `JdkStrictDivergenceTest`. Rows for an engine or parser fix that expect a native
  result compile with `Reggie.compile` (no fallback) and assert `isJdkFallback()` is false,
  so the fallback
  cannot hide the native result (for example `(?i)\p{Lu}` in B16 and the B2 `\10` row
  reach the fallback through `compileAllowingFallback` today).
- **Pattern notation.** For `\uXXXX` only: `"\\u00e9"` (quoted, double backslash) is the
  escape text in the pattern or input string, and a single backslash, as in `\u00e9`, stands
  for the literal character. Every other backslash sequence in a pattern (`\p{Lu}`, `\1`,
  `\b`, `\x{e9}`, `\0101`, …) is regex escape text. In an input string, every other
  (unquoted) backslash sequence stands for the character. String literals inside Java call
  expressions, such as `toReggieFlags("(\\W)\\1", …)`, use Java source syntax.
- **Routes.** For every engine fix, confirm the route with `./gradlew debugPattern` before and
  after the change. Force one test pattern per affected route.
- **Project rules** (AGENTS.md):
  - a new AST field goes into `StructuralHash`;
  - a fix in the shared parser, code generators or engines must also cover the
    annotation-processor path (dual path), with a JDK-differential test on both paths.
    Every test row of such a fix runs on both paths, also where a section lists only
    runtime rows. Runtime-only fixes (B4, B14, B18, B19 + B25 + B27 + B28, B34) test the
    runtime path, plus the processor rows listed in their sections;
  - AST walks use `RegexVisitor`.
- **Helpers.** New helpers are listed in *Proposed helpers*. None is added without approval.
- **Line numbers** refer to the working tree on `fix/more_dfa`, including the uncommitted work.

## Decisions needed before work starts

1. **Commit the current uncommitted work first.** This is the work on `fix/more_dfa`:
   - `NFA.countedLoopPinGroup`;
   - the ThompsonBuilder pins and the possessive `repeatDepth` reset (`ThompsonBuilder.java:202`);
   - `BackrefBacktrackMatcher` pins;
   - the PikeVM atomic redesign;
   - the parser and detector guards.

   B23 and B26 depend on it.
2. **B2 + B11, sign-off.** Implement the JDK octal and backref number rules. This breaks
   `OctalHexEscapeTest`, README.md:591 and :652, and doc/TUTORIAL-RUNTIME.md:336-338. All of
   these document `\100` as `@`.
3. **B9, sign-off.** Under `(?x)`, skip whitespace inside classes. This breaks
   `InlineModifiersTest.java:556-563` and the parser javadoc at `RegexParser.java:1296-1299`.
4. **B10 `\a`, sign-off.** `\a` becomes BEL. This breaks `ReggieCompileFlagsTest.java:32`,
   which asserts that `\a` with `CASE_INSENSITIVE` matches `A`.
5. **B12: é/É in case-insensitive backrefs.** For `(?i)(.)\1` on `éÉ`, reggie matches today
   and the JDK does not.
   - Recommendation: fold ASCII only, as the JDK does. Under `(?iu)` / `(?iU)` backrefs are
     refused instead (*B12 + B33*), so the ASCII fold is never used where the JDK folds
     Unicode.
6. **B34: `UNICODE_CASE` in the JDK fallback, sign-off.** `JavaRegexFallbackMatcher` adds
   `UNICODE_CASE` (`:33-37`). As a result, the fallback folds Kelvin (U+212A) to `k`, but the
   native engines do not (`UnicodeCaseFoldRefusalTest:61`).
   - Trade-off: under `(?i)` the native engines fold non-ASCII letters (`(?i)é` matches `É`),
     and so does the fallback today; the JDK without `UNICODE_CASE` does not. Dropping the
     flag makes the fallback equal the JDK and the native engines on the six special folds,
     but makes the fallback differ from the native engines on every other non-ASCII cased
     letter. A `(?i)` pattern with non-ASCII letters then changes results when its route
     moves between native and fallback (B36 moves routes to the fallback).
   - Strict mode does not depend on B34: its fallback uses the M1 `Pattern`.
   - Recommendation: drop the flag, so the default-mode fallback equals the JDK.
7. **B19 + B25, sign-off.** `cached(key, p, options)` with an existing key and different
   options throws `IllegalStateException`. Today it returns the old instance. This changes
   the assertion at `LazyDFABytecodeGeneratorTest.java:73`. doc/TUTORIAL-RUNTIME.md:206
   ("old one replaced") is already wrong for a different pattern, which throws today
   (`Reggie.java:153-156`, `RuntimeCompilerTest:207-214`); B19 corrects it.
8. **B23: backref bodies.** Keep backref bodies unpinned. The JDK pins them, so this stays a
   known divergence, which strict mode flags under S2c.
9. **B13: perf trade-off.** Two triggers are possible:
   - route every recursive-descent pattern with a backref to the backtracker;
   - use a narrower trigger.

   The choice is made after the JMH run (*B13*).
10. **B36: routing change.** Pinned patterns on a strategy that ignores pins go to the JDK
    fallback (with `ALLOW_JDK_FALLBACK`) or throw. Today they compile natively and return
    wrong spans. Phase 1 lists the patterns and strategies affected (*B36*).
11. **B4: `CASE_INSENSITIVE | UNICODE_CASE`, sign-off.** `toReggieFlags` maps this today, and
    the comments at `JdkPatternCompatibility.java:75-76` and `:96-97` document the mapping.
    Under `UNICODE_CASE`, the JDK folds exactly six ASCII letters to non-ASCII partners:
    `I`/`i` to U+0130 and U+0131, `K`/`k` to Kelvin (U+212A), `S`/`s` to ſ (U+017F). These
    six are among the characters listed in JDK 21 `Pattern.java:2918-2925`. Reggie folds
    none of them. Non-ASCII cased characters also differ: JDK `(?i)µ` with `UNICODE_CASE`
    matches `μ`, and `Ⓐ` matches `ⓐ`; reggie matches neither. ß (U+00DF) has no case
    mapping of its own, but it is the fold of ẞ (U+1E9E): JDK `aß` with CI |
    `UNICODE_CASE` matches `aẞ`. The JDK compares backrefs
    with `toUpperCase` and then `toLowerCase` (`Pattern.java:5254-5259`).
    - The same applies to `UNICODE_CASE` without the CI flag when the pattern turns on an
      inline `i`: JDK `Pattern.compile("(?i)k", UNICODE_CASE)` matches Kelvin, but today
      `toReggieFlags` strips the flag (`:95-99`) and reggie `(?i)k` does not.
    - Recommendation: when `UNICODE_CASE` is set, reject the mapping if a leaf parsed under
      `i` (by flag or inline) has a set that contains `I`, `i`, `K`, `k`, `S` or `s`, or a
      non-ASCII cased character, or if a backref is built under `i`. A leaf is a literal,
      a range or a class member, taken before negation and intersection; so `[h-j]` counts.
      Other patterns keep the mapping (*B4*).
    - The pattern-unaware overload `toReggieFlags(int)` strips `UNICODE_CASE` (`:95-99`) but
      cannot see an inline `i`. Recommendation: it rejects `UNICODE_CASE`, as it already
      rejects CI. The pattern-aware overload clears `UNICODE_CASE` before it delegates to
      `toReggieFlags(int)` (`:66`, `:78`).
    - Contract and doc changes that this decision also signs off:
      - under CI alone, patterns with non-ASCII characters that have no case (such as `→`)
        become accepted. Today the `JdkPatternCompatibility` class javadoc (`:23-44`) and
        the `JdkPatternCompatibilityTest` class javadoc (`:28-31`) say non-ASCII patterns
        are rejected;
      - a syntax error makes `toReggieFlags` throw `IllegalArgumentException`. The
        `@throws` at `:56` covers only flags that cannot be mapped;
      - a valid pattern that reggie parses as unsupported (`UnsupportedPatternException`,
        for example `\X` after B10) with CI or `UNICODE_CASE` also makes `toReggieFlags`
        throw `IllegalArgumentException`. Today it returns flags and
        `Reggie.compile(p, flags, ALLOW_JDK_FALLBACK)` works. Without the parse the leaf
        checks cannot run, so the mapping cannot be shown to be safe;
      - docs to update: class javadoc `:23-44`, method javadocs `:49-57` and `:81-84`,
        messages `:69-73`, `:89-93` and `:117-122`, comments `:75-76` and `:96-97`, and the
        test class javadoc.
    - Alternative: map CI | `UNICODE_CASE` to a `(?iu)` prefix and let
      `checkUnicodeCaseFold` (`RegexParser.java:996`) refuse. This rejects every pattern
      with a cased letter. It misses cased non-letters until B38 lands.

## Ordering

1. **Wave 0.** The commit from decision 1.
2. **Wave 1, runtime caching:**
   1. B18;
   2. B19, B25, B27 and B28 as one change.
3. **Wave 2, parser:**
   1. B37, so that B15 and B10 can test per-method processor errors;
   2. B15;
   3. B21 + B30 + B31;
   4. B1 + B10, without `\G`. The `\G` part moves to Wave 5, after B14;
   5. B2 + B11;
   6. B8;
   7. the class rewrite B6 + B7 + B9 + B32.
4. **Wave 3, case folding:** B39; B12 + B33 together with the B4 backref reject; B16;
   B38; the rest of B4; B34. B7 lands before B16. The B4 backref reject brings in the
   parse inside `toReggieFlags` (only when `UNICODE_CASE` is set), the H6 backref result,
   the `IllegalArgumentException` on a parse failure, and the backref reject reason. The
   leaf results and the other reject reasons land with the rest of B4.
5. **Wave 4, engine captures:**
   - B3, B22 and B24 are independent;
   - B26 lands before B23;
   - B13 lands before or with B23;
   - B23 lands together with B36 and the routing-invariant test. B35 was merged into B36.
6. **Wave 5, others:**
   - B17, with a JMH check;
   - B5;
   - B14, after B18;
   - the `\G` part of B10, after B14.

## Wave 1: runtime caching

### B18: prefilter in front of the JDK fallback

- **Cause.** `wrapCompiled` (`RuntimeCompiler.java:661-671`) wraps every result in a
  prefilter built from reggie's parse. That includes `JavaRegexFallbackMatcher`. When reggie
  misparses a construct, the prefilter rejects inputs that the JDK accepts.
- **Fix.** Put `if (matcher.isJdkFallback()) return matcher;` in `wrapCompiled` right after
  the `matcher == null` check (`:662`), before the `LITERAL_CACHE` lookup. Update the javadoc (`:656-660`).
- **Tests.**
  - Behavioural: `(?:(a)|b)+\1\v` find on `aa\u000B` with `compileAllowingFallback`. This
    row works only while B1 is open.
  - Structural: `getClass() == JavaRegexFallbackMatcher` for `compile`, compile with flags,
    and `cached`.
  - Processor: the FALLBACK path calls `Reggie.compileAllowingFallback`, so the runtime fix
    covers it. Add the behavioural row for a `@RegexPattern` with `ALLOW_JDK_FALLBACK`.

### B19 + B25 + B27 + B28: `cached()`

- **Causes.**
  - **B19.** `cached(key, p, options)` passes the bare pattern as `cacheKey`
    (`:874` → `:953-956`). Engines built for an option then fill default-keyed caches. It also
    fails the other way: `compile(p)` followed by `cached(k, p, namedOnly)` gets the default
    engine.
  - **B25.** `cached()` skips the post-compile fixups (`:860`, `:874-875` vs `:549-579`).
    A mutable-strategy instance is stored under the user key and shared across threads.
  - **B27.** `cached()` and `compile()` share the `PATTERN_CACHE` namespace. After
    `cached("\\d+", "[a-z]+")`, `compile("\\d+")` returns the `[a-z]+` matcher.
  - **B28.** `cached()` skips the total compile deadline (`:480-485`).
- **Fix.**
  - Compute `engineKey = cacheKeyFor(p, options)` in `cached()`. Delete
    `compileInternal(String[, opts])` (`:948-956`).
  - Store user keys in a separate `USER_CACHE<String, UserEntry>`. `UserEntry` holds the
    pattern, the options and the matcher. `cacheSize`, `cachedPatterns` and `clearCache`
    include it.
  - Same key, different pattern or options → `IllegalStateException` (decision 7). This
    follows the contract at `Reggie.java:153-156` and is tested at
    `RuntimeCompilerTest:207-214`.
  - Mutable strategies are handed out as a `PooledMatcher`. Its factory runs the fast-path
    lookups (`:497-526`) under `engineKey`. This needs a shared factory helper, and the
    existing private `PooledMatcher(factory, first)` constructor (`PooledMatcher.java:46-51`)
    widened to package-private (*Proposed helpers* H1, H2).
  - Apply the total deadline.
- **Conflicts.**
  - `LazyDFABytecodeGeneratorTest:66-80` asserts distinct instances (`assertNotSame`, `:73`)
    that share the generated class and the static `CACHE`. After B19 and B27, `cached()` may
    return the `compile()` instance or a `PooledMatcher`, so `:73` and the unwrap must change
    (decision 7).
  - doc/TUTORIAL-RUNTIME.md:206 says the old entry is replaced. That is already wrong for a
    different pattern; fix it.
- **Tests.**
  - `assertSame` on repeated `cached()` calls (guard row).
  - Unwrap each mutable route and check it is an `instanceof PooledMatcher`.
  - The B27 namespace row.
  - The B19 rows from the bug table in both directions.
  - The phase-1 concurrency probe.
- **Strict design.** After this change, `PooledMatcher` is the outermost layer for `cached()`
  results, so the `SurrogateGuardMatcher` goes inside the pool.

### B29: retired

A `compile()` / `clearCache()` race was suspected. No interleaving leaves a
mutable instance in `PATTERN_CACHE`. `clearCache` (`:885-894`) clears `PATTERN_CACHE`
first. `ConcurrentHashMap.clear` waits for a bin held by an in-flight `computeIfAbsent` and
then removes the stored entry. A mutable instance can reach only that single caller. The ID
stays reserved.

## Wave 2: parser

### B15: reversed class range

- **Cause.** `RegexParser.java:447-450` builds `CharSet.Range` without an order check. The
  constructor throws `IllegalArgumentException` (`CharSet.java:50-51`).
  - `RuntimeCompiler.java:1796-1798` wraps it in a `RuntimeException`.
  - The PikeVM, backtrack and chain entry points catch only `ParseException` (`:755`,
    `:810`, `:837`). The LTS paths catch `UnsupportedOperationException` and
    `IllegalStateException` (`:1883`, `:1912`), but not `IllegalArgumentException`.
  - In the processor, the exception fails the whole annotated class until B37 lands.
- **Fix.** Throw `ParseException("Illegal character range")` when `end < start`, including
  escaped range ends. The `[a-\v]` row belongs to B1: today `\v` in a class is `v`, so
  `[a-v]` is valid.
- **Tests.**
  - `RegexParserTest` (codegen): parsing `[z-a]` throws `ParseException`.
  - `ParserExceptionTest`: `Reggie.compile("[z-a]")` throws `PatternSyntaxException`, on each
    entry point listed above.
  - A processor test: `[z-a]` gives a build error for that method only.

### B21 + B30 + B31: `(?x)` comments and whitespace

- **Causes.**
  - **B21.** `skipExtendedWhitespace` (`:1312-1321`) ends a comment only at `\n` and
    consumes it.
  - **B30.** `:1309` uses `Character.isWhitespace`. The JDK skips only `[\t\n\x0B\f\r ]`.
  - **B30, quantifier braces.** `skipWhitespaceInQuantifier` (`:1334`) also uses
    `Character.isWhitespace`, and it runs outside `(?x)` too (javadoc `:1329-1331`). This
    has no parity effect: the JDK rejects whitespace before the first digit
    (`(?x)a{ 1,2}`: "Illegal repetition") and any whitespace inside braces outside `(?x)`.
    These forms are M1 territory (*Not fixed here*).
  - **B31.** Whitespace-only alternatives throw `ParseException`, both at the end of the
    pattern and inside it. `parseConcatenation` (`:88-104`) checks
    `isAlternationOrGroupEnd` before it calls `skipExtendedWhitespace`. At the end of the
    pattern, `parseQuantified` skips whitespace, then `parseAtom` peeks past the end
    (`:113-114`). The JDK accepts `(?x)a| `, `(?x)a|#c`, `(?x)(a| )b` and `(?x)a| |b`.
- **Fix.**
  - End a comment *before* any of these: `\n`, `\r`, U+0085, U+2028, U+2029, and NUL.
  - Skip only the ASCII whitespace set, so U+0085, U+2028 and U+2029 become literal atoms.
    `skipWhitespaceInQuantifier` stays as it is.
  - In `parseConcatenation`, skip whitespace and comments before the
    `isAlternationOrGroupEnd` check, so an empty alternative becomes epsilon.
- **Tests.** Differential rows, comparing `matches()` (with a boolean `find()`, both sides
  return true, so the terminator rows would pass today):
  - `(?x)a#c\u000Db` / `ab`;
  - `(?x)a#c\u0085b` / `a\u0085b`;
  - `(?x)a#c\u0000b` / `a\u0000b`;
  - `(?x)a\u001Cb` / `a\u001Cb`;
  - `(?x)a| ` / empty input, `(?x)a|#c` / empty input, `(?x)(a| )b` / `b`, `(?x)a| |b` / `b`.

  `InlineModifiersTest:538-544` and `:612-620` must keep passing.
- **Plan update.** B30 makes the `jdk-strict-option.md` M2 entry "`(?x)` whitespace outside
  classes" wrong for non-ASCII whitespace until this fix lands.

### B1 + B10: missing escapes

- **Cause.** These letters fall through to the `default` branches: `:684-686` outside classes
  and `:563-564` inside classes. Both return a literal.
- **Fix,** following JDK 21 `Pattern.escape`:
  - `\e` = 0x1B.
  - `\a` = 0x07, **sign-off** (decision 4).
  - `\cX` = `X ^ 64`, without case mapping. `\c` at the end of the pattern is a syntax error.
    Build all three through `literalNode()` so `(?i)` applies.
  - `\h` / `\H`: horizontal whitespace and its complement. `\v` / `\V`: vertical whitespace
    and its complement. Both work inside classes. As a class range bound, `\v` is VT (0x0B).
  - `\R` = `(?:\r\n|[\n\x0B\f\r\x85\u2028\u2029])`, ordered and not atomic.
  - `\X`: throw `UnsupportedPatternException`, so the pattern goes to the fallback.
  - `\G` (Wave 5, after B14): the same. `AnchorNode` has no `\G` type
    (`AnchorNode.java:21-30`).
  - `\R` and `\X` inside a class: the JDK rejects them. Reggie throws `ParseException`,
    which agrees with M1. `[\G]` gets the same treatment in Wave 5.
  - `\h` as a range end: the JDK rejects `[a-\h]`. Throw `ParseException`. As a range
    start, `[\h-a]` is not a range: it is `\h`, `-` and `a`.
- **Conflicts.** `ReggieCompileFlagsTest.java:32` (decision 4).
- **Tests.**
  - A runtime differential per escape, inside and outside classes, excluding `\G` and
    `[\G]` (Wave 5).
  - Parser tests for the shape of `\R`, for `[\v-\r]`, `[\h-a]` and `[a-\h]`.
  - `\R\n` on `\r\n` matches.
  - Processor: `\X` with `ALLOW_JDK_FALLBACK` builds and equals the JDK (needs B37).
- **Helpers.** Constants `CharSet.HORIZONTAL_WS` and `CharSet.VERTICAL_WS` (H3).

### B2 + B11: octal and numeric escapes (sign-off, decision 2)

- **Causes.**
  - **B2.** The `\0` case (`:624-627`) backs up onto the `0` and reads three digits
    including it (`:842-850`). This is the PCRE rule, documented at `:822-828`. The comment at
    `:637-641` claims JDK semantics.
  - **B11.** For `\1`–`\9` with no valid group, `:660-669` falls back to octal (1–7) or to a
    literal `8`/`9`.
  - **Extension.** Multi-digit references are extended against `totalGroupCount` (`:651`).
    The JDK extends against the groups opened so far.
- **Fix,** following JDK `Pattern.o()` and `Pattern.ref()`:
  - `\0` takes 1–3 octal digits, and a third digit only if the first is ≤ 3.
  - `\1`–`\9` are always backrefs. Extend the number while it stays ≤ the groups opened so far.
  - A reference to a group that does not exist must never match. Emit an empty
    `CharClassNode`, because `ThompsonBuilder.java:680` would store an out-of-range group
    number in `backrefCheck`.
  - JDK-rejected forms (`\0` alone, `\08`, `[\1]`) are M1 territory and stay out of scope.
- **Conflicts.**
  - `OctalHexEscapeTest`: the class javadoc (`:23-27`) and the rows at `:62`, `:70`, `:78`,
    `:86`, `:121` and `:161`. The row at `:150` (`[\100-\132]`) is rejected by the JDK and
    stays M1 territory.
  - README.md:591 and :652.
  - doc/TUTORIAL-RUNTIME.md:336-338.
  - The parser javadoc at `:822-828`.
- **Tests.**
  - Rewrite the affected `OctalHexEscapeTest` rows as JDK differentials.
  - Add `\0101`, `\0400`, `\1`, `\8`.
  - Add `(a)(b)(c)(d)(e)(f)(g)(h)(i)(?:\10|(j))+` on `abcdefghija0`. The JDK result is [0,12).

### B8: quantifier after `\Q..\E`

- **Cause.** `parseQuotedLiteral` (`:959-974`) returns the whole quote as one `Concat`
  atom, and `parseQuantified` (`:112-158`) quantifies it.
- **Fix.** Port the JDK `RemoveQEQuoting` pre-pass into `parse()`. It runs only when the
  pattern contains `\Q`.
  - A digit directly after `\Q` becomes `\x3d`, as in the JDK.
  - Delete `parseQuotedLiteral` and `parseQuotedCharClassRanges` (`:468-479`).
  - The three lexical scanners listed under the class rewrite already skip quotes, so they
    need no change for B8.
  - Error positions in exceptions shift.
- **Tests.**
  - Parser shape: `\Qab\E+` gives `Concat(a, Quant(b))`.
  - Differential rows: `\Qab\E+` / `abb`, `a\Q\E+` / `aa`, `a+\Q\E?` with find,
    `[\Qa\E-c]` / `b`.
  - `LogsBackendParserCompatibilityTest` and the `LITERAL` test
    (`ReggieCompileFlagsTest.java:54-60`) keep passing.

### B6 + B7 + B9 + B32: character class rewrite

- **Causes.**
  - **B6.** `:411` `while (hasMore() && peek() != ']')` closes on a leading `]`. Production
    is affected: line 216 of `logs-backend-patterns.tsv` is `(^|\S)@[]/]`. Reggie compiles
    `[]` to an empty class that never matches. The JDK reads the class as `{], /}`.
  - **B7.** `[` and `&` are plain characters, so `[a[bc]]` parses as `[a[bc]` followed by a
    literal `]`.
  - **B9.** Reggie keeps whitespace inside classes under `(?x)` (javadoc `:1296-1299`). The
    JDK skips it. **Sign-off** (decision 3).
  - **B32.** `GroupCounter` and `RegexParser.countCapturingGroups` (`:1599`) do not skip
    `(?x)` comments, so `(?x)a#(\n` counts one group. `countCapturingGroups` feeds
    `totalGroupCount`, which today drives backref digit extension.
- **Fix.** Make `parseCharClass` recursive and follow JDK 9+ `Pattern.clazz()`:
  - A `]` is literal while the class has nothing accumulated yet. Track this with a flag, so
    `[^]a]` and `(?x)[ ]a]` both work.
  - A nested `[...]` is joined as a union.
  - `&&` intersects with the right operand. `[&&a]` and `[a&&]` both equal `[a]`.
  - `a-[` is not a range.
  - `^` negates the whole result. Nested negations use `CharSet.complement()`.
  - Under `(?i)`, fold literals and ranges at the leaves, before set operations.
    `(?i)[a-z&&[^A]]` matches neither `A` nor `a` in the JDK. Reggie agrees today, so this
    is a guard row: it catches a rewrite that folds after the set operation. Class escapes and properties keep today's folding
    until B16.
  - Under `(?x)`, skip whitespace and comments with the B21/B30 skipper. `(?x)[#x\n]` is a
    JDK syntax error. The exact token points come from differential rows, not from reading
    the JDK source.
  - Update the three lexical scanners to the same class rules, through one shared helper
    (H4):
    - `GroupCounter.count` (`GroupCounter.java:54-62`). Its comment at `:55-57` is wrong:
      `[](]` counts a group where the JDK counts none;
    - `RegexParser.countCapturingGroups` (`:1618-1625`);
    - `RuntimeCompiler.hasSourceInlineModifier` (`:1010-1041`).

    For B32, `GroupCounter` and `countCapturingGroups` also skip `(?x)` comments.
- **Conflicts.** B9 only (decision 3).
- **Tests.**
  - A differential per class pattern that sweeps chars 0..0x24F plus `]`, `[`, `&`, `-`, `^`
    and space, against the JDK.
  - `x[a&&b]|y` across strategies.
  - `GroupCountCharClassRegressionTest` rows `[](]`, `[a[(]](b)`, `(?x)a#(\n`.
  - `ParserExceptionTest` for the unclosed `[]` and `[[]`.

## Wave 3: case folding

### B39: `(?i)` drops a titlecase literal itself

- **Cause.** `literalNode` (`RegexParser.java:1007-1020`) builds the class {`toLowerCase(ch)`,
  `toUpperCase(ch)`} and leaves out `ch`. For the titlecase letters U+01C5, U+01C8, U+01CB
  and U+01F2 both differ from `ch`, so `(?i)\u01c5` does not match `\u01c5`. The JDK
  matches it. The class path keeps the original ranges (`:1354`), so `[\u01c5]` is not
  affected.
- **Fix.** Add `ch` to the class.
- **Tests.** `(?i)\u01c5` on `\u01c5`, and the same for U+01C8, U+01CB and U+01F2, in
  `literalNode` routes and inside a concatenation.
  - `(?i)\u01c5` on `\u01c6` stays a divergence after the fix (JDK false, reggie true). It
    belongs in the S6 list, not in an equality row.

### B12 + B33: case-insensitive backrefs

- **Causes.**
  - **Detecting the flag.** `BackrefBacktrackMatcher.java:298` sets
    `caseInsensitive = pattern.contains("(?i)")`, and `RuntimeCompiler.isCaseInsensitive`
    (`:3024`) detects only a leading `(?i)`.
  - **Folding too much (B12).** Some backref compares use `String.regionMatches(true, …)`:
    - `BackrefBacktrackMatcher` `:1261`, `:1455`;
    - `LinearPatternBytecodeGenerator` `:650-669`;
    - `NFABytecodeGenerator` `:1888-1904`, `:8882-8901`.

    That call folds Kelvin, ſ, İ, ı and é. The JDK without `UNICODE_CASE` folds only ASCII.
  - **Not folding at all (B33).** The other backref generators always compare
    case-sensitively, even under `(?i)`:
    - FixedRepetition, OptionalGroup, VariableCapture, PinnedBackreference, Backreference;
    - `BackreferenceHelpers` `:85`, `:132`, `:174`;
    - `RecursiveDescentBytecodeGenerator.visitBackreference` (`:4212`). With the narrow B13
      trigger (decision 9), backref patterns stay on this generator.

    This fails on plain ASCII:

    | Pattern | Input | Reggie | JDK | Route |
    |---|---|---|---|---|
    | `(?i)(\w+)-\1` | `ab-AB` | no match | [0,5) | PINNED_BACKREFERENCE |
    | `(?i)(.+)\1` | `abAB` | no match | match | VARIABLE_CAPTURE_BACKREF |
    | `(?i:(a)\1)` | `aA` | no match | match | LINEAR_BACKREFERENCE |

  - **Unicode case folding not refused.** `checkUnicodeCaseFold` (`RegexParser.java:996`)
    runs for literals and classes (`:460`, `:1009`), not for backrefs. The JDK compares
    backrefs under `(?iu)` with `toUpperCase` and then `toLowerCase`
    (`Pattern.java:5254-5259`):

    | Pattern | Input | Reggie | JDK |
    |---|---|---|---|
    | `(?iu)(.)\1` | `\u212Ak` | no match | match |
    | `(?iu)(\W)\1` | `\u00e9\u00c9` | no match | match |

- **Fix.**
  - Add a `caseInsensitive` field to `BackreferenceNode`, set from the parser's
    `currentModifiers`. Carry it to the NFA backref check (`NFA.java:436`) and to every
    backref generator.
  - Add the field to `StructuralHash`.
  - Replace `regionMatches(true, …)` with an ASCII-fold static helper (H5), called through
    `INVOKESTATIC` as in the `LineTerminators` precedent (`BytecodeUtil.java:49`).
  - Remove the `contains("(?i)")` heuristic in `BackrefBacktrackMatcher`.
  - Call `checkUnicodeCaseFold` when the parser builds a backref under CI, so `(?iu)` and
    `(?iU)` backrefs go to the fallback or throw.
  - In the same change, B4 rejects backrefs under CI | `UNICODE_CASE`. Without it,
    `toReggieFlags("(\\W)\\1", CASE_INSENSITIVE | UNICODE_CASE)` on `\u00e9\u00c9` matches
    today through `regionMatches(true, …)`, as the JDK does, and stops matching after H5.
  - `RuntimeCompiler.isCaseInsensitive` (`:3024`) stays for its other uses: prefilter
    extraction (`:606`, `:632`), and the value from `:1377` that flows into
    `StructuralHash.compute` (`:1638-1639`) and the generators (`:1686`, `:2721-2875`).
    Only the backref compare switches to the node field. The processor copy
    (`ReggieMatcherBytecodeGenerator.java:447`, `:1086`) is handled the same way.
  - é/É: see decision 5.
- **Tests.** In `CaseInsensitiveBackrefTest`, one JDK-differential row per route:
  LINEAR, PINNED, VARIABLE_CAPTURE, FIXED_REPETITION, OPTIONAL_GROUP, RECURSIVE_DESCENT,
  BackrefBacktrack and the NFA path. Confirm the B33 route column with `debugPattern`. Add scoped `(?i:…)` rows and negative rows for Kelvin, ſ, İ and ı.
  - Refusal rows: `(?iu)(.)\1` on `\u212Ak` and `(?iu)(\W)\1` on `\u00e9\u00c9` go to the
    fallback with `compileAllowingFallback` and throw without it.
  - The `toReggieFlags` row above is rejected.

### B16: `(?i)` with properties and class escapes

- **Causes.**
  - Bare `\p` goes through `parseUnicodeProperty` (`:694-717`) without any case-insensitive
    handling.
  - Inside brackets, `applyCaseInsensitiveToRanges` (`:1353`) runs over everything at
    `:459-462`, so `\W` and `\P{..}` get folded too.
  - Rows:
    - `(?i)\p{Lu}` / `a`: JDK true, reggie false;
    - `(?i)\P{Lu}` / `a`: JDK false, reggie true;
    - `(?i)[\W]` / `k`: JDK false, reggie true.
- **Fix.** Add a case-insensitive property lookup that follows the JDK `CharPredicates`
  rules:
  - Lu, Ll and Lt each become Lu|Ll|Lt;
  - POSIX `Lower` and `Upper` become ASCII alpha;
  - `javaLowerCase`, `javaUpperCase` and `javaTitleCase` become their union;
  - predefined classes are not folded.

  Use it for bare and bracketed `\p`. Fold only literals and ranges.
- **Out of scope.** `\p{IsUppercase}`, `\p{IsLowercase}` and `\p{IsTitlecase}` are missing
  from `CharSet` (`:400-490`) and throw `UnsupportedPatternException`. This is a feature gap
  that already fails loudly.
- **Tests.** A new `CaseInsensitivePropertyTest` with a JDK-differential table: Lu, Ll, Lt,
  Upper, Lower, the `\P` negations, `[\W]`, `[^\W]` and `[\D]`, both bare and in brackets.

### B38: `(?iu)` / `(?iU)` refusal misses cased non-letters

- **Cause.** `literalNode` calls `checkUnicodeCaseFold` (`:996`) only inside the
  `Character.isLetter` gate (`:1008-1009`). The class path (`:458-460`) calls it for every
  class under `i`. So `(?iu)\u24b6`, `(?iU)\u24b6` and `(?iu)\u2160` compile natively and
  do not match `\u24d0` / `\u2170`, while the JDK matches.
- **Fix.** In `literalNode`, under `i`, call `checkUnicodeCaseFold` when `ch` is *non-ASCII
  cased* (the B4 predicate) or one of `I`, `i`, `K`, `k`, `S`, `s`, not only for letters.
  Letters keep today's refusal.
- **Tests.** Extend `UnicodeCaseFoldRefusalTest`: `(?iu)\u24b6`, `(?iU)\u24b6`,
  `(?iu)\u2160` go to the fallback with `compileAllowingFallback` and throw without it;
  `(?iu)\u2192` still compiles natively (guard row).
  - `(?iu)\ud801\udc00` as literal characters (U+10400, the JDK folds it to U+10428):
    refused. The escape text is already refused today by the surrogate-escape check.
  - Behaviour change: the predicate counts surrogate code units, so `(?iu)` with an uncased
    supplementary literal (`(?iu)\ud83d\ude00`) also leaves the native path. It compiles
    natively today. The class path already refuses every class under `(?iu)`. Row:
    `(?iu)\ud83d\ude00` as literal characters refused.
- **Plan update.** B4's `UNICODE_CHARACTER_CLASS` sentence and the decision 11 Alternative
  rely on this fix.

### B4: `toReggieFlags` accepts patterns that behave differently

- **Cause.** `JdkPatternCompatibility.java:59-71` checks only that the pattern text is ASCII.
  Escapes such as `"\\u00e9"`, `\x{e9}` and octal pass the check. The comments at `:75-76` and
  `:96-97` claim that CI with `UNICODE_CASE` maps exactly to reggie CI. That is wrong:
  - JDK `(?iu)k` matches Kelvin, and reggie `(?i)k` does not. The same holds for `i` with
    U+0130 / U+0131 and `s` with ſ.
  - `[^a-z]` with CI | `UNICODE_CASE` on Kelvin: JDK false, reggie `(?i)[^a-z]` true.
  - Backrefs: see *B12 + B33*.
- **Fix.** The AST cannot answer this: `LiteralNode` and `CharClassNode` keep no modifiers,
  and B7 merges set operations into one `CharSet`. For example `[k&&K]` with CI |
  `UNICODE_CASE` matches U+212A in the JDK, but parsed without CI it is the empty set. So
  the check runs in `RegexParser` (H6). B4 lands after B7, so the hook sits in the
  rewritten class parser.
  - **What the parser records.** For each leaf parsed while `i` is active (`literalNode`,
    the class leaf at `:460`, next to `checkUnicodeCaseFold` `:996`), before the
    `Character.isLetter` gate at `:1007-1008` and `:1358-1359`:
    - *non-ASCII cased*: the leaf's set contains a code point ≥ 0x80 whose
      `toLowerCase`, `toUpperCase` or `toTitleCase` differs from it, or U+00DF, or a
      surrogate code unit. This covers non-letters such as `Ⓐ` (So) and `Ⅰ` (Nl), which
      reggie never folds, and ß, which is a fold target without its own mapping. The parser
      sees supplementary characters as two uncased surrogates, so surrogates count too.
      A guard test runs over all code points x. It checks that every non-ASCII result
      r ≠ x of `toUpperCase(x)` or `toLowerCase(toUpperCase(x))` satisfies this predicate,
      and that every ASCII result r ≠ x for a non-ASCII x is in `IiKkSs`. These are the
      probes of the JDK folds `SingleU` (`Pattern.java:5923-5926`) and `CIRangeU`
      (`:5953-5960`);
    - *special ASCII*: the leaf's set contains `I`, `i`, `K`, `k`, `S` or `s`.

    It also records whether a backref was built while `i` is active.
  - **How `toReggieFlags` uses it.**
    - The `LITERAL` + CI reject (`:62`) stays and runs first.
    - The parse runs only when CI or `UNICODE_CASE` is set, and not for `LITERAL` without
      CI: `LITERAL` turns off inline flags, and `UNICODE_CASE` does nothing without CI. It
      prepends `(?i)` only when the CI flag is set, and never applies `(?u)` or `(?U)`.
    - A `ParseException` (including `UnsupportedPatternException`) in this parse makes
      `toReggieFlags` throw `IllegalArgumentException`, which points to `(?i)` and
      `compileAllowingFallback`. This changes the contract: today `toReggieFlags("[z-a]",
      CASE_INSENSITIVE)` returns flags and the later compile fails.
  - **Reject if:**
    - CI is set and a leaf is *non-ASCII cased*. This replaces the source-text `asciiOnly`
      check at `:61`. Non-ASCII characters without case, such as `→`, become accepted:
      the JDK and reggie both match them only against themselves;
    - `UNICODE_CASE` is set and a leaf is *non-ASCII cased* or *special ASCII*, or a
      backref was built under `i` (decision 11, sign-off). This covers CI | `UNICODE_CASE`
      and `UNICODE_CASE` with inline `i`. The backref part lands with B12.
  - `toReggieFlags(int)` rejects `UNICODE_CASE` (decision 11).

  B4 lands after B16, so bracketed class escapes need no reject.
  `UNICODE_CHARACTER_CLASS` with `i` needs no reject after B38: `toReggieFlags` maps it to
  `ReggieFlags.UNICODE_CHARACTER_CLASS` (`JdkPatternCompatibility.java:100-103`), reggie
  turns that into `(?U)` (`RuntimeCompiler.java:1146-1148`), and `checkUnicodeCaseFold`
  (`:996`) refuses it. Before B38 the refusal covers letters only.
- **Tests.**
  - `"\\u00e9"` (the escape text) + `CASE_INSENSITIVE` on `É`: JDK false, so it must be
    rejected.
  - `[k&&K]` with CI | `UNICODE_CASE`: rejected.
  - Extend `JdkPatternCompatibilityTest` near `caseInsensitiveRejectedForNonAsciiPatterns`
    (~`:73`) with each reject reason.
  - Run the differential over the adversarial inputs in
    `foldsAgreeForAsciiPatternsOnAdversarialInputs` (~`:100`). That test uses CI only
    (`:99-125`). Every case there contains `i`, `k`, `s` or a range over them, so a CI |
    `UNICODE_CASE` run asserts rejection, not equality.
  - CI | `UNICODE_CASE` rows: `i`, `k`, `s`, `[a-z]`, `[^a-z]` are rejected; `a`, `[b-h]`
    and `\d` are accepted and equal the JDK on U+0130, U+0131, U+212A and U+017F (guard
    rows).
  - `(?i)k` + `UNICODE_CASE` alone: rejected. `(?i)a` and `k(?i)a` + `UNICODE_CASE`:
    accepted (guard rows).
  - `"a\\u00df"` and `"[\\u00df-\\u00df]"` (escape text) with CI | `UNICODE_CASE`:
    rejected (JDK matches `a\u1e9e` and `\u1e9e`). The literal forms are rejected today by
    `asciiOnly` (`:61`) and stay as guard rows.
  - `\ud801\udc00` (U+10400, literal characters) with CI | `UNICODE_CASE`: rejected (guard
    row, `asciiOnly` today). The escape text `"\\ud801\\udc00"` makes the parse throw
    `UnsupportedPatternException`, so it is a parse-failure row: `IllegalArgumentException`.
  - `a(` and `(?i)k` with `LITERAL | UNICODE_CASE`: accepted, as today (guard rows).
  - `"(?i)\\u00b5"` and `"(?i)\\u24b6"` (escape text) + `UNICODE_CASE`: rejected.
    `"\\u24b6"` and `"\\u2160"` (escape text) with CI | `UNICODE_CASE`: rejected.
  - `[h-j]` with CI | `UNICODE_CASE`: rejected.
  - Parse failures: `[z-a]` + CI and `(?iu)k` + CI throw `IllegalArgumentException`.
  - The literal character `→` (not the escape text) + CI: accepted and equal to the JDK.
  - `toReggieFlags(UNICODE_CASE)`: throws `IllegalArgumentException`.

### B34: `UNICODE_CASE` in the fallback

- **Cause.** `JavaRegexFallbackMatcher` adds `UNICODE_CASE` (`:33-37`). Its comment says it
  folds "the same way" as reggie's native parser, which is wrong.
- **Fix.** Drop the flag (decision 6). Correct the comment.
- **Test.** `(?i)k` / U+212A through the package-private constructor
  `new JavaRegexFallbackMatcher("(?i)k", …)` (`:31`) and through native compilation (guard
  row, `UnicodeCaseFoldRefusalTest:61`). Both results equal JDK without `UNICODE_CASE`.

## Wave 4: engine captures

### B3: `(a|)+` returns null

- **Cause.** `EpsilonNode` extends `LiteralNode` with `ch = 0`. `PatternAnalyzer:9681`
  (`altChild instanceof LiteralNode`) makes SPECIALIZED_QUANTIFIED_GROUP treat the empty
  branch as NUL, so the pattern behaves like `[a\0]{1,}`.
- **Fix.**
  - `detectQuantifiedCapturingGroup` declines when a branch is an `EpsilonNode`.
  - Confirm the new route with `debugPattern`.
  - Audit the other `instanceof LiteralNode` sites for the same mistake: 60 in
    `PatternAnalyzer`, 97 across codegen and runtime. A hit
    found by the audit is a new bug with its own ID.
- **Tests.** Rows `(a|)+` and `(|a)+`, with find on `b` and `match("")`, next to
  `QuantifiedGroupAltPriorityTest`.

### B22: DFA_UNROLLED_WITH_GROUPS sets captures of an alternative not taken

- **Cause.**
  - The A2 guard `hasCapturingGroupAbsentFromSomeAlternative`
    (`FallbackPatternDetector.java:1572`) exempts alternations whose branches are all single
    characters (`:1590-1597`). That exemption is unsound outside tail position.
  - `SubsetConstructor.computeGroupActions` C2.4 (`:1066-1067`) prunes dead-thread markers
    only in accepting states.
- **Fix.** Keep the exemption only in tail position, or when the branches have disjoint
  first-character sets. Other patterns go to PIKEVM or BITSTATE.
- **Tests.** `(?:a|(a))[ab]` / `aa`, `(?:(b)|(b))b` / `bb`, plus one tail-position negative
  that stays on the DFA route (guard row).

### B24: SPECIALIZED_FIXED_SEQUENCE loses nested group spans

- **Cause.** A `SequenceElement` holds one group number. See:
  - `flattenElement` `:9329-9338`;
  - `analyzeElement` `:8963-8974`;
  - the single-element path `:8597-8604`;
  - the generator, `:763-776` and `:905-918`.
- **Fix.** Compute `groupStart` / `groupEnd` offset arrays at compile time and store them on
  `FixedSequenceInfo`. Include them in `structuralHashCode` (`:7588-7597`). This is
  enough for the structure cache, because `StructuralHash.compute` includes
  `patternInfo.structuralHashCode()` (`StructuralHash.java:107`, `:144`).
- **Tests.** `((a))c` / `ac`, `(b(ab))b` / `babb`.

### B26: pin inside atomic groups

- **Cause.** The atomic branch of `visitGroup` (`ThompsonBuilder.java:592-612`) does not reset
  `repeatDepth`. `visitAssertion` does (`:696-699`).
- **Fix.** Save `repeatDepth`, set it to 0 around the atomic body, then restore it.
- **Also.** The same branch sets `atomicBody = node.child` (`:597`) and never restores it.
  With nested atomic groups, the inner group overwrites the outer value. Save and restore it
  too, and add a nested-atomic row.
- **Tests.** `(?:(?>(b)*c)a)+` / `bcabca`: JDK g1 is [3,4). A nested row such as
  `(?:(?>(?>(b)*c)d)a)+` / `bcdabcda`, with the expected value taken from the JDK.

### B13: RECURSIVE_DESCENT does not backtrack into alternations

- **Cause.** `visitAlternation` (`RecursiveDescentBytecodeGenerator.java:2288`) commits to
  the first branch that succeeds.
- **Fix.** In `BacktrackSupport.routesToBacktracker`, `case RECURSIVE_DESCENT` returns true
  when any NFA state has a `backrefCheck`. Both the runtime and the processor use this
  helper.
- **Perf.** All recursive-descent patterns with a backref move to the interpreter. The
  narrower option is to trigger only when the referenced group's body has an alternation or
  a variable quantifier. Run JMH on the backref benchmarks for both triggers, then decide
  (decision 9).
- **Scope.** Patterns that `PatternAnalyzer.requiresRecursiveDescent` (`:79-92`) forces to
  RECURSIVE_DESCENT (subroutines, conditionals, branch reset) have no NFA, so
  `routesToBacktracker` never sees them. B13 covers only patterns that have an NFA, which
  includes its own row.
- **Tests.** `(a|ab)\1+` / `abab`, in `RecursiveDescentBackrefRegressionTest`.

### B23 + B36: own-group pin coverage

- **Cause (B23).** `DETERMINISTIC_BODY` (`ThompsonBuilder.java:235-300`) is narrower than
  JDK `study()`.
- **Fix (B23).** Broaden `DETERMINISTIC_BODY` to follow JDK 21 `Pattern` `study()`:
  - `Curly` and `GroupCurly` are deterministic when `cmin == cmax` and the body is
    deterministic;
  - an atomic group (`Ques` with type INDEPENDENT) is deterministic when its body is;
  - `X?+` is `Ques` with type POSSESSIVE, which is non-deterministic. Reggie builds `X?+` as
    an atomic group around `Quant(X,0,1)` (`RegexParser.java:217-223`), so the atomic rule
    gives the same answer;
  - capturing and non-capturing groups are deterministic when their child is;
  - lookaround nodes do not override `study()`, so their bodies are ignored;
  - anchors are deterministic: `Dollar.study` returns deterministic, and `Caret` and `Bound`
    do not override `study()`. This clause is for consistency only: an anchor inside a
    quantifier other than `{1,1}` already goes to the fallback
    (`FallbackPatternDetector.java:92-100`), so no native route sees it;
  - backrefs stay non-deterministic in reggie (decision 8). In the JDK, `BackRef` only sets
    `maxValid = false`.
- **Open point.** A condition that the body always consumes at least one character is not
  part of `study()`, so it is not required. Phase 1 adds rows with nullable
  deterministic bodies. Add the condition, and helper H7, only if those rows show that the
  pin emulation fails on them.
- **B36: routes that ignore pins.** Pins are honored by PikeVM (including the PikeVM half of
  HYBRID) and by `BackrefBacktrackMatcher`. Other routes ignore them:
  - RECURSIVE_DESCENT, when `routesToBacktracker` returns false for it;
  - on the `unsupportedFeature` path, `RuntimeCompiler` (`:1487-1494`) sets
    `backtrackNfa = null`, and routing falls through to the generated LOOKAROUND, BACKREFS or
    RECURSIVE_DESCENT code.
- **Fix (B36).**
  - Add `|| nfa.hasPinnedGroups()` to the RECURSIVE_DESCENT case of `routesToBacktracker`.
  - When pinned groups exist and the final route honors no pins, call `fallbackOrThrow`.
    Two places need this:
    - `RuntimeCompiler` ~`:1478-1494`;
    - `ReggieMatcherBytecodeGenerator` ~`:193`.
  - This changes routing (decision 10). Phase 1 lists the corpus and test patterns that move.
  - Correct the javadoc at `PatternAnalyzer.java:2953` ("which only PikeVM honors"):
    `BackrefBacktrackMatcher` honors pins too.
- **Reachability.** Phase 1 checks whether B36 can be reached with patterns that are pinned
  today. Patterns that become pinned through B23 reach it in any case.
- **Perf.** Shapes that become pinned lose BitState and run on PikeVM, which is about 3.4×
  slower. The phase-4 corpus run reports how many patterns move.
- **Tests.**
  - Positive rows: the exact-count, atomic, lookaround and nested-capture rows from S2c,
    including `(?:((b))*a)+` / `baba` (g1 JDK [0,1), reggie [2,3); g2 JDK [2,3)).
  - Negative rows that stay unpinned, as in the JDK: `(?:(x?+b)*a)+`,
    `(?:((?>b|c))*a)+`, `(?:((?:b|c){1})*a)+`, each on `baba` (guard rows).
  - A routing-invariant test: every pinned NFA routes to PikeVM, to HYBRID with a PikeVM
    half, to `BackrefBacktrackMatcher`, or to `fallbackOrThrow`.

## Wave 5: others

### B17: `\b` / `\B` and non-spacing marks

- **Cause.** Every `\b` site uses ASCII `isWord(prev) != isWord(cur)`. The JDK uses
  `Pattern.Bound.check` with `hasBaseCharacter`:
  - a non-spacing mark counts as a word character when walking back over the marks reaches a
    letter or digit (Unicode, so `é` counts);
  - the walk-back stops at the region start (`Pattern.java:5587-5591`, `Bound.check`
    `:5551`). `find(int)` resets the region to the whole input, so a search that starts
    after a mark still walks back past its start.
- **Rows** (U+0301 is a combining acute accent):
  - `\b` find on `a\u0301`: JDK [0,0) and [2,2); reggie [0,0) and [1,1).
  - `a\b` / `a\u0301`: JDK no match; reggie [0,1).
  - `\bfoo` / `a\u0301foo`: JDK no match; reggie [2,5).
  - `\b` find from 2 on `a\u0301!`: JDK [2,2).
- **Sites.**
  - Runtime engines:
    - `PikeVMMatcher` `isWordChar` `:1507` and `checkAnchor` `:1539-1550`. `checkAnchor` is
      shared with `BitStateMatcher` (`:698`) and `LaurikariCaptureNfaStep` (`:314`);
    - `BackrefBacktrackMatcher` `:1629`, `:1649-1660`;
    - `BackreferenceHelpers` `:62`, `:120-134`, `:184`;
    - `LaurikariDFACache` `:375-388`. Its cache key (consumed char, lookahead class) does
      not capture a mark. Add a mark lookahead class, or take an uncached path when either
      char is a non-spacing mark.
  - Code generators:
    - `NFABytecodeGenerator` `emitWordBoundaryGuard` `:1669` and `emitIsWordChar` `:1709`;
    - `DeterministicChainBytecodeGenerator` `emitWordBoundary` `:1520`;
    - `FixedSequenceBytecodeGenerator` `:231-379`;
    - `GreedySuffixBytecodeGenerator` `:109-163`;
    - `BitStateBytecodeGenerator` `:143-236`, `:396-423`;
    - `LinearPatternBytecodeGenerator` `:737-854`;
    - `BackreferenceBytecodeGenerator` `:736-765`, `:813-947`, `:1015-1141`;
    - `CharClassBitmap.generateIsWordCharCheck` `:300`, called from `NFABytecodeGenerator`
      `:10406`, `:10421`, `:10448`, `:10462`, `:10488`, `:10503`.
  - Not handled correctly today, reachability to be checked in phase 1:
    - `OnePassBytecodeGenerator.java:640`: `WORD_BOUNDARY` is a no-op that always passes;
    - `RecursiveDescentBytecodeGenerator.java:4195`: a word boundary always fails.

    If either is reachable, it is a new bug with its own ID.
- **Fix.** A runtime helper `WordBoundary` (H8), called from generated code through
  `INVOKESTATIC`.
  - Fast path: an ASCII word character returns true, and any other character below U+0300
    returns false.
  - Slow path, out of line: the non-spacing-mark walk-back.
  - The walk-back lower bound is a parameter: 0 for `find`, `findFrom`, `findAll` and the
    cursor; `start` for `matchesBounded` and `matchBounded` (`ReggieMatcher.java:358`,
    `:379`).
- **Perf.**
  - Expected cost: one extra compare on the non-word path.
  - A run of k marks costs O(k) per check, as in the JDK.
  - Run JMH on the `\b` benchmarks before and after.
- **Tests.** A new `WordBoundaryCombiningMarkTest`, JDK differential with `findAll`.
  - Inputs, all with U+0301 as a literal character: `a\u0301`, `\u00e9\u0301`, `_\u0301`,
    `a\u0301b`, `x a a\u0301`.
  - Patterns, one per route: `\b`, `a\b`, `\B`, `\bfoo`, `\b\w+\b`, `(a)\b`,
    `\b(\w+)\s+\1\b`.
  - `\b` with `findFrom` 2 and with `findAll` on `a\u0301!`, where the second search starts
    after the mark.
  - Region bound, with `matchesBounded(input, 1, 3)` on `a\u0301!` against JDK `region(1,3)`
    with opaque bounds: `.\B!` true and `.\b!` false. With transparent bounds the JDK gives
    the opposite, so these rows show that the walk-back stops at the region start. Reggie
    passes them today because `matchesBounded` and `matchBounded` work on a substring
    (`ReggieMatcher.java:358`, `:379`). They are guard rows for the lower-bound parameter.

### B5: split on the empty pattern

- **Finding.** This is fixed in the current tree. With
  `Reggie.compile("")`:
  - `findAll("abc")` equals the JDK;
  - `split` with limits 0, -1 and 2 equals the JDK.
- **Fix.** Remove the three `@Disabled` annotations at `SplitWithLimitTest.java:184-215`, then
  run the test. These rows are guard rows. Add a processor row for `@RegexPattern("")`: its generated matcher may use a
  different strategy than the runtime fast path.

### B14: `\G` resets in the fallback's iteration APIs

- **Cause.** `JavaRegexFallbackMatcher` overrides none of the iteration APIs. Its
  `findMatchFrom` (`:124`) creates a new JDK `Matcher` per call, so `\G` resets on every
  call. The same per-call loop runs in:
  - the base `findAll` (`:742`), `split` (`:686`), `replaceAll` (`:575`, and
    `findBoundsFrom` at `ReggieMatcher.java:532`) and `replaceAll(Function)` (`:639`);
  - the cursor (`MatchCursor.java:80`, `ReggieMatcher.cursor` at `:806`);
  - `PooledMatcher`, which the processor emits for the fallback
    (`ImplClassBytecodeGenerator.java:278-300`). It forwards `findAll`, `split` and
    `replaceAll` (`PooledMatcher.java:181-227`) but not `cursor()`.
- **Fix.**
  - Override `findAll`, `split(String, int)` (through `javaPattern.split`), both `replaceAll`
    overloads, and the cursor. Each drives one JDK `Matcher`.
  - Make `expandReplacement` protected (H9).
  - The fallback's `cursor` uses a `MatchCursor` mode that holds one JDK `Matcher` and
    steps it with `find()` (H10). `MatchCursor` is final and drives
    `matcher.findMatchFrom` (`MatchCursor.java:34`, `:36`, `:80`), so it needs this mode.
    Phase 3 of the parent plan reuses it for the strict fallback.
  - `PooledMatcher` overrides `cursor()` as the parent plan prescribes (*Processor pool*): it
    creates a dedicated instance with `create(factory)`, never released, and returns that
    instance's cursor. A pooled instance is not forwarded, because `release`
    (`PooledMatcher.java:72-74`) puts it back into the slot while the cursor still uses it.
  - `PrefilteringMatcher` and `NameEnrichingMatcher` need no forwarding: after B18
    `wrapCompiled` (`RuntimeCompiler.java:670`) returns fallbacks unwrapped, and
    `NameEnrichingMatcher` wraps only generated matchers.
- **Tests.** A new `JavaRegexFallbackIterationTest`. It uses the package-private constructor
  `new JavaRegexFallbackMatcher("\\G", …)`, because no route reaches `\G` until the `\G` part
  of B10 lands (Wave 5). Compare `cursor`, `findAll`, `split`, `replaceAll` and
  `replaceAll(Function)` with the JDK on `ab` and `aab`.
  - A `PooledMatcher` built on a factory that returns `new JavaRegexFallbackMatcher("\\G", …)`:
    its `cursor()` equals the JDK.
  - Guard row against a forwarded pooled instance: a `PooledMatcher` built on a factory that
    returns a new PikeVM instance per call (for example `insertArguments` on the constructor
    handle, as `RuntimeCompiler.createChainFallback` does). One thread steps a cursor while
    a second thread calls `find` on the `PooledMatcher` in a loop; the cursor results equal
    the JDK. It passes today, because the base cursor goes through
    `PooledMatcher.findMatchFrom` (`:141`) on every step. The hazard is concurrent use of
    the instance that `release` puts back in the slot, so this row is a stress test: it
    cannot fail with the dedicated-instance design, and with a forwarded pooled instance it
    fails only when the threads interleave.
  - With the `\G` part of B10: a processor row, `\G` with `ALLOW_JDK_FALLBACK`, comparing
    `cursor`, `findAll`, `split` and `replaceAll` with the JDK.

### B37: parse exceptions in the processor

- **Cause.** `RegexPatternProcessor` (`:207-212`) catches only
  `UnsupportedOperationException`. A `ParseException` propagates out of `processClass` to
  the `catch (Exception)` in `process()` (~`:86-92`), which fails the whole annotated class,
  not only the one method.
  `RegexParser.UnsupportedPatternException` extends `ParseException`
  (`RegexParser.java:1679`), so a pattern the parser refuses as unsupported, such as
  `(?iu)k` (`checkUnicodeCaseFold`, `:996`), also fails the class, even with
  `ALLOW_JDK_FALLBACK`. The runtime routes the same patterns to the fallback. After B10,
  `\X` and `\G` join this group. Today `\X` parses as a literal `X` (`:684`).
- **Fix.**
  - Catch `UnsupportedPatternException` first and map it to `DELEGATE_FALLBACK` when
    fallback is allowed. Otherwise report a build error on that method.
  - Catch other `ParseException`s next and report a build error on that method.
- **Tests.**
  - A class with one bad and one good pattern: only the bad method gets an error.
  - `(?iu)k` with `ALLOW_JDK_FALLBACK`: the build succeeds and results equal the JDK.
  - B10 adds the same row for `\X`.

## Proposed helpers (approval needed)

| ID | Helper | Used by |
|---|---|---|
| H1 | Private static factory in `RuntimeCompiler` that runs the fast-path lookups (`:497-526`) for an engine key | B25 |
| H2 | Widen the existing private `PooledMatcher(factory, first)` constructor (`:46-51`) to package-private; its factory must already be adapted to `()ReggieMatcher` (`adapt`, `:53`) | B25 |
| H3 | `CharSet.HORIZONTAL_WS`, `CharSet.VERTICAL_WS` | B1 |
| H4 | `skipClass(String, int)` in `codegen.parsing`, shared by the three lexical scanners | B6, B7, B9, B32 |
| H5 | Runtime static ASCII-fold region compare for backrefs | B12 |
| H6 | `RegexParser` post-parse results for leaves parsed under `i` (non-ASCII cased, including U+00DF and surrogates; special ASCII `IiKkSs`) and for backrefs built under `i`, read by `JdkPatternCompatibility`; the non-ASCII cased predicate is shared with B38 | B38, B4 (the backref part lands with B12, Wave 3) |
| H7 | `RegexVisitor` that answers "always consumes at least one char"; only if phase 1 needs it | B23 |
| H8 | Runtime `WordBoundary` (`isWordBefore`, `isWordAt`), with the walk-back lower bound as a parameter | B17 |
| H9 | `expandReplacement` made protected | B14 |
| H10 | `MatchCursor` mode that holds one JDK `Matcher` and steps it with `find()` (package-private constructor); phase 3 of the parent plan reuses it | B14 |

## Not fixed here

These differences are recorded and stay M1 territory:

- JDK-rejected syntax that reggie accepts: `[\b]` (`ParserExceptionTest.java:63` asserts
  backspace), one-digit `\x4`, `[\1]`, letters such as `\i`, `\q`, `\y`, and inline
  comments `(?#...)` (`RegexParser.java:43-44`; the JDK reports "Unknown inline modifier"),
  and whitespace inside `{}` before the first digit, or anywhere inside `{}` outside `(?x)`.
- JDK-accepted syntax that reggie rejects: `\N{name}` and `\b{g}`. These patterns go to the
  fallback or fail, and parity is not affected.
