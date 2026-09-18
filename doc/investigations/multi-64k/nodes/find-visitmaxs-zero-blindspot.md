---
id: find-visitmaxs-zero-blindspot
type: finding
status: confirmed
depends_on: [find-l2-splitter-shipped, ev-split-coverage-probe]
supersedes: []
related: [find-l2-splitter-shipped, hyp-v2-driver-lowering]
tags: [visitmaxs, computemaxes, asm-analyzer, bug, post-ship-fix, maxlocals]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# visitMaxs(0,0) generators made the shipped L2 splitter blind to all real methods — fixed

## Reasoning chain
Discovered after the benchmark, by deliberately compiling a 40k-char literal through the real
pipeline: the splitter declined with `AnalyzerException at instruction 0: "Trying to set an
inexistant local variable 0"`.

- Generators stream into COMPUTE_MAXS writers and legally declare `visitMaxs(0, 0)` — **~230
  call sites across 21 generator files** (NFABytecodeGenerator alone: 22).
- The buffered `MethodNode` therefore carries `maxLocals = 0`, and ASM's `Analyzer` sizes its
  initial Frame from `node.maxLocals` → refuses at instruction 0 → `analyze()` returned false →
  **every real generator method silently declined to verbatim**. The L2 net shipped effectively
  inert for the pipeline it was built for.
- The unit suite passed because `buildClass` declared real maxima (`visitMaxs(64, 64)`) — tests
  never exercised the 0/0 contract the generators actually use.

**Fix (in tree, uncommitted, v2 patch):** in `MethodSplitter.analyze()`, before analysis:
`node.maxLocals = max(varAccessed)+1 (≥ paramSlots)` and
`node.maxStack = max(node.maxStack, 64)`. The real writer recomputes both at serialization, so the
repair affects the analysis only. A still-too-small repaired stack floor (operand stack >64 in a
method that declared zeros) surfaces as AnalyzerException → decline → verbatim — never corrupt.
Regression test `splitsOversizedMethodDeclaredWithZeroMaxes` (suite now 7/7, reggie-runtime +
codegen green, spotless clean).

## Open questions
- Should the floor 64 be derived (e.g. paramSlots + heuristic) instead of constant? Not urgent —
  failure mode is decline, not corruption.
