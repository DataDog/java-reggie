---
id: find-l2-splitter-shipped
title: L2 splitter implementation notes for revival (DROPPED — patches preserved at reports/l2-splitter-v3-dropped.patch, apply from 2fc44cc): SplittingClassVisitor/MethodSplitter/DescriptorInterpreter design and the subtleties only tests found
kind: finding
tags: [implementation, splitter, pipeline, wiring, tests]
applies_to: []
source: multi-64k/find-l2-splitter-shipped
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# L2 splitter implementation notes (dropped — revival reference)

Built on feat/dd-backend-check (on top of 2fc44cc), then DROPPED (see the L2 verdict memory).
Never landed as a commit; the working tree was reverted to 2fc44cc. Revive via `git apply`
from 2fc44cc: `reports/l2-splitter-v3-dropped.patch` (v3 = final, includes both post-ship
fixes; v3 = v2 + 2 files, v2 = v1 + 3 files), `reports/l2-splitter-v1-benchmark.patch`
(A/B-benchmarked tree). All subtleties below remain accurate for any future revival.

- **`SplittingClassVisitor.java`** (reggie-codegen/codegen): wraps the real ClassWriter;
  returns a `MethodNode`-backed `BufferedMethod` for every splittable method header
  (`isSplittableHeader`: never `<init>`/`<clinit>`/abstract/native). Flush at visitMaxs:
  conservative upper-bound sizer (jumps as wide forms, ldc_w, padded switches — a fitting
  conservative bound is *guaranteed* to fit) → verbatim replay via `MethodNode.accept`;
  else exact probe (scratch `ClassWriter(0)`, fresh labels, **toByteArray**) → verbatim;
  else `MethodSplitter.trySplit`. All refusals → verbatim → `MethodTooLargeException` → L3.
- **`MethodSplitter.java`**: instruction indexing, CFG/successors (incl. exception edges),
  slot-level backward liveness (ring-buffer worklist), DV frames (Analyzer), noCut zones,
  greedy cut scan at CHUNK_BUDGET=60,000 conservative bytes, `validateChunks` (per-chunk
  conservative size incl. callSeq estimates; try-triples whole; param slot count ≤255),
  emission (chunk0 keeps original header; `$sK` chunks private synthetic, same static-ness;
  per-chunk slot remap — params identity, extras become trailing params, rest shifted;
  tail = push params + invokestatic/invokespecial + matching xreturn; LDC-Integer replays
  via `BytecodeUtil.pushInt` per repo convention).
- **`DescriptorInterpreter.java`** — see find-descriptor-interpreter-design.
- Wiring: `RuntimeCompiler` + `ReggieMatcherBytecodeGenerator` both wrap their writer
  (dual-path rule satisfied at a single shared implementation). `asm-tree` +
  `asm-analysis` 9.10.1 deps added to reggie-codegen.
- Design doc `doc/plans/method-size-splitting.md` updated with all corrections.

## Subtleties found only by tests
- `validateChunks` must not add a tail-call estimate for the final boundary — `insnCount`
  is the end boundary, not a chunk entry (first crash: IndexOutOfBounds in `extrasAt`); the
  final chunk has no tail call.
- Crossing GOTO → inline call+return; crossing conditional → jump to a local end-of-chunk
  label block; crossing switch case → per-case local label blocks. Forward jumps into a
  *later chunk's interior* are forbidden by noCut by construction.
- `visitMaxs(0,0)` blind spot (see that memory) — every real generator method silently
  declined until analyze() repaired node.maxLocals/maxStack.
- Wide-switch methods (per-config NFA cascade) stay unsplittable in v1 — documented in
  doc/plans/method-size-splitting.md.
- computeExtras early-return fix (see that memory).
