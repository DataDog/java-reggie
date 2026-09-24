---
id: q-fuzz-preexisting-divergences
type: question
status: open
depends_on: [ev-rd-hybrid-landed]
supersedes: []
related: []
tags: [fuzz, pre-existing, backref, span]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Pre-existing out-of-corpus divergences found by fresh fuzz seeds (90210/424242)

All reproduce on 9a4d842 (pre-RD-hybrid); none in the 513-corpus or real-input batteries.
- (1[^c]?)[^-1]*. on "c_1cb"/"b01-a": find boolean false + span [2,5) vs [2,4) — give-back shape
  (FIXED by the RD hybrid tranche, kept as regression canary)
- (.[^b-b]+.+)(\1_[10]){0}0 on "_1a-cc0b1": find boolean false — quantified-backref {0} shape
- .{3,}([^_-a0]c{1,1})[^-0] on "\n00c_cb1_1c1": group 1 span [9,11) vs [10,11)
- [10-_--0]$|[_-c]\z on "a1_-_c\nba\n-": first-match span [10,11) vs [2,3) — $/\z alternation
  (suspect: END-anchor alternation handling, same family as the hybrid substring-rematch hazard)
- 424242 has 5 total (2 shown in first runs; see probe runs for the full list)
Standing seeds: 777/48879/131071/31337/90210/424242 — zero NEW findings on 5e9abbf.
