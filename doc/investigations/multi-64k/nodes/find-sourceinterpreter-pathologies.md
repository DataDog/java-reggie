---
id: find-sourceinterpreter-pathologies
type: finding
status: confirmed
depends_on: [ev-asm-semantics-probes, ev-merge-hang-threaddump]
supersedes: []
related: [dead-sourceinterpreter-typing, find-descriptor-interpreter-design]
tags: [asm, sourceinterpreter, quadratic, astore-masking, analysis]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# SourceInterpreter has two pathologies for slot-typing at cut points

## Reasoning chain
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

## What this rules out
- dead-sourceinterpreter-typing — SourceInterpreter as the typing basis for the splitter

## Open questions
- none
