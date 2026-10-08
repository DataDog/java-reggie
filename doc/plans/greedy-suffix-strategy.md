# Greedy-group + suffix strategy (GREEDY_BACKTRACK replacement)

## Problem

Patterns of the shape `P(.*)S` / `P(.+)S` route to RECURSIVE_DESCENT, BITSTATE_CAPTURE or
DETERMINISTIC_CHAIN_BYTECODE since GREEDY_BACKTRACK was removed. These routes are correct but
slow. `GreedySuffixBenchmark` on workspace-jb (JDK 26), LONG input (~1.1K chars), ops/ms:

| pattern | findMatch old GB | findMatch now | JDK |
|---|---|---|---|
| `(.*)bar` | 18878 | 6 | 301 |
| `(.*)(\d+)` | 2626 | 3 | 336 |
| `(.*)(\d+)$` | 2657 | 3 | 316 |
| `(.*)\b(\d+)` | 2621 | 109 | 774 |

The old GREEDY_BACKTRACK generator (2957 lines, one hand-written code path per suffix type × API
method) was fast but wrong in most shapes. A JDK-differential probe over 20 patterns × 32 inputs
gave 204 mismatches on the pre-removal tree.

| Failure class | Example | Cause |
|---|---|---|
| `.` treated as matching `\n` | `(.*)(\d+)` on `"a\n12"` | greedy run not bounded by the `.` charset |
| find start not leftmost, no give-back | `(.+)(\d+)` on `"12"` find=false | forward `indexOf` scan to the first suffix char |
| `.+` lower bound lost | `(.+)(\d*)` | min=1 not enforced during give-back |
| `$` ignored in find | `(.*)ab$` find | anchor checked only in matches() |
| single char-class suffix | `(.*)\d` never matches | CHAR_CLASS path broken |
| matches() vs match() disagree | `(.+)(\d+)` on `"1"` | separate code paths per method |

`GreedyBacktrackInfo.structuralHashCode()` also hashed `prefix.size()` instead of the prefix
content. This never surfaced because `StructuralHash.compute` also mixes in
`nfa.contentHashCode()` (`StructuralHash.java:104`), but the new info class must hash by value.

## Goal

A new strategy for the same shapes. It must be correct by construction, have a single search
algorithm shared by all API methods, run in O(n) per call, and run at or near old-GB speed on the
cases the old generator got right.

## Phase 0: `.` excludes every JDK line terminator

Without `UNIX_LINES`, JDK `.` excludes `\n`, `\r`, U+0085, U+2028 and U+2029. The parser maps `.`
to `CharSet.ANY_EXCEPT_NEWLINE` (`RegexParser.java:243`), which today excludes only `\n`
(`CharSet.java:181`). As a result, `a.b` matches `"a\rb"` and `"a\u2028b"` in reggie but not in the
JDK. Reggie has no `UNIX_LINES` (`(?d)`) support, so nothing needs the `\n`-only set.

Changes:

1. `CharSet.ANY_EXCEPT_NEWLINE` becomes `ANY` minus the five terminators. The name is kept, so
   the `equals` checks in `PatternCategorizer:282`, `PatternAnalyzer:10170` and
   `FallbackPatternDetector:1748` keep recognising `.`. Its Javadoc is updated.
2. `CharSet.isAnyChar()` (`CharSet.java:660`) hard-codes the 2-range `[\0-\t][\x0B-\uFFFF]` shape.
   It becomes `equals(ANY)`. This takes non-dotall `.` off the lookahead fast paths in
   `NFABytecodeGenerator` (`:7461`, `:7701`, `:7707`). Those paths never test line terminators:
   today `(?=.*a)(?=.*b).{3,}` `matches("a\nbc")` is `true` in reggie and `false` in the JDK.
   The MultiGroupGreedy guard at `PatternAnalyzer:9199` gets an explicit
   `|| charset.equals(CharSet.ANY_EXCEPT_NEWLINE)`, so its routing for `.` stays as it is. The
   comment at `PikeVMMatcher:1820` is updated; `:1822` also requires `contains('\n')`, so its
   behaviour does not change.
3. `LinearTokenSequenceMatcher.consumeToEndExceptNewline` (`:655`) stops at any of the five
   terminators, not only `\n`.
