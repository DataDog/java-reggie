---
id: find-try-handler-chunk-constraint
title: Try-region cut rule: the protected region [start,end) AND the handler ENTRY label must share one chunk; handler bodies may tail-chain across cuts — noCut = positions separating {start, end−1, handler}
kind: finding
tags: [jvm, exception-table, try-catch, cut-points, verifier]
applies_to: []
source: multi-64k/find-try-handler-chunk-constraint
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Try regions: region + handler entry share a chunk; bodies may tail-chain

The per-method exception table means each `tryCatchBlock(start, end, handler, type)` is
re-emitted into the chunk that contains it — so the protected region `[start, end)` and the
**handler entry label** must be in one chunk. The handler **body** is ordinary code and may
extend past chunk boundaries via normal tail chaining (handler-entry → … → cut → chunk tail
call); only the label binding matters.

Cut-safety rule derived: a cut is forbidden iff it separates the triple
{start, end−1, handler} (noCut zone `[min(start,handler)+1 .. max(end-1, handler)]`).

The initial heuristic — bounding the no-cut zone by the handler's *extent* (walk to first
athrow/return/goto) — was wrong: fall-through handlers (`catch { acc = -999 }` then continue
into the method tail) never hit a terminal, so the walk consumed the whole remainder of the
method and `chooseCuts` declined the split (seen: conservative=66018, probe failed, split
declined, verbatim threw). See the handler-extent dead-end memory.
