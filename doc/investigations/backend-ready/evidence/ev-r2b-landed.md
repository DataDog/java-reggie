---
id: ev-r2b-landed
type: evidence
status: confirmed
depends_on: [ev-r2-rd-landed, ev-r1-prefilter-landed]
supersedes: []
related: [hyp-unanchored-find-prefilter, q-lazydfa-findfrom-leftmost, find-rust-engine-crossover]
tags: [r2b, r1b, case-insensitive-facts, 1-char-facts, prefilter, workspace-jb, 97ec426]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# R2b: case-insensitive + 1-char required facts in the R1 prefilter (97ec426)

## Reasoning chain
Residual re-sized with C2-CONVERGED probes (see methodology lesson): no-match 2.66ms split
GENERATED 1.32ms / Hybrid 0.58ms / BitState 0.45ms / NameEnriching 0.19ms. Two fact families
the R1 extractor silently dropped explained the top offenders:
1. Global (?i): parser folds cased letters into char classes -> cased literal facts NEVER surface
   ((?i)^.*kafka\.consume.*$ family, ~320us).
2. No >=2-char literal: version-string family ((.*[a-z0-9/-]*)-([0-9]*) etc., ~470us) has only
   1-char facts ('-'), and [a-z]+_... (~78us) only '_'.

LANDED (97ec426, suite+integration+fuzz 10.5k checks 0 findings green): ci facts extracted from
the leading-(?i)-stripped pattern, scanned ASCII-case-insensitively (exact vs Java (?i) without
(?u); scanning ci is the permissive side); RequiredLiteralAnalyzer.requiredChar = soundness-by-
construction AST walk (concat: any child's required char; alternation: char required by every
branch; quantifier min>=1) -> 1-char facts as last resort; PrefilteringMatcher gains
asciiCaseInsensitive + allocation-free ASCII-ci indexOf. 7 test files updated to unwrap via
EngineRouting (engine-class reflection + adversarial budget test asserts on unwrapped engine).

## Measured (workspace-jb, SAME-DAY single box state, controls flat: rust no-match 1.72-1.77ms,
## jdk 30.5-33.2ms across all four runs)
- reggieSweepNoMatch: 15,571 (5210bac) -> 2,475 (R1 38eeb62) -> 2,070 (R2 b5cf63e) -> 983 (97ec426)
  = 15.8x vs baseline. reggie/rust no-match: 9.0x behind -> 1.79x AHEAD.
- reggieSweepMatched: 353 -> 357 -> 335 -> 338 (flat by design; reggie 1.48x behind rust, 2.0x
  ahead of jdk on matched).
- Post-R1b no-match residual composition: BitState 384us (dominant), generated lanes ~475us
  spread, Hybrid COLLAPSED 582->58us, NameEnriching 61us.

## Methodology lesson (standing rule)
Reggie's GENERATED matcher methods need ~10k+ invocations to reach C2. 200-rep probes measured
C1-level code and overstated reggie ~6x (rust JNI/native and JDK unaffected -> diagnosable).
All future probes warm 12k + measure 4k, or use JMH. Earlier local-probe ABSOLUTE numbers are
inflated; box JMH numbers stand. Box drift is real: the R1/R2-session box state was ~1.85x
slower than today's (same commits re-measured); cross-day absolute comparisons invalid — use
same-run controls (rust/jdk) to normalize.

## R2b-proper disposition
The hybrid single-pass reinjection DFA was NOT needed for the corpus: R1b+ci eliminated the
family's no-match cost (inputs lacking the required char). Remaining hybrid no-match residual
is 58us (inputs WITH '-'). LazyDFACache.findFrom restart-at-death+1 WITHOUT re-walk appears
leftmost-unsound in general (skip of viable starts inside dead attempts; 'ab|b' on 'xaab'
reasoning) — latent (NO corpus pattern routes LAZY_DFA), recorded as q-lazydfa-findfrom-leftmost;
must be fixed before ever routing hybrid through it.
