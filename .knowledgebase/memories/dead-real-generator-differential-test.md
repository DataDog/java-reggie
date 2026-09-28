---
id: dead-real-generator-differential-test
title: No constructible pattern both overflows a generated method and compiles within the 10s deadline — an end-to-end overflow differential test is infeasible; splitter semantics must be covered at unit level
kind: dead-end
tags: [testing, differential, probe, ci-flake]
applies_to: []
source: multi-64k/dead-real-generator-differential-test
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# End-to-end overflow differential test is infeasible

Test-plan idea: "a pattern whose NFA step method overflows 64KB today compiles,
loads, and matches java.util.regex differentially." No such pattern could be constructed:

- Huge literal capture alternations route to BitStateMatcher (compact) or LiteralAlternation
  trie strategies — method size never overflows.
- Pushing counts up (4000 alternatives) hits OOM in the analysis phase, before codegen.
- Compiles that do succeed take 8.5–13.5s against the 10s total-compile deadline — too close
  for a stable CI test; the probe test was deleted rather than kept flaky.

Consequence: splitter semantics must be covered at unit level (load real classes so the JVM
verifier validates recomputed frames, check exact values) plus the runtime suite through the
wired-in pipeline. Don't spend more time hunting a triggering pattern within the current
strategy/limit envelope.