4. Multiline `^` and `$` recognise only `\n` as a line end. In the JDK, a lone `\r`, U+0085,
   U+2028 and U+2029 also end a line, and `\r\n` counts as one line end. Today `.` consumes `\r`,
   so `(?m)^.+$` on `"foo\r\nbar"` gives the right boolean with a wrong span. After changes 1-3,
   `.` stops at `\r` and `$` does not fire there, so find returns `false` where the JDK returns
   `true`. The multiline anchor tests must therefore use the JDK line-terminator set in the same
   phase. Sites:
   - `OnePassBytecodeGenerator:612-642`, `:1013-1028`
   - `LinearPatternBytecodeGenerator:694-748`
   - `RecursiveDescentBytecodeGenerator:4050-4102`
   - `DFASwitchBytecodeGenerator:1396-1411`
   - `DFAUnrolledBytecodeGenerator:1080-1095`, `:4169-4193`
   - `NFABytecodeGenerator:1682-1690`, `:1746-1753`, `:3770-3778`, `:3876-3884`
   - `BitStateBytecodeGenerator` multiline findFrom (`:748-772`, `:827-862`)
   - `BackrefBacktrackMatcher:891-893`
   - `PikeVMMatcher:299`, `:632`, `:663`, `:1487-1489`
   - `LaurikariCaptureNfaStep:492-502`, `LaurikariDfaMatcher:320-329`, `LaurikariDFACache:384`
   - `DeterministicChainBytecodeGenerator` multiline `$` (no `\r\n` rule)

   The rules live in one runtime class, `LineTerminators` (`isLineTerminator`, `isMultilineStart`,
   `isMultilineEnd`). Runtime matchers call it directly. Generated code calls it with
   `INVOKESTATIC` through `BytecodeUtil.invokeMultilineStart`/`invokeMultilineEnd`, by internal
   name, because `reggie-codegen` does not depend on `reggie-runtime`.

   Two more sites need more than a predicate swap:
   - The DFA generators' empty-match-at-end path returned `tryPos` without checking the start
     state's acceptance anchor conditions, so `(?m)^` matched at the end of input. It now runs
     `emitAcceptanceAnchorChecks` first (`DFASwitchBytecodeGenerator`,
     `DFAUnrolledBytecodeGenerator`).
   - PikeVM's lazy DFAs and the Laurikari engine model `(?m)^` by re-injecting the `^`-crossing
     start closure after each consumed terminator. That model cannot express "never at the end"
     or "not between `\r` and `\n`". `PikeVMMatcher.multilineReinjectExact` declines both
     engines when a state reachable only through `(?m)^` accepts or consumes `\n`; those patterns
     use the PikeVM thread simulation. Otherwise the re-injection triggers on any terminator.
     `LaurikariDFACache` gets a fourth lookahead class (terminators other than `\n`), so the cached
     `END_MULTILINE` outcome stays a function of (consumed char, lookahead class).
5. `SPECIALIZED_LITERAL_LOOKAHEADS` `matches()` ran the literal `indexOf` checks and then matched
   only the main pattern, without the lookaheads. It now treats the `indexOf` checks as necessary
   conditions and runs the full NFA (`NFABytecodeGenerator`).
6. Non-multiline `$` and `\Z` match at the end, before a final line terminator at `end - 1` (not
   the `\n` of a `\r\n`), or before a final `\r\n` at `end - 2`. Four sites tested only `\n` or
   missed the `\r\n` rule: `LinearPatternBytecodeGenerator` END, the `NFABytecodeGenerator`
   per-config closure, `DeterministicChainBytecodeGenerator.emitEndAnchorCheck` and
   `BackrefBacktrackMatcher.checkAnchor`. They call `LineTerminators.isEnd` (generated code via
   `BytecodeUtil.invokeEnd`).
7. A pattern where only some paths start with `(?m)^`, such as `(?m)(^a|b)`, must not limit find
   to line starts. `DFAUnrolledBytecodeGenerator` now uses `NFA.requiresMultilineStartAnchor()`
   for that filter; its per-transition entry guards enforce the `^` on the paths that have it.
   `DFASwitchBytecodeGenerator` has no per-transition guards, so `PatternAnalyzer` no longer routes
   such patterns to the DFA_SWITCH strategies (`hasPartialMultilineStart`).
