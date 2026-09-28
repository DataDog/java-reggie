---
id: find-fallback-optin-refuse
title: JDK fallback is opt-in (ALLOW_JDK_FALLBACK) — by default reggie REFUSES patterns it cannot compile natively with UnsupportedPatternException; every degradation path funnels through RuntimeCompiler.fallbackOrThrow's single gate
kind: finding
tags: [fallback, jdk-fallback, unsupported-pattern-exception, refuse-by-default, safety-posture, redos, linear-time]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java, reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieOptions.java]
source: multi-64k/find-fallback-optin-refuse
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Fallback is opt-in: default posture is fail-fast refusal, never silent degradation

Code-verified on feat/dd_backend_check @ a5ef0fb:
- RuntimeCompiler.fallbackOrThrow (line ~787): `if (!options.has(ReggieOption.ALLOW_JDK_FALLBACK))
  throw new UnsupportedPatternException(reason);` — every degradation path (MethodTooLarge,
  deadline, StateExplosion, compile-scope OOM) funnels here.
- Exactly ONE construction site of JavaRegexFallbackMatcher in reggie-runtime main sources,
  inside fallbackOrThrow behind that gate — no bypass path.
- ReggieOptions.builder().allowJdkFallback() is an explicit enable(); the option is off by
  default. Empirically: default Reggie.compile on declined patterns throws
  UnsupportedPatternException (e.g. lookahead×1000 → 286ms refusal).

Consequences:
- Default safety posture = fail-fast refusal, NOT silent degradation to backtracking
  java.util.regex. The README's engine-comparison table ("O(n) guaranteed / ReDoS safe",
  footnote "applies to Reggie.compile(), default, native engine only — throws
  UnsupportedPatternException") is grounded in exactly this gate.
- For JDK-regex replacement: default behavior never silently changes match semantics —
  a declined pattern is an explicit exception the service must route.
- For RE2J replacement: the residual exposure is the REFUSE rate (patterns reggie won't
  compile natively become explicit errors where RE2J would still serve them linearly) — a
  census question, not a silent-safety one. Opt-in ALLOW_JDK_FALLBACK re-introduces
  backtracking semantics only when a service explicitly chooses it.
- Optional hardening: PikeVMMatcher(NFA, String) is a runtime NFA interpreter constructible
  from the already-built NFA at the MethodTooLarge catch point — a linear-time terminal
  fallback that would reduce the refuse rate without regressing the guarantee.
