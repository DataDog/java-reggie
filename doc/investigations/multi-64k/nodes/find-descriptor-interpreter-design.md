---
id: find-descriptor-interpreter-design
type: finding
status: confirmed
depends_on: [find-sourceinterpreter-pathologies]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [asm, interpreter, type-descriptor, soundness, api-gotchas]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# DescriptorInterpreter: one descriptor per value, O(1) merges, conservative UNKNOWN

## Reasoning chain
Replacement interpreter (`reggie-codegen/codegen/DescriptorInterpreter.java`): every abstract
value is a `DV` wrapping one type-descriptor string (or `UNKNOWN`).

- **copy ops propagate the descriptor unchanged** — the ASTORE-masking problem disappears by
  construction; a local's type at any point is directly the frame value.
- **merge = equal descriptors or UNKNOWN** — no class loading, no set growth; O(n) total.
- **UNKNOWN sources**: divergent merges (the original verifier would compute a LUB —
  reconstructible only with a class hierarchy), `ACONST_NULL` (null-typed local used as a chunk
  param typed Object breaks verification of later uses), uninitialized locals (`newEmptyValue`),
  unresolvable ops.
- **Soundness**: chunk parameter types must be exactly what the verifier would accept at the
  cut. Strict equality is conservative: a divergent slot that is *live* at a cut → the cut is
  rejected, never guessed. `SimpleVerifier` LUB merging was ruled out: it needs classloading,
  and generated class names (ReggieMatcher$xxx) are not loadable in the codegen module.

ASM 9.10 Interpreter API gotchas hit along the way (all fixed in the file):
- interpreter methods are `public`, not `protected`;
- `newExceptionValue(TryCatchBlockNode, Frame<DV>, Type)` — takes a `Type`, not a value;
- values must implement `org.objectweb.asm.tree.analysis.Value` (Frame<V extends Value>);
- `MethodNode.exceptions` is `List<String>` (not String[]), `annotationDefault` is `Object`
  (methods carrying an annotation default are refused by the splitter; verbatim handles them).

## Evidence
- ev-validation-green — all tests pass with this interpreter

## Open questions
- none
