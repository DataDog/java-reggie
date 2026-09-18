---
id: hyp-bitstate-blowup-root
type: hypothesis
status: confirmed
depends_on: [ev-scale-timings]
supersedes: []
related: [find-semver-exponential-compile, find-fixes-complete]
tags: [root-cause, bitstate, dfa, determinization]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Root cause: NOT BitState itself — analysis determinization dominated

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

User's local WIP branch (fix/bitstate-findfrom-ws-run-skip, modified
BitStateBytecodeGenerator) does NOT overlap these findings — they are all in
SubsetConstructor/ThompsonBuilder/parser, untouched by that branch.
