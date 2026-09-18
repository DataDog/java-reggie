---
id: find-groupcount-charclass-parens
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [p1, bug, groupcount, character-class, parser]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/**, reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P1: groupCount counts parens inside character classes

Verified with findMatch: `[()\s]` → reggie groupCount=1 (JDK 0);
`([\[\]{}()*+?.\\^$|])` → reggie 2 (JDK 1); `[{}()\[\].+*?^$\\|]` → reggie 1
(JDK 0). Matching booleans/spans appeared correct in these cases — group
numbering/extraction is what's wrong (likely a naive paren count feeding the
capture-slot layout).

3 patterns in the consumers hit this (2 logs-backend, both String-method
split/match patterns over punctuation classes).
