---
id: find-openj9-deadline-rejection
type: finding
status: confirmed
depends_on: [ev-bench-ab-temurin]
supersedes: []
related: [find-no-overflow-trigger-today]
tags: [openj9, deadline, compile-deadline, j9, deployment]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# OpenJ9 rejects HotSpot-compilable patterns via the 10s total-compile deadline

## Reasoning chain
First A/B attempt ran the same benchmark selection on IBM Semeru OpenJ9 21.0.12 (sdkman
"current" on workspace-jb). 37/83 benchmark forks failed identically in BASELINE and
CANDIDATE at warmup iteration 1: RuntimeException "Failed to compile pattern:
(?:a+b+|b+a+){75}" from RuntimeCompiler.compileWithDeadline — i.e., the default 10s total
-compile deadline (commit b0c5615) expires on OpenJ9 for a bounded-quantifier pattern that
compiles well within the deadline on Temurin/HotSpot 21.0.12 (all 83 entries ran green
after the JVM switch). JMH additionally warns "Not a HotSpot compiler command compatible VM
— compiler hints are disabled" on OpenJ9, so JMH microbenchmarks on it are compromised anyway.

Implications:
- Deployment relevance: dd-trace/Reggie on IBM J9-derived runtimes would fall back to
  java.util.regex for patterns that compile fine on HotSpot — a silent performance cliff
  gated by JVM flavor, not pattern class.
- Benchmarking on workspace-jb must explicitly set JAVA_HOME to Temurin
  (/usr/local/sdkman/candidates/java/21.0.12-tem); sdkman "current" points at Semeru OpenJ9.

## Open questions
- Quantify the HotSpot↔OpenJ9 compile-speed ratio for deadline-prone pattern families?
  (not blocking this investigation)
