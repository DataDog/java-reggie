---
id: ev-lane1-jit-hybrid-landed
type: evidence
status: confirmed
depends_on: [find-jit-hugemethodlimit, q-hybrid-anchored-admission]
supersedes: []
related: [ev-r2b-landed]
tags: [jit, hybrid, re-admission, box-measured, 6b583a8]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Lane 1 LANDED (6b583a8): JIT-sized DFA_SWITCH + hybrid re-admission — BOTH sweeps win

## Changes
1. DFASwitchBytecodeGenerator: size-aware bucket planning (JIT_METHOD_TARGET=5000 est, safety
   1.3, STATE_SPLIT_THRESHOLD stays the per-bucket hard cap) applied to the transition switch,
   findMatchEnd (encoded $fe_step_J helpers — FE_ACCEPTING/FE_PRIORITY_CUT bits carry the
   accepting facts a by-value return cannot), both bounded switches ($nb_step_J shared);
   per-accept anchor/assertion blocks factored into $ng_accA_<id> (String: assertions+anchors)
   and $ng_accC_<id> (CharSequence: anchors only — preserving the bounded sites' semantics).
   Group-tracking shares the same planner.
2. Hybrid re-admission: drop requiresStartAnchor from the exclusion (\b stays, correctness).
   Hybrid NFA half mirrors the standalone engine (newHybridNfaHalf: BitStateMatcher with the
   Laurikari trial for BITSTATE_CAPTURE originals — was PikeVM, 3.4x slower on captures).
   LaurikariHybridDisjointnessTest updated (disjointness via LaurikariEligibility rejecting
   usePosixLastMatch) + POSIX last-match span assertion added.

## Measured
- Target shape (suppressed-kind dfa-half): matchesAtStart 21,477 -> 2,386 bytecodes; dfa.find
  19,538ns -> 756ns on 162 chars (4.7 ns/char, 25.8x — matches the -XX:-DontCompileHugeMethods
  headroom exactly); findMatchFrom 9.2 ns/char (21x). Corpus census: all 41 DFA_SWITCH classes
  <= 8KB max method (was 3 over at ~9.1KB).
- Box JMH (6b583a8, controls flat: rust 228.5/1730, jdk 653/31486):
  MATCHED 313.667±3.931us (was 320-336) — WIN.
  NOMATCH 665.344±10.031us (was 943-955) — 29% FASTER.
  The 10b1a43 anchored-admission flip regressed matched 340->687; the same admission now yields
  320->313.7 — regression inverted into a win (mechanism = the interpreted-codegen diagnosis).
- Gates: full suite, RealFindParity 266,662/0, RealInputParity 270,351/0, fuzz 777/48879/131071
  all zero. Routing: hybrid 28 -> 57 patterns, standalone BITSTATE 60 -> 31.
- Arc totals: no-match 15,571 -> 665us (23.4x total); reggie 2.6x ahead of rust no-match
  (665 vs 1730), 1.37x behind on matched (313.7 vs 228.5), 2.1x ahead of jdk matched.

## Lane-1 remainders (not blocking; separate tranches)
- The 2 OPTIMIZED_NFA monsters (react-decoder 40KB, python-pkgs 19.8KB — NFABytecodeGenerator,
  no bucketing) — real-traffic tail risk, zero sweep impact; fix before shadow rollout ideally.
- "Priority-correct lazy leftmost-first captureless DFA find" (the lazy families still route
  BitState because the analyzer leaves dfa==null for lazy quantifiers) — the deeper lane-1 goal;
  would move the stack-frame/kind-message families to hybrid DFA find.
