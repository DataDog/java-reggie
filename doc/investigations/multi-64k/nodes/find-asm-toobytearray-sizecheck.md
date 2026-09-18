---
id: find-asm-toobytearray-sizecheck
type: finding
status: confirmed
depends_on: [ev-asm-semantics-probes]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [asm, methodtoolargeexception, tobytearray, probe]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# MethodTooLargeException fires at toByteArray(), NOT at visitMaxs()

## Reasoning chain
Empirically verified with a scratch probe (25,000 × {NOP; ICONST_1; POP} ≈ 75,001 code bytes
into a `ClassWriter(0)`): `visitMaxs` returns normally, `visitEnd` returns normally,
`toByteArray()` throws `MethodTooLargeException` with `getCodeSize()=75001`. The COMPUTE_FRAMES
| COMPUTE_MAXS writers used by Reggie behave the same (test failures surfaced at
`realWriter.toByteArray()`).

Consequences:
1. **A size probe must call `toByteArray()`** on its scratch writer. The first probe version
   only emitted + visitMaxs → it silently reported 72–144KB methods as "fits" → flush took the
   verbatim path → the entry point's toByteArray threw later. Found via flush-debug prints
   (`conservative=71994 probeFits=true`).
2. The real writer's guard surfaces at the entry point's `toByteArray()` → still inside
   `RuntimeCompiler`'s existing try/catch → L3 preserved (a chunk-margin bug cannot escape
   to a corrupt class, only to the fallback).

This corrects the earlier in-conversation claim that MethodWriter throws in visitMaxs —
the user's doubt ("I am not sure whether ASM does not check the method size while writing")
was well-founded.

## Evidence
- ev-asm-semantics-probes — full probe output

## Open questions
- none
