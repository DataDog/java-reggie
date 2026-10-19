# Plan: `ReggieOption.JDK_STRICT`

## Goal

Add an opt-in compilation option. With it, a compiled matcher gives the same results as plain
`java.util.regex.Pattern.compile(pattern)` (no flags) **on the JDK that runs the matcher**, for
every pattern it accepts.

"Same results" covers:

- syntax acceptance and rejection;
- match bounds and group spans of `matches()`, `find()`, `findFrom()`, `match()`,
  `findMatch()`, `findMatchFrom()`, `matchesBounded()` and `matchBounded()`;
- the int-array forms `matchInto`, `findMatchInto` and `findBoundsFrom`;
- for `matchesBounded`/`matchBounded`, the JDK reference is `Matcher.region(s, e).matches()`
  with default (opaque, anchoring) bounds;
- the rich API: `findAll`, `replaceFirst`, `replaceAll` (string and function forms), `split`
  and `cursor()`.

Without the option, behavior stays exactly as today.

The default keeps its current semantics. It already emulates several JDK quirks: the GroupCurly
pin (`NFAState.pinGroup`, `NFAState.countedLoopPinGroup`), per-iteration atomic possessive
`X{n,m}+` (`RegexParser.possessive`), and the `usePosixLastMatch` hybrid. Changing the default
would change results for existing callers. A PCRE-semantics "fast" option, which drops those
emulations, is a separate later plan and out of scope here.

**Limit of the guarantee.** Parity holds for the divergence families below and the M1/M2
guards, and assumes the native engines are correct. Unknown JDK quirks and unknown reggie engine
bugs (B22–B24 were found with a small fuzz) are caught only by phase-4 fuzzing. The
javadoc says this.

## Safety posture (unchanged)

The JDK fallback stays opt-in (`fallbackOrThrow`, `RuntimeCompiler.java:1154`). `JDK_STRICT`
never silently drops to `java.util.regex`. For a pattern the native pipeline cannot run with JDK
results:

- `JDK_STRICT` + `ALLOW_JDK_FALLBACK` returns the strict JDK matcher (see *Strict fallback
  matcher*);
- `JDK_STRICT` alone throws `UnsupportedPatternException` naming the family.

A pattern with no divergent shape compiles through the normal runtime pipeline, on the same
engines.

## Option interactions

| Combination | Behavior |
|---|---|
| `JDK_STRICT` + `CAPTURE_NAMED_ONLY` | Rejected with `IllegalArgumentException` in `RuntimeCompiler.compile(String, ReggieOptions)` and in the processor's options read (`RegexPatternProcessor.java:195-202`). `ReggieOptions.Builder.build()` validates nothing today. Reason: `CaptureProjection` makes unnamed groups non-capturing, so the native and fallback routes would report different group sets. |
| `JDK_STRICT` + flag entry (`ReggieFlags`) | Parity is defined against `Pattern.compile(pattern, jdkFlags)`; phase 1 lists the mapping. Flags with no exact JDK equivalent are rejected. `CASE_INSENSITIVE \| UNICODE_CASE` through `JdkPatternCompatibility.toReggieFlags`, which today drops UNICODE_CASE, is an S6 trigger until B4 lands. After B4, `toReggieFlags` rejects the patterns where the flag changes results, and `toReggieFlags(int)` rejects UNICODE_CASE. M1 compiles the **original** pattern with `jdkFlags`, not the normalized `(?i)(?m)…`-prefixed one, so error indexes and pattern text match the JDK. |
| `JDK_STRICT` + `cached(key, pattern, options)` | Covered by bug fix B19 (below). After it, strict state never lands in a default-keyed cache entry. |

## Detection mechanisms

### M1: syntax parity

Under `JDK_STRICT`, the runtime calls `Pattern.compile(pattern[, jdkFlags])` first.

- **JDK throws:** rethrow its `PatternSyntaxException`, whether or not fallback is allowed. This
  covers PCRE-only syntax (`(?1)`, `(?R)`, conditionals, branch reset, `\K`, `\g{}`, `(?'n')`)
  and reggie's lenient acceptance. Examples of the latter: group names `(?<a_b>)`/`(?<é>)`,
  `(?#c)`, `[^]`, `[\b]`, `[\1]`, `[\A]`, unknown escapes `\y` `\i` `\E`, `\k'n'`, `\0`, `\08`,
  `\xA`.
- **JDK accepts, reggie throws:** the reggie exception (`ParseException`,
  `UnsupportedPatternException`, or the `RuntimeException` from `[z-a]`) goes to
  `fallbackOrThrow`. It never becomes a `PatternSyntaxException`. Examples: `\x{1F600}`, `\pL`,
  `\N{..}`, `\b{g}`, `a{2}{3}`, `(?d)`, `(?u)`, `(?U)\b`.

The processor also runs M1 at annotation time, but only as an advisory build ERROR. The runtime
check is authoritative, because the build JDK may differ.

**Reusing the M1 `Pattern`.** M1 runs only on a compile miss: cache hits take the fast paths
(`RuntimeCompiler.java:497-526`) or L1 and never reach `compileInternal`. The `Pattern` it builds
is immutable and thread-safe, so it is not thrown away when the strict fallback needs it:
`fallbackOrThrow` (whose signature changes anyway, see *Strict fallback matcher*) receives it,
and the strict fallback uses it instead of compiling again. The surrogate guard is built in the
`wrapCompiled` post-step, which fast paths reach without M1. It gets its `Pattern` from the S9
verdict entry instead (see *Runtime path*, step 4). On a compile miss, `compileInternal` seeds
that entry with the M1 `Pattern` when the S9 check hits, so a guarded pattern pays one JDK
compile in total.

