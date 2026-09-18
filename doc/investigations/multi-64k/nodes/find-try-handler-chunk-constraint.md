---
id: find-try-handler-chunk-constraint
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [dead-handler-extent-heuristic, find-l2-splitter-shipped]
tags: [jvm, exception-table, try-catch, cut-points, verifier]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Try regions: entry+handler-entry must share a chunk; handler bodies may tail-chain

## Reasoning chain
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
declined, verbatim threw).

## What this rules out
- dead-handler-extent-heuristic

## Evidence
- SplittingClassVisitorTest.tryRegionStaysWholeAndBothPathsBehave — both paths exact after fix

## Open questions
- none
