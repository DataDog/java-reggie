---
id: find-brace-literal-rejected
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: [q-fallback-syntax-gap]
tags: [p1, compat, parser, brace, mustache, syntax]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P1: JDK-literal '}' rejected outright — even under compileAllowingFallback

JDK accepts a bare `}` (not part of a valid quantifier) as a literal; reggie
trunk throws `PatternSyntaxException: Unexpected metacharacter '}'`. Verified:
`compileAllowingFallback` does NOT rescue these — the syntax-level rejection
precedes the fallback decision (see q-fallback-syntax-gap).

13 patterns affected: profiling-backend 1 (`\{([\w.]+)}`), logs-backend 12 —
notably EVERY `{{ … }}` mustache-template parsing pattern (urlencode, is_match,
is_exact_match, #if/eval, local_time…) and `~<lambda>|~\{closure}|…`. Also bare
`}` alone.

Fix: treat `}` (and un-quantifier `{`) as literal when not a valid quantifier,
in both strict and fallback paths. No pattern-rewrite workaround exists for
consumers.
