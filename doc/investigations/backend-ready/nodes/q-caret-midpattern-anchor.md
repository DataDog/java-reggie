---
id: q-caret-midpattern-anchor
type: question
status: resolved-fixed
depends_on: []
supersedes: []
related: [find-refusal-set-parity]
tags: [anchor, caret, alternation, pre-existing, fuzz]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Mid-pattern ^ in alternation diverges from JDK — OPEN, PRE-EXISTING

## Reasoning chain
Fresh fuzz seed 131071 (first time exploring this region): pattern
`(?:[^a-caa]|c)^|.\\z\\z` on "1\\n0\\n_cbcc_" — jdk first-match [9,10) (the .\\z\\z
branch at the last char) vs reggie [0,1) (the (?:..)^ branch at 0); findAll() counts
differ (1 vs 7). Reproduces IDENTICALLY on the pre-fix tree (222c8e6 minus both fixes —
verified via git stash) — pre-existing, NOT introduced by the leftmost/greedy fixes.
Suspect: mid-pattern `^` (default mode, non-multiline) inside an alternation branch —
reggie appears to accept the (?:X)^ branch at non-zero positions or resolve branch
priority differently. Anchor-dilution-adjacent family (see find-refusal-set-parity for
the honest-refusal relatives). Fix separately; fuzz-seed 131071 is the reproducer
(FuzzProbe in /tmp/prefilter now takes the seed as argv[0]).

## RESOLVED (2026-09-17, 5c3587b + follow-up)
Root cause found by minimization: NOT the caret alone — two independent bugs in the same
shape family:
1. scanForMisplacedStartAnchor had NO AlternationNode case, so a consuming alternation
   (?:c|a) before ^ scanned as anchor-at-start and the misplaced-anchor guard never fired.
   The subset DFA for (?:c|a)^|.\z\z then ERASED the [START] acceptance condition on the
   (?:c|a)-branch accept states (merge with the parallel branch's [STRING_END_ABSOLUTE]
   conditions collapsed to unconditional, no dilution flag) → find() fired ^ at position 1.
   FIX: alternation in the spine counts as consumed when any branch consumes (over-approx,
   safe direction). Fuzz 131071: findings 3 → 0.
2. (Follow-up, found via NEW fuzz seed 777 — always run multiple seeds) the guard's decline
   target OPTIMIZED_NFA miscompiles mid-alternation consumer-then-^ into a GLOBAL start
   anchor: -^.|(?:1-) rejects EVERYTHING (even plain '1-' matching the other branch).
   FIX: decline target → PIKEVM_CAPTURE (routes BitState). Fuzz 777: findings 2 → 0.
Minimal repros: CaretProbe/NfaCaret probes in /tmp/prefilter.
