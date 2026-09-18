---
id: ev-r2-rd-landed
type: evidence
status: confirmed
depends_on: [ev-r1-prefilter-landed, find-rust-engine-crossover]
supersedes: []
related: [hyp-unanchored-find-prefilter, q-z-anchor-span-bug]
tags: [r2, recursive-descent, give-back, anchor-start, workspace-jb, b5cf63e]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# R2 first half: linear find() for .*-prefix recursive-descent patterns (b5cf63e)

## Reasoning chain
Diagnosis by complexity fingerprint (LengthProbe): the post-R1 no-match residual was
dominated by ^/$-anchored .*-prefix rules (^(.*)-([0-9]{1,4})$ family) routing
RECURSIVE_DESCENT (requiresBacktrackingForGroups), QUADRATIC in the greedy give-back —
the backtrack loop re-matched the body k times from scratch per level (O(n^2), ~300us @
n=1024); the position loop was secondary noise.

LANDED (b5cf63e; full suite + fuzz + span-parity battery green): (1) anchor-start —
conservative leftmost-mandatory-spine ^/\A (non-multiline) detection -> single position
attempt, findFrom(start>0) = -1; (2) fast give-back — capture-free deterministic body ->
int[] iterEnd recorded during greedy consume, give-back = array lookup (POSIX 12/13 slots
set from array; only read by the tryMatchCount==1 inner-shrink retry). Slot threading:
iterEnd at localBase, nested backtracking at localBase+1 (+6/level) — a slot-16 collision
with generateNestedBacktracking corrupted results (caught by the span battery: groupCount
leaked positions; fixed before landing). Detour shipped: BytecodeDebugger now reports
ACTUAL pipeline routing (RuntimeCompiler.describeRouting/ROUTING_NOTE, 6e9b8eb) — the
isolated-analyzer output had been misleading routing triage.

MEASURED (workspace-jb, rust control flat): reggieSweepNoMatch 3,727 -> 2,657us (R1+R2:
33,896 -> 2,657 = 12.7x; reggie 1.19x AHEAD of rust 3,176). Matched 586 -> 508us. Blended
whole-sweep: reggie 3,165us vs rust 3,598 vs jdk ~50K — fastest engine on the real corpus
(~16x jdk). Per-pattern: ^(.*)-([0-9]{1,4})$ 306us -> 3.0us @ n=1024 (100x, linear).

REMAINING R2b: HybridMatcher lane (unanchored .*-prefix family) ~1.1ms of the 2.66ms
residual (~40%): needs reverse-DFA or .*?-prefixed single-pass DFA for its boolean find.
Optional: 1-char high-selectivity facts (~, _) as R1 last resort.
Full detail: repo cairn doc/investigations/multi-64k/evidence/ev-r2-rd-landed.md.
