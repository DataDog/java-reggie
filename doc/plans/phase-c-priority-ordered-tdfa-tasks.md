# Phase C — Priority-Ordered TDFA: Detailed Task Plan

**Parent plan:** `track2-native-capture-implementation-plan.md` (§ Phase C).
**Prereqs landed:** Phase A (`orderedEpsilonClosure`, commit `7a9d5ba`), Phase B (PikeVM, same commit).
**Goal:** make `DFA_*_WITH_GROUPS` produce **Perl/Java leftmost-greedy-correct** group spans for the
pure-regular capture-ambiguous subclass that Phase B currently routes to `PIKEVM_CAPTURE` — recovering
O(n) DFA speed and unblocking Phase D (native alternation-priority `find()`).

**Oracle:** Phase B's `PikeVMMatcher` is already proven span-correct (0/76,248 fuzzer). Phase C must
produce **identical spans to PikeVM and JDK** on the same inputs. PikeVM is the cheap, in-repo
cross-check that de-risks the high-blast-radius determinization surgery.

---

## 0. Root cause (re-confirmed against current code)

Two capture-assignment paths in `SubsetConstructor` pick a group's START/END marker by **lowest
NFA-state-id**, which is a *structural* tiebreak decoupled from *which thread wins the match*:

1. **`computeGroupActions(Set<NFAState>)`** — `SubsetConstructor.java:493-528`. Fed only the target
   NFA-state **set** (no ordering). Dedups by `(groupId, type)` keeping `op.priority < existing.priority`
   (line 519, `priority == NFAState.id`, `DFA.java:116`), then sorts by priority (line 526).
2. **`computeTagOperations(...)`** — `SubsetConstructor.java:714-807` (Tagged-DFA path). Same
   lowest-id-wins tiebreak at lines 745, 777, 791, and in `trackEpsilonPathTags` at 838/848. Fed
   `flattenClosure(anchoredClosures)` (line 172), which discards ε-order via `keySet()` (line 391).

The winning thread is determined by **epsilon insertion order** (Perl thread priority), which Phase A
captured in `orderedEpsilonClosure` (`SubsetConstructor.java:202`) but which neither path above
consumes. **Fix: select each group's marker from the highest-priority thread (lowest index in the
ordered closure) that actually reaches the target NFA state — not the lowest NFA-state-id.**

---

## Task C1 — Make the ordered closure the source of capture truth

**Scope:** introduce a priority index without disturbing the boolean DFA paths.

- C1.1 — In `SubsetConstructor.buildDFA`, after `precomputeAnchoredClosures`, build a
  **per-source priority-ordered closure** using Phase-A `orderedEpsilonClosure` (or an anchored
  variant) so each `(source → reachable NFAState)` carries a **priority rank** (index in the ordered
  list; lower = higher priority). Keep the existing set-based `epsilonClosures` for the
  bypass-reachability analysis (`computeGroupsWithBypass`) untouched — that is correctness-orthogonal.
- C1.2 — Decide the carrier shape: a `Map<NFAState, Integer>` rank map per source state, or thread
  the ordered `List<NFAState>` directly into the two capture methods. Prefer the **rank map** —
  smallest change to method signatures, O(1) lookup in the tiebreak.
- C1.3 — **Do not** change `buildDFAWithAssertions` (line 867) in this task; assertion DFAs are not
  in the capture-ambiguous subclass. Confirm by grep that no capture-ambiguous pattern reaches it.

**Gate:** compiles; all existing tests still green; **no routing change yet** (capture-ambiguous still
goes to PikeVM). This task is pure substrate — behavior-neutral.

---

## Task C2 — Winning-thread selection (TDD, the core change)

**Replace the lowest-id tiebreak in both paths with priority-rank selection.**

- C2.1 (RED) — Write `SubsetConstructorPriorityTest` against the Phase-B repro set **before**
  touching the methods. Build the NFA → DFA with `computeTags`, assert the emitted group
  actions / tag ops bind to the **highest-priority** thread's marker. Repros (spans must equal JDK):
  `(.)?b`/"b", `(a)?b`/"ab", `(fo|foo)`/"foo", `(a|ab)`/"ab", `(a|ab)(c|)`/"abc", `(aa|a)a`/"aaa",
  plus the **full 13 Track-1 repros** (pull from the Track-1 commit `6af634a` test set). Watch them
  fail with the current lowest-id code.