### M2: parser allowlist flag

`RegexParser` sets `jdkStrictUnsafe` when it handles an escape, class construct or inline flag
outside a vetted set. A construct is vetted **per flag state**: a construct that agrees under
`(?i)` is not thereby vetted under `(?U)`. These agree with the JDK, on
JDK 21, for BMP input including non-ASCII:

- plain literals;
- `\d \w \s \D \W \S`, inside and outside classes;
- `\A \z \Z` and `$`;
- `\t \n \r \f`, `\uXXXX` (BMP), `\xHH`, `\\` and escaped punctuation;
- simple ranges and negation;
- `.` against line terminators;
- `(?i)` on ASCII constructs;
- `(?m)`, `(?s)`, and `(?x)` ASCII whitespace outside classes. Non-ASCII whitespace under
  `(?x)` is not vetted until B30 is fixed. A `(?x)` `#` comment is vetted only when it ends
  at `\n` or the pattern end (B21).

Under `(?U)`, nothing is vetted. `\b` and `\B` are **not** vetted (B17: the JDK treats a
non-spacing mark after a letter or digit as a word character). `\p{..}`/`\P{..}` are vetted for
BMP membership only; their surrogate and supplementary behavior is handled by S9 and S6(b). A
construct joins the set only with a differential test row.

M2 catches the misparse family: `\v \h \R \e \a \cA \X \H \G`, `\1`/`\8` with no such group,
`\0nnn`, `]` first in a class, `[a[bc]]`, `&&`, a quantified `\Q..\E`, and `(?x)` whitespace
inside a class.

### M3: AST family detector

`JdkDivergenceDetector` goes in `reggie-codegen/.../analysis/` as a `RegexVisitor<...>` with an
explicit scope stack. It runs on the unprojected AST, combined with the parser-recorded flags.

**"Deterministic body"** below means what the JDK's `study()` keeps as `deterministic == true`
(JDK 21 `Pattern` source). An alternation (`Branch`), an optional (`Ques`, including the
POSSESSIVE type that `X?+` builds), a `Curly`/`GroupCurly` with `cmin != cmax`, and a `Loop`
make a body non-deterministic. An atomic group (`Ques` INDEPENDENT) and an exact `{n}` are
deterministic when their body is. Backrefs, lookarounds (their bodies are ignored) and
anchors keep it deterministic. The detector also treats every atomic group as deterministic;
that is conservative (it over-flags, e.g. `(?:(?>a|b)(c)){1}d|c` agrees with the JDK). The JDK builds a `GroupCurly` for every non-possessive deterministic-body
quantifier, any min and max, `{1}` included. When `GroupCurly.match` fails, it restores only its
own group; inner groups set by completed iterations stay set. `Matcher.search` clears groups
once per find, so leaked slots survive later start positions and alternatives.

