---
id: dead-real-generator-differential-test
type: deadend
status: refuted
depends_on: [find-no-overflow-trigger-today]
supersedes: []
related: []
tags: [testing, differential, probe, ci-flake]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Real-generator overflow differential test — DEAD END

## Reasoning chain
Design-doc test plan item 7: "a pattern whose NFA step method overflows 64KB today compiles,
loads, and matches java.util.regex differentially." No such pattern could be constructed:

- Huge literal capture alternations route to BitStateMatcher (compact) or LiteralAlternation
  trie strategies — method size never overflows.
- Pushing counts up (4000 alternatives) hits OOM in the analysis phase, before codegen.
- Compiles that do succeed take 8.5–13.5s against the 10s total-compile deadline — too close
  for a stable CI test; the probe test was deleted rather than kept flaky.

Splitter semantics are instead covered at unit level (`SplittingClassVisitorTest`), which
loads real classes (JVM verifier validates recomputed frames) and checks exact values, plus
the 3,152-test runtime suite through the wired-in pipeline.

## What this rules out
- Spending more time hunting a triggering pattern within the current strategy/limit envelope.
