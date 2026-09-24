---
id: q-2pct-fallback-policy
type: question
status: answered
depends_on: [ev-backend-usage-survey, find-fallback-optin-refuse]
supersedes: []
related: [q-prod-readiness, find-bounded-quantifier-regression, find-logs-backend-re2j-migration]
tags: [fallback-policy, jdk-fallback, redos, refuse-by-default, degradation, migration-policy, dynamic-patterns]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# "Do the refused 2% need JDK fallback? How are failures handled in the current setup? (constraint: no silent drop to ReDoS-able impl)"

## ANSWER (2026-09-17): No silent JDK fallback is needed — the current services already
## refuse/drop on compile failure everywhere; reggie's default maps 1:1 onto that behavior.

## Current-setup handling (verified in both services, 2026-09-17)
| site | on pattern-compile failure |
|---|---|
| profiling-backend UserDefinedRegex | IllegalArgumentException -> HTTP 400 (refuse request) |
| logs-backend RenamingRuleEvaluator | IllegalArgumentException -> rule SKIPPED for the org (WARN) |
| logs-backend StringerRuleEventFilter | PatternSyntaxException -> LOGGER.error, filter returns null (disabled) |
| logs-backend QuantizationRules | PatternSyntaxException -> rule dropped from compiled scope (WARN) |
| grok Re2jWithJdkFallbackRegexPatternSupplier | RE2J fail -> WARN + SILENT java.util.regex fallback — the only ReDoS-able backdoor, and it is currently UNREFERENCED in main code (mid-migration artifact) |

So the "never silently drop to a ReDoS-able engine" constraint already holds in production
today — degradation is refuse/drop per rule, request, or filter.

## Policy for the 11/563 refused patterns under reggie (all static literals, none dynamic)
1. **Dynamic / user-supplied patterns: NEVER JDK fallback.** Default reggie = Unsupported-
   PatternException -> maps to the existing skip/400/disable handlers verbatim. ALLOW_JDK_
   FALLBACK stays OFF for this class. (Also strictly better than RE2J: reggie accepts the
   lookaround/backref patterns RE2J rejects, so the drop rate shrinks.)
2. **Static literal patterns (the 11 + the semver family): explicit, audited decisions —
   not silent.** Options per pattern: (a) rewrite to a reggie-compatible equivalent that
   preserves JDK semantics (viable esp. for the 4 alternation-priority + 2 anchor-dilution
   cases); (b) keep THAT pattern on java.util.regex via an explicit code-reviewed site — a
   static literal's worst-case behavior is statically analyzable at review time, which is
   exactly what "not silent" means; (c) file as a reggie capability gap where PikeVM-class
   semantics could close it later.
3. Optional future hardening (separate from this decision): a linear-time terminal fallback
   for emission-overflow cases (compact NFA, PikeVM-constructible at the MethodTooLarge
   catch point) — note this is NOT applicable to the bounded-quantifier giant-NFA family
   (find-bounded-quantifier-regression measured PikeVM 125-402x worse there).

## TRIAGE EXECUTED 2026-09-17 (ev-refused-triage)
Of the 11: 4 migratable via verified rewrites (unifiedkv strongest: native NameEnriching,
captures preserved; rustc case needs NO regex at all), 1 rewrite candidate pending
verification (SortTags lookbehind — position parity unverifiable via public API), 6
JDK-retained with marker comments. Effective native coverage post-triage: ~98.9-99.1%.
Meta: version-string parsing is the recurring refused/slow family.

## User decisions captured 2026-09-17
- JDK regex is ALSO in replacement scope (not just RE2J).
- OpenJ9 out of scope for backend replacement.
- Constraint recorded: no silent degradation to ReDoS-able implementations.
