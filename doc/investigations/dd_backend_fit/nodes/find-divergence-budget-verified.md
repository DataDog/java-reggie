---
id: find-divergence-budget-verified
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [correctness, differential, divergence]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Only 7/1150 literal patterns diverge from JDK on synthetic inputs

Differential (matches/find booleans, match span, group spans+values, ~19
inputs/pattern incl. non-ASCII): 7 divergences total.
- 3 = groupCount charclass bug (find-groupcount-charclass-parens)
- 2 = NUL bug (find-nul-pattern-truncation)
- 1 = optional-group span divergence `^(?:(\d+):)?([0-9A-Za-z.~^_]+)…` — fits
  the documented 28-item fuzz divergence budget family (span-only, boolean OK)
- 1 = flag artifact (UNICODE_CHARACTER_CLASS passed to JDK but not reggie —
  not an engine divergence)

Graceful fallback worked as designed for the ~17 fallback-eligible rejects
(JavaRegexFallbackMatcher engaged). Engine correctness on production-shaped
patterns is otherwise clean.
