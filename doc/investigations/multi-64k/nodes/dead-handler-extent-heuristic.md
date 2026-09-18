---
id: dead-handler-extent-heuristic
type: deadend
status: refuted
depends_on: []
supersedes: []
related: [find-try-handler-chunk-constraint]
tags: [exception-handler, no-cut-zone, refuted-approach]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Handler-extent-to-first-terminal noCut zone — REFUTED

## Reasoning chain
First attempt at protecting try/handler regions: noCut zone = protected region ∪ handler
*extent*, where the extent was computed by walking forward from the handler label to its first
`athrow`/`return`/`goto`/switch. Two flaws:

1. **Over-conservative**: a fall-through handler (catch sets a flag, then continues into the
   method tail) has no terminal → the walk ran to the end of the method → the entire method
   became no-cut → `chooseCuts` declined (observed: tryRegion test declined then verbatim
   threw).
2. **Wrong model**: the handler body extending across a cut is *fine* — control flows through
   the chunk tail chain; only the handler *entry label* must live in the region's chunk.

## What replaced it
- find-try-handler-chunk-constraint — region + handler entry must share a chunk; bodies may
  tail-chain. noCut = positions separating {start, end−1, handler}.
