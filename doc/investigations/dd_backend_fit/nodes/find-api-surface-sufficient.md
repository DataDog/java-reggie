---
id: find-api-surface-sufficient
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [find-replaceability-coverage]
tags: [api, compatibility, grok, spi, shadow]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieMatcher.java, reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieMatcher.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# API surface no longer a blocker; grok SPI + shadow infra is the insertion seam

Trunk `com.datadoghq.reggie.runtime.ReggieMatcher` covers every JDK Matcher op
observed in both repos: matches/find/findFrom, match/findMatch→MatchResult
(indexed+named groups, spans), matchInto/findMatchInto (alloc-free),
replaceFirst/replaceAll (literal + Function<MatchResult,String> — covers
appendReplacement loops and results() streams), split(input[,limit]), findAll,
cursor() with appendReplacement/appendTail.

Caveat: top-level `com.datadoghq.reggie.ReggieMatcher` facade exposes ONLY
matches/find — consumers must type against runtime.ReggieMatcher.

re2j maps 1:1 (`Pattern.matcher(input).find()/matches()`, groupCount, named
groups). logs-backend grok pipeline: production runs `JdkRegexPatternSupplier`
(re2j supplier is non-production); `GrokPatternBundle` supports a SHADOW
supplier with configurable shadow_ratio + `RegexMatcherShadowActor` — A/B
validation infra already built for a `ReggieRegexPatternSupplier`. The re2j
supplier's JDK→RE2 syntax-conversion hack (`(?P<` rewrite) becomes unnecessary.
Remaining JDK-adjacent needs: Pattern.quote (keep on JDK), asPredicate adapter,
Jackson Pattern deserialization (pb YAMLInsightProvider), Map<FrameField,
Pattern> adapter type.
