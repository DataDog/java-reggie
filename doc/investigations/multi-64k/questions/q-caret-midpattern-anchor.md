---
id: q-caret-midpattern-anchor
type: question
status: open
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
