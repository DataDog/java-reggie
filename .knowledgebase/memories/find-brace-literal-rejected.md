---
id: find-brace-literal-rejected
title: JDK treats a bare '}' (not part of a valid quantifier) as a literal — reggie rejected it with PatternSyntaxException (fixed: parseAtom treats it as literal; mustache {{…}} patterns depend on this)
kind: finding
tags: [p1, compat, parser, brace, mustache, syntax]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
source: dd_backend_fit/find-brace-literal-rejected
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# JDK treats a bare '}' as a literal; reggie rejected it (now fixed)

JDK accepts a bare `}` (not part of a valid quantifier) as a literal; reggie used to
throw `PatternSyntaxException: Unexpected metacharacter '}'`. The syntax-level rejection
preceded the fallback decision, so `compileAllowingFallback` did NOT rescue these.

13 patterns affected: profiling-backend 1 (`\{([\w.]+)}`), logs-backend 12 —
notably EVERY `{{ … }}` mustache-template parsing pattern (urlencode, is_match,
is_exact_match, #if/eval, local_time…) and `~<lambda>|~\{closure}|…`. Also bare
`}` alone. No pattern-rewrite workaround exists for consumers.

FIXED (commit 0727796): `}` (and an un-quantifier `{`) parse as a literal; invalid
quantifier specs still error with JDK parity.
