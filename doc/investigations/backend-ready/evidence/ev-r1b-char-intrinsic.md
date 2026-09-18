---
id: ev-r1b-char-intrinsic
type: evidence
status: confirmed
depends_on: [ev-4lane-bench-results]
supersedes: []
related: []
tags: [prefilter, r1b, intrinsic, simd, 293d0e6]
generator: cairn
created: 2026-09-18
updated: 2026-09-18
---

# 293d0e6: 1-char prefilter facts use the char indexOf intrinsic (7.3x on the LdapNoMatch LONG outlier)

The 4-engine matrix (ev-4lane-bench-results) had ONE shape where an engine beat reggie:
LdapNoMatch LONG (24KB no-match line, required literal '('), re2j 361 vs reggie 276 ops/ms.
Root cause: PrefilteringMatcher called the STRING indexOf overload even for 1-char facts; the
String intrinsic's first-char scan is ~5.7x slower on x86 than the char indexOf(int) intrinsic
(box probe: 2.73 vs 0.48us over the same 22KB input — NOTE the local mac probe showed ~1x,
NEON/Apple masked the gap; never trust local for intrinsics). Fix: 1-char exact facts ->
indexOf(char); 1-char ci facts -> dual lower/upper exact scans with min (extraction validated
the fact char is ASCII, so the pair is complete; non-letters have no fold).

Box verification: LdapNoMatch LONG 276 -> 2,026 ops/ms (7.3x; re2j 358, jdk 90, rust 17 —
reggie now leads every shape in the matrix). RealCorpus (513-pattern isolation run):
no-match 639.3 -> 628.2us (~9% relative vs same-run jdk control drifting +7%), matched flat
180.2. NOTE: the 528-pattern corpus run (e61fdd9+fix) reads matched 211.6 / no-match 711.7 —
that is corpus-growth cost of the 15 synthetic guards (reggie grew 17% matched vs jdk's 3%),
documented as the new 528 baseline. Gates: suite green, batteries 0-div, fuzz 15/0-reg. RERUN NOTE: JMH method-level selectors are regexes over fully-qualified method names — use '.*LdapNoMatch', NOT 'IastRegexpBenchmark.LdapNoMatch' (the dotted form matches nothing; gradle fails exit-1 with 'No matching benchmarks').
