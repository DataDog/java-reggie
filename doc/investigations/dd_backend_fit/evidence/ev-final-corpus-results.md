---
id: ev-final-corpus-results
type: evidence
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: [find-fixes-complete, find-remaining-incompatibilities-resolved]
tags: [corpus, coverage, divergence, final]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Final 1150-pattern corpus results after the 6-fix sequence

Harness re-run against feat/dd_backend_check @ dbd51e3 (same corpus and
methodology as ev-harness-results; incremental results after each fix in
/tmp/rf/results_{fixed,nulfix,bracefix,gcfix,posixfix,final}.jsonl — final
copy preserved at
~/dd/java-reggie/.investigations/replaceability-2026-09-16/results_after_fixes.jsonl).

Final strict Reggie.compile coverage (unique patterns):
- profiling-backend: explicit 47/47 (100%), string-method 31/31 (100%)
- logs-backend: explicit 535/549 (97%), string-method 496/523 (94% of all;
  98% of JDK-valid — 28 are textual-scan false positives)
- call sites with native-compiling pattern: 2369/2419 (97%)
- divergences 7 -> 2, zero new; remaining:
  (a) [^\w:\-\.\/] with UNICODE_CHARACTER_CLASS flag (flag artifact),
  (b) ^(?:(\d+):)?... optional-group span (documented 28-item fuzz budget)
- full gradle suite green after each fix (cleanTest-verified at the end)

UPDATE 2026-09-16 (residuals pass, commit 2fc44cc): after the 5 remaining
incompatibility fixes (60fda73..2fc44cc) the corpus is at its terminal state —
1110/1150 strict-native (the .*[\p{IsAlphabetic}].* site became native), DIVERGENCES: 0,
zero regressions. Preserved at
~/dd/java-reggie/.investigations/replaceability-2026-09-16/results_after_incompatibilities.jsonl
