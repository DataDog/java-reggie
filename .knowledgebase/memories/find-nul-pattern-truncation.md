---
id: find-nul-pattern-truncation
title: A pattern-side NUL literal used to truncate the pattern (epsilon-sentinel collision) so matchers matched everything — fixed via ast.EpsilonNode; input-side NUL audited clean (2,152 differential checks, SWAR paths NUL-free)
kind: finding
tags: [p0, bug, nul, c-string, truncation, pattern]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
source: dd_backend_fit/find-nul-pattern-truncation
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Pattern-side NUL used to truncate the pattern (fixed); input-side NUL audited clean

The bug: `Reggie.compile("\0")` (single NUL char as pattern) yielded a matcher whose
find()/matches() returned TRUE on every input. Root cause: `LiteralNode((char)0)` collided
with the epsilon sentinel — a pattern-side C-string-truncation semantics. `\x00` hex
escape behaved the same; `\x41`/`\x{48}` were correct.

Impact sites found in consumers:
- logs-backend production: `domains/event-platform/libs/processing/processing-common/
  src/main/java/com/dd/ciapp/processors/Utils.java:9` —
  `str.replaceAll("\u0000", "")` would strip ENTIRE strings if migrated.
- profiling-backend test: `TraceProcessorIntegrationTest.java:83,112`
  `split("\0")` on proto string-cell tables.

FIXED (commit cbb6ca5): new `ast.EpsilonNode extends LiteralNode`; ~15 epsilon-check
sites converted to instanceof EpsilonNode. Raw NUL/\x00/\0 now match NUL like JDK;
replaceAll("\u0000","")/split("\0") semantics verified.

Input-side NUL separately audited (commit 542a76c): 2,152 differential checks (38
patterns x 30 inputs, matches/find+span+groups/findFrom/findAll + matchesBounded over
String/StringBuilder/StringBuffer/CharBuffer all regions), zero divergences. SWAR
findFirstByte/findFirstHexDigit are XOR/range-mask based — NUL is never a sentinel; both
String coders covered (UTF-16 inputs force non-byte paths).
