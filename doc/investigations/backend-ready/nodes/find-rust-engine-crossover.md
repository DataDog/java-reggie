---
id: find-rust-engine-crossover
type: finding
status: confirmed
depends_on: [find-backend-prod-regex-cost]
supersedes: []
related: [hyp-unanchored-find-prefilter, ev-rust-bench-lane-standardized, ev-4lane-bench-results]
tags: [rust-regex, regex-automata, dd_sds, real-corpus, smoke-test, ffm-jni, crossover, f-revision]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# reggie vs JDK vs Rust regex on the REAL logs-backend mix — CONFIRMED

## Reasoning chain
User: "would reggie be competitive against that rust impl?" and "use patterns that are in
logs-backend". Smoke: 513 real logs-backend pattern literals (readiness census,
backend-pats.json -> logs-pats.tsv) x 6 realistic log lines, engines jdk/reggie/rust
(regex 1.13.1 = regex-automata meta engine = dd_sds family; FFM incl. per-call UTF-8
marshalling; mode-split by JDK oracle boolean).

Coverage: jdk 0 / reggie 10 (2.0%) / rust 12 (2.3%) refusals; 491 common; ZERO divergences
across 2,946 pairs. Rust counted-quantifier WALL real: semver {0,256} exceeds default 10MB
NFA limit AND 256MB raised; {0,128} builds in 394ms; {0,192} fails at 256MB.

MATCH mode (327 pairs, grok-parsing shape where prod 5.6% lives): jdk 198us, reggie 180us,
rust 16us; reggie/jdk median 0.48x (2.1x faster); reggie/rust median 2.77x.
NOMATCH mode (2,619 pairs, rule-filter scans): jdk 11.2ms, reggie 5.2ms, rust 97us;
reggie/jdk median 0.68x; reggie/rust median 4.9x, totals 54x.
Worst reggie shapes: .*-prefixed unanchored patterns, e.g. '(.*) \((.*)\)': jdk 213us,
reggie 1.7ms(!), rust 316ns — no literal prefilter + per-start-position scanning.

Consequences: (1) rust is 3-5x faster than reggie at median on the real mix (11-54x totals),
surviving FFI marshal — dd_sds replacement by reggie would RAISE CPU; architectural case
only (dd.sds.Encoder ~1% apm = the cross-runtime tax the user hates). (2) f for the savings
model revised 3-6x -> ~2x central (matched-pairs median) -> see find-backend-prod-regex-cost.
(3) reggie engineering gaps surfaced: R1/R2 (see hyp-unanchored-find-prefilter).
Multi-64k: ev-reggie-cpu-savings-estimate follow-up + ev-rust-engine-bench-standardized.
