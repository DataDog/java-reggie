---
id: find-semver-exponential-compile
type: finding
status: confirmed
depends_on: [ev-scale-timings]
supersedes: []
related: [hyp-bitstate-blowup-root, find-re2j-dynamic-safety-gap]
tags: [p0, bug, bitstate, quantifier, compile, dos, semver]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/**, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieNativeCompileBudget.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieCompiledPatternCompiler.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P0: bounded quantifiers {n,m} cause exponential BitState compile (hang)

The canonical semver pattern (logs-backend
`domains/rum/libs/rum-commons-domain/src/main/java/com/dd/rum/utils/SemVerParser.java`,
~300 chars, three `{0,256}`-style quantifiers + named groups) NEVER finishes
compiling under `Reggie.compile()` — killed after 10+ min; JDK compiles instantly.

Measured scaling (BitStateMatcher, synthetic semver variants, Scale.java):
bound 2→121ms, 4→19ms, 8→51ms, 16→215ms, 32→2.3s, 64→68.6s, 128/256 effectively
infinite. Exponential in the {n,m} bound.

Security angle: request-supplied patterns can nest `{n,m}` to CPU-DoS the
compiler. re2j protects via program-size caps; reggie's
`ReggieNativeCompileBudget` caps only source *length* (default 16,384 chars in
`ReggieCompiledPatternCompiler`), and plain `RuntimeCompiler.compile` applies
no budget at all.

Blocks: semver parsing site, ALL untrusted/dynamic adoption (UserDefinedRegex,
grok customer patterns) until a state/work budget with graceful
`UnsupportedPatternException` exists, enforced by default in `Reggie.compile()`.
