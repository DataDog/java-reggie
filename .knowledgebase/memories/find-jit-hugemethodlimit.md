---
id: find-jit-hugemethodlimit
title: Generated DFA-switch matcher methods above HotSpot's HugeMethodLimit (8000 bytecodes) run interpreted forever — the "slow DFA lane" is a JIT blindspot, not an algorithmic cost
kind: finding
tags: [jit, hugemethodlimit, dfa-switch, codegen, interpreted, mechanism]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/DFASwitchBytecodeGenerator.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java]
source: backend-ready/find-jit-hugemethodlimit
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# The "slow DFA lane" is a JIT blindspot (HugeMethodLimit), not an algorithm

## Mechanism (proven by flag-only A/B on identical generated code)
Generated DFA_SWITCH methods (matchesAtStart=21.5KB, matchInto=17.9KB, match=17.5KB,
matchesBounded=15.9KB, matches=13.8KB, findMatchEnd=9KB on the measured pattern) all exceed
HotSpot's HugeMethodLimit (default 8000 bytecodes): methods above it are NEVER JIT-compiled,
C1 or C2 — they run interpreted. Same pattern, same generated class, only the JVM flag
changed:
- dfa.find: 19,538ns -> 941ns with -XX:-DontCompileHugeMethods (20.8x; 120.6 -> 5.8 ns/char)
- dfa.findMatchFrom: 31,762ns -> 1,787ns (17.8x; 196 -> 11.0 ns/char)
The compiled code is fast as-is; the 140–420ns/char "DFA cost" attributed to it elsewhere
was interpreted-execution cost.

## Root causes in DFASwitchBytecodeGenerator
- STATE_SPLIT_THRESHOLD=100 buckets per-state case logic into $ng_step_N helpers sized
  against the 64KB JVM hard limit (~30KB helpers) — 3.75x over the 8KB JIT limit.
- The accept-state check block (per accept state: sequential state==id compare + full
  anchor-condition emission, ~400B/accept for $-anchor families) is emitted INLINE in the
  main method — alternation-heavy patterns (dozens of accept states) blow past 8KB even
  when transitions are bucketed.
- matchesAtStart/findMatchEnd for anchored shapes also inline the anchor prologue.

## Corpus census (513 real patterns, bytecode dump + javap size walk)
Only 5 classes exceed 8000 bytecodes: 3 DFA_SWITCH (9.0–9.1KB), 1 OPTIMIZED_NFA_WITH_
BACKREFS (40KB), 1 OPTIMIZED_NFA_WITH_LOOKAROUND (19.9KB). All DFA_UNROLLED (86) and CHAIN
(44) classes are under 8KB. The 5 contribute ~0 to JMH sweeps (canonical inputs match none;
no-match is R1-prefiltered) — their cost is a REAL-TRAFFIC tail (a prod line paying
interpreted rates): a shadow-rollout robustness item, not a sweep item.

## Consequences
- Hybrid re-admission of start-anchored patterns is blocked ONLY by this: a JIT-able
  dfa-half fixes the find() regression.
- compileHybrid picks PikeVM as nfa-half even when the original routed BITSTATE_CAPTURE
  (skips the routeBitState upgrade): measured 45.5us vs 13.5us BitState on the capture
  path. Fix candidate: nfa-half = BitState for those.

## Method note
Dump the generated classes with -Dreggie.debug.bytecode=<dir>, walk max javap offsets per
method; A/B timing with/without -XX:-DontCompileHugeMethods isolates JIT-blindspot cost.