8. `OnePassBytecodeGenerator` emits `new HybridMatcher.OffsetMatchResult(...)`. The constructor
   was package-private, so processor-generated ONEPASS_NFA classes, which live in the user's
   package, threw `IllegalAccessError` from `findMatchFrom`. The constructor is now public.

Every other generator tests `.` through the charset ranges, so it follows the new set. The new set
has 5 ranges instead of 2; no generator's range-count threshold is crossed. A user-written `[^\n]`
no longer equals `ANY_EXCEPT_NEWLINE`, so it loses the `.`-specific recognitions at
`PatternCategorizer:282`, `PatternAnalyzer:10170` and `FallbackPatternDetector:1748`. That is a
routing and speed change, not a correctness change.

Verification:

- `DotLineTerminatorTest`: a JDK differential on inputs containing each terminator and `\r\n`. It
  compares matches, find and findMatch spans, and asserts each pattern's route. Patterns, by route:
  - DFA_UNROLLED / STATELESS_LOOP / RECURSIVE_DESCENT / BITSTATE_CAPTURE: `a.b`, `.*`, `.+`,
    `(.*)x`, `x.*$`, `(.+)(\d+)`, `.{2,3}`, `a.*b`, `(?s)a.b`;
  - DETERMINISTIC_CHAIN: `^(.*)$`, `a.+?b`; ONEPASS: `(.)(.)`; SUFFIX_SEQUENCE: `.*$`;
    DFA_UNROLLED_WITH_GROUPS: `(a.)+`; DFA_UNROLLED_WITH_ASSERTIONS: `(?<=a.)b`;
    MULTI_GROUP_GREEDY: `x(.*)`;
  - former `isAnyChar` fast paths: `(?=.*a)(?=.*b).{3,}`, `(?=.*\d).+`;
  - the `:9199` guard: `(.+)\r\n(\w+)`, `(.+)\n(\d+)`;
  - multiline: `(?m)^.+$`, `(?m)^(.*)$`, `(?m)^\w+`, `(?m)\d$`, plus `(?m)^`/`(?m)$` shapes that
    can match empty or consume a terminator, and DETERMINISTIC_CHAIN `(?m)a[\r]+?$`;
  - partial `(?m)^`: `(?m)(^a|b)`, plus `(?m)^abcdefghijklmnopqrstuvwxyz|b` and
    `(?m)(?:^a|b)\d{0,25}` (DFA_SWITCH-sized, now OPTIMIZED_NFA);
  - non-multiline `$`: `a.+?$`, `(\w)\1$` (LINEAR_BACKREFERENCE), `(a|b)\1$`
    (OPTIMIZED_NFA_WITH_BACKREFS).
- `MultilineEngineDifferentialTest`: the same comparison on PikeVM, Laurikari (when eligible) and
  BackrefBacktrack, built directly from the NFA so that routing does not hide them.
- `LiteralLookaheadsDifferentialTest`: JDK differential for `SPECIALIZED_LITERAL_LOOKAHEADS`.
- LinearTokenSequence: the same comparison through `RuntimeCompiler`'s
  `tryCompile*LinearTokenSequence` entry points (`:1785`, `:1838`), which plain compile does not
  reach.
- `DotLineTerminatorProcessorTest`: an `@RegexPattern` subset of the patterns above, compiled
  through the annotation processor.
- The full test suite, to find tests that encoded the old behaviour. Each such test is fixed
  against the JDK, not to keep the old answer.
- Benchmark: `StrategyBenchmark` and `AllStrategyVsJdkBenchmark` rows with `.` and `(?m)`, base vs
  mod, to measure the range-test cost.

## Pre-existing gaps this strategy inherits (not fixed here)

1. **Surrogate pairs.** JDK gives back per code point; reggie engines work per `char`.
   `(.*)([^a])` on `"x😀"` gives JDK g1=`[0,1)`, reggie g1=`[0,2)` on the current tree.

The correctness claim of this plan is therefore: identical to `java.util.regex` for every accepted
pattern, on input without surrogates.

## Accepted shape (v1)

