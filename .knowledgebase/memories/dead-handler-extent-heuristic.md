---
id: dead-handler-extent-heuristic
title: Exception-handler noCut zone must be {region start, region end−1, handler entry} only — a handler-extent heuristic (walk to first terminal) over-conservatively no-cuts the whole method for fall-through handlers
kind: dead-end
tags: [exception-handler, no-cut-zone, refuted-approach]
applies_to: []
source: multi-64k/dead-handler-extent-heuristic
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# noCut = positions separating {region start, end−1, handler entry} — not the handler extent

First attempt at protecting try/handler regions: noCut zone = protected region ∪ handler
*extent*, where the extent was computed by walking forward from the handler label to its first
`athrow`/`return`/`goto`/switch. Two flaws:

1. **Over-conservative**: a fall-through handler (catch sets a flag, then continues into the
   method tail) has no terminal → the walk ran to the end of the method → the entire method
   became no-cut → `chooseCuts` declined (observed: tryRegion test declined then verbatim
   threw).
2. **Wrong model**: the handler body extending across a cut is *fine* — control flows through
   the chunk tail chain; only the handler *entry label* must live in the region's chunk.

Correct rule: region + handler entry must share a chunk; bodies may tail-chain. noCut =
positions separating {start, end−1, handler}.
