# Fallback Behavior & Known Limitations

Detail doc for `AGENTS.md`. Covers the correctness guarantee, automatic JDK fallback
conditions, and current known limitations.

## Correctness Guarantee

Reggie never silently returns a wrong answer for an accepted pattern. Every result is either:

- **(a) provably correct** — generated natively by one of Reggie's bytecode strategies, or
- **(b) explicitly rejected** — pattern compilation via `Reggie.compile()` throws
  `UnsupportedPatternException` for unsupported constructs; `@RegexPattern` rejects the pattern
  at build time. To enable JDK delegation for unsupported patterns, use
  `Reggie.compileAllowingFallback()` or pass `ReggieOption.ALLOW_JDK_FALLBACK`.

This guarantee is enforced continuously in CI by two automated gates:

- **Differential fuzzer** (`AlgorithmicFuzzTest.divergenceGate_enforcedViaProperty`):
  400k+ deterministic (pattern, input) pairs checked against JDK — divergences must stay within
  the known budget (28 pre-existing divergences on adversarial inputs: group-span gaps in
  `DFA_UNROLLED_WITH_GROUPS` and `SPECIALIZED_CONCAT_GREEDY_GROUP`; boolean divergences in
  `OPTIMIZED_NFA_WITH_BACKREFS`, `DFA_SWITCH` (\\A anchor), and backref/anchor-combo patterns).
  Override with `-Dreggie.fuzz.maxFindings=N`.
- **Per-strategy correctness meta-test** (`StrategyCorrectnessMetaTest`): all 8 public
  `ReggieMatcher` methods exercised for every `MatchingStrategy` against JDK — 0 mismatches
  required (enforced via `-Dreggie.metatest.enforce=true` in every build).

## Automatic Fallback to java.util.regex

Certain pattern structures are not handled natively. By default, `Reggie.compile()` throws
`UnsupportedPatternException` for these patterns. To enable JDK delegation instead, use
`Reggie.compileAllowingFallback()` or pass `ReggieOption.ALLOW_JDK_FALLBACK` — this emits a
`WARNING` log:

```
Falling back to java.util.regex for pattern '<pattern>': <reason>
```

**Patterns that fall back** — two sources contribute reasons:

### `FallbackPatternDetector` (AST-level checks, `reggie-codegen`)

