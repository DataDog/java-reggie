---
id: hyp-v2-driver-lowering
type: hypothesis
status: open
depends_on: [find-no-overflow-trigger-today]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [v2, driver, continuation, state-machine, back-edges]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# v2: driver/continuation lowering for back-edge-crossing cuts

## Reasoning chain
v1 forbids cuts crossed by loop back-edges (a jump can't cross methods). A method whose whole
>64KB body is one straight-line loop body with a single back edge therefore stays unsplit →
verbatim → L3 fallback. The general fix is driver lowering: chunks return a continuation id;
the original method becomes a `tableswitch` driver loop; loop-carried live slots are passed
per-entry (types from DescriptorInterpreter as today).

**Dormant but no longer triggerless**: no *corpus* pattern needs it, but a constructible
trigger family now exists (ev-split-coverage-probe): "a"×40000 → NFA per-config cascade →
4.7 MB findLongestMatchEnd with wide dispatch switches — v1 declines (noCut flood from
forbidCrossing over switch edges). Now the ONLY path to L2 liveness on real patterns (find-l2-dead-code-verdict: v1 rescues
nothing real; every overflow family is switch-dominated). Do not build speculatively — but if
L2 is kept rather than unwired, v2 (or NFA L1 bucketing, which obviates L2 there) is the
decision that matters.

**Recursion trap if built**: replacing back-edges with *calls* (not the driver) creates one JVM
frame per loop iteration → stack overflow on long inputs. Must be the driver loop, and result
carrying needs a completion channel (field or outcome object) because chunk return type
changes from the method's return type to the continuation id.

## Evidence
- find-l2-splitter-shipped — v1 invariants (declinesToSplitLoopBody test asserts current refusal)

## Open questions
- none blocking (dormant by design)

## DISPOSITION (2026-09-16): not planned; L2 dropped entirely
With L1 dropped, v2 is moot unless a REAL need for >64KB method rescue emerges (extreme
synthetic patterns only, all currently L3-fallback — today's accepted behavior). If it ever
returns: the trigger families (ev-split-coverage-probe) + the recursion trap + the driver
design notes above are the starting point; NFA-L1 bucketing (P~0.4-0.5 to supersede) or
accepting L3 remain the alternatives.
