---
id: find-matchtime-heap-scaling
type: finding
status: confirmed
depends_on: [ev-deadline-fix-verification]
supersedes: []
related: [find-deadline-coverage-gaps, q-prod-readiness]
tags: [match-time, memory, pikevm, groups, test-infrastructure, gradle, scaling]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Native matcher match-time memory scales with capture-group count — 12k groups kills a 512m JVM

## Reasoning chain
While writing DeadlineCoverageHolesTest (2026-09-17), the first run killed the Gradle test
executor: suite-level `OutOfMemoryError at PikeVMMatcher.java:193` (java heap space) — NOT the
compile path. The 6g DlProbe runs had compiled calt-6000/x12000 (12k-24k groups) to native
BitState/PikeVM matchers in seconds without ever calling matches(); the test then called
`matches()` on a native matcher for the 12k-group pattern, and MATCH-time allocation
(per-position thread state × group arrays in the NFA simulation) exceeded the test JVM heap.
Gradle test JVMs default to ~512m here (reggie-runtime/build.gradle sets no maxHeapSize; only
`-Xss8m` + add-opens), while the same compile+match at 6g heap completes fine.

Properties established:
- Compile is bounded (a5ef0fb) — this is a separate, PRE-EXISTING match-time property, not a
  deadline hole: the fix bounds compilation cost, not matching memory.
- The exposure is (pattern group count × matching state) — services compiling+matching native
  matchers for pathological group counts need match-time headroom, or a match-scope cap
  (none exists today).
- Test-design consequence (applied in DeadlineCoverageHolesTest): heap-safe tests — semantic
  match assertions only on the fallback path (JavaRegexFallbackMatcher delegates to
  java.util.regex, heap-light); native-route coverage via codegen unit tests + big-heap probe
  evidence.
- For q-prod-readiness: relevant to the RE2J axis only as a footnote — RE2J also grows memory
  with pattern complexity; reggie's linear-native routes have the same class of scaling, so
  this is a capacity-planning input, not a correctness blocker.

## Evidence
- ev-deadline-fix-verification (boundary note: match-time heap called out as separate)
- Failed first run of DeadlineCoverageHolesTest: OOM at PikeVMMatcher:193, 512m test JVM,
  vs identical compile+match completing at 6g (DlProbe2) — same session

## Open questions
- Should there be a match-scope memory/scale guard analogous to the compile emission budget
  (e.g., cap on groups×states at matcher construction)? Not blocking; noted for the
  readiness follow-up if extreme-group service patterns exist in the fleet corpus.
