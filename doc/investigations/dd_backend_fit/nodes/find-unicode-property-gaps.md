---
id: find-unicode-property-gaps
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [p1, compat, unicode, posix-classes]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P1: general Unicode property classes unsupported (only \p{L}/\p{N})

Rejected on trunk: `\p{Alnum}`, `\p{Alpha}`, `\p{ASCII}`, `\p{Cntrl}`,
`\p{Lower}`, `\p{IsAlphabetic}` (and negations, e.g. `[^\p{ASCII}]`,
`[^\p{Alnum}.]`). README documents only `\p{L}`, `\p{N}` + negations.

7 sites, all logs-backend. Ordinary backlog (README says script/name properties
not yet implemented) — these POSIX-ish aliases are needed for full
logs-backend literal coverage. Note `UNICODE_CHARACTER_CLASS` flag (1 lb site)
also unsupported (JdkPatternCompatibility throws).
