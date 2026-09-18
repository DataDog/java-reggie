---
id: q-l2-disposition
type: question
status: answered
depends_on: [find-l2-dead-code-verdict]
supersedes: []
related: [find-l2-splitter-shipped, hyp-v2-driver-lowering]
tags: [disposition, decision, scope]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Keep L2 wired / unwire / drop / build v2? — ANSWERED: DROPPED (user decision, 2026-09-16)

Rationale on record (find-l2-dead-code-verdict): fires on nothing real — 11 constructible
overflow families, all declined (wide-switch noCut flood); +9% compile cost on every pattern;
2–12x slower failures on oversized patterns; NFA-L1 supersede probability ~0.4-0.5.
Decision: drop entirely.

Executed: 5 new files deleted (SplittingClassVisitor, MethodSplitter, DescriptorInterpreter,
SplittingClassVisitorTest, doc/plans/method-size-splitting.md), 33 L2-marked files reverted
to 2fc44cc, spotless re-applied. Verified: codegen/runtime/processor/integration tests green,
spotless clean, zero L2 residue; remaining diff vs 2fc44cc = 24 files of pure formatting
drift (+ untracked doc/investigations/ cairn export).

The work is preserved as patches in this investigation: reports/l2-splitter-v3-dropped.patch
(final: + visitMaxs fix + computeExtras fix + 8/8 tests) and
reports/l2-splitter-v1-benchmark.patch (the exact tree the Temurin A/B benchmark ran).
Resurrection: from 2fc44cc, `git apply reports/l2-splitter-v3-dropped.patch`.
Benchmark artifacts remain on workspace-jb: ~/bench/l2-validation/ (jars, probes, results).