| ID | Divergence | Example → JDK vs reggie | Shape | Source |
|---|---|---|---|---|
| S1 | Self-referencing backref | `^(a\1?)+$` / `aaa`: g1 JDK [1,3), reggie [2,3). `(a\1+)`: JDK no match, reggie matches | A backref (numbered or named) to group N inside group N's body | `BackrefBacktrackMatcherTest.java:180-186`; `doc/agents-fallback-and-limitations.md:140`; `doc/plans/fuzz-findings-triage.md:119-147` |
| S2a | Backed-off iteration slot seen through a backref | `(?:(a))+\1` / `aa`: g1 JDK [1,2), reggie [0,1). `(?:(a){1,2})+\1` | A backref to a capturing group strictly inside any quantifier, with no width or count condition | `LinearBackrefAnchorDifferentialTest.java:104-122`; cairn `q-jdk-capture-leaks` item 1 |
| S2b | GroupCurly keeps inner slots of backed-off iterations or failed attempts | `(?:[ab](c))+[ab]` / `acac`: g1 JDK [3,4) (outside the match), reggie [1,2). `(?:(a)b){1}c\|b`, find on `ab`: g1 JDK [0,1), reggie unset. `(?:(a)b){3}\|ab` with `match("ab")`: g1 JDK [0,1) | A capturing group inside a non-possessive quantifier with a deterministic body, other than the quantifier's direct operand (non-capturing groups are **not** unwrapped for this test: `(?:(a)){1}c\|a` / `a` leaks, JDK g1 [0,1), reggie unset; `(a){1}c\|a` agrees), when **any** of: (a) `max > min`, or (b) the quantifier is not on every successful path: it sits under an alternation branch, an optional or `min == 0` ancestor, or a lookaround; or (c) it has an ancestor quantifier with `max > 1` (greedy, lazy or possessive; possessive included conservatively; exact-count ancestors agree with the JDK, so `max > min` would suffice, but `max > 1` is kept as the conservative rule at zero corpus cost). Case (c): `(?:(?:(a)b){1}c?)+c` / `abcab`: g1 JDK [3,4) (outside the match), reggie [0,1). Negative: `(?:(a)b){2}`, `(?:(a)b){2}c` | cairn `q-new-preexisting-backref-bugs` item 15; JDK 21 probes |
| S2c | Own-group GroupCurly pin missed when the group's body is JDK-deterministic but rejected by reggie's pin condition `ThompsonBuilder.DETERMINISTIC_BODY` (`ThompsonBuilder.java:235-300`, used by `pinGroupOf` `:220-228`), which refuses every quantifier, atomic group, lookaround, backref, anchor and capturing group (reggie bug B23). After B23, the clause stays for bodies that contain a backref (bug-fix plan decision 8) | `(?:(b{1})*a)+` / `baba`: g1 JDK [0,1), reggie [2,3). `(?:((?:bc){1})*a)+` / `bcabca`: JDK [0,2), reggie [3,5). `(?:((?>b))*a)+` / `baba`: JDK [0,1), reggie [2,3). `(?:((?<=a)b)*a)+` / `ababa`: JDK [1,2), reggie [3,4). `(?:((b))*a)+` / `baba`: g1 JDK [0,1), reggie [2,3); g2 JDK [2,3). Without the inner construct they agree | A quantified capturing group under an ancestor quantifier with `max > 1`, whose body is JDK-deterministic but not accepted by `DETERMINISTIC_BODY`. `{0}` counts as an exact count. Anchors inside quantifiers are already refused natively | JDK 21 probes |
| S3 | A quantified capture group with a zero-width body stays unset in the JDK | `((?<!..))*` / `""`: g1 JDK unset, reggie [0,0) | A capturing group directly under a quantifier whose body is nullable | cairn `q-new-preexisting-backref-bugs` item 15 |
| S4 | Atomic/possessive keeps slots of a failed attempt | `(?>(a)\|(ab))*b`, find on `acb`: g1 JDK [0,1), reggie unset | A capturing group inside a `GroupNode` with `atomic == true`; possessive is covered by the `RegexParser.possessive` wrap (`RegexParser.java:217-223`) | cairn `q-jdk-capture-leaks` item 2 |
| S5 | A lookahead capture survives a failed alternation branch | `(?=(a))ab\|(a)c` / `ac`: g1 JDK "a", reggie null | A capturing group inside an `AssertionNode` | `LookaheadGroupCaptureTest.java:80-97` |
| S6 | Case-insensitive folding differs | `(?i)é` / `É`: JDK false, reggie true. `(?i)\p{Upper}` / `a`: JDK true, reggie false. `(?i)(.)\1` on `\u212Ak` (Kelvin sign, then `k`): JDK false, reggie true | Parser flag: (a) a non-ASCII code point folded, tested on the parsed code-point value, not the source text (so `(?i)\xc9` and `(?i)İ` are caught) (`literalNode`, `applyCaseInsensitiveToRanges`); (b) a case-sensitive property class under `(?i)`; (c) any backref under `(?i)` | `JdkPatternCompatibilityTest.java:129-139`; JDK 21 probes |
| S7 | Supplementary code points in the pattern | `😀+` / `😀😀`: JDK [0,4), reggie [0,2) | Any surrogate code unit in a `LiteralNode` or `CharClassNode` | JDK 21 probes; `UnicodeCharacterClassTest.java:35-38` |
| S9 | Supplementary code points in the input | `.` / `😀`: JDK [0,2), reggie [0,1). `\p{L}` / `𝐀`: JDK [0,2), reggie no match. `\p{Cs}` / `😀`: JDK no match, reggie [0,1) | Any atom whose JDK membership includes a supplementary code point or a surrogate: `.`, negated classes, `\W \S \D`, `\P{..}`, and properties/scripts/blocks with supplementary members or surrogate categories | JDK 21 probes |

### S9 treatment (user decision)

With `JDK_STRICT` + `ALLOW_JDK_FALLBACK`, an S9 pattern compiles natively and is wrapped in a
new `SurrogateGuardMatcher` (proposed here).

- **Full API override.** It overrides every public `ReggieMatcher` method listed under *Goal*.
  Each operation scans the whole input once (lookbehind can see text before `start`), then hands
  the whole operation to one engine. The inherited per-match loops are not used: they would
  rescan quadratically and could switch engines within one operation.
- **Engine choice.** With no surrogate, the native matcher runs. With one, the strict fallback
  matcher runs, i.e. a real `java.util.regex.Pattern` compiled from the normalized pattern that
  `wrapCompiled` receives, with no flags. On the flags path that pattern carries the inline
  `(?i)(?m)(?s)` prefix or `Pattern.quote`, which is equivalent to the mapped `jdkFlags`. The S9 verdict entry
  holds it and publishes it safely (holder or volatile), and every guard for that pattern shares
  it. Normally it is the M1 `Pattern` seeded on the compile miss. Only when the entry was
  recomputed without M1 (after a `clearCache()` race) is it compiled lazily, at most once per
  pattern, on the first surrogate input.
- **Wrapper order.** The guard is the outermost layer, except for `cached()` results of
  mutable strategies: after B25 these are a `PooledMatcher`, and each pooled instance holds
  its own guard. `PrefilteringMatcher` and
  `NameEnrichingMatcher` override none of the rich API, so a guard inside them would be reached
  only through the inherited per-match loops (`ReggieMatcher.java:575-760`), one full scan per
  match. The prefilter sits inside the guard, on the native side; its required literal holds in
  both engines, since M2 has already guaranteed the parses agree.
- **Cursor.** `MatchCursor` is final, binds one matcher (`MatchCursor.java:36,48-51`) and keeps
  it across `reset(String)` (`:134-143`). Proposed helper: a package-private hook on
  `ReggieMatcher`, `cursorEngineFor(String input)`, returning `this` by default. `MatchCursor`
  keeps the outer matcher (its `final matcher` field) and adds a mutable current-engine field;
  it calls the hook in its constructor and in `reset`. The guard overrides it to return the
  native matcher or the strict fallback after one scan.
