---
id: ev-rd-hybrid-landed
type: evidence
status: confirmed
depends_on: [ev-alternation-retry-landed]
supersedes: []
related: [ev-lane1-jit-hybrid-landed]
tags: [recursive-descent, give-back, hybrid, 5e9abbf]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# RD give-back hybrid LANDED (5e9abbf): matched -2.8%, no-match -7.7% — both sides win

## Mechanism
RECURSIVE_DESCENT originals routed there only by requiresBacktrackingForGroups (capture give-back:
a([bc]*)(c+d)) hybridize: captureless DFA serves boolean find, PikeVM serves captures (thread
priority = universal give-back engine). Hard RD (subroutines/conditionals/quantified backrefs/
lookaround) never yield a captureless DFA -> availability filter; lazy-RD stays JDK-fallback via
the uniform needsFallback(ast, PIKEVM_CAPTURE) guard. needsFallback uses the PIKEVM predicate for
ALL hybrid originals (the hybrid never runs the RD engine; B5 RD-descent bugs don't apply).

## Measured (box, controls flat: rust 230.6/1731.2, jdk 651.6/33080)
- MATCHED 187.6 -> 182.4±8.3 us (-2.8%; probe's 22us RD residual was synthetic-line-weighted)
- NOMATCH 695.1 -> 641.9±16.3 us (-7.7% — the RD unanchored .* backtracking scans on no-match
  lines replaced by fast DFA rejection)
- Corpus: 75 -> 102 hybrid (+27). Lane-2 totals: matched 340 -> 182.4 (-46%), no-match
  943 -> 641.9. Vs rust: matched 1.27x ahead, no-match 2.7x ahead. Vs jdk: 3.6x / 51x.
- Arc no-match total: 15,571 -> 641.9us = 24.3x.

## Gates + fuzz-seed lesson
Full suite; both batteries 0/537k. Fresh fuzz seeds 31337/90210/424242 run for the wider blast
radius: ZERO new findings — 90210's four divergences all reproduce on the pre-change build
(backref `(.[^b-b]+.+)(\1_[10]){0}0` boolean, `.{3,}([^_-a0]c{1,1})[^-0]` group span,
424242's `[10-_--0]$|[_-c]\z` span + others) — PRE-EXISTING out-of-corpus divergences, recorded
as q-fuzz-preexisting-divergences (open). The RD change fixed 3 of seed 90210's 7.
Standing seeds now 777/48879/131071/31337/90210/424242.
