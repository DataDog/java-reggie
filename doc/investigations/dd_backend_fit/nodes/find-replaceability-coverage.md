---
id: find-replaceability-coverage
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: [find-api-surface-sufficient, find-re2j-dynamic-safety-gap]
tags: [replaceability, profiling-backend, logs-backend, coverage]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Reggie trunk replaces 93-100% of literal regex patterns in both dd backends

Measured 2026-09-16 against reggie origin/main @ fd362c2, with both consumer
repos freshly updated (profiling-backend e3288d5956→e105e153d8, logs-backend
737f65554f85→d6c8aed5347c, both origin/prod).

Strict `Reggie.compile()` acceptance of unique literal patterns (JDK-valid):

| Repo / cohort | patterns | strict-native | site coverage |
|---|---|---|---|
| profiling-backend / Pattern API | 47 | 46 (97%) | 97% |
| profiling-backend / String methods | 31 | 31 (100%) | 100% |
| logs-backend / Pattern API | 549 | 512 (93%) | 95% |
| logs-backend / String methods | 495 valid | 492 (98%) | 97% |

28 logs-backend String-method "patterns" are textual-scan false positives
(glob matchers `*SA*`, Windows paths, `$1{_}`) — not valid JDK regex, no action.

Rejections split: ~13 brace-literal `}`, ~7 Unicode property classes, ~17
graceful fallback-eligible (alternation-priority 6, anchor-dilution 4,
nullable-capture 4, lazy 1, anchor-in-quantifier 1, compound lookaround 1,
method-too-large). Plus 1 semver hang (see find-semver-exponential-compile).

## Verdict
profiling-backend feasible now (post P0/P1 fixes); logs-backend feasible with
`\p{…}` class support added. Dynamic sites (8 pb + 176 lb explicit) blocked on
compile-budget guarantee.
