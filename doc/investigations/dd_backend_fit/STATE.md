# Current State

## Active investigation
Feasibility check: can reggie (trunk) replace JDK regex + re2j in profiling-backend and logs-backend.
**Assessment COMPLETE** — verdict delivered; report at
`~/dd/java-reggie/doc/temp/2026-09-16-replaceability-assessment-profiling-logs-backends.md`
(reggie trunk fd362c2; pb e105e153d8, lb d6c8aed5347c). Investigation is now in
"fix findings in reggie" phase (this branch: feat-dd-backend-check).

## Current hypothesis
(none — all hypotheses resolved; hyp-bitstate-blowup-root CONFIRMED, see node)

## What I'm doing now
ALL residual incompatibilities RESOLVED (commits 60fda73..2fc44cc; see
find-remaining-incompatibilities-resolved): optional-group span leaks (chain
capture hygiene + B17 TDFA routing), UNICODE_CHARACTER_CLASS ((?U), BMP-differential
verified), input-side NUL audit (2152 checks clean), total-compile deadline (10s
default, NFA pass-through), huge-charset codegen (transition merge + lookup tables).
CORPUS: 1110/1150 strict-native, DIVERGENCES: 0, full suite green. Only
q-fallback-syntax-gap remains open (general design question, not a blocker).

## Open questions
- q-fallback-syntax-gap — general design question (should compileAllowingFallback defer to
  JDK parsing on reggie syntax errors?) — the concrete brace case was fixed via parser
  leniency in 0727796; not a migration blocker

## Confirmed findings (don't re-derive)
- find-fixes-complete — all 6 checklist items fixed, commits 99a9461..dbd51e3,
  final corpus 97-100%, divergences 7→2 (see node for residuals)
- find-remaining-incompatibilities-resolved — all 5 residuals fixed (60fda73..2fc44cc);
  corpus 1110/1150 strict-native, divergences 0
- find-replaceability-coverage — 93-100% literal coverage (pb 97/100%, lb 93/98%)
- find-semver-exponential-compile — P0 hang/DoS: bound 32→2.3s, 64→69s, 256→∞
- find-nul-pattern-truncation — P0: "\0" pattern matches everything
- find-brace-literal-rejected — P1: 13 patterns incl. all mustache templates
- find-groupcount-charclass-parens — P1: `[()\s]` → groupCount 1
- find-unicode-property-gaps — P1: 7 lb sites need POSIX-ish classes
- find-api-surface-sufficient — rich API OK; grok SPI+shadow = rollout seam
- find-case-insensitive-viable — inline (?i): 15/15, 0 divergence
- find-divergence-budget-verified — 7/1150 divergences, causes identified
- find-re2j-dynamic-safety-gap — re2j sites all untrusted → blocked on budget

## Ruled out (don't re-investigate)
- dead-env-gotchas — stale index.lock, 'green' refspec, git ls-files for lb,
  ASM classpath, incremental harness output (all resolved, use workarounds)
- String-method false positives (28 glob/path "patterns") — not regex, no action
- Scala/Kotlin regex in pb — none exists (checked all 13 files)