| Condition | Strategy scope | Reason string |
|-----------|---------------|---------------|
| Lookahead inside a quantified group (#28) | all | `lookahead inside quantified group` |
| Anchor inside a quantifier within a capturing group | all | `anchor inside quantifier within capturing group: capture span tracking incorrect` |
| Anchor inside any quantifier (range ≠ {1,1}) | all | `anchor inside quantifier: zero-width anchor with quantifier produces incorrect match positions` |
| END/STRING_END anchor immediately before a non-newline char consumer | all | `end-anchor before non-newline consumer: DFA does not model this path correctly` |
| Lazy quantifier | `RECURSIVE_DESCENT`, `OPTIMIZED_NFA_WITH_BACKREFS` | `lazy quantifier: requires shortest-match semantics not supported by this strategy` |
| Backref used in one branch whose capturing group is in a different branch | `RECURSIVE_DESCENT`, `OPTIMIZED_NFA_WITH_BACKREFS` | `cross-alternative backref: group captured in one branch, used in another` |
| Backref to an ambiguously nullable group (content can capture strings of length > 1, e.g. `([0]?-*)\1`) | `OPTIMIZED_NFA_WITH_BACKREFS` | `backref to nullable group: parallel NFA simulation records wrong capture span` |
| Backref to a nullable group inside a capturing group | `RECURSIVE_DESCENT` | `backref to nullable group inside capturing group: recursive descent parser mishandles zero-length capture in nested group context` |
| Lookahead assertion inside an alternation branch | `OPTIMIZED_NFA_WITH_LOOKAROUND` | `lookahead inside alternation branch: NFA thread scheduler does not correctly isolate assertions per branch` |
| Non-anchor, non-handleable node before the capturing group (e.g. QuantifierNode prefix) | `VARIABLE_CAPTURE_BACKREF` | `variable-capture backref with unsupported prefix node type: generator only handles literal and char-class prefix nodes` |
| Outer quantifier wraps the entire capturing group (e.g. `(X)+\1`) | `VARIABLE_CAPTURE_BACKREF` | `quantified capturing group with backref: outer quantifier on group not supported by backref engine` |
| Nullable or alternation-body group wrapped in outer quantifier | `OPTIONAL_GROUP_BACKREF` | `optional-group backref to unsupported capturing group: nullable or alternation-body group not handled by optional-group backref engine` |
| Capturing group with nullable content under a nullable outer quantifier (e.g. `(0*-?){0,}`) | `DFA_UNROLLED_WITH_GROUPS`, `DFA_SWITCH_WITH_GROUPS`, `PIKEVM_CAPTURE` | `capturing group with nullable content and nullable outer quantifier: PIKEVM_CAPTURE diverges; TDFA POSIX last-match span also incorrect` |
| STRING_END (`\Z`/`$`) anchor inside an alternation combined with capturing group, nullable/empty branch, or broad char-class branch | `OPTIMIZED_NFA` | `string-end anchor in alternation with capturing group or nullable/empty branch: OPTIMIZED_NFA find() span or group-span tracking incorrect` |
| Start-class anchor (`\A`/`^`) inside an alternation branch alongside a capturing group | `OPTIMIZED_NFA` | `start anchor in alternation with capturing group: OPTIMIZED_NFA group span tracking for unmatched branches incorrect` |
| Any alternation branch is nullable (can match the empty string) | `OPTIMIZED_NFA` | `nullable alternation branch: find() first-alternative semantics incorrect for empty/nullable branch` |
| Negated `CharClassNode` as the quantifier child inside a concat greedy group (e.g. `[1]([^b]{2})`) — generator stores un-negated charset without negation flag | `SPECIALIZED_CONCAT_GREEDY_GROUP` | `SPECIALIZED_CONCAT_GREEDY_GROUP: negated char class in group quantifier — generator passes non-negated charset without negation flag, producing wrong matches` |
| Inner quantifier with `min > 1` inside a quantified capturing group (e.g. `(c{2}){1,}`) — per-char loop allows fewer chars than inner min requires | `SPECIALIZED_QUANTIFIED_GROUP` | `SPECIALIZED_QUANTIFIED_GROUP: inner quantifier min > 1 — per-char greedy loop cannot enforce multi-char per-iteration constraint` |
| Recursive subroutine call inside an alternation arm (palindrome structure) | all | `recursive subroutine requires intra-call backtracking: pattern is context-free, not regular — use compileAllowingFallback() for JDK delegation` |

### `RuntimeCompiler` (analyzer-flag checks)

| Condition | Example | Reason string |
|-----------|---------|---------------|
| DFA construction diluted an anchor condition | patterns where DFA state merging loses `^`/`$` precision | `anchor condition diluted in DFA construction` |
| Hybrid DFA build (group extraction path) diluted an anchor condition | patterns with groups where DFA merge loses anchor precision | `anchor condition diluted in hybrid DFA build` |
| DFA longest-match conflicts with NFA first-alternative priority | `(a\|ab)` in find context | `alternation priority conflict: DFA longest-match vs NFA first-alternative` |
| Capture-ambiguous group bindings requiring POSIX last-match semantics | `(a\|a)+` | `capture-ambiguous group bindings: group spans require java.util.regex semantics` |
| Generated method exceeds JVM 64 KB method-size limit (large alternations) | large Grok patterns | `generated method too large: <class>.<method><desc> codeSize=<n>` |

In addition to full fallback, two strategies use a **hybrid** approach: `SPECIALIZED_LITERAL_ALTERNATION`
and `FIXED_REPETITION_BACKREF` generate native boolean methods (`matches`/`find`/`findFrom`) but
delegate group-extraction (`match`/`findMatch`/`findMatchFrom`) to a lazily-compiled `java.util.regex`
pattern. `Reggie.compile()` logs a one-time WARNING; `@RegexPattern` emits a `MANDATORY_WARNING`.

**FULL_FALLBACK strategies** (2): `VARIABLE_CAPTURE_BACKREF`, `NESTED_QUANTIFIED_GROUPS`
(incomplete MatchResult API). `SPECIALIZED_MULTIPLE_LOOKAHEADS`, `SPECIALIZED_LITERAL_LOOKAHEADS`,
and `HYBRID_DFA_LOOKAHEAD` are no longer FULL_FALLBACK — their boolean-engine defects were fixed
(see line 111 below); `StrategyJdkClassifier.classifyJdkDependency()` has no case for any
lookahead strategy and falls through to `default: NATIVE`.

**RICH_API_HYBRID strategies** (2): `SPECIALIZED_LITERAL_ALTERNATION`, `FIXED_REPETITION_BACKREF`.

**`@RegexPattern` delegating-stub policy:**

- **PIKEVM_CAPTURE patterns** (capture-ambiguous without backrefs): the processor emits a delegating
  stub that calls `RuntimeCompiler.compilePikeVm()` at runtime — no `ALLOW_JDK_FALLBACK` flag needed.
  Example: `(<\w+>).*(</\w+>)`.

- **FULL_FALLBACK patterns** (patterns that require `java.util.regex` for correctness — e.g.
  `captureAmbiguous` backref bypass, anchor-in-quantifier, lazy-backref): if the method carries
  `options = ReggieOption.ALLOW_JDK_FALLBACK`, the processor emits a delegating stub that calls
  `Reggie.compileAllowingFallback()` at runtime and emits a `MANDATORY_WARNING`. Without
  `ALLOW_JDK_FALLBACK` such patterns are a **build error** — use `Reggie.compile()` at runtime
  instead.

`Reggie.compile()` **throws** `UnsupportedPatternException` for unsupported patterns — it does not
silently delegate to JDK. To enable JDK delegation, call `Reggie.compileAllowingFallback()` (or
pass `ReggieOption.ALLOW_JDK_FALLBACK`). When delegation fires, a `WARNING` log is emitted and
JDK performance applies for that pattern; all other patterns use the fast Reggie engine.

For `@RegexPattern` (compile-time path): patterns matching a fallback condition fail at build time
with an `UnsupportedOperationException`. Use `Reggie.compile()` instead for those patterns.

**Previously documented fallback reasons that no longer exist in production code:**
- `multiple backreferences to group 1 in NFA mode` — removed; multi-backref patterns are now handled correctly or routed via specific strategies
- `lookbehind followed by unbounded quantifier` — removed; this case is no longer a known bug
- `alternation inside lookbehind` — removed; this case is no longer a known bug
- `SPECIALIZED_MULTIPLE_LOOKAHEADS`, `SPECIALIZED_LITERAL_LOOKAHEADS`, `HYBRID_DFA_LOOKAHEAD` boolean-engine defects — fixed (Wave 3); all three strategies now generate correct `find()`/`findFrom()` native code
- `VARIABLE_CAPTURE_BACKREF` MatchResult API not implemented — fixed (Wave 2); full native `match()`/`findMatch()` generated
- `NESTED_QUANTIFIED_GROUPS` MatchResult API not implemented — fixed (Wave 2); full native group-extraction generated
- `SPECIALIZED_LITERAL_ALTERNATION` and `FIXED_REPETITION_BACKREF` hybrid group-extraction — fixed (Wave 1); both strategies now emit complete native rich API
- `DFA with capturing group inside quantifier: DFA cannot track per-iteration spans` (`DFA_UNROLLED`, `DFA_UNROLLED_WITH_ASSERTIONS`) — eliminated (Wave 2); `PatternAnalyzer` now routes these patterns to `PIKEVM_CAPTURE` before the DFA ladder
- `nullable alternation branch in anchor context`, `end-anchor in leading-nullable alternation`, `optional-branch alternation` (`DFA_*` strategies) — eliminated (Wave 1); `PatternAnalyzer` routes these to `PIKEVM_CAPTURE`
- `variable-capture backref to nullable group: empty-capture path handled incorrectly` (`VARIABLE_CAPTURE_BACKREF`) — removed; the bounded-quantifier cap fix in Wave 2 made this condition obsolete
- `nested quantified groups with alternation in inner content` (`NESTED_QUANTIFIED_GROUPS`) — removed; the NESTED_QUANTIFIED_GROUPS generator was extended to handle alternation content natively
- `variable-capture backref with bounded inner quantifier` (`VARIABLE_CAPTURE_BACKREF`) — removed (Wave 2); generator caps initial `groupEnd` to `groupMaxCount` for bounded quantifiers
- `alternation with prefix-overlap: leftmost-first ordering diverges from JDK longest-match` (FallbackPatternDetector, `OPTIMIZED_NFA`) — removed from `FallbackPatternDetector`; the check is now handled at the `RuntimeCompiler` level via `alternationPriorityConflict`
- `non-capturing GroupNode prefix before backref group` (subset of `VARIABLE_CAPTURE_BACKREF`) — fixed (Wave 3/B12); `emitPrefixMatch` now recurses into handleable non-capturing group content
- `backref to nullable group with max capture length ≤ 1` (subset of `OPTIMIZED_NFA_WITH_BACKREFS`) — fixed (Wave 3/B7); zero-length early-accept in `generateBackreferenceCheck` handles these correctly

## Known Limitations

- **Backreferences**: Broadly supported with targeted limitations:
  - Most backreference patterns work natively: `(a{2})\1`, `<(\w+)>.*</\1>`, `(\w+)\s+\1`, etc.
  - Specific structural patterns still fall back to `java.util.regex` (see `FallbackPatternDetector` table above)
  - **Self-referencing backreferences**: `(a\1?){4}`, `(a\1?)(a\2?)` — native support via the C-01 partial-open sentinel code path in `RecursiveDescentBytecodeGenerator` (lines 2041–2090) handles per-iteration capture updates. Canonical cases (`^(a\1?){4}$`, `^(a\1?)+$`, `(a\1?|b){4}`) are verified by `SelfReferencingBackrefTest` (CI-active, no knownFailures gate). Remaining edge cases are an open audit item; issue #39 is partially addressed.
- **Recursive patterns**: Limited support via RECURSIVE_DESCENT strategy:
  - Subroutines (`(?R)`, `(?1)`) and conditionals (`(?(1)yes|no)`) work for most cases
  - **Recursive palindromes**: Palindrome patterns (`^((.)(?1)\2|.?)$`, `^((.)(?R)\2|.?)$`) now throw `UnsupportedPatternException` by default. To match them, use `Reggie.compileAllowingFallback()` which delegates to `java.util.regex`. Issue #38 is closed by explicit JDK routing.
- **Branch reset groups**: Partially supported — `(?|...)` with numbered groups routes to
  `RECURSIVE_DESCENT` and passes correctness tests. The named-capture variant
  `(?|(?'name'...)|(?'name'...))` is not yet supported and throws at compile time.
- **Unicode properties**: Not yet supported (`\p{L}`, `\p{N}`, etc.)
- **Atomic groups**: Not supported (`(?>...)`)
- **Possessive quantifiers**: Not supported (`*+`, `++`, `?+`)
- **Inline flags**: Partially supported - `(?i)`, `(?m)`, `(?s)`, `(?x)` work globally; scoped flags like `(?i:...)` not yet supported

### Performance known gaps

The following are **performance-only** issues — not correctness issues. Observed in the JMH
benchmark suite and deferred as follow-up work:

- **`ONEPASS_NFA` `find()` regression**: `find()` on `ONEPASS_NFA` patterns measures below JDK
  throughput. This is a pre-existing gap in the native path.
- **`SPECIALIZED_MULTI_GROUP_GREEDY` and `SPECIALIZED_BOUNDED_QUANTIFIERS`**: ~2–2.5x over JDK, the
  weakest gains among generated strategies. Profiling may reveal further optimization opportunities.
