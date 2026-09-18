---
id: ev-harness-results
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-replaceability-coverage, find-divergence-budget-verified]
tags: [harness, corpus, differential]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Replaceability harness: 1150 patterns, strict+fallback+differential vs JDK

Method: import-aware textual scan (extractor: `.investigations/replaceability-2026-09-16/extract_regex.py`,
also /tmp/rf/) over ALL tracked .java of updated profiling-backend + logs-backend
(plus verified 0 regex in pb's 13 scala/kt files). 1150 unique literal patterns
(78 explicit Pattern/RE2 API + 1072 String-method); each tested with
Reggie.compile strict, compileAllowingFallback, and a differential vs JDK
(matches/find booleans, span, group spans/values) on ~19 synthetic inputs
including non-ASCII folding probes. CI patterns tested via inline `(?i)` prefix.

Artifacts (copied to java-reggie `.investigations/replaceability-2026-09-16/`):
- results_all.json — per-pattern results (1099 + 50 + injected semver HANG entry)
- corpus.json — unique patterns with files/apis/flags
- pb_sites.json / lb_sites.json — raw call-site inventory (231 / 3792 sites)
- Report: java-reggie `doc/temp/2026-09-16-replaceability-assessment-profiling-logs-backends.md`
- Harness sources in /tmp/rf/ (Harness.java via gen_harness.py; Verify/V2/V3/Semver/Scale probes)

Key raw numbers: dynamic (unresolvable) explicit-API sites: pb 8, lb 176.
JDK-invalid false positives: 28 (lb string-methods). Matcher-API footprint:
lb group( 438 / find( 212 / start( 36 / end( 20; pb group( 54 / find( 76.