`P · (G{m,}) · S` at the top level. The detector flattens nested non-capturing `ConcatNode`s
(e.g. from `\Q..\E`) and does not unwrap any other group.

- **P (prefix)**: zero or more elements, each a `LiteralNode` or `CharClassNode`. Width `w = |P|`.
- **Greedy group**: the only capturing group before S. It contains a greedy `QuantifierNode` over a
  `CharClassNode` with charset `G`, `min = m ∈ {0,1}`, `max = ∞`. `G` is `CharSet.ANY` or
  `CharSet.ANY_EXCEPT_NEWLINE`. The algorithm does not depend on this; the restriction keeps the
  routing change to the measured shapes.
- **S (suffix)**: `[\b]? T [END]?` where `T` is one of:
  - a non-empty fixed sequence of `LiteralNode`/`CharClassNode`, not captured (`(.*)bar`, `(.*)\d`);
  - one capturing group over a greedy `C{k,max}`, `C` a char class, `0 ≤ k`, `max` finite or ∞
    (`(.*)(\d+)`, `(.*)(\d*)`, `(.*)(\d{2,})`).
  - `END` is non-multiline `$` (`AnchorNode.Type.END`, `multiline == false`), `\Z`
    (`STRING_END`) or `\z` (`STRING_END_ABSOLUTE`).
- Flags need no check. The parser already applies case folding to the charsets
  (`RegexParser.java:968-980`) and applies `(?s)` to `.` (`:243`). Global modifiers become epsilon
  nodes that are filtered out (`:88-104`). The algorithm works on any charset.

**Declined** (detector returns null): backreferences; lookaround; lazy quantifiers; atomic groups
(this includes possessive quantifiers, `RegexParser.java:127-136`); alternation; `^`, `\A`, `\B`,
`\K`; multiline `$`; more than two capturing groups; any group in P; a `max`-bounded or `min > 1`
greedy group; a captured `C{k,max}` followed by END where `C` contains a line terminator (`\n`,
`\r`, U+0085, U+2028, U+2029) — for example `\s`, `\D`, `[^a]`.

Group numbering is fixed by the shape: greedy group = 1; captured `T` = 2.

## Semantics (leftmost-first, matches JDK)

Notation: input `s`, end `n` (region end for bounded methods). For a start `st`:

1. P must match at `st` (fixed width, deterministic). Let `a = st + w`.
2. Greedy run end `R = max r` such that `s[a..r) ⊆ G`.
3. The backtracking engine tries greedy counts from longest to shortest, so it tries
   `q = R, R-1, …, a+m`. For each `q`, S is tried with its own preference order:
   - `\b` must hold at `q`.
   - Fixed `T`: must match at `q`; end `e = q + |T|`.
   - Captured `C{k,max}`: `c = min(run of C from q, max)`; requires `c ≥ k`. Without END, S
     succeeds with `e = q + c` (greedy, nothing after it can fail). With END, S succeeds iff END
     holds at `q + c` (proof below).
   - END, when present, is checked at `e` with the JDK `Dollar` rules that
     `RecursiveDescentBytecodeGenerator.java:4108-4280` already emits: `pos==n`; `pos==n-1` before a
     lone `\n` (CRLF guard), `\r`, U+0085, U+2028 or U+2029; `pos==n-2` before `\r\n`. `\Z` behaves
     like non-multiline `$`; `\z` is `pos==n`.
   - matches()/match(): additionally `e == n`.
4. The first `q` that succeeds gives the match `[st, e)`, group 1 `[a, q)`, group 2 (if captured)
   `[q, e)`.

**Proof for END + captured `C{k,max}`.** Suppose S fails with `e = q + c` and some shorter
`j < c` succeeds. Then `s[q+j]` is in `C`, and END at `q+j < n` needs `s[q+j]` to start a final line
terminator. The declined list forbids line terminators in `C`, so this cannot happen. So checking
END only at `q + c` is exact.

The restriction is conservative. A real divergence without it needs a capped `c` that ends between
`\r` and `\n`, e.g. `(.*)(\s?)$` on `"x\r\n"` under JDK `.` semantics (JDK g2 = `[1,1)`).

## Search algorithm (one routine for all APIs)

