---
id: find-matchtime-heap-scaling
title: Native matcher MATCH-time memory scales with pattern group count (per-position thread state × group arrays) — 12k-group pattern OOMs a 512m JVM while compiling fine; heap-safe tests assert semantics on the fallback path only
kind: finding
tags: [match-time, memory, pikevm, groups, test-infrastructure, gradle, scaling]
applies_to: []
source: multi-64k/find-matchtime-heap-scaling
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Match-time memory scales with group count — 12k groups OOMs a 512m JVM

While writing DeadlineCoverageHolesTest, the first run killed the Gradle test
executor: `OutOfMemoryError at PikeVMMatcher.java:193` (java heap space) — NOT the
compile path. The 6g probe runs had compiled 12k-24k-group patterns to native
BitState/PikeVM matchers in seconds without ever calling matches(); the test then called
`matches()` on a native matcher for the 12k-group pattern, and MATCH-time allocation
(per-position thread state × group arrays in the NFA simulation) exceeded the test JVM heap.
Gradle test JVMs default to ~512m here (reggie-runtime/build.gradle sets no maxHeapSize; only
`-Xss8m` + add-opens), while the same compile+match at 6g heap completes fine.

Properties:
- Compile is bounded (a5ef0fb) — this is a separate, PRE-EXISTING match-time property: the
  compile fixes bound compilation cost, not matching memory.
- The exposure is (pattern group count × matching state) — services compiling+matching native
  matchers for pathological group counts need match-time headroom, or a match-scope cap
  (none exists today).
- Test-design consequence (applied in DeadlineCoverageHolesTest): heap-safe tests — semantic
  match assertions only on the fallback path (JavaRegexFallbackMatcher delegates to
  java.util.regex, heap-light); native-route coverage via codegen unit tests + big-heap probe
  evidence.
- Capacity-planning input, not a correctness blocker (RE2J also grows memory with pattern
  complexity). Possible follow-up: a match-scope guard analogous to the compile emission
  budget (e.g. cap on groups×states at matcher construction) — only if extreme-group
  service patterns exist in the fleet corpus.