- **JDK-iteration cursor mode.** Driving the strict fallback through `findMatchFrom` resets `\G`
  on every step. So when the selected engine is a strict fallback, `MatchCursor` holds one JDK
  `Matcher` for the current input and steps it with `find()`, building each `MatchResult` from
  it. `reset` re-runs the hook and recreates that `Matcher`. The strict fallback's own `cursor()`
  uses the same mode. The cursor detects a strict fallback and gets its strict `Pattern` through
  a package-private accessor (proposed here); the existing private `jdkRichDelegate()` compiles
  `pattern()` without the strict flags and is not reused.
- **Processor pool.** `PooledMatcher` does not override `cursor()`, so a cursor would bind the
  pool and rescan per `findNext`. Forwarding the pooled instance is unsafe: it goes back into the
  pool slot (`release`, `PooledMatcher.java:72-74`) while the cursor still uses it. `PooledMatcher` gets a
  `cursor` override that creates a dedicated instance (`create(factory)`, never released) and
  binds the cursor to it, so the hook runs on the guard. The override applies to every
  `PooledMatcher`, strict or not (chain fallback, hybrid NFA half, FALLBACK stubs); for those it
  costs one factory call per `cursor()`.
- **Why the fallback gate.** The input selects the engine, so a surrogate in attacker-controlled
  input forces the JDK's backtracking engine. That is why the guard needs
  `ALLOW_JDK_FALLBACK`.
- **Without fallback.** With `JDK_STRICT` alone, S9 patterns compile natively, and the javadoc
  documents their parity as BMP-input only.

Engine-wide supplementary support is a separate future plan.

### Word boundary treatment (B17)

`\b`/`\B` stay outside the M2 vetted set until B17 is fixed natively. Until then they route
through `fallbackOrThrow` under `JDK_STRICT`.

### Rich API family R

| Divergence | Evidence | Treatment |
|---|---|---|
| Replacement-string syntax | `ReggieMatcher.expandReplacement` handles only single-digit `$n` and `$$`, with a literal fast path when there is no `$`. 9 of 10 probed replacement strings diverged | Strict replacement (below) |
| `replaceAll(String, Function)` | `ReggieMatcher.replaceAll(String, Function)` (`:639`) appends the function's result literally (`:645`); the JDK expands `$`/`\` in it. `(a)` on `xa` with `r -> "$1$1"`: JDK `xaa`, reggie `x$1$1` | Strict replacement (below) |
| `\G` iteration | `\G` findAll on `ab`: JDK 1, fallback 3. `\G\x{61}?` cursor on `ab`: JDK 2, fallback 3. The inherited `findAll` and `MatchCursor.findNext` (`MatchCursor.java:80`) loop on `findMatchFrom`, and `m.find(s)` resets `\G` | The strict fallback delegates `findAll`/`split` to JDK `Matcher` iteration, and `cursor` to the JDK-iteration cursor mode (below). `\G` is outside M2, so native strict compiles never see it |

