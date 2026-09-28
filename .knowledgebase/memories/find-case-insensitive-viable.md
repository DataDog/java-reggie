---
id: find-case-insensitive-viable
title: JDK CASE_INSENSITIVE is safe to map for pure-ASCII patterns; input-side non-ASCII NEVER diverges (İ/ı/K/ſ verified) — the only divergence is non-ASCII pattern letters (reggie folds é->éÉ, JDK without UNICODE_CASE does not)
kind: finding
tags: [flags, case-insensitive, migration]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/compat/JdkPatternCompatibility.java]
source: dd_backend_fit/find-case-insensitive-viable
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# JDK CASE_INSENSITIVE is safe to map for pure-ASCII patterns

`JdkPatternCompatibility.toReggieFlags` maps MULTILINE/DOTALL/LITERAL and (since commit
dbd51e3, `toReggieFlags(pattern, flags)`) JDK CASE_INSENSITIVE for pure-ASCII patterns;
previously it THREW on JDK CASE_INSENSITIVE (Unicode vs ASCII folding). All 15 unique
case-insensitive literal patterns across both repos (incl. pb
BillingMetricsSender VALID_COMMIT_SHA / REGEX_NON_EMPTY_HOST_TAG) compiled
natively via inline `(?i)` prefix with ZERO divergences on the probe set.
One lb site uses `IGNORE_CASE_AND_MULTILINE` (custom constant = CI|MULTILINE, Mongo query path).

Folding equivalence, empirically established: input-side non-ASCII NEVER diverges
(İ U+0130, ı U+0131, Kelvin K U+212A, long-s ſ U+017F all verified against literals AND
char classes — neither engine folds non-ASCII input). The ONLY divergence is non-ASCII
pattern letters: Reggie folds é->[éÉ], JDK without UNICODE_CASE does not.