```
search(s, from, n, anchoredStart, requireEnd):
  st = from
  while st + w + m + minSuffixWidth <= n:
    if !P matches at st: if anchoredStart return FAIL; st++; continue
    a = st + w
    R = a; while R < n && G(s[R]): R++               // greedy run
    for q = R down to a + m:                          // give back
      if S succeeds at q with end e:
        if requireEnd && e != n: return FAIL          // see "requireEnd shortcut"
        return (st, q)
    if anchoredStart return FAIL
    st = max(st + 1, R - w + 1)                       // skip rule
  return FAIL
```

- **Skip rule.** If no `q ∈ [a+m, R]` succeeds, any later start `st'` with `a' ≤ R` has the same
  run end `R` (the run from `a` covers `[a, R)` ⊆ G). Its candidate range `[a'+m, R]` is a subset
  of the range that already failed. The first start that can differ has `a' ≥ R + 1`, i.e.
  `st' = R - w + 1`.
- **requireEnd shortcut.** The first `q` (from the top) where S succeeds is the one the backtracking
  engine commits to only in find. In matches it continues to smaller `q` when `e != n`. Under the
  accepted shape no smaller `q` can reach `e == n` either:
  - Fixed `T`: `e = q + |T|` falls as `q` falls.
  - Captured `C{k,max}`, no END: `e = q + c(q)`. If the run of `C` from `q` stops before `n`, it stops
    there for every smaller `q` too, or earlier because of the `max` cap.
  - Under requireEnd the END check is redundant, because `e == n` implies END, so it is skipped.

  So returning FAIL at the first successful `q` with `e != n` is exact. The exhaustive oracle
  checks this.
- **Result.** `search` returns `(st, q)` packed into a `long`, or `-1L` for FAIL. Callers that
  need `e` recompute it: `q + |T|`, or `q + min(runC(q), max)` — an O(c) scan, done once per
  reported match. No per-call allocation, no instance state.
- **Complexity.** The P test costs O(w) per start. The forward run scan and the backward
  give-back scan run at most once per G-run, because after a failure the search skips past `R`.
  So a call costs O(n · (w + |T|)).
  - Captured `C{k,max}`: the backward scan keeps `runC` incrementally
    (`runC(q) = C(s[q]) ? runC(q+1) + 1 : 0`), so each step is O(1).
  - The initial `runC(R)` needs a forward scan from `R`:
    - find without END: cap it at `k`, since only `c ≥ k` matters during the search.
    - requireEnd, or END present: cap it at `max`.
  - Fixed `T` with END: for each allowed END position `t`, the only candidate is
    `q = t - |T|`. That is an O(|T|) check per G-run, with no scan.
  - Under findAll, the call after a match at `(q, e)` rescans `[e, R)` once and fails, because
    every `q' ∈ (q, R]` already failed. That is at most two passes per G-run.

## Region (bounded) semantics

Contract: `ReggieMatcher.java:344-358`. JDK `Matcher.region(rs, re)` with the defaults, i.e.
opaque bounds and anchoring bounds.

- `n = re`, and the anchored start is `rs`.
- END uses `re` as the input end.
- `\b` sees only `[rs, re)`: positions outside the region count as non-word.

The reviewer's model diverged in 45,096 cases without the `\b` clamp and in 0 with it. Example:
`([^\n]*)\b\d` on `"a1"` with region `[1,2)` — JDK matches; an unclamped `\b` does not.

## Code generation

- One new generator, `GreedySuffixBytecodeGenerator`. It emits `search` once as a
  `private static long search(CharSequence, int lo, int from, int end, boolean anchored,
  boolean requireEnd)`. `lo` is the lower `\b` clamp: `rs` for bounded methods, and `0` for every
  other method. findFrom, findMatchFrom and findBoundsFrom use `0` even when `from > 0`, because JDK
  `find(int)` resets the region. `search` takes a `CharSequence` so that matchesBounded and
  matchBounded (`ReggieMatcher.java:358`, `:379`) need no `toString()`; String callers pass the
  String. Thin wrappers for each API method call `search`:
  - matches, match, matchesBounded, matchBounded;
  - find, findFrom, findMatch, findMatchFrom;
  - findBoundsFrom.

  Charset tests use the existing CharSet check emitters. Local slots come from the existing
  allocator; there are no hardcoded slots, and ints are pushed only with `pushInt`. `\b` uses a
  private static `isWordChar` like `BitStateBytecodeGenerator.java:154`, with the region clamp.
