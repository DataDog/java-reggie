---
id: find-computeextras-early-return-bug
title: computeExtras early-return could drop live non-param extras at cuts → chunk-signature corruption (VerifyError or silent miscompilation) — fix lives only in the dropped L2 splitter patch (reports/l2-splitter-v3-dropped.patch)
kind: finding
tags: [bug, liveness, extras, chunk-signature, corruption, post-ship-fix]
applies_to: []
source: multi-64k/find-computeextras-early-return-bug
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# computeExtras early-return could drop live non-param extras

Found by code reading during the splitter battery analysis. `computeExtras` had:
`if (live.nextSetBit(0) >= 0 && live.nextSetBit(0) < paramSlots) return List.of();` — an
optimization meaning "all live slots are params" that actually only checked the FIRST live
slot. A method with live params AND live non-param locals at a cut (the common real shape)
would return empty extras → chunk signatures missing live state → VerifyError at load or
silent miscompilation. It never fired in the shipped tests: the ()I tests have paramSlots=0
(early return dead) and the (II)I crossing test carries everything in params (no extras
needed). The existing `for (int s = live.nextSetBit(paramSlots); …)` loop already handles the
all-params case correctly — the early return was pure liability.

Status: the fix (delete the early return; regression test `splitsWithLiveParamsAndLocalAcrossCut`,
(II)J with a long accumulator live across cuts) exists only in the preserved dropped-L2 patch
`reports/l2-splitter-v3-dropped.patch` — re-apply it on any revival.
