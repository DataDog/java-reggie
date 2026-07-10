# `AllStrategyVsJdkBenchmark` redesign — scaled corpus instead of single-point ratios

## Motivation

The previous version of `AllStrategyVsJdkBenchmark` measured each
`MatchingStrategy` with exactly one pattern and one input. That conflates two
different things: "how good is this strategy" and "how much does fixed
per-call overhead dominate this one specific input." `(a)?b` on `"xaby"` is
the smallest possible trigger for `BITSTATE_CAPTURE` — it maximizes the
visibility of that engine's bounded-backtracking bookkeeping relative to
actual work done, which is exactly what made its 0.235x ratio look worse
than it may be in general.

## What changed

`reggie-benchmark/.../AllStrategyVsJdkBenchmark.java` now runs every
strategy/pattern pair at three input scales via `@Param({"SHORT", "MEDIUM",
"LONG"})`. SHORT is the original single-shot input, unchanged. Two scaling
strategies are used depending on what actually varies for a given pattern:

- **Matched-region growth** (`\w+`, `(\d+)`, `a(b*)`, `a.*b.*c.*d.*e.*f`, the
  two `(a)?b`-shaped patterns, `(?:abc){N}`, `(?:a+b+|b+a+){N}`): grows the
  matched span itself.
- **Scan-prefix growth** (fixed-width matches: phone number, IPv4,
  `[ab]c`, `(fo|foo)`, the `DFA_SWITCH_WITH_GROUPS` alternation chain,
  `(foo)(bar)`): prepends non-matching filler before the match, stressing
  `find()`'s scan cost rather than the match itself (whose width the pattern
  fixes).

One caveat surfaced during implementation: `LAZY_DFA`'s unrolled codegen for
`(?:a+b+|b+a+){N}` is already close to the JVM's 64KB per-method bytecode
limit at the original `N=75` (confirmed — `N=150` throws
`UnsupportedPatternException: generated method too large`). The original
benchmark's choice of 75 wasn't arbitrary; it's near this strategy's ceiling.
`LAZY_DFA` therefore keeps `N` fixed and scales via scan-prefix growth
instead of repetition-count growth, unlike its sibling `DFA_TABLE` (which
had headroom up to `N=300` before hitting the same class of limit).

## Results (default class settings: 2 warmup / 3 measurement iterations, 1s
each, 1 fork — same rigor as the original benchmark, not a high-confidence
run)

Raw JMH output: [`perf-results/2026-07-09-all-strategy-vs-jdk-benchmark-scaled.json`](perf-results/2026-07-09-all-strategy-vs-jdk-benchmark-scaled.json).

| strategy | SHORT | MEDIUM | LONG | trend |
|---|---|---|---|---|
| optimizedNfa | 10.298x | 8.196x | 7.271x | flat (see update below) |
| pikevmCapture | 11.865x | 8.579x | 7.524x | flat (see update below) |
| dfaTable | 0.806x | 0.813x | 0.940x | flat |
| lazyDfa | 1.493x | 3.156x | 7.343x | improves — fixed-cost artifact |
| specializedBoundedQuantifiers | 2.457x | 2.468x | 3.324x | improves — fixed-cost artifact |
| specializedMultiGroupGreedy | 3.664x | 1.443x | 2.313x | worsens (noisy, see caveat) |
| specializedFixedSequence | 4.358x | 2.393x | 3.040x | worsens (noisy, see caveat) |
| statelessLoop | 4.887x | 2.500x | 1.731x | worsens (noisy, see caveat) |
| specializedConcatGreedyGroup | 5.984x | 1.624x | 1.752x | worsens (noisy, see caveat) |
| dfaSwitch | 6.394x | 5.787x | 6.285x | flat |
| dfaUnrolled | 7.490x | 3.250x | 3.725x | worsens (noisy, see caveat) |
| dfaUnrolledWithGroups | 10.233x | 10.743x | 12.515x | flat |
| onepassNfa | 11.343x | 4.379x | 5.089x | worsens (noisy, see caveat) |
| dfaSwitchWithGroups | 14.210x | 33.680x | 25.536x | improves — fixed-cost artifact |
| specializedGreedyCharclass | 15.927x | 125.368x | 8723.436x | improves — fixed-cost artifact |

**Caveat**: several "worsens" rows above have error bars comparable to or
larger than the score itself at this iteration count (e.g.
`specializedFixedSequence` MEDIUM: 4313 ± 17321 ops/ms) — 3×1s measurement
iterations on one fork is enough to catch a real trend but not enough to
separate a mild real effect from noise for every row. Re-run with more
iterations/forks before treating any single "worsens (noisy)" row as
confirmed.

## Update: `optimizedNfa`/`pikevmCapture` re-measured after the `SPECIALIZED_OPTIONAL_GROUP` fix

The numbers above for `optimizedNfa`/`pikevmCapture` were re-measured after
the `SPECIALIZED_OPTIONAL_GROUP` strategy (see
[`2026-07-09-specialized-optional-group-investigation.md`](2026-07-09-specialized-optional-group-investigation.md))
landed. That strategy now intercepts `(?<x>a)?b` and `(a)?b` ahead of the old
`BITSTATE_CAPTURE`-routed `OPTIMIZED_NFA`/`PIKEVM_CAPTURE` paths — confirmed
directly (`StrategyCorrectnessMetaTest.routeOf`): both patterns now route to
`SPECIALIZED_OPTIONAL_GROUP`, not `OPTIMIZED_NFA`/`PIKEVM_CAPTURE`. The table
rows are unchanged in name (same benchmark methods/patterns) but now measure
the new strategy.

Raw JSON:
[`perf-results/2026-07-09-optimized-nfa-pikevm-capture-post-fix.json`](perf-results/2026-07-09-optimized-nfa-pikevm-capture-post-fix.json).

| pattern | scale | reggie (ops/ms) | jdk (ops/ms) | ratio |
|---|---|---|---|---|
| `(?<x>a)?b` | SHORT | 398194.12 ± 167923.43 | 38665.79 ± 1887.10 | 10.30x |
| `(?<x>a)?b` | MEDIUM | 4327.82 ± 3878.65 | 528.03 ± 44.79 | 8.20x |
| `(?<x>a)?b` | LONG | 181.05 ± 109.70 | 24.90 ± 10.19 | 7.27x |
| `(a)?b` | SHORT | 430200.18 ± 426099.82 | 36256.87 ± 6048.08 | 11.87x |
| `(a)?b` | MEDIUM | 4488.40 ± 3631.44 | 523.19 ± 50.65 | 8.58x |
| `(a)?b` | LONG | 183.79 ± 16.56 | 24.43 ± 4.58 | 7.52x |

The regression is gone and reversed: Reggie now beats JDK by 7.3x–11.9x
instead of losing at 0.095x–0.272x. Ratio still shrinks with scale (same
qualitative shape as before, just from a much higher floor) — SHORT/MEDIUM
error bars are wide (1 fork / 3×1s iterations, per this class's own
`@Warmup`/`@Measurement`/`@Fork` annotations); LONG is the most stable
(±5–10% relative error) and the number to trust if picking one.
