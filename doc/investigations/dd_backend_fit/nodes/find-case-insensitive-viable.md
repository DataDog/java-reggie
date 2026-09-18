---
id: find-case-insensitive-viable
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [flags, case-insensitive, migration]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/compat/JdkPatternCompatibility.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Inline (?i) is a viable CASE_INSENSITIVE migration; JDK flag int is rejected

`JdkPatternCompatibility.toReggieFlags` maps MULTILINE/DOTALL/LITERAL but THROWS
on JDK CASE_INSENSITIVE (Unicode vs ASCII folding). All 15 unique
case-insensitive literal patterns across both repos (incl. pb
BillingMetricsSender VALID_COMMIT_SHA / REGEX_NON_EMPTY_HOST_TAG) compiled
natively via inline `(?i)` prefix with ZERO divergences on the probe set,
including non-ASCII inputs (ÄÖÜ äöü, ΣΙΣΥΦΟΣ, K U+212A, İ). One lb site uses
`IGNORE_CASE_AND_MULTILINE` (custom constant = CI|MULTILINE, Mongo query path).
UPDATE 2026-09-16 (post-fix): folding equivalence now established.
Input-side non-ASCII NEVER diverges (İ U+0130, ı U+0131, Kelvin K U+212A,
long-s ſ U+017F all verified against literals AND char classes — neither
engine folds non-ASCII input). The ONLY divergence is non-ASCII pattern
letters: Reggie folds é->[éÉ], JDK without UNICODE_CASE does not.
Consequence: JDK CASE_INSENSITIVE is safe to map for pure-ASCII patterns —
implemented in commit dbd51e3 as
JdkPatternCompatibility.toReggieFlags(pattern, flags).
