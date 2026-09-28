---
id: find-descriptor-interpreter-design
title: DescriptorInterpreter for slot-typing at chunk cuts — one descriptor per value, copy ops propagate unchanged, merge = equal-or-UNKNOWN (O(1), no classloading); SimpleVerifier LUB merging is impossible because generated class names are not loadable in the codegen module
kind: finding
tags: [asm, interpreter, type-descriptor, soundness, api-gotchas]
applies_to: []
source: multi-64k/find-descriptor-interpreter-design
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# DescriptorInterpreter: descriptor-flowing slot typing with O(1) merges

Replacement interpreter (built in the dropped L2 splitter patch,
`reggie-codegen/codegen/DescriptorInterpreter.java`): every abstract value is a `DV` wrapping
one type-descriptor string (or `UNKNOWN`).

- **copy ops propagate the descriptor unchanged** — the ASTORE-masking problem (see the
  SourceInterpreter memory) disappears by construction; a local's type at any point is directly
  the frame value.
- **merge = equal descriptors or UNKNOWN** — no class loading, no set growth; O(n) total.
- **UNKNOWN sources**: divergent merges (the original verifier would compute a LUB —
  reconstructible only with a class hierarchy), `ACONST_NULL` (null-typed local used as a chunk
  param typed Object breaks verification of later uses), uninitialized locals (`newEmptyValue`),
  unresolvable ops.
- **Soundness**: chunk parameter types must be exactly what the verifier would accept at the
  cut. Strict equality is conservative: a divergent slot that is *live* at a cut → the cut is
  rejected, never guessed. `SimpleVerifier` LUB merging was ruled out: it needs classloading,
  and generated class names (ReggieMatcher$xxx) are not loadable in the codegen module.

ASM 9.10 Interpreter API gotchas (all fixed in the file):
- interpreter methods are `public`, not `protected`;
- `newExceptionValue(TryCatchBlockNode, Frame<DV>, Type)` — takes a `Type`, not a value;
- values must implement `org.objectweb.asm.tree.analysis.Value` (Frame<V extends Value>);
- `MethodNode.exceptions` is `List<String>` (not String[]); methods carrying an
  annotation default are refused by the splitter (verbatim handles them).
