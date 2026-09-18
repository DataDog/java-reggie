---
id: find-computeextras-early-return-bug
type: finding
status: confirmed
depends_on: [find-l2-splitter-shipped]
supersedes: []
related: [find-visitmaxs-zero-blindspot]
tags: [bug, liveness, extras, chunk-signature, corruption, post-ship-fix]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# computeExtras early-return could drop live non-param extras — fixed

## Reasoning chain
Found by code reading during the battery analysis. `computeExtras` had:
`if (live.nextSetBit(0) >= 0 && live.nextSetBit(0) < paramSlots) return List.of();` — an
optimization meaning "all live slots are params" that actually only checked the FIRST live
slot. A method with live params AND live non-param locals at a cut (the common real shape)
would return empty extras → chunk signatures missing live state → VerifyError at load or
silent miscompilation. Never fired in the shipped tests: the ()I tests have paramSlots=0
(early return dead) and the (II)I crossing test carries everything in params (no extras
needed). The existing `for (int s = live.nextSetBit(paramSlots); …)` loop already handles the
all-params case correctly — the early return was pure liability. Deleted; regression test
`splitsWithLiveParamsAndLocalAcrossCut` ((II)J, params + long accumulator live across cuts,
exact closed-form result) added — suite 8/8.