**Strict replacement (user decision: JDK semantics under strict only).** `MatchCursor.java:194-199`
already implements `java.util.regex.Matcher` replacement semantics: `\` escapes, `$n`,
`${name}`, and the JDK's exceptions. Strict replacement reuses that expander and does not add a
third. That expander is a private instance method reading `nameIndex`
(`MatchCursor.java:199`), so it is first extracted into a static helper taking the name map
(proposed here). `PrefilteringMatcher` and `NameEnrichingMatcher` do not override the rich API,
so `ReggieMatcher.replaceAll` runs on the outermost wrapper, and `PooledMatcher` forwards to its
instances. The strict bit is therefore a field on `ReggieMatcher` itself, read by the layer
that runs the rich-API loop; the strict post-step sets it (see *Runtime path*, step 4). The default keeps today's
`expandReplacement`. The bit is runtime instance state, not part of a generated class, so
`StructuralHash` is not affected.

## Strict fallback matcher

`JavaRegexFallbackMatcher` compiles with `Pattern.UNICODE_CASE`
(`JavaRegexFallbackMatcher.java:37`) to mirror reggie's native folding. Strict mode does not
depend on this flag, because it passes the M1 `Pattern` to the matcher. B34 (default mode,
sign-off) drops the flag. The matcher inherits the
rich-API methods from `ReggieMatcher`. The strict mode:

- never compiles on its own. Built in `fallbackOrThrow`, it receives the M1 `Pattern` (the
  original pattern, no flags or the mapped `jdkFlags`). Under the guard, it receives the verdict
  entry's `Pattern` (the normalized pattern, no flags). Both match the same inputs and have the
  same groups; its `ReggieMatcher.pattern()` stays the normalized string. `toJdkCompatible` is
  not applied, because M1 has already rejected `(?'n')`;
- overrides every public method listed under *Goal*, delegating to the JDK `Matcher`/`Pattern`;
- takes group names from the JDK `Pattern`, not from reggie's parse: when M2 routes a pattern
  here because the parses differ, reggie's name map can be wrong (`[^](?<n>a)]`: reggie `{n:1}`;
  the JDK reads one character class with no groups);
- is selected inside `fallbackOrThrow` itself whenever `options.has(JDK_STRICT)`. Its signature
  gains the original pattern and `jdkFlags`, so all 15 call sites change. That covers
  all 15 call sites in `compileInternal` (`:1362`, `:1405`, `:1429`, `:1443`, `:1462`, `:1473`,
  `:1497`, `:1610`, `:1625`, `:1656`, `:1684`, `:1695`, `:1758`, `:1775`, `:1785`).

**Prefilter.** `wrapCompiled` (`RuntimeCompiler.java:661-671`) wraps every result, the fallback
included, with a `PrefilterFact` from reggie's own parse. It is called at `:500`, `:507-508`,
`:516-517`, `:525`, `:538-546`, the post-compile fixups `:552`, `:560-561`, `:569-570`, `:578`,
and `:860`, `:874-875`. Bug fix B18 makes `wrapCompiled` itself return
any JDK fallback (`isJdkFallback()`) unwrapped, which covers the strict fallback too.

## Runtime path

1. **Option check** in `RuntimeCompiler.compile(String, ReggieOptions)`: the
   `CAPTURE_NAMED_ONLY` rejection.
2. **Flags entry.** `fallbackOrThrow` and `compileInternal` see only the normalized pattern
   (`(?i)…` or `Pattern.quote`). The original pattern and `jdkFlags` are threaded down to
   `compileInternal` so M1 and the strict fallback use them.
3. **In `compileInternal`** (`RuntimeCompiler.java:1351`), with `JDK_STRICT` set:
   - Run M1 before the `try` that starts at `:1356`, so its `Pattern` is in scope for the
     fallback sites inside catch blocks (`:1758`, `:1775`, `:1785`, plus the new strict routes
     for `ParseException` at `:1789` and the `[z-a]` `RuntimeException` at `:1796`), and an
     `OutOfMemoryError` thrown by M1 itself is not caught as a reggie compile failure.
     A reggie parse failure under strict goes to
     `fallbackOrThrow`. Today `ParseException` becomes a `PatternSyntaxException` at
     `:1789-1792`; under strict that conversion is skipped, because M1 has already decided
     syntax validity.
   - After the parse and deadline check (`:1358-1363`), and before the `CAPTURE_NAMED_ONLY`
     block, check M2/M3. A hit goes to `fallbackOrThrow`.
4. **Strict post-step in `wrapCompiled`.** `compileInternal`'s return value is not what callers
   get for mutable-state strategies: the post-compile fixups (`:549-579`) build fresh instances
   from `PikeVMEntry`, `BitStateEntry`, `HybridEntry` or `NfaMatcherFactory`, and later calls take
   the fast paths (`:497-526`) without entering `compileInternal`. All of these go through
   `wrapCompiled`. So `wrapCompiled` gets the options. It applies, in order: the B18 skip for JDK fallbacks, the prefilter, the
   guard wrap when the S9 verdict says so, and the strict replacement bit. At the L1 sites the
   post-step runs inside the `computeIfAbsent` mapping, so the published instance is complete.
   - **S9 verdict.** A `STRICT_VERDICT_CACHE` (proposed here) copies the `LITERAL_CACHE` pattern
     (`:665-666`): keyed by the normalized pattern, which already encodes the flags,
     `computeIfAbsent(pattern, …)` parses and runs the S9 check on a miss, and
     `clearCache()` (`:885`) clears it. `PATTERN_CACHE` (`:109`) has no eviction and drops the
     mutable strategies right after their first compile (`:551`, `:559`, `:568`, `:577`), so the
     verdict must not be tied to it. Computing on a miss needs no ordering contract with
     `compileInternal` and has no `clearCache` race; it re-parses only on a miss.
     For a guarded pattern the entry also holds the JDK `Pattern` shared by all guards of that
     pattern. **Seeding:** when the S9 check hits in `compileInternal`, it does
     `putIfAbsent(pattern, verdict)` with the verdict from its own parse and the M1 `Pattern`.
     The `wrapCompiled` post-step in the same mapping then gets a cache hit. The seeded `Pattern`
     comes from the original pattern + `jdkFlags`, which matches the same inputs and has the same
     groups as the normalized form. Seeding is only an optimization: if
     `clearCache()` runs in between, `computeIfAbsent` recomputes the verdict and the `Pattern`
     is compiled lazily, so correctness never depends on the order.
   - **Replacement bit.** The bit is read by whichever layer runs the inherited rich-API loop. The
     post-step sets it on the outermost layer and, under a guard, also on the guard's native child
     (the outermost native layer, usually `PrefilteringMatcher`), because the guard hands the whole
     replace operation to that child. On the fallback side the strict fallback delegates to JDK
     `Matcher` replacement directly.

```java
if (options.has(ReggieOption.JDK_STRICT)) {
  String reason = JdkDivergenceDetector.divergence(ast, parser);
  if (reason != null) {
    return fallbackOrThrow(pattern, "JDK_STRICT: " + reason, nameMap, options);
  }
}
```

**Entry coverage**:

- Every public runtime entry reaches this `compileInternal`: `Reggie.compile*`, `cached*`,
  `compileAllowingFallback`, and the flags path (`:451-462` → `normalizePatternFlags`
  `:1130` → `:543`).
- These entries bypass it: `compilePikeVm` (`:787`), `compileBackrefBacktrack` (`:821`),
  `createChainFallback` (`:741`) and `ReggieCompiledPattern.tryCompile`. They take no options,
  and only non-strict processor stubs reach them.

## Processor path

Under `JDK_STRICT` the processor generates **no native class**. Every strict-annotated method
gets a stub that compiles at runtime through the strict runtime pipeline. Reasons:

- **Version skew.** Parity is defined against the runtime JDK. Reggie bakes
  `Character`-derived sets into generated code at annotation time (`CharSet.java:256-259`,
  `:292`, `:568`), and the build JDK's Unicode version can differ from the runtime's (JDK 21
  has Unicode 15.0, JDK 26 has Unicode 17.0, and JDK 26 also changed `(?iu)` folding).
- **One code path.** S9 guarding, strict replacement and strict fallback selection then exist
  only in the runtime.

Implementation:

- `RegexPatternProcessor` reads `JDK_STRICT` from `options()`, applies the
  `CAPTURE_NAMED_ONLY` rejection, and runs advisory M1 (a JDK syntax error becomes a build
  ERROR).
- `resolveRealization` gets a new `jdkStrict` parameter and a new realization,
  `DELEGATE_STRICT`, returned when it is set. The check runs **before** the parse (`ReggieMatcherBytecodeGenerator.java:134-135`), because
  JDK-valid patterns reggie cannot parse (`\x{1F600}`, `(?x)( ?:a)`) must still get a stub. The
  processor catches only `UnsupportedOperationException` (`RegexPatternProcessor.java:207-212`).
  So the advisory M2/M3 step treats a reggie parse failure as "needs fallback", not as a generic
  build failure.
- Its stub has the `FALLBACK` stub shape (`ImplClassBytecodeGenerator.java:278-300`, a
  `PooledMatcher` over a bound method handle), bound to a new static helper (proposed here):
  `Reggie.compileJdkStrict(String pattern, boolean allowFallback)`. It calls
  `compile(pattern, JDK_STRICT [| ALLOW_JDK_FALLBACK])`. The stub binds the `boolean` with
  `insertArguments` and a boxed `Boolean.TRUE`/`FALSE`.
- Without fallback, a pattern the runtime detector would refuse fails on first use, not at
  build time. The processor also runs the shared detector (M2/M3) at annotation time and
  reports a hit as a build ERROR when fallback is not allowed, so most refusals surface early.
  A runtime refusal is then possible only through build/runtime JDK skew. The `PooledMatcher`
  constructor compiles eagerly (`PooledMatcher.java:42-44`) inside the generated synchronized
  lazy-init accessor (`ImplClassBytecodeGenerator.java:216-324`); the field stays null after a
  throw, so such a refusal is thrown again on every accessor call. The javadoc documents this.
- Cost: strict processor matchers compile at first use, like any runtime compile.
  Non-strict annotations are unchanged.

## Prerequisite bug fixes (default mode, bug policy)

IDs are stable: B20 was folded into the strict replacement design, and
the former S8 (JDK-rejected syntax) became M1. Each is fixed on its own, with a regression test. Until a fix lands, M2 or the listed mechanism
flags its shape under `JDK_STRICT`. A construct joins the M2 vetted set once fixed. Any fix that
changes documented reggie semantics instead of fixing a bug goes back to the user first.

| Bug | Example → JDK vs reggie |
|---|---|
| B1: `\v`, `\h`, `\R` parsed as literal letters | `\v` matches `v`; `\h` misses U+00A0; `\R` misses `\r\n` |
| B2: octal `\0nnn` truncated | `\0101`: reggie `\010` then `1`; JDK `A` |
| B3: `(a\|)+` and `(\|a)+` return null | find on `b` and `match("")`: JDK [0,0), g1 [0,0) |
| B4: `toReggieFlags(pattern, CASE_INSENSITIVE)` checks only that the source is ASCII (`JdkPatternCompatibility.java:61`), and maps CI \| `UNICODE_CASE` although the JDK then folds `i`, `k`, `s` to non-ASCII partners and folds non-ASCII cased characters (µ, Ⓐ, ß as the fold of ẞ) that reggie does not | `"\\u00e9"` + CASE_INSENSITIVE on `É`: JDK false, reggie true. `[^a-z]` + CI \| `UNICODE_CASE` on `\u212A`: JDK false, reggie true. `(?i)k` + `UNICODE_CASE` on `\u212A`: JDK true, reggie false |
| B5: split on an empty-string pattern | `SplitWithLimitTest.java:184-215` (`@Disabled`) |
| B6: `]` first in a class closes it | `[]a]` / `a`, `[^]a]` / `b`: JDK true, reggie false |
| B7: no class union or intersection | `[a[bc]]` / `b`: JDK true, reggie false. `[a&&b]` / `&`: JDK false, reggie true |
| B8: a quantifier after `\Q..\E` applies to the whole quote | `\Qab\E+` / `abb`: JDK true, reggie false |
| B9: reggie keeps whitespace inside a class under `(?x)`; the JDK skips it | `(?x)[ a]` / ` `: JDK false, reggie true |
| B10: escapes `\e`, `\a`, `\cA`, `\X`, `\H`, `\G` | `\Ga` / `Ga`: JDK false, reggie true |
| B11: `\1`/`\8` with no such group | `\1` / U+0001, `\8` / `8`: JDK false, reggie true |
| B12: `(?i)` backref folds non-ASCII and Kelvin; `(?iu)` backrefs are not refused | `(?i)(.)\1` on `\u212Ak` (Kelvin sign, then `k`): JDK false, reggie true. `(?iu)(.)\1` on `\u212Ak`: JDK true, reggie false |
| B13: RECURSIVE_DESCENT does not backtrack into a referenced group's alternation | `(a\|ab)\1+` / `abab`: JDK g1 [0,2), reggie no match |
| B14: fallback `findAll`/`split` reset `\G` | `\G` findAll on `ab`: JDK 1, fallback 3 |
| B15: `[z-a]` throws a `RuntimeException`, not a `PatternSyntaxException` | — |
| B16: `(?i)` property classes not folded consistently | `(?i)\p{Upper}` / `a`: JDK true, reggie false; `[\p{Lu}]` folded, bare `\p{Lu}` not; `(?i)\p{IsUppercase}` throws |
| B17: `\b`/`\B` ignore the JDK's non-spacing-mark rule (`Pattern.Bound.check` `hasBaseCharacter`) | `\b` find on `a\u0301` (combining acute): JDK 0 and 2, reggie 0 and 1. `a\b` / `a\u0301`: JDK no match, reggie [0,1) |
| B18: `wrapCompiled` puts a prefilter built from reggie's parse in front of the JDK fallback | `(?:(a)\|b)+\1\v` find on `aa\u000B` with `compileAllowingFallback`: JDK true, reggie false |
| B19: `cached(key, p, options)` passes the bare pattern as `cacheKey` (`RuntimeCompiler.java:874` → `:953-956`), so option-specific engines fill default-keyed caches | `cached("k", "(?:(a)\|(?<n>b))+[ab]", namedOnly)`, then `Reggie.compile` of the same pattern on `abab`: g1 -1; a fresh compile gives [2,3). Also `cached("k2", "(a)b", DEFAULT)` then `cached("k2", "(a)b", namedOnly)` returns the same instance. Fix: pass `cacheKeyFor(pattern, options)` down, and compare options for an existing key |
| B21: a `(?x)` `#` comment ends only at `\n`; the JDK ends it at any line terminator | `(?x)a#c\rb` / `ab`: JDK [0,2), reggie [0,1). `(?x)a#c\u0085b` / `ab`: JDK no match, reggie [0,1) |
| B22: DFA_UNROLLED_WITH_GROUPS sets captures of an alternative not taken | `(?:a\|(a))[ab]` / `aa`: JDK g1 unset, reggie [0,1). `(?:(b)\|(b))b` / `bb`: JDK g2 unset, reggie [0,1) |
| B23: own-group pin emulation (`DETERMINISTIC_BODY`) rejects JDK-deterministic bodies: exact-count quantifiers and atomic groups with deterministic bodies, lookarounds, nested captures. Backref bodies stay unpinned (bug-fix plan decision 8) | see S2c |
| B24: SPECIALIZED_FIXED_SEQUENCE loses or truncates nested group spans | `((a))c` / `ac`: JDK g1 [0,1), reggie unset. `(b(ab))b` / `babb`: JDK g1 [0,3), reggie [0,1) |
| B26: the GroupCurly pin fires inside an atomic group when the quantifier is not the atomic group's direct body; `repeatDepth` is reset for possessive (`ThompsonBuilder.java:202`, in the uncommitted work on this branch) and lookaround (`:696-699`) but not for atomic groups. S4 flags the shape under strict | `(?:(?>(b)*c)a)+` / `bcabca`: JDK g1 [3,4), reggie [0,1) |
| B25 (`cached()` returns one instance for repeated calls; concurrency probe in phase 1): `cached()` runs none of the post-compile fixups (`:860`, `:874-875` vs `:549-579`), so a mutable-strategy instance from `compileInternal` is stored under the user key and shared across threads | code reading only; phase 1 adds a concurrent probe |
| B27: `cached()` and `compile()` share the `PATTERN_CACHE` namespace | `cached("\\d+", "[a-z]+")`, then `compile("\\d+")` returns the `[a-z]+` matcher |
| B28: `cached()` skips the total compile deadline (`RuntimeCompiler.java:480-485`) | code reading |
| B29: retired; the suspected `compile()`/`clearCache()` race was not shown | — |
| B30: `(?x)` skips any `Character.isWhitespace` char; the JDK skips only `[\t\n\x0B\f\r ]` | `(?x)a\u001Cb` / `a\u001Cb`: JDK true, reggie false |
| B31: whitespace-only `(?x)` alternatives throw `ParseException` | `(?x)a\| ` and `(?x)(a\| )b`: JDK compiles, reggie throws |
| B32: group counters ignore `(?x)` comments | `(?x)a#(\n`: JDK 0 groups, reggie 1 |
| B33: `(?i)` backrefs compare case-sensitively on several routes | `(?i)(\w+)-\1` / `ab-AB`: JDK [0,5), reggie no match |
| B34: the JDK fallback adds `UNICODE_CASE` | `(?i)k` / U+212A: native no match, fallback match |
| B35: merged into B36 | — |
| B36: routes that ignore own-group pins (RECURSIVE_DESCENT, generated LOOKAROUND/BACKREFS code) | reachability checked in phase 1 |
| B37: a parse exception in the processor fails the whole annotated class, and `UnsupportedPatternException` is not mapped to the fallback | `(?iu)k` with `ALLOW_JDK_FALLBACK`: runtime falls back, processor fails the build |
| B38: the `(?iu)`/`(?iU)` refusal in `literalNode` covers letters only | `(?iu)\u24b6` on `\u24d0`: JDK true, reggie compiles natively and gives false |
| B39: `(?i)` drops a titlecase literal itself | `(?i)\u01c5` on `\u01c5`: JDK true, reggie false |

