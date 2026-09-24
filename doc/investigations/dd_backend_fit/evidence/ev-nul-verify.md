---
id: ev-nul-verify
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-nul-pattern-truncation]
tags: [nul, verification, probe]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# NUL truncation verification outputs (V3.java)

pattern=<NUL literal char>: find("abc")=true (WRONG, expect false),
find("a\0b")=true (correct).
pattern=a: find("x\0ay")=true, matches("a")=true (correct).
pattern=b: find("a\0b")=true (correct), find("a\0c")=false (correct).
Hex escape `\x00` (4-char source) reproduces: find/matches true on every input.
`\x41` and `\x{48}` behave correctly → specifically the NUL value, consistent
with C-string sentinel semantics (pattern truncated at first NUL → empty
pattern matches everything).
