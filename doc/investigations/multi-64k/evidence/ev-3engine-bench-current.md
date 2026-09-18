---
id: ev-3engine-bench-current
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [q-prod-readiness, find-bounded-quantifier-regression]
tags: [benchmark, jmh, 3-engine, jdk, re2j, verdict, current-recorded-run]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Current recorded 3-engine JMH verdict: reggie beats JDK and RE2J on every comparable entry

## Setup
Mac (Corretto 26.0.1 HotSpot), trimmed JMH (-wi 3 -w 1 -i 3 -r 1 -f 1), 3 invocations over
the 9 three-engine suites: 7 no-param suites (139 entries, 26 min), IastRegexp scale=SHORT
(54 entries, 10 min), IastTokenizerDrain (81 entries avgt mode, 15 min). a5ef0fb tree.
Caveats: single fork, 1s measurement iterations — direction/magnitude reliable, single-digit
percentages are not. NOTE: bench harness script had a wrong-results-path bug (mv from root
build/reports instead of reggie-benchmark/build/reports) — runs A+B recovered by parsing the
JMH summary tables from /tmp/bench3e/run.log; run C preserved in
reggie-benchmark/build/reports/jmh/results.json. Parsed verdicts: /tmp/bench3e/verdict.json.

## Verdict (throughput; reggie speedup = score ratio)
- **vs java.util.regex: faster in 64/64 comparable entries. Median 5.9x, >=10x in 20,
  max 1000x** (StateExplosionBenchmark OptionalSequenceNoMatch / NestedQuantifiersNoMatch —
  exactly the ReDoS shapes; vs-re2j GroupExtraction DigitsGroup and MultilineDFA shortLines
  also 1000x).
- **vs RE2J: faster in 62/62 comparable entries. Median 46.5x, >=10x in 52, max 1000x.**
- Smoke (separate, untrimmed): phone-match reggie 1,115,390 ops/ms vs JDK 25,712 (~43x) vs
  RE2J 6,702 (~166x).
- Drain suite (avgt, us/op — lower better): JDK catastrophic on LDAP filters, e.g.
  jdkDrain LDAP_NESTED_OPEN_EQ @1024 = 136,775us/op = **137ms per op** (unbounded
  backtracking); reggie/re2j entries are in the preserved results.json.

## Scope caveats
- This corpus does NOT include bounded-quantifier unrolling shapes (semver {0,256} family) —
  the one known reggie-vs-JDK REGRESSION family surfaced by the real-service census instead
  (find-bounded-quantifier-regression). All-strategy-wins claims must carry that carve-out.
- Confirms README's "faster than JDK Pattern and RE2J on typical patterns" with current
  numbers on current hardware; closes the "no recorded verdict" gap from q-prod-readiness
  (the stale pre-6841723 run is no longer the only thing on file).
