---
id: hyp-unanchored-find-prefilter
type: hypothesis
status: confirmed
depends_on: [find-rust-engine-crossover, ev-rust-bench-lane-standardized, ev-r1-prefilter-landed, ev-r2-rd-landed, ev-r2b-landed, ev-r1b-char-intrinsic, ev-4lane-bench-results]
supersedes: []
related: []
tags: [prefilter, literal-extraction, memchr, single-pass, unanchored-find, roadmap, R1, R2, projection-invalid]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# CONFIRMED 2026-09-18: full arc delivered and measured — R1 (38eeb62) -> R2 (b5cf63e) ->
# R2b/R1b (97ec426) -> 1-char intrinsic (293d0e6). No-match sweep 15.6ms -> 636us (24x);
# reggie 1.79x ahead of rust no-match at the R2b checkpoint and 0.25us/pair vs rust 0.66
# in the final 4-engine matrix (e61fdd9); acceptance gate (RealCorpusScanBenchmark) passed.

## Reasoning chain
Real-corpus smoke shows reggie's fleet-mix f is capped at ~2x vs JDK by the no-match scan:
R1 = memchr-style required-literal prefilter for unanchored find() (as rust/JDK BnM do)
would collapse NOMATCH totals where the required literal is absent (Java String.indexOf is
SIMD-intrinsic'd); R2 = single-pass unanchored scan (start-state self-loop closure, RE2
style) instead of per-start-position restarts for .*-prefix shapes (kills the 1.7ms-class
patterns). Expected to lift blended f above 2x again (user's framing); at logs-processing
scale each 1x of f ~ $100K/yr.

UPDATE 2026-09-17 (38eeb62): R1 SHIPPED — sound extractor built (language-level facts),
audited against the JDK oracle (0 violations, 287/513 patterns, 63% of no-match pairs
instant-reject), and MEASURED on workspace-jb: no-match sweep 33.9ms -> 3.7ms (9.1x),
reggie 1.17x behind rust (was 10.6x), 12x faster than JDK; matched unchanged. The old
projections are now measurements. R2 (single-pass .* scan for no-literal .*-prefix
patterns) is the remaining half — it now dominates the 3.7ms residual.
