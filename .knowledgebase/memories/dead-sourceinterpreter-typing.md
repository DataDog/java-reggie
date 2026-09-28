---
id: dead-sourceinterpreter-typing
title: SourceInterpreter is unusable as slot-typing basis for large generated methods — copyOperation masks producers (ASTORE) and source sets grow quadratically at loop-carried merges (78k-insn method hung the test worker >120s)
kind: dead-end
tags: [asm, sourceinterpreter, refuted-approach, performance]
applies_to: []
source: multi-64k/dead-sourceinterpreter-typing
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# SourceInterpreter pathologies make it unusable for slot-typing large generated methods

The original typing plan derived live-slot types from `SourceInterpreter` frames (per-source
descriptors + unmask recursion for ASTORE/xLOAD). Two fatal flaws:

1. Quadratic set growth on loop-carried locals at merge points — a 78k-insn realistic test
   method hung the test worker (>120s, jstack pinned `Analyzer.merge`, Analyzer.java:642).
   Generated NFA-style methods are exactly this shape: each merge unions the previous
   iteration's accumulated set with new defs → O(k) set unions per merge → O(n²) total.
2. ASTORE source masking forced an unmask-recursion layer (operand sources at the store, local
   sources at loads) — `copyOperation` attributes values to the *copy instruction*, so a
   local's type cannot be derived directly from its source insns. Built, working, then deleted
   with the interpreter swap.

Consequence: use a descriptor-flowing interpreter instead (see the DescriptorInterpreter
memory). SourceInterpreter remains fine for small methods or one-shot analyses; the pathology
needs loop-carried locals surviving merge points.
