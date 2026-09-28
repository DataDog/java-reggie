---
id: find-semver-exponential-compile
title: Bounded quantifiers {n,m} scaled compile exponentially (semver pattern never finished; 64x bound = 68.6s, 128x+ effectively infinite) — request-supplied patterns were a CPU-DoS; bounded by work budget + deadline + NFA state cap
kind: finding
tags: [p0, bug, bitstate, quantifier, compile, dos, semver]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/**, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieNativeCompileBudget.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieCompiledPatternCompiler.java]
source: dd_backend_fit/find-semver-exponential-compile
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Exponential compile on bounded quantifiers {n,m} (bounded, correct now)

The canonical semver pattern (logs-backend
`domains/rum/libs/rum-commons-domain/src/main/java/com/dd/rum/utils/SemVerParser.java`,
~300 chars, three `{0,256}`-style quantifiers + named groups) NEVER finished
compiling under `Reggie.compile()` — killed after 10+ min; JDK compiles instantly.

Measured scaling (BitStateMatcher, synthetic semver variants, Scale.java):
bound 2→121ms, 4→19ms, 8→51ms, 16→215ms, 32→2.3s, 64→68.6s, 128/256 effectively
infinite. Exponential in the {n,m} bound.

Security angle: request-supplied patterns can nest `{n,m}` to CPU-DoS the
compiler. re2j protects via program-size caps; reggie's
`ReggieNativeCompileBudget` caps only source *length* (default 16,384 chars in
`ReggieCompiledPatternCompiler`), and plain `RuntimeCompiler.compile` applies
no budget at all.

RESOLVED: root cause was analysis-side, not BitState (see the bitstate-blowup-root
memory); bounded by NFA state cap (1M), DFA work budget + deadline, and the
total-compile deadline — semver ~850ms, bombs get a graceful
UnsupportedPatternException instead of a hang or OOM.