- C2.2 (GREEN) — `computeGroupActions` (493): replace `op.priority < existing.priority` (519) with
  "keep the marker whose source NFA-state has the **best priority rank** in the ordered closure of
  this DFA state." For the per-state case, rank = position of that NFA-state in the DFA state's own
  ordered closure.
- C2.3 (GREEN) — `computeTagOperations` (714): at each of the three keep-points (745, 777, 791) and
  in `trackEpsilonPathTags` (838/848), replace lowest-id with best-rank-wins. The "actually entering"
  guard (`isGroupActuallyEntered`, 632) stays — it gates *whether* to emit, orthogonal to *which*
  thread wins.
- C2.4 — On **accepting** DFA states, commit the tag/group vector of the **highest-priority accepting
  thread** (the lowest-rank NFA accept state in the closure), matching PikeVM's clist-truncation rule.
- C2.5 — Keep the final `sort(...by priority)` (526, 805) **only if** generators rely on a stable
  order; otherwise sort by rank. Verify against the generator in C3.
- C2.6 (REFACTOR) — Collapse the four near-identical "keep highest priority" blocks into one helper
  `keepBest(Map<Integer,TagOperation> ops, TagOperation candidate, int rank)` once green.

**Gate:** `SubsetConstructorPriorityTest` green; existing `SubsetConstructor`/DFA tests still green.

---

## Task C3 — StructuralHash HARD RULE (mandatory analysis + conditional change)

**Why this is load-bearing:** `StructuralHash.computeDFATopologyHash` (`StructuralHash.java:123`)
hashes group actions as `(groupId, type.ordinal)` in list order (138-141) and tag ops as
`(tagId, type.ordinal)` in list order (164-167). It does **not** hash `priority`. Phase C changes
*which* thread's marker is selected — if that changes neither the `(id,type)` pairs nor their list
order, two patterns with genuinely different capture behavior could collide in the L2 cache → silent
wrong results.

- C3.1 — Determine empirically whether C2 changes the **emitted list** (membership or order) or only
  the **priority field** of otherwise-identical entries. Add a temporary assertion/log over the
  repro set.
- C3.2 — **If** a new bytecode-affecting signal is introduced (a priority-cut flag on `DFAState`, a
  reordered tag-op sequence the generator depends on, or a per-transition winning-thread id): add the
  field to `DFA.java`, hash it in `computeDFATopologyHash`, and add a `StructuralHashTest` distinctness
  case mirroring `groupAtDifferentPositions_produceDifferentHashes` (`StructuralHashTest.java:73`).
- C3.3 — **If** C2 only changes values that are already order-encoded (list order shifts because the
  sort key changed): confirm the order-sensitive hash already distinguishes them, and add a
  `StructuralHashTest` case proving two priority-distinct patterns hash differently anyway.
- C3.4 — Either way, land at least one new `StructuralHashTest` case that would **fail** if priority
  selection were silently ignored by the hash.

**Gate:** `StructuralHashTest` green including the new case; the new case fails when reverted.

---

## Task C4 — Narrow the Track-1 decline; re-point routing to DFA

**Only after C2+C3 prove correct.** Today `PatternAnalyzer` routes the capture-ambiguous subclass:
- `dfa.isCaptureAmbiguous()` → PikeVM if pure-regular/anchor-free, else JDK fallback
  (`PatternAnalyzer.java:780-804`).

- C4.1 — For patterns the priority-correct TDFA now handles within state caps, prefer
  `DFA_UNROLLED_WITH_GROUPS` / `DFA_SWITCH_WITH_GROUPS` (O(n)) over `PIKEVM_CAPTURE` (O(n·m)). Insert
  the DFA branch **before** the PikeVM branch at line 780, guarded by the same pure-regular /
  anchor-free / no-named-group predicates already present (781).
