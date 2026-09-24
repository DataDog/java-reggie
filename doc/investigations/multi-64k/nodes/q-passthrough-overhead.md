---
id: q-passthrough-overhead
type: question
status: answered
depends_on: [ev-bench-ab-temurin]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [benchmark, performance, compile-time]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Does the verbatim passthrough path add measurable compile-time overhead? — ANSWERED

**Answer: yes, modestly — ~+9% point estimate on total cache-miss compile time
(range ≈ +0–15% given noise), zero runtime regression.**

Measured 2026-09-16 on workspace-jb (Temurin 21.0.12, idle 16-core): CompileTimeProbe over
8 strategy-family templates, 200 real PATTERN_CACHE-miss compiles each — mean 2,949 →
3,226 µs/compile (+9.4%); JMH A/B 83 entries: 79 stable, 3 improved, 1 flagged entry is a
JDK-side benchmark (no code path — noise). The passthrough (MethodNode buffer + accept
replay for every splittable method) roughly doubles the emission step, which is ~10% of
total pipeline cost for these patterns. Against the 10s compile deadline: immaterial.

Mitigation if ever needed: lazy buffering is NOT possible via direct-write + retry (writer
is dirtied by the time MethodTooLargeException fires at toByteArray) — the buffer-first
shape is required; alternatives (threshold by cheap instruction count before replay) exist
but are unwarranted at +9%. See ev-bench-ab-temurin for raw numbers.
