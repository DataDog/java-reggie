---
id: find-groupcount-charclass-parens
title: groupCount must count only parens outside character classes — a naive textual paren count breaks capture-slot layout (fixed; 3 consumer patterns hit it)
kind: finding
tags: [p1, bug, groupcount, character-class, parser]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/**, reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
source: dd_backend_fit/find-groupcount-charclass-parens
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# groupCount must not count parens inside character classes (fixed)

A naive textual paren count feeding groupCount/capture-slot layout counts parens inside
`[...]` classes. Verified with findMatch before the fix: `[()\s]` → reggie groupCount=1
(JDK 0); `([\[\]{}()*+?.\\^$|])` → reggie 2 (JDK 1); `[{}()\[\].+*?^$\\|]` → reggie 1
(JDK 0). Matching booleans/spans were correct in these cases — group
numbering/extraction was what was wrong.

FIXED (commit fdd6df2): the textual scan now skips `[...]` classes. 3 consumer patterns
hit this (2 logs-backend, both String-method split/match patterns over punctuation
classes).
