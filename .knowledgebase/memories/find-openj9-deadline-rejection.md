---
id: find-openj9-deadline-rejection
title: OpenJ9 compiles deadline-prone patterns ~an order slower than HotSpot — the 10s total-compile deadline rejects HotSpot-compilable patterns on Semeru 21 (37/83 JMH forks failed identically in baseline and candidate), and JMH on OpenJ9 is compromised anyway; benchmark on Temurin
kind: finding
tags: [openj9, deadline, compile-deadline, j9, deployment]
applies_to: []
source: multi-64k/find-openj9-deadline-rejection
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# OpenJ9 compile-speed cliff under the total-compile deadline

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

Open: quantify the HotSpot↔OpenJ9 compile-speed ratio for deadline-prone pattern families.