- New `GreedySuffixInfo implements PatternInfo` holds:
  - prefix charsets, `G`, `m`;
  - suffix kind, and either the `T` charsets or `C`/`k`/`max`;
  - the `\b` flag, the END kind, and the group count.

  `structuralHashCode()` hashes all fields by value.
- `PatternAnalyzer`: the detector goes at the old GB position, after SPECIALIZED_GREEDY_CHARCLASS
  and before multi-group greedy. It adds a new enum value; `hasMutableMatchState()` stays false.
  The new result has `dfa == null`, so `shouldUseHybrid` does not wrap it.
  `StrategyJdkClassifier` and the processor's `resolveRealization` default to NATIVE, so neither
  needs a change.
- Dual path: `RuntimeCompiler` and `ReggieMatcherBytecodeGenerator` both dispatch the new
  strategy.
- Test-side entries:
  - `StrategyCorrectnessMetaTest` spec map;
  - `ProcessorStrategyCoverageTest` rows;
  - `AllStrategyVsJdkBenchmark` entry;
  - restore a `StrategyBenchmark` section.

## Verification plan

1. **Exhaustive differential test (the oracle), `GreedySuffixExhaustiveTest`.**
   - Shapes:
     - m ∈ {0,1};
     - P ∈ {∅, `x`, `[a1]`, `x\n`, `\n`, `\s`, `[\r\n]x`}. Prefixes that can match the character
       ending the G-run (a line terminator) exercise the skip arithmetic. A prefix that cannot match
       that character does not distinguish `st = R - w + 1` from `st = R + 1`.
     - `\b` ∈ {no, yes};
     - T ∈ {`ab`, `\d`, `\n`, `a\r`, `(\d+)`, `(\d*)`, `(\d{2,3})`, `(\d{0,1})`, `([a1]{1,2})`};
     - END ∈ {none, `$`, `\Z`, `\z`};
     - G ∈ {`.`, `(?s).`}.
   - Alphabet: {`a`, `x`, `1`, ` `, `\n`, `\r`, U+0085, U+2028}.
   - Exhaustive for lengths ≤ 4 for all shapes, and length ≤ 6 for a fixed subset (no `\b`, one P
     per width), so it runs in minutes, not hours.
   - Oracles:
     - `matches()`, `match()` → `Matcher.matches()`;
     - `find()`, `findMatch()`, and `findFrom`/`findMatchFrom`/`findBoundsFrom` at every start →
       `Matcher.find(int)`;
     - `matchesBounded`/`matchBounded` on every sub-region → `region(rs, re).matches()`;
     - all group spans compared.
   - The test asserts that each shape routes to the new strategy, so it fails if routing changes.
2. **Long inputs.** Seeded random inputs of length 50–2000 over the same alphabet, plus `(\d{7,})`
   and `(\d{7,})$` to cover `k > 5` and the capped `runC(R)` path. Includes a findAll /
   replaceAll / split comparison against a JDK `while (m.find())` loop.
3. Keep `GreedySuffixGiveBackDifferentialTest`, already in the tree.
4. **APT path.** A processor test that compiles a subset of the shapes through the annotation
   processor and runs the length ≤ 4 oracle on them.
5. **Structural hash.** A unit test that `GreedySuffixInfo.structuralHashCode()` changes when each
   field changes on its own.
6. **Gates.** `./gradlew spotlessApply`, `./gradlew build`, `./gradlew :reggie-integration-tests:test`.
7. **Performance.** `GreedySuffixBenchmark` on workspace-jb, base = current tree (GB removed),
   mod = new strategy.
   - Targets: ≥ JDK on every row, and within 2× of old GB on the rows old GB got right.
   - JDK columns are the noise control: run twice if JDK drifts by more than 30%.

## Decisions

1. **Strategy name:** `GREEDY_SUFFIX`.
2. **Implementation:** generated bytecode.
3. **`.` line terminators:** fixed in the parser first, as a separate step before the strategy
   (see Phase 0).
