---
id: find-sourceinterpreter-pathologies
title: SourceInterpreter's two measured pathologies — copyOperation masks value producers (local 1's source is the ASTORE, not the LDC) and quadratic source-set growth at loop-carried merges (Analyzer.merge pinned in a >120s hang)
kind: finding
tags: [asm, sourceinterpreter, quadratic, astore-masking, analysis]
applies_to: []
source: multi-64k/find-sourceinterpreter-pathologies
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# SourceInterpreter pathologies for slot-typing at cut points

Two independent problems, both empirically verified:

**1. copyOperation masks producers.** `SourceInterpreter.copyOperation` attributes a value to
the *copy instruction*: after `ldc "abc"; astore 1`, local 1's source set is `[VarInsnNode#58]`
(the ASTORE), not the LDC. A local's type at a cut therefore cannot be derived directly from
its source insns — stores must be unmasked (primitive stores from the opcode; ASTORE by
recursing into the stored operand's sources; xLOAD stack sources by recursing into the loaded
local's sources). That unmasking machinery was built, then deleted when the interpreter was
replaced.

**2. Quadratic set growth on loop-carried locals.** Source sets grow ~1 node per loop
iteration at merge points (each `Lend`-style merge unions the previous iteration's accumulated
set with new defs), so Frame merges perform O(k) set unions → O(n²) total. A 78k-insn test
method (6,001 if/else iterations) took >120s, hung the Gradle test worker; jstack pinned
`Analyzer.merge` (Analyzer.java:642). ASM's `MethodNode.accept`-time behavior is fine — it's
only the *analysis* that degrades.

Ruled out: SourceInterpreter as the typing basis for any splitter/analysis over generated
NFA-style methods (see the dead-end and DescriptorInterpreter memories).
