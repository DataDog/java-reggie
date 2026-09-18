---
id: dead-sourceinterpreter-typing
type: deadend
status: refuted
depends_on: [find-sourceinterpreter-pathologies]
supersedes: []
related: []
tags: [asm, sourceinterpreter, refuted-approach, performance]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# SourceInterpreter as slot-typing basis — REFUTED

## Reasoning chain
The original typing plan derived live-slot types from `SourceInterpreter` frames (per-source
descriptors + unmask recursion for ASTORE/xLOAD). Two fatal flaws emerged:

1. Quadratic set growth on loop-carried locals at merge points — a 78k-insn realistic test
   method hung the test worker (>120s, thread dump in ev-merge-hang-threaddump). Generated
   NFA-style methods are exactly this shape.
2. ASTORE source masking forced an unmask-recursion layer (operand sources at the store, local
   sources at loads) — built, working, then deleted with the interpreter swap.

## What replaced it
- find-descriptor-interpreter-design — DescriptorInterpreter (descriptors flow through copies;
  O(1) merges; UNKNOWN on divergence/null)

## Caveat
SourceInterpreter remains fine for small methods or one-shot analyses; the pathology needs
loop-carried locals surviving merge points.