- C4.2 — Keep `PIKEVM_CAPTURE` as the **explosion fallback**: above `DFA_SWITCH_STATE_LIMIT` or on
  `StateExplosionException`, fall to PikeVM (not JDK) for the pure-regular subclass.
- C4.3 — Narrow `SubsetConstructor.hasCaptureAmbiguity` (547) usage: a pattern that the priority TDFA
  now binds correctly should **no longer set** `captureAmbiguous`. Decide whether to (a) keep the flag
  but stop routing on it for the now-correct subclass, or (b) tighten the detector. Prefer (a) — the
  detector stays a conservative safety net; routing decides. Document the rationale inline.
- C4.4 — Leave genuinely non-regular constructs (backrefs, variable-width lookaround, named groups
  until PikeVM/`MatchResult.group(String)` supports them) on their current paths.

**Gate:** `PikeVMRoutingTest` updated — the patterns it asserts now route to a `DFA_*_WITH_GROUPS`
strategy (or document why they stay on PikeVM); routing tests green.

---

## Task C5 — Determinization-cost guard + native find() prerequisite check

- C5.1 — Confirm the existing DFA state caps (`>10000` in `buildDFA` line 180; `DFA_UNROLLED_STATE_LIMIT`,
  `DFA_SWITCH_STATE_LIMIT` in `PatternAnalyzer`) and the `StateExplosionException` → PikeVM fallback
  (C4.2) bound worst-case determinization. No new cap needed unless C2 inflates state counts.
- C5.2 — Verify the priority-correct DFA makes native `find()` span-correct for the alternation-boundary
  class (the Phase-D prerequisite). Add a focused test for `find()` spans on `(fo|foo)`, `(a|ab)`; this
  gates whether Phase D can proceed.

**Gate:** no state-count regression beyond caps on the repro + fuzzer corpus.

---

## Task C6 — Phase C gates (all must hold, in order)

1. **Differential fuzzer** `zeroDivergenceGate_enforcedViaProperty` (`-Dreggie.fuzz.enforceZero=true`)
   at **0** over ≥76k checks.
2. **`StrategyCorrectnessMetaTest`** (`-Dreggie.metatest.enforce=true`) at **0** mismatches across all
   8 public API methods; add `DFA_*_WITH_GROUPS` representatives for the newly-routed subclass.
3. **`StructuralHashTest`** green incl. the C3 case.
4. **Oracle cross-check:** PikeVM spans == TDFA spans == JDK spans on the repro set (explicit test).
5. **Benchmark** (`GroupExtractionBenchmark` / `StrategyBenchmark`): TDFA recovers DFA-speed over
   PikeVM on the declined subclass — record the delta.
6. **`./gradlew build`** green. **`./gradlew spotlessApply`** before any commit and before any push.

---

## Sequencing within Phase C

```
C1 (substrate, neutral) ──► C2 (selection, TDD) ──► C3 (HARD RULE) ──► C4 (routing) ──► C5 (guard) ──► C6 (gates)
                                  ▲                                          │
                                  └──── PikeVM (Phase B) is the oracle ──────┘
```

C2's repro tests and the C3 `StructuralHashTest` case can be **drafted in parallel** with the C1
substrate, then run as the red→green loop once C2 lands. Land Phase C as one commit (or a small series:
C1+C2+C3 as the algorithmic change, C4+C5 as the routing change) with all C6 gates green.

## Risks & mitigations
- **Cache poisoning** (C3 missed) → mitigated by the HARD-RULE analysis + a failing-on-revert test.
- **Determinization correctness** → mitigated by PikeVM-as-oracle + fuzzer at 0 + the 13 Track-1 repros.
- **State explosion from priority splitting** (if rank forces distinct DFA states) → mitigated by caps
  + PikeVM fallback (C4.2). Watch C5.1's state-count check.
- **Lazy quantifiers** remain out of scope (deferred from Phase A); assert the analyzer never routes
  lazy-quantified captures into the priority TDFA — if the fuzzer surfaces one, keep it on PikeVM/JDK.