## Fuzz findings

The default window 25,001–50,000 has a budget of 37
(`reggie-integration-tests/.../AlgorithmicFuzzTest.java:49-71`). `doc/fuzz/2026-06-29.md`
clusters 34 findings of the earlier corpus into E1–E6. The 34→37 shift after the corpus change
is not classified.

## Not in v1 (recorded alternative)

Native emulation of S2/S4 inside `BackrefBacktrackMatcher`, keeping slots of failed attempts as
the JDK does. It would make `JDK_STRICT` usable without `ALLOW_JDK_FALLBACK` for these families.
The cost is a second slot semantic in the memoized DFS, whose memo key would then have to
include the leaked slots.

## Phases

1. **Reproduce and pin down.**
   - Write `JdkStrictDivergenceTest`, an in-project JUnit differential test: one row per S, R
     and B example; each must fail before the work.
   - Add regression rows that already agree, for example `(?i)k` / U+212A.
   - Probe S3 `{n}` forms. List the `ReggieFlags` → JDK flag mapping.
   - Measure the M1 overhead with a JMH benchmark in `reggie-benchmark`: compile-miss time and
     allocation (`-prof gc`) with and without `JDK_STRICT` over the logs-backend corpus
     (`reggie-benchmark/src/main/resources/corpus/logs-backend-patterns.tsv`, loaded with the
     TSV unescape step as in `RealCorpusScanBenchmark.java:103`). Production compiles with
     `ReggieFlags.DOTALL`, so the strict lane goes through the flags path. "Compile miss" means
     `clearCache()` per operation, which also clears `STRUCTURE_CACHE`. Without it the second
     lane would get structure-cache hits from the first and look cheaper. The
     result goes into *Cost and risk*. If M1 dominates the strict compile-miss cost, the user
     decides whether to optimize it before phase 3.
