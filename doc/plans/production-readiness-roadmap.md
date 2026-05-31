# Production-Readiness Roadmap

Tracks the work to let Reggie drop the "experimental" label.

## Bar

**Reggie must never silently return a wrong answer for a pattern it accepts.** Every
result is either provably correct or transparently delegated to `java.util.regex`.
Performance is not the gate — correctness trust is.

## Correctness gates (the product, not just the fixes)

- **Differential fuzzer** — `AlgorithmicFuzzTest.zeroDivergenceGate` cross-checks `find()`
  and `matches()` booleans + first-match spans against `java.util.regex` over a
  deterministic ≥50k-check sweep. Production target: **0 divergences**.
- **Per-strategy meta-test** — `StrategyCorrectnessMetaTest` exercises all 8 public
  `ReggieMatcher` methods for every `MatchingStrategy` against JDK. Production target: **0
  mismatches** (`-Dreggie.metatest.enforce=true`).

Both gates were authored during this effort and run in collect/disabled mode until the
backlog clears, then flip to enforcing in CI (Wave C).

## Status (2026-05-31)

- Differential fuzzer: **0 / 76,232 checks** — all booleans agree with JDK.
- Meta-test: 67 residual mismatches across 9 strategies, all in the **MatchResult /
  group-extraction** surface of backref + lookahead strategies (booleans correct;
  `match()/findMatch()/matchBounded()` return null/wrong-span/throw).

### Completed
- Bounded-quantifier ranged-overlap silent wrong answer (`[ab]{1,2}b`) — analyzer declines
  the non-backtracking fast path on overlap → routes to a correct strategy. (127d8bf)
- Silent-wrong boolean classes: alternation-in-quantified-group, anchor+alternation
  zero-width/leftmost, greedy-group-over-newline. (529ad8e)
- Base `ReggieMatcher.matchBounded/matchesBounded(CharSequence,…)` made concrete delegating
  defaults — cleared 55 `AbstractMethodError` cases and prevents the class from recurring. (529ad8e)

### In progress — B3/B4: rich MatchResult API for backref/lookahead

**Decision (owner, 2026-05-31): Hybrid (option 1).** Keep the fast allocation-free
`matches()/find()/findFrom()` on these strategies; make the rich methods
(`match/findMatch/findMatchFrom/matchBounded`) correct by delegating to a lazily-compiled
JDK `Pattern` for group spans. Correctness everywhere; the hot boolean path stays fast;
per-call cost only when groups are actually extracted.

Rejected alternatives:
- *Full fallback* — routing these patterns entirely to JDK loses Reggie's speed even for
  `find()`.
- *Full in-place span tracking* — large, and reintroduces the backtracking complexity Reggie
  is designed to avoid (revisit under the deferred investigation below).

### Remaining
- Wave C: flip both gates to enforcing in CI; reconcile `AGENTS.md` ("Production-ready") vs
  `README.md` ("experimental") to a single accurate statement; final go/no-go sign-off.

## Governing principle

**Fall back / delegate when not provably correct — never silently fast-path a wrong
answer.** New fast paths must extend the analyzer's "provably correct" predicate and pass
both correctness gates before they are allowed to handle a pattern shape.

## Strategy → JDK-dependency classification

Authoritative source: `StrategyJdkClassifier.classifyJdkDependency(MatchingStrategy)` in
`reggie-codegen/.../analysis/`. Three classes, determined by reading the strategy switch in
`RuntimeCompiler.compileInternal` (which strategies return `JavaRegexFallbackMatcher`) and the
per-strategy method emission in `RuntimeCompiler.generateBytecode` (does the generator emit its own
`match`/`findMatch`/`findMatchFrom`, or inherit the JDK-backed base defaults in `ReggieMatcher`).

