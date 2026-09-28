---
id: find-unicode-property-gaps
title: POSIX/Unicode property classes (\p{Alnum}..\p{IsAlphabetic}) implemented, incl. (?U) UNICODE_CHARACTER_CLASS; remaining loud rejects under (?U): \b/\B and \p{Graph}/\p{Print}/\p{XDigit} (JDK sets not reproduced)
kind: finding
tags: [p1, compat, unicode, posix-classes]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
source: dd_backend_fit/find-unicode-property-gaps
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# POSIX/Unicode property classes: implemented; remaining (?U) gaps are \b/\B and Graph/Print/XDigit

Was: rejected on trunk — `\p{Alnum}`, `\p{Alpha}`, `\p{ASCII}`, `\p{Cntrl}`,
`\p{Lower}`, `\p{IsAlphabetic}` (and negations) unsupported; README documented only
`\p{L}`, `\p{N}`. 7 sites, all logs-backend.

RESOLVED: POSIX ASCII classes (Alnum/Alpha/Digit/Lower/Upper/Blank/XDigit/ASCII/
Cntrl/Space/Graph/Print/Punct) + IsAlphabetic/IsLetter/IsDigit Unicode-aware landed
(f52461a); UNICODE_CHARACTER_CLASS (?U) landed with set membership DERIVED EMPIRICALLY
over the whole BMP (d6e49b9 — see the incompatibilities-resolved memory for the derived
sets). `\p{IsAlphabetic}` standalone strict-rejects on the 64KB method limit only for
huge-charset shapes — fixed by bitmap charset codegen (2fc44cc).

Remaining gaps (loud reject, by design): under (?U), `\b`/`\B` (Unicode word boundary
needs every engine evaluator mode-aware — out of scope) and `\p{Graph}`/`\p{Print}`/
`\p{XDigit}` (JDK sets not reproduced). Script/name properties still unimplemented
(README).
