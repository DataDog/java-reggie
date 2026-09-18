---
id: find-nul-pattern-truncation
type: finding
status: confirmed
depends_on: [ev-nul-verify]
supersedes: []
related: [q-input-side-nul]
tags: [p0, bug, nul, c-string, truncation, pattern]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P0: NUL character truncates the pattern → matcher matches everything

`Reggie.compile("\0")` (single NUL char as pattern) yields a matcher whose
find()/matches() return TRUE on every input (pattern-side C-string truncation
semantics). `\x00` hex escape behaves the same. `\x41`/`\x{48}` are correct.

Impact sites found in consumers:
- logs-backend production: `domains/event-platform/libs/processing/processing-common/
  src/main/java/com/dd/ciapp/processors/Utils.java:9` —
  `str.replaceAll("\u0000", "")` would strip ENTIRE strings if migrated.
- profiling-backend test: `TraceProcessorIntegrationTest.java:83,112`
  `split("\0")` on proto string-cell tables.

Input-side NUL looked correct in spot checks (pattern=b over a<NUL>c=false,
a<NUL>b=true) — only pattern-side truncation confirmed; see q-input-side-nul.
