---
id: find-visitmaxs-zero-blindspot
title: COMPUTE_MAXS generators legally declare visitMaxs(0,0) (~230 call sites, 21 generator files) — ASM Analyzer sizes its frame from node.maxLocals=0 and fails at instruction 0; any MethodNode-based analysis must repair maxLocals/maxStack first (fix in the dropped L2 patch)
kind: finding
tags: [visitmaxs, computemaxes, asm-analyzer, bug, post-ship-fix, maxlocals]
applies_to: []
source: multi-64k/find-visitmaxs-zero-blindspot
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# visitMaxs(0,0) generators blind MethodNode-based analysis

Discovered by compiling a 40k-char literal through the real pipeline: the splitter declined
with `AnalyzerException at instruction 0: "Trying to set an inexistant local variable 0"`.

- Generators stream into COMPUTE_MAXS writers and legally declare `visitMaxs(0, 0)` — **~230
  call sites across 21 generator files** (NFABytecodeGenerator alone: 22).
- The buffered `MethodNode` therefore carries `maxLocals = 0`, and ASM's `Analyzer` sizes its
  initial Frame from `node.maxLocals` → refuses at instruction 0 → `analyze()` returned false →
  every real generator method silently declined. Any MethodNode-based analysis over generator
  output hits this.
- Unit suites pass anyway when the test harness declares real maxima (`visitMaxs(64, 64)`) —
  tests never exercise the 0/0 contract the generators actually use.

Fix (in the preserved dropped-L2 patch, MethodSplitter.analyze()): before analysis,
`node.maxLocals = max(varAccessed)+1 (≥ paramSlots)` and
`node.maxStack = max(node.maxStack, 64)`. The real writer recomputes both at serialization, so
the repair affects the analysis only. A still-too-small repaired stack floor surfaces as
AnalyzerException → decline → verbatim — never corrupt. Regression test
`splitsOversizedMethodDeclaredWithZeroMaxes`.

Open: should the floor 64 be derived (paramSlots + heuristic) instead of constant? Not urgent —
failure mode is decline, not corruption.
