---
id: find-repo-method-size-landscape
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [find-l2-splitter-shipped, find-no-overflow-trigger-today]
tags: [jvm, method-limit, codegen, architecture, layered-design]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Reggie's method-size landscape and the agreed layered design

## Reasoning chain
JVM caps one method's bytecode at 65,535 bytes (code_length is a `u2` — an over-limit method
cannot even be encoded; post-hoc repair of a serialized class is impossible). Reggie generates
specialized matcher classes per pattern (36+ strategies in `reggie-codegen/codegen/`), so some
patterns overflow one method.

Pre-existing repo state:
- **L3 (last resort)**: `RuntimeCompiler.compile()` catches `MethodTooLargeException` →
  `fallbackOrThrow` → JDK `java.util.regex` delegation with a warning (reggie-runtime,
  ~line 1042). Comment names NFABytecodeGenerator as a generator without splitting.
- **L1 (per-generator structural lowering)**: precedent `DFASwitchBytecodeGenerator`
  STATE_SPLIT_THRESHOLD=100 — bucket helpers `$ng_step_N`/`$gt_step_N`, ~30KB each; recent
  commits added huge-charset boolean[] lookup tables and BitState (compact) alternatives.

Agreed design (conversation): **L1** generators lower as far as their structure allows;
**L2** = generic in-pipeline splitter as safety net (this work); **L3** unchanged. Failure of
L2 must degrade to verbatim emission → today's exception → L3 (never worse than status quo).

## Evidence
- doc/plans/method-size-splitting.md (written + updated this session, uncommitted)

## Open questions
- none for this node
