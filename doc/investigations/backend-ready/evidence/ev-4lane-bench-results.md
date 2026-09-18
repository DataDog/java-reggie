---
id: ev-4lane-bench-results
type: evidence
status: confirmed
depends_on: [ev-rust-bench-lane-standardized]
supersedes: []
related: []
tags: [benchmark, re2j, rust, 4-engine, e61fdd9, corpus]
generator: cairn
created: 2026-09-18
updated: 2026-09-18
---

# 4-engine benchmark matrix (e61fdd9): re2j never-landed decision quantified

Full unified tables: repo doc/temp/bench-2026-09-18-53a9425/FULL-RESULTS.md (+ raw JMH JSONs,
box ~/benchmarks/2026-09-18-4lane/ and ~/benchmarks/2026-09-18-53a9425/). Lanes: re2j added to
RealCorpusScanBenchmark (own served subset 492/513, pairs printed at setup), rust scan-semantics
lanes added to IastRegexpBenchmark (0 refusals), benchmark corpus copy synced 513->528 (was
drifted from the parity-test copy).

## RealCorpus per-pair (us): reggie 0.494/0.246 m/nm, rust 0.622/0.662, jdk 1.805/11.969,
re2j 6.580/5.140 — re2j SLOWEST on matched (13x reggie), only beats jdk on no-match (2.3x).
## IAST: reggie fastest nearly everywhere; rust leads only scan-prefix no-match at SHORT;
re2j loses everywhere EXCEPT LdapNoMatch LONG (re2j 361 vs reggie 276 ops/ms — the one shape
where an engine beats reggie; jdk 90, rust 17). MatchOperation: reggie ~1M ops/ms on fixed
shapes vs rust 7-11K / jdk 4-22K / re2j 0.7-22K.
