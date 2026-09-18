---
id: find-fallback-optin-refuse
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [q-prod-readiness, find-deadline-coverage-gaps]
tags: [fallback, jdk-fallback, unsupported-pattern-exception, refuse-by-default, safety-posture, redos, linear-time]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# JDK fallback is opt-in; by default reggie REFUSES patterns it cannot compile natively

## Reasoning chain
Code-verified 2026-09-17 (feat/dd_backend_check @ a5ef0fb):
- RuntimeCompiler.fallbackOrThrow (line ~787): `if (!options.has(ReggieOption.ALLOW_JDK_FALLBACK))
  throw new UnsupportedPatternException(reason);` — every degradation path (MethodTooLarge,
  deadline, StateExplosion, compile-scope OOM) funnels here.
- Exactly ONE construction site of JavaRegexFallbackMatcher in reggie-runtime main sources,
  inside fallbackOrThrow behind that gate — no bypass path.
- ReggieOptions.builder().allowJdkFallback() is an explicit enable(); the option is off by
  default. Empirically seen all session: default Reggie.compile on declined patterns throws
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
- Optional hardening (q-prod-readiness): PikeVMMatcher(NFA, String) is a runtime NFA
  interpreter constructible from the already-built NFA at the MethodTooLarge catch point —
  a linear-time terminal fallback that would reduce the refuse rate without regressing
  the guarantee.

## Evidence
- Code: RuntimeCompiler.java:786-794; single `new JavaRegexFallbackMatcher` at :791;
  ReggieOptions.java:71-73 (explicit enable).
- README.md:158-168 engine comparison table + footnote.
- Session probes: DlProbe2 default-options runs → UnsupportedPatternException (see
  ev-deadline-fix-verification).

## Open questions
- (none for the mechanism itself; refuse-rate census tracked in q-prod-readiness)