2. **Prerequisite bugs B1–B39** (bug policy), each with a regression test. The fix plan is
   `doc/plans/jdk-strict-bug-fixes.md`.
3. **Strict mechanisms, runtime.**
   - Build M1 and M2 (parser helpers, proposed here) and M3, plus:
     - the strict fallback mode with full API overrides and strict selection in
       `fallbackOrThrow`;
     - `SurrogateGuardMatcher`;
     - the strict replacement bit, reusing the `MatchCursor` expander;
     - the `CAPTURE_NAMED_ONLY` rejection.
   - Write `JdkDivergenceDetectorTest`, with positive and negative cases per family.
     - Negatives: `(a)+`, `(?:ab)+`, `(?:(a)b){2}`, `(?:(a)b){2}c`, `(?:(a)b)?`,
       `(?:(a+)b)+ab`, `(?>a+)b`, `(?=a)a`, `(a)\1`, ASCII `(?i)abc`.
     - Positives include `(?:(a)b){1}c|b` and `(?:(a)b){3}|ab`.
   - Differential checks:
     - strict plus fallback: every row agrees with plain JDK;
     - strict alone: every row throws, and M1 rows throw `PatternSyntaxException` either way.
   - Mutation check: disabling each family clause fails its rows.
4. **Fuzz under JDK_STRICT.**
   - Add an `AlgorithmicFuzzTest` mode with `JDK_STRICT` + `ALLOW_JDK_FALLBACK` over the
     25,001–50,000 window. The input alphabet adds non-spacing marks (U+0301), line terminators
     (`\r`, `\n`, U+0085, U+2028) and one surrogate pair.
   - Run every API from *Goal* against the JDK.
   - Assert 0 divergences, plus an upper bound on the share of patterns sent to the strict
     fallback (how much the detector over-flags). The bound is set from the first run and
     agreed with the user.
   - Do not compare finding counts with the default mode: compile rejects shift both RNGs
     (`AlgorithmicFuzzTest.java:62-69`).
   - Measure the same share on the logs-backend corpus.
   - Classify each finding: a reggie bug → fix it; a new quirk family → add a clause and a
     table row.
