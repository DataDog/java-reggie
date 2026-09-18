---
id: ev-scale-timings
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-semver-exponential-compile]
tags: [benchmark, timing, exponential]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# BitState compile-time scaling measurements (semver-shaped patterns, {n,m}=bound)

Scale.java, reggie trunk fd362c2, all → BitStateMatcher:
bound 2 → 121 ms; 4 → 19 ms; 8 → 51 ms; 16 → 215 ms; 32 → 2,295 ms;
64 → 68,628 ms; 128 → killed (>120 s); 256 (real SemVerParser pattern) →
killed at 10+ min, never completes.

Real-site reproduction: `Reggie.compile` of the exact
SemVerParser.java pattern hangs >2 min (timeout kill), JDK compiles instantly.
Scale.java source preserved in /tmp/rf/ (regenerable from this node's numbers).
