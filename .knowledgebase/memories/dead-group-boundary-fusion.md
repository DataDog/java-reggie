---
id: dead-group-boundary-fusion
title: Group-boundary state fusion in the capture-tracking DFS is net-negative — group writes sit on loop edges off the hot path, while the extra ops array taxes every push/pop
kind: dead-end
tags: [fusion, group-boundary, dfs, capture, perf, measured-negative, reverted]
applies_to: []
source: backend-ready/dead-group-boundary-fusion
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# Group-boundary state fusion in the capture-tracking DFS is net-negative

## What was tried
Fusing group enter/exit-only states into incoming edges so capture writes ride on edges
instead of dedicated marker frames. Two variants built and fully tested:
- EAGER: ops applied at push — pays ~150 wasted capture clones for never-popped
  marker-stop frames on greedy paths.
- DEFERRED: ops row on the frame, applied at pop (sound: push pos == pop pos of the
  bypassed state); post-write dedup strictly better.

## Why it was refuted (measured)
- Realistic 313-char accept shape: +1.4–2.4us; reject shape: +6.2–7.8us; only degenerate
  5-char inputs win ~0.1us.
- Instrumentation: fusion removes only ~10 pops on the 313-char accept — the pre-release
  loop body has NO boundary states (group writes sit on loop EDGES, off the winning path) —
  while the extra ops array taxes every push/pop (~0.9ns x ~1700 frames); reject paths pay
  on 5–10x more frames.
- State-id bit-packing of ops (~0.3–0.5ns/pop) also estimated net-negative for the same
  reason.

## Verdict / redirect
Trades a negligible win on degenerate short inputs for microsecond-class losses on
realistic shapes. Short-input closure cost needs a hybrid engine, not fusion.
