---
id: q-input-side-nul
type: question
status: resolved
depends_on: [find-nul-pattern-truncation]
supersedes: []
related: []
tags: [nul, input, correctness, audit]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/swar/**, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Is input-side NUL fully correct in reggie (StringView/SWAR paths)?

**RESOLVED 2026-09-16**: RESOLVED (audited, no engine change needed): 2,152 differential checks zero divergences — SWAR XOR/range-mask never treats NUL as sentinel; both String coders; bounded regions over 4 CharSequence types. Pinned by InputSideNulAuditTest.


Pattern-side NUL truncation is confirmed. Input-side NUL was only spot-checked
(pattern b over "a\0b"/"a\0c" correct). reggie's SWAR/StringView code may treat
NUL as a sentinel in bulk-scan fast paths → matching past a NUL in the input
could be wrong for some strategies. Needs a focused differential (inputs with
embedded NULs × all strategies) before declaring NUL handling fixed; the fix
for the pattern side must not stop at the parser.
