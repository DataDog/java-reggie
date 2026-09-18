---
id: ev-jit-gate-landed
type: evidence
status: confirmed
depends_on: [find-jit-hugemethodlimit, ev-rd-hybrid-landed]
supersedes: []
related: []
tags: [jit, hugemethodlimit, gate, release-readiness, 5a826bd]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# JIT method-size gate LANDED (5a826bd): lane-3 robustness done; release-ready state

## Census (all 506 compiled corpus patterns, direct classfile walk)
11 methods > 8000 bytecodes, all from exactly 2 patterns: React error-decoder
(OPTIMIZED_NFA_WITH_BACKREFS, 40,045 x 5, 395 NFA states) + python wheel lookahead
(OPTIMIZED_NFA_WITH_LOOKAROUND, 19,840 x 6, 70 states). Next-largest method corpus-wide: 6.6KB.

## Mechanism
largestMethodBytecodes(): direct classfile walk (constant pool -> methods -> Code attribute
u4 code_length; CONSTANT_Integer is u4, Long/Double two slots — first version misread Integer
as u2 and misaligned into opcodes, caught by validation against javap). Gates:
- compileInternal: oversized class -> JDK fallback ONLY with ALLOW_JDK_FALLBACK (JDK faster
  than interpreted generated code). Strict compile() keeps the native interpreted matcher —
  native-or-throw contract + 7-refusal set unchanged (batteries: refused=7 identical).
- compileHybrid: oversized dfa-half -> NFA half alone.
A full NFABytecodeGenerator bucketing refactor was ruled out: disproportionate for 2 corpus
patterns with ~0 sweep weight; the fallback gate gives the same fleet outcome (JDK speed) at
zero codegen risk.

## Measured (box, controls flat: rust 227.8/1724.8, jdk 644.0/32990)
Sweep-neutral as expected: matched 181.8±6.7, no-match 637.4±9.6 (gate only affects the 2
rare-tail patterns under fallback).

## Release-readiness state (branch feat/dd_backend_check through the changelog commit)
- Full build + jacocoVerify green; suite + batteries + 6 fuzz seeds verified this session.
- CHANGELOG 0.4.0 section updated with the arc (matched 340->182us / 1.26x vs rust; no-match
  943->637us / 52x vs jdk; 28->102 hybrids).
- REMAINING (owner actions): merge feat/dd_backend_check -> main; ./scripts/release.sh minor
  (SSM-gated CI publish to Maven Central); logs-backend PR (needs the published 0.4.0 artifact;
  Bastien Lemale's branch is the wiring precedent; ReggieRegexPatternSupplier draft exists from
  the real-input-parity work).
