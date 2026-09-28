---
id: find-refusal-set-parity
title: Reggie and rust refusal sets on the real corpus are disjoint; reggie's refusals are JDK-fidelity guards (8/513, reducible to 7 via PikeVM re-route), not resource limits — with allowJdkFallback there are zero functional refusals
kind: finding
tags: [refusals, coverage, migration, jdk-fallback, alternation-priority]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java]
source: backend-ready/find-refusal-set-parity
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# Refusal-set parity: reggie vs rust on the 513-pattern corpus

## Measured (plain compile, no fallback options)
- reggie refuses 10, rust refuses 14 (12 rust-only + 2 shared) — the sets are DISJOINT, so
  replacing one engine with the other DOES introduce new refusals.
- reggie-only refusals (8/513 = 1.6%): 4x alternation-priority conflict (DFA longest-match
  vs NFA first-alternative), 2x anchor-condition dilution in DFA construction, 1x
  nullable-capture divergence, 1x anchor-inside-quantifier. All are CORRECTNESS guards, not
  resource limits.
- Shared: camelCase splitter (lookaround alternation), backref+case-insensitive — no delta.
- rust-only refusals reggie serves natively: 12, incl. the counted-quantifier {0,256}
  semver family via counted-loop lowering (rust hits a real NFA-size wall there).

## Deployment seam
With allowJdkFallback (the natural seam mode) ALL reggie-only refusals route to
java.util.regex — zero functional refusals — and the R1 PrefilteringMatcher wraps the
fallback matcher too, so no-match inputs keep the literal prefilter. Hard-refuse
deployment would strand 1.6% of rules. For the actual grok-on-JDK seam the relevant parity
is vs the JDK, not vs rust.

## Lift analysis
- The alternation-priority-conflict guard is OVER-CONSERVATIVE for 4 of the 8: re-routing
  to PikeVM (which does first-alternative priority AND is linear-time, preserving ReDoS
  resistance) verified 0 divergences vs the JDK oracle over 325 inputs/pattern. Lift
  landed: corpus refusals 10 -> 7 (native 506/513 = 98.6%).
- Honest remains-refused (PikeVM itself refuses or diverges): kind:message and multiline
  \n|$ blocks (anchor-in-quantifier), (^|\S)@[]/] (14 real divergences), nullable-capture
  .*?\{\{ family. These need backtracking semantics to be byte-identical to Java — reggie
  refuses rather than return subtly-wrong spans.

## Root principle
rust accepts all 8 because it promises rust semantics; reggie's contract is JDK-identical
spans (grok field extraction). ReDoS resistance was never the gate — reggie's native
engines are all linear-time; the only honest blocker is semantic fidelity.
