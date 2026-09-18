---
id: ev-alternation-retry-landed
type: evidence
status: confirmed
depends_on: [ev-leftmost-pruning-landed]
supersedes: []
related: [ev-lazy-hybrid-landed]
tags: [alternation, priority, retry, hybrid, ae3a2c4]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Alternation-priority retry LANDED (ae3a2c4): matched 225.3 -> 187.6us (-17%), 1.21x ahead of rust

## Mechanism
The priority-aware retry is now unconditional for PIKEVM/BITSTATE originals whose captureless
pass declined (lazy OR alternation/optional priority): analyzeAndRecommendPriorityAware
(renamed mode capturelessPriorityRetry) bypasses the three alternation-priority re-route blocks;
the certification + leftmost-first pruning own the soundness. The pruned-DFA marker moved onto
DFA.leftmostFirstPruned (HybridMatcher.lazyFind keys off it — alternation retries route
matches()/anchored spans to the NFA half like lazy ones).

## Soundness holes caught pre-commit (gates earned their keep again)
1. fuzz 48879 on 0*(-\Z|[1b_-b])|.[--aac]+: the hybrid re-matches the DFA span as a STANDALONE
   substring for capture extraction — a $/\Z inside an alternation branch fires at the span
   boundary in the re-match where it would not in-context (group 1 [-1,-1) vs [1,2)). Fix:
   certification declines hasStringEndAnchorInAlternation ||
   hasBareEndAnchorLeadingInAlternation. THE HYBRID'S SUBSTRING RE-MATCH IS A GENERAL
   ANCHOR-CONTEXT HAZARD — keep in mind for any future hybrid admission relaxation.
2. The hybrid path lacked the standalone sections' FallbackPatternDetector guard — added
   (needsFallback != null -> skipHybrid): without it, capture-divergent shapes could hybridize
   once the retry succeeds for them.

## Corpus/measured (box, controls flat: rust 227.2/1724.3, jdk 649.5/31768)
- MATCHED 225.300 -> 187.623±2.145 us (-16.7%) — 1.21x FASTER THAN RUST, 3.5x ahead of jdk.
- NOMATCH 700.0 -> 695.1±5.2 (flat).
- Lane-2 matched arc: 340 -> 187.6 us (-45%). Corpus: 74 -> 75 hybrid.
- go/npm semver (^v?(0|[1-9]\d*)\.(...)$) hybridizes with JDK-identical spans — its char
  classes exclude \n so the refined \Z/$-race certification passes (unpruned: anchored).
- maven semver (possessive {1,19}+) STAYS PikeVM, honest decline: atomic groups in a DFA need
  charset-disjoint exits; the maven's prerelease \w overlaps the inner digits (give-back
  semantics). Probe: a{1,2}+a on "aa" = jdk no-match, greedy-DFA would match [0,2) — divergence
  class proven; sound condition declines the maven shape.

## Gates
Full suite; RealFindParity 266,662/0; RealInputParity 270,351/0; fuzz 777/48879/131071 zero.