### NATIVE — booleans and rich API fully generated; no `java.util.regex` at runtime
`STATELESS_LOOP`, `SPECIALIZED_GREEDY_CHARCLASS`, `SPECIALIZED_MULTI_GROUP_GREEDY`,
`SPECIALIZED_CONCAT_GREEDY_GROUP`, `SPECIALIZED_FIXED_SEQUENCE`,
`SPECIALIZED_BOUNDED_QUANTIFIERS`, `SPECIALIZED_QUANTIFIED_GROUP`, `GREEDY_BACKTRACK`,
`LINEAR_BACKREFERENCE`, `SPECIALIZED_BACKREFERENCE`, `OPTIONAL_GROUP_BACKREF`, `ONEPASS_NFA`,
`DFA_UNROLLED` (+ `_WITH_ASSERTIONS`, `_WITH_GROUPS`), `DFA_SWITCH` (+ `_WITH_ASSERTIONS`,
`_WITH_GROUPS`), `DFA_TABLE`, `OPTIMIZED_NFA`, `OPTIMIZED_NFA_WITH_BACKREFS`,
`OPTIMIZED_NFA_WITH_LOOKAROUND`, `LAZY_DFA`, `RECURSIVE_DESCENT`.

### RICH_API_HYBRID — native booleans, JDK-backed group extraction
`SPECIALIZED_LITERAL_ALTERNATION`, `FIXED_REPETITION_BACKREF`. Their generators emit
`matches`/`find`/`findFrom` but deliberately not the rich `match`/`findMatch`/`findMatchFrom`, which
fall through to the lazily-compiled `java.util.regex.Pattern` in `ReggieMatcher`. `Reggie.compile()`
logs a one-time WARNING; the annotation processor emits a `MANDATORY_WARNING` on the annotated
element.

### FULL_FALLBACK — whole matcher delegates to `java.util.regex`
`SPECIALIZED_MULTIPLE_LOOKAHEADS`, `SPECIALIZED_LITERAL_LOOKAHEADS`, `HYBRID_DFA_LOOKAHEAD`
(lookahead boolean-engine defects), `VARIABLE_CAPTURE_BACKREF`, `NESTED_QUANTIFIED_GROUPS`
(incomplete MatchResult API). `Reggie.compile()` already warns via `JavaRegexFallbackMatcher`.

**Runtime-vs-compile-time inconsistency (open):** the annotation processor only rejects the
*AST-level* fallbacks (`anchorConditionDiluted`, `alternationPriorityConflict`,
`FallbackPatternDetector.needsFallback`). The five FULL_FALLBACK strategies above are NOT rejected at
build time — the processor still emits their (known-incorrect) generated bytecode while
`Reggie.compile()` routes them to JDK. This is now surfaced as a `MANDATORY_WARNING` per annotated
element rather than left silent; promoting it to a hard build error is deferred because several
benchmark `@RegexPattern` fixtures currently resolve to these strategies.

A one-time backstop WARNING in `ReggieMatcher.jdkRichDelegate()` fires if a matcher classified
NATIVE ever builds the JDK delegate, so a future misclassification is never silent.

### Benchmark requirement
A full benchmark must verify that the RICH_API_HYBRID and FULL_FALLBACK strategies do **not** regress
versus raw `java.util.regex`: because they wrap the JDK engine (HYBRID for group extraction,
FULL_FALLBACK entirely), they must be **at least as fast as** `java.util.regex` on the methods that
delegate. Any measured slowdown is pure wrapper overhead and is a regression.

## Deferred: safe backtracking at full speed

The Hybrid decision trades speed for correctness on backtracking-requiring patterns (they
delegate to JDK for group spans). A separate, larger R&D effort is planned to investigate
**keeping O(n) (no ReDoS) while handling backtracking constructs at generated-bytecode
speed**, so fewer patterns need to decline. Directions to explore:

- Bounded/guarded backtracking with a hard step budget (PCRE2-JIT style) vs. provably-linear
  approaches.
- Tagged-NFA/DFA submatch extraction without backtracking (Laurikari TNFA; RE2 OnePass /
  BitState; Go `regexp` machine selection).
- Stronger per-shape correctness proofs so the analyzer declines fewer patterns.
- Backtrackable subroutine/quantifier generation with last-iteration group semantics (the
  documented self-referencing-backref and recursive-palindrome gaps).

Any new fast path must pass the differential fuzzer and per-strategy meta-test at zero.
