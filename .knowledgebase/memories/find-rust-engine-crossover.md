---
id: find-rust-engine-crossover
title: On the real logs-backend pattern mix rust regex is 3-5x faster than reggie at median (11-54x totals, surviving FFI marshal); honest fleet speedup of reggie vs JDK is ~2x, capped by unanchored find (fixed later by R1/R2)
kind: finding
tags: [rust-regex, regex-automata, real-corpus, benchmark, ffm-jni, crossover]
applies_to: [reggie-benchmark/src/main/java/com/datadoghq/reggie/benchmark/RealCorpusScanBenchmark.java, reggie-benchmark/src/main/java/com/datadoghq/reggie/benchmark/engines/RustRegexEngine.java]
source: backend-ready/find-rust-engine-crossover
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# reggie vs JDK vs Rust regex on the real logs-backend mix

## Setup
513 real logs-backend pattern literals x 6 realistic log lines; engines jdk / reggie /
rust (regex 1.13.1 = regex-automata meta engine = the dd_sds family); FFM with per-call
UTF-8 marshalling; mode-split by JDK oracle boolean. 491 common patterns, ZERO divergences
across 2,946 pairs. Coverage: jdk 0 / reggie 10 / rust 12 refusals (see
find-refusal-set-parity).

## Results
- MATCH mode (327 pairs, grok-parsing shape where prod 5.6% CPU lives): jdk 198us,
  reggie 180us, rust 16us; reggie/jdk median 0.48x (2.1x faster); reggie/rust median 2.77x.
- NOMATCH mode (2,619 pairs, rule-filter scans): jdk 11.2ms, reggie 5.2ms, rust 97us;
  reggie/jdk median 0.68x; reggie/rust 4.9x median, 54x totals.
- Worst reggie shapes: .*-prefixed unanchored patterns (e.g. '(.*) \((.*)\)': jdk 213us,
  reggie 1.7ms, rust 316ns) — no literal prefilter + per-start-position restarts.
- Rust counted-quantifier wall is real: semver {0,256} exceeds the default 10MB NFA limit
  AND 256MB raised; {0,128} builds in 394ms.

## Consequences
1. Rust (regex-automata) is 3–5x faster than reggie at median on the real mix, surviving
   FFI marshal — replacing dd_sds rust with reggie would RAISE CPU; the case for it is
   architectural only (cross-runtime FFI tax).
2. The savings model's f (see find-backend-prod-regex-cost) revised 3–6x -> ~2x central
   (matched-pairs median) — the cap is the no-match scan, addressed by the literal
   prefilter + single-pass scan arc (find-unanchored-find-prefilter).
