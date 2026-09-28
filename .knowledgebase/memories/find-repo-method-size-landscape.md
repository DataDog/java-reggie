---
id: find-repo-method-size-landscape
title: JVM caps a method at 65,535 bytes of bytecode (u2 code_length — over-limit methods cannot even be encoded); reggie's layering: L1 per-generator structural lowering (DFASwitch bucketing, charset tables, BitState), L2 generic splitter (dropped), L3 MethodTooLargeException → fallbackOrThrow JDK delegation
kind: finding
tags: [jvm, method-limit, codegen, architecture, layered-design]
applies_to: []
source: multi-64k/find-repo-method-size-landscape
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Method-size landscape and the L1/L2/L3 layering

JVM caps one method's bytecode at 65,535 bytes (code_length is a `u2` — an over-limit method
cannot even be encoded; post-hoc repair of a serialized class is impossible). Reggie generates
specialized matcher classes per pattern (36+ strategies in `reggie-codegen/codegen/`), so some
patterns overflow one method.

Layering:
- **L3 (last resort)**: `RuntimeCompiler.compile()` catches `MethodTooLargeException` →
  `fallbackOrThrow` → JDK `java.util.regex` delegation with a warning (reggie-runtime,
  ~line 1042). Comment names NFABytecodeGenerator as a generator without splitting.
- **L1 (per-generator structural lowering)**: precedent `DFASwitchBytecodeGenerator`
  STATE_SPLIT_THRESHOLD=100 — bucket helpers `$ng_step_N`/`$gt_step_N`, ~30KB each; huge-charset
  boolean[] lookup tables (2fc44cc) and BitState (compact) alternatives absorb the known
  overflow families.
- **L2 (generic in-pipeline splitter)**: built, measured as firing on nothing real, and
  DROPPED (see the L2 verdict memory; patches preserved). Failure of L2 must degrade to
  verbatim emission → today's exception → L3 (never worse than status quo).

Design doc: doc/plans/method-size-splitting.md.
