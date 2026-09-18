---
id: find-l2-splitter-shipped
type: finding
status: confirmed
depends_on: [find-asm-classwriter-final, find-asm-toobytearray-sizecheck, find-descriptor-interpreter-design, find-try-handler-chunk-constraint, ev-split-class-javap]
supersedes: []
related: [find-repo-method-size-landscape, find-no-overflow-trigger-today]
tags: [implementation, splitter, pipeline, wiring, tests]
generator: cairn
created: 2026-09-16
updated: 2026-09-17
---

# L2 generic splitter: what shipped, where, and the implementation subtleties

## Reasoning chain
Shipped on branch feat/dd-backend-check (on top of 2fc44cc), uncommitted:

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

Subtleties found only by tests:
- `validateChunks` must not add a tail-call estimate for the final boundary — `insnCount`
  is the end boundary, not a chunk entry (first crash: IndexOutOfBounds in `extrasAt`); the
  final chunk has no tail call.
- Crossing GOTO → inline call+return; crossing conditional → jump to a local end-of-chunk
  label block; crossing switch case → per-case local label blocks. Forward jumps into a
  *later chunk's interior* are forbidden by noCut by construction.

## Evidence
- ev-validation-green — full validation results

## Open questions
- q-passthrough-overhead (benchmark), q-commit-hygiene (commit split)

## Post-ship updates (still uncommitted)
- **Fixed**: `visitMaxs(0,0)` blind spot (find-visitmaxs-zero-blindspot) — every real generator
  method had silently declined; analyze() now repairs node.maxLocals/maxStack. Regression test
  added: suite 7/7.
- **Documented**: wide-switch methods (per-config NFA cascade) stay unsplittable in v1 —
  doc/plans/method-size-splitting.md "Wide-switch methods stay unsplittable in v1".
- v2 patch = v1 + MethodSplitter fix + test + doc (56 other files byte-identical).
- **Battery verdict (post-benchmark)**: every constructible overflow family declines (12/12)
  — see find-l2-dead-code-verdict + ev-split-coverage-probe. Suite now 8/8.
- **Fixed**: computeExtras early-return could drop live non-param extras → chunk-signature
  corruption (find-computeextras-early-return-bug).
- Patches: v1 (benchmark A/B) -> v2 (visitMaxs fix + test 7/7 + doc) -> v3 (computeExtras fix +
  test 8/8 + doc). v3 = v2 + exactly 2 files; v2 = v1 + 3 files.

## DISPOSITION (2026-09-16): DROPPED from the tree
User decision after the effectiveness analysis (find-l2-dead-code-verdict). The implementation
never landed as a commit; the working tree was reverted to 2fc44cc (+ formatting). Preserved:
reports/l2-splitter-v3-dropped.patch (v3 = final with both post-ship fixes),
reports/l2-splitter-v1-benchmark.patch (A/B-benchmarked tree). Resurrect via git apply from
2fc44cc. All subtleties documented here remain accurate for any future revival.
