---
id: hyp-bitstate-blowup-root
title: Exponential BitState compile root cause was analysis-side, NOT BitState codegen — per-transition flattenClosure, unmemoized canReachGroupExit, exponential {n,m} unrolling, residual quadratic per-state work
kind: finding
tags: [root-cause, bitstate, dfa, determinization]
applies_to: []
source: dd_backend_fit/hyp-bitstate-blowup-root
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Exponential compile root cause: analysis-side, NOT BitState codegen

CONFIRMED via jstack sampling during hang + fix-by-fix measurement. The
"exponential BitState compile" was actually four stacked problems, none of them
the BitState codegen itself:

1. SubsetConstructor.buildDFA called flattenClosure(anchoredClosures) inside the
   per-(DFA-state, charset) transition loop — O(NFA states x closure size) work
   rebuilt every transition. Hoisting it: semver never-finishes -> ~850ms.
   (jstack leaf frame: SubsetConstructor.flattenClosure under
   PatternAnalyzer.doAnalyze -> buildDFA.)
2. canReachGroupExit (called from isGroupActuallyEntered <- computeTagOperations)
   ran an unmemoized closure-crawling recursion PER TRANSITION, iterating
   O(|closure| x |trans| x |closure|) per invocation. Memoized reverse BFS from
   group EXIT markers: 165s -> ~26s on the 12x (a|b){0,256} bomb.
3. ThompsonBuilder.buildCountedQuantifier unrolls nested {n,m} copies
   exponentially (8^20 states for 20x{0,8}) — OOM before any budget sees it.
   Fixed with the NFA state cap (100K was too low — semver legitimately builds
   ~0.3-0.6M states; 1M default verified under -Xmx512m).
4. Residual: per-state work is still quadratic up to the 10K DFA-state cap;
   the work budget + wall-clock deadline bound it (12x bomb ~10-15s, correct).
