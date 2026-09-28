---
id: find-asm-toobytearray-sizecheck
title: ASM MethodTooLargeException fires at toByteArray(), NOT at visitMaxs() — any method-size probe must call toByteArray() on its scratch writer; probe results that skip it silently over-report "fits"
kind: finding
tags: [asm, methodtoolargeexception, tobytearray, probe]
applies_to: []
source: multi-64k/find-asm-toobytearray-sizecheck
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# MethodTooLargeException fires at toByteArray(), not at visitMaxs()

Empirically verified with a scratch probe (25,000 × {NOP; ICONST_1; POP} ≈ 75,001 code bytes
into a `ClassWriter(0)`): `visitMaxs` returns normally, `visitEnd` returns normally,
`toByteArray()` throws `MethodTooLargeException` with `getCodeSize()=75001`. The COMPUTE_FRAMES
| COMPUTE_MAXS writers used by Reggie behave the same.

Consequences:
1. **A size probe must call `toByteArray()`** on its scratch writer. A probe that only emits +
   visitMaxs silently reports 72–144KB methods as "fits" — the flush then takes the verbatim
   path and the entry point's toByteArray throws later.
2. The real writer's guard surfaces at the entry point's `toByteArray()` → inside
   `RuntimeCompiler`'s existing try/catch → L3 fallback preserved (a chunk-margin bug cannot
   escape to a corrupt class, only to the fallback).
