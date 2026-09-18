---
id: find-jit-hugemethodlimit
type: finding
status: confirmed
depends_on: [q-hybrid-anchored-admission]
supersedes: []
related: [ev-lazydfa-nfa-delegate-limit]
tags: [jit, hugemethodlimit, dfa-switch, codegen, interpreted, mechanism]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# The "slow LazyDFA/RD lane" is a JIT-blindspot, not an algorithm

## Mechanism (PROVEN, r5 box-local experiment 2026-09-17)
The generated DFA_SWITCH matcher class for the suppressed-kind pattern has
matchesAtStart=21,477 bytecodes, matchInto=17.9k, match=17.5k, matchesBounded=15.9k,
matches=13.8k, findMatchEnd=9k — ALL over HotSpot's HugeMethodLimit (default 8000
bytecodes: methods above it are NEVER JIT-compiled, C1 or C2 — they run interpreted).
Proof (DumpGen2 on /tmp/r5, same pattern, same generated code, only the JVM flag):
- dfa.find:        19,538ns ->    941ns with -XX:-DontCompileHugeMethods (20.8x, 120.6 -> 5.8 ns/char)
- dfa.findMatchFrom: 31,762ns -> 1,787ns (17.8x, 196 -> 11.0 ns/char)
So the 140-420ns/char "DFA_SWITCH matchesAtStart cost" in q-hybrid-anchored-admission
is INTERPRETED-execution cost. The 20x headroom means the compiled code is FAST as-is.

## Root cause in DFASwitchBytecodeGenerator
- STATE_SPLIT_THRESHOLD=100 buckets per-state case logic into $ng_step_N helpers sized
  against the 64KB JVM hard limit (~30KB helpers) — 3.75x OVER the 8KB JIT limit.
- The accept-state check block (per accept state: sequential state==id compare + FULL
  anchor-condition emission, ~400B/accept for $-anchor families) is emitted INLINE in the
  MAIN method — for alternation-heavy patterns (dozens of accept states) this alone blows
  past 8KB even when transitions are bucketed.
- matchesAtStart/findMatchEnd for anchored shapes ALSO inline the anchor prologue.

## Corpus census today (513 patterns, -Dreggie.debug.bytecode dump + javap size walk)
Only 5 classes exceed 8000: OPTIMIZED_NFA_WITH_BACKREFS react-decoder 40,059;
OPTIMIZED_NFA_WITH_LOOKAROUND python-pkgs 19,854; DFA_SWITCH elasticsearch 9,123,
kafka-consume 9,086, kafka-produce 9,114. All under 8KB: DFA_UNROLLED (86), CHAIN (44),
everything else. The 5 contribute ~0 to both JMH sweeps (6 canonical INPUTS match none of
them; no-match is R1-prefiltered) — their cost is REAL-TRAFFIC tail (a React-error line in
prod paying interpreted rates) — a shadow-rollout robustness item, not a sweep item.

## Consequences
- Hybrid re-admission of start-anchored patterns: blocked ONLY by this (JIT-able dfa-half
  fixes the find() regression; reggieSweepMatched is a find()-boolean sweep).
- compileHybrid picks PikeVMMatcher as nfa-half even when the original routed
  BITSTATE_CAPTURE (skips the routeBitState upgrade) — measured 45.5us vs 13.5us BitState
  on the suppressed-kind capture path. Fix candidate: nfa-half = BitState for those.
- The suppressed-kind 21.5KB dfa-half exists only when anchored patterns enter hybrid
  (they don't today); the 3 marginal DFA_SWITCH classes exist today.

## Probes
DumpGen2 (/tmp/prefilter, timing with/without -XX:-DontCompileHugeMethods),
SizeCensus + -Dreggie.debug.bytecode=/tmp/prefilter/gen-classes dump,
/tmp/methodsize.py + /tmp/allsize.py (javap max-offset walker).
