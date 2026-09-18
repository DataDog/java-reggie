---
id: find-refusal-set-parity
type: finding
status: confirmed
depends_on: [find-rust-engine-crossover, ev-rust-bench-lane-standardized]
supersedes: []
related: [find-backend-prod-regex-cost]
tags: [refusals, coverage, migration, jdk-fallback, alternation-priority, 8-of-513]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Refusal-set parity: reggie vs rust on the 513-pattern corpus — CONFIRMED

## Reasoning chain
USER QUESTION: "is the 10 refused by reggie from the rust's 12 set? if we replace rust with
reggie, we are not going to get new refusals, right?" Answer: NO — the sets are DISJOINT.

Measured (RefusalProbe over the committed corpus; plain compile, no fallback options):
- reggie refuses 10, rust refuses 14 (12 rust-only + 2 shared; the benchmark's "rust=12"
  counts only reggie-accepted patterns).
- reggie-only refusals (NEW if replacing rust): 8/513 = 1.6%:
  4x "alternation priority conflict: DFA longest-match vs NFA first-alternative"
  (tableName rule, requirements.txt parser, semver pre/post/dev, kind:message parser),
  2x "anchor condition diluted in DFA construction" ((^|\S)@[]/], ^/rustc|/rustlib/),
  1x nullable-capture divergence (.*?\{\{...>.*), 1x anchor-inside-quantifier
  (multiline log block). All are CORRECTNESS guards, not resource limits.
- shared refusals (both engines): camelCase splitter (lookbehind/lookahead alternation),
  React minified-error backref+case-insensitive — no delta from replacement.
- rust-only refusals reggie SERVES natively: 12 (incl. the counted-quantifier {0,256}
  semver family via counted-loop lowering).

DEPLOYMENT NUANCE: with allowJdkFallback (the natural seam mode), ALL 8 route to
java.util.regex — zero functional refusals, and the R1 PrefilteringMatcher wraps the
fallback matcher too, so no-match inputs still get the literal rejection prefilter.
Hard-refuse deployment would strand 1.6% of rules.

LIFT LANDED (6caf5db): the 3 alternation-priority rules now re-route to PikeVM
(RuntimeCompiler alternationPriorityConflict branch; needsFallback stays the safety net).
Corpus refusals 10 -> 7 (native 506/513 = 98.6%). kind:message stays refused (own guard),
^/rustc|/rustlib/ deliberately NOT lifted (anchor-dilution class contains a measured
diverger: (^|\S)@[]/]). Gates: full suite + fuzz + corpus audit + route test with span
parity. NOTE (user correction, accepted): logs-backend regex is grok-on-JDK; rust serves
apm-processing, not these patterns — the rust lane is a reference, so refusal parity
matters only for a hypothetical rust-surface replacement; for the actual grok seam the
relevant parity is vs JDK (8 refusals -> 7, zero divergences).

PIKEVM JUDGMENT (measured 2026-09-17, user challenge "rust accepts them, why not reggie"):
hand-built PikeVM matchers (RuntimeCompiler.compilePikeVm bypasses the guard) vs the JDK
oracle, 325 inputs/pattern:
- LIFTS (0 findings): tableName rule, requirements.txt parser, semver pre/post/dev,
  ^/rustc|/rustlib/ — the alternation-priority-conflict guard is OVER-CONSERVATIVE for
  these: it fires when the DFA strategy can't express Java first-alternative preference,
  but the selector refuses instead of re-routing to PikeVM (which does first-alternative
  priority AND is linear-time, so ReDoS resistance is preserved). Native coverage with
  re-route: 507/513 (98.8%).
- HONEST (PikeVM itself refuses or diverges): kind:message (anchor-in-quantifier guard),
  (^|\S)@[]/] (14 real findings — divergence), .*?\{\{... (nullable-capture B16 family),
  multiline \n|$ block (anchor-in-quantifier). These need backtracking semantics to be
  byte-identical to Java — reggie refuses rather than return subtly-wrong spans.

ROOT PRINCIPLE: rust accepts all 8 because it promises rust semantics; reggie's contract
is JDK-identical spans (grok field extraction). ReDoS resistance was never the gate —
reggie's native engines are all linear-time; the only honest blocker is semantic fidelity.