5. **Processor.**
   - Read `JDK_STRICT` in `options()`; add `DELEGATE_STRICT`, its stub,
     `Reggie.compileJdkStrict`, and the advisory M1/M2/M3 build errors.
   - Processor tests assert runtime results against plain JDK:
     - an S4 pattern fails the build without fallback, and returns JDK spans with it;
     - a PCRE-only pattern fails the build with the JDK syntax error;
     - a strict replacement gives the JDK result.
6. **Docs.**
   - Add a `JDK_STRICT` row in `doc/agents-fallback-and-limitations.md` and an AGENTS.md
     pointer.
   - Cover the mechanisms, the limit of the guarantee, the S9 treatment and its ReDoS caveat,
     the replacement semantics, runtime-JDK parity, and the option interactions.

## Cost and risk

- **Compile time:** one `Pattern.compile` (M1) and an extra AST walk, only with the option set
  and only on a compile miss. A compile that throws (M1 rejection, strict refusal) caches
  nothing, so every retry is a miss, as parse errors are today. The M1 `Pattern` is reused by the strict fallback, so routed
  patterns pay one JDK compile, not two. For natively compiled patterns its JDK node graph is
  garbage once M1 returns. S9-guarded patterns reuse the M1 `Pattern` through the seeded verdict
  entry; a second JDK compile happens only after a `clearCache()` race, at most once per pattern. The size of the M1 cost compared with a reggie compile
  miss is not measured yet; phase 1 measures it. Strict processor matchers compile at first use.
- **Memory:** `STRICT_VERDICT_CACHE` is unbounded and cleared only by `clearCache()`, like
  `LITERAL_CACHE` (`:665-666`, `:885-894`). Because of seeding, it keeps one JDK `Pattern` per
  S9-guarded pattern from its first compile, whether or not a surrogate input ever appears, and
  even after every matcher for that pattern is gone. This matches
  `PATTERN_CACHE`, which keeps fallback matchers forever. The holder may be a `SoftReference`,
  matching the DFA-bundle retention policy of #140.
- **Runtime:** no cost for accepted non-S9 patterns, which run on the same engines. S9 patterns
  with fallback pay one O(n) surrogate scan per operation.
- **Over-flagging:** on the logs-backend corpus (528 entries, 527 parsed), S2 without a
  width condition flagged 84, excluding `max == 1` flagged 15, and the deterministic-body
  condition flagged 2. The S2b case (b) and the `\b` exclusion from M2 raise
  this. Phase 4 re-measures it.
- **Coverage:** parity holds only for known families plus M1/M2. Phase-4 classification is
  part of the definition of done.

## Out of scope

- The PCRE-semantics fast option (separate plan).
- Changing default results beyond the bug fixes B1–B39.
- Native emulation of the leak families (recorded alternative above).
- Engine-wide supplementary code point support.
