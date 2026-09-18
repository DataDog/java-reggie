---
id: ev-r1-prefilter-landed
type: evidence
status: confirmed
depends_on: [find-rust-engine-crossover, ev-rust-bench-lane-standardized]
supersedes: []
related: [hyp-unanchored-find-prefilter, q-z-anchor-span-bug]
tags: [r1, prefilter, required-literal, rejection, workspace-jb, 9x, 38eeb62]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# R1 required-literal rejection prefilter LANDED (2026-09-17, 38eeb62)

## Reasoning chain
Commit 38eeb62 (feat/dd_backend_check, full suite green). RequiredLiteralAnalyzer
(sound language-level AST facts) + universal PrefilteringMatcher rejection wrapper at
every compile() return (all strategies incl. the DFA lanes that dominate the corpus —
per-generator bytecode emission was tried first and missed them; since removed).
Two extractor bugs found by the JDK-oracle-gated audit and fixed: (1) exact chains
ending at a non-exact child with empty prefixRun never closed; (2) 1-char facts
flooded the fact set and evicted long chains (now >=2 chars only + evict-shortest).
Fuzz-oracle interaction fixed via public isJdkFallback() (wrapper defeated the
instanceof-based fallback skip, shifting the input RNG stream).

MEASURED on workspace-jb (idle 16-core Linux; user directive: benchmarks run there,
not locally — local mac was ±2.5x noisy from gradle daemons):
RealCorpusScanBenchmark -wi 3 -i 5 -f 2, baseline 5210bac vs R1 38eeb62:
- reggieSweepNoMatch 33,896us -> 3,727us = 9.1x; reggie/rust 10.6x behind -> 1.17x;
  reggie vs JDK no-match 12x faster (was 0.78x).
- reggieSweepMatched 564 -> 586us (unchanged). jdk/rust control lanes flat across
  builds (43.7/44.9ms, 3.20/3.18ms) — methodology validated.
Audit: 287/513 patterns with literal (avg 9.0 chars), 1690/2696 no-match pairs = 63%
instant reject, zero soundness violations, zero divergences.

R2 (single-pass .* scan) REMAINS: sweep now dominated by no-literal .*-prefix
patterns (^(.*)-([0-9]{1,4})$ family, (.*) \((.*)\), (.*[a-z0-9/-]*)-([0-9]*)).
Full detail: repo cairn doc/investigations/multi-64k/evidence/ev-r1-prefilter-landed.md.
