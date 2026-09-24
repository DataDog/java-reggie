---
id: dead-group-boundary-fusion
type: deadend
status: refuted
depends_on: []
supersedes: []
related: []
tags: [fusion, group-boundary, dfs, perf, measured-negative, reverted, 677417b]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Group-boundary fusion pass (user directive) — REFUTED, REVERTED

## Reasoning chain
Fusing group enter/exit-only states into incoming edges (writes ride on edges). Two variants
built + fully tested: EAGER (ops applied at push — pays ~150 wasted caps clones for
never-popped marker-stop frames on greedy paths) and DEFERRED (ops row on the frame, applied
at pop; sound: push pos == pop pos of the bypassed state; post-write dedup strictly better).

Measured (SplitPerf/Re2jProbe, vs shipped 677417b): eager 5ch 0.585us win but 313ch +2.4us,
reject +7.8us; deferred 5ch 0.516 win, 313ch +1.4us, reject +6.2us. Instrumented: fusion
removes only ~10 pops on the 313-char accept (prerelease loop body has NO boundary states —
group writes sit on loop EDGES, off the winning path) while a 5th opsStack array taxes
every push/pop (~0.9ns x ~1700 frames; reject path pays on 5-10x more frames).

Verdict: trades a 0.1-0.14us win on degenerate 5-char for 1.4-6us losses on realistic shapes
-> REVERTED (no code delta vs 677417b). State-id bit-packing of ops (~0.3-0.5ns/pop) also
estimated net-negative for 313ch. Short-input closure needs a hybrid engine, not fusion.
Details: multi-64k ev-re2j-parity-push follow-up section (commit de871b5).
