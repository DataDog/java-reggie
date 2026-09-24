# Investigation Index

## Findings (confirmed)
- find-replaceability-coverage | Reggie trunk covers 93-100% of literal patterns in both dd backends (1150-pattern harness) | [replaceability, coverage] 
- find-semver-exponential-compile | P0: {n,m} bounded quantifiers → exponential BitState compile (semver pattern hangs; DoS vector) | [p0, bitstate, dos]
- find-nul-pattern-truncation | P0: NUL char truncates pattern → matcher matches everything | [p0, nul, truncation]
- find-brace-literal-rejected | P1: JDK-literal '}' rejected outright, even under fallback (13 patterns incl. mustache) | [p1, compat, parser]
- find-groupcount-charclass-parens | P1: groupCount counts ( ) inside character classes | [p1, bug, groupcount]
- find-unicode-property-gaps | P1: \p{Alnum}/\p{Alpha}/\p{ASCII}/\p{Cntrl}/\p{Lower}/\p{IsAlphabetic} unsupported (7 lb sites) | [p1, unicode, posix-classes]
- find-api-surface-sufficient | API surface no longer a blocker; grok SPI + shadow-ratio infra is the insertion seam | [api, grok, spi]
- find-case-insensitive-viable | Inline (?i) viable for CASE_INSENSITIVE migration (15/15 patterns, 0 divergence) | [flags, case-insensitive]
- find-divergence-budget-verified | Only 7/1150 patterns diverge vs JDK; 1 within known 28-item budget, 1 flag artifact | [correctness, differential]
- find-re2j-dynamic-safety-gap | re2j replacement blocked: all re2j sites dynamic/untrusted; reggie lacks bounded compile | [re2j, untrusted, dos, blocker]

## Hypotheses
- hyp-bitstate-blowup-root | CONFIRMED: not BitState — flattenClosure-in-loop + unmemoized canReachGroupExit + Thompson unrolling (details in node) | [root-cause, dfa, determinization] | CONFIRMED

## Dead ends
- dead-env-gotchas | Stale git locks, logs-backend 'green' refspec, use git ls-files, ASM not bundled in reggie jar | [environment, git, classpath] | RESOLVED

## Evidence
- ev-harness-results | 1150-pattern corpus + results (strict/fallback/differential); artifact paths | [harness, corpus]
- ev-scale-timings | BitState compile scaling: 32→2.3s, 64→69s, 256→infinite | [timing, exponential]
- ev-nul-verify | NUL truncation probe outputs (pattern-side wrong, input-side spot-OK) | [nul, verification]

## Questions
- q-input-side-nul | Is input-side NUL fully correct across all strategies? | [nul, input, audit] | OPEN
- q-fallback-syntax-gap | Why doesn't compileAllowingFallback rescue syntax-level rejections? | [fallback, design, parser] | OPEN

## Findings (confirmed) — added 2026-09-16 fix sequence
- find-fixes-complete | All 6 release-checklist items fixed (commits 99a9461..dbd51e3); final coverage 97-100% | [fixes, coverage]
- q-total-compile-deadline | Should Reggie enforce a TOTAL-compile deadline (per-determinization only bounds passes×10s; 12x bomb still 10-15s)? | [design, deadline, untrusted] | OPEN
- q-huge-charset-codegen | Can 1000+-range charsets avoid the 64KB generated-method limit (bitmap codegen for \p{IsAlphabetic} shapes)? | [codegen, 64kb-limit] | OPEN

## Evidence — added 2026-09-16 fix sequence
- ev-final-corpus-results | Final corpus after 6-fix sequence: 97-100% coverage, 2369/2419 sites, divergences 7→2 | [corpus, coverage, final]

## Findings (confirmed) — added 2026-09-16 residuals pass
- find-remaining-incompatibilities-resolved | All 5 residual incompatibilities fixed (span leaks, UNICODE_CHARACTER_CLASS, NUL audit, compile deadline, huge-charset codegen); corpus divergences 0 | [residuals, divergences-zero]

## Question resolutions 2026-09-16
- q-total-compile-deadline | RESOLVED (b0c5615) | [design, deadline]
- q-huge-charset-codegen | RESOLVED (2fc44cc) | [codegen]
- q-input-side-nul | RESOLVED (542a76c, audit clean) | [nul, input]
