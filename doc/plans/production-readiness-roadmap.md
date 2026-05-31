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
