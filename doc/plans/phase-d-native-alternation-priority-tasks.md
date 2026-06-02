# Phase D — Phase 1a: native alternation-priority `find()` / `match()` (retire the decline)

**Parent:** `track2-native-capture-implementation-plan.md` § Phase D.
**Prereqs landed:** A (`7a9d5ba`), B (`7a9d5ba`), C1–C2 (`f926402`), C3 (`2c7d5b0`), C4 (`c8a8b31`), C5 (#41).
**Tracker:** **#42 (D1)**, **#43 (D2)**.

**Goal:** retire the `alternationPriorityConflict` decline so the alternation-boundary class
(`(fo|foo)`, `(a|ab)`, `(a|ab)(bc|c)`, `(aa|a)a`) is matched by the native O(n) DFA path with
Perl/Java leftmost-greedy-correct spans, instead of falling back to `OPTIMIZED_NFA` / JDK.

---

## 0. Corrected verdict — C5.2 "Outcome A" is insufficient as the implementation basis

C5.2 concluded **Outcome A** ("the C2 DFA + a blanket first-accept rule reproduces JDK spans; no new
`DFAState` signal required"). **That conclusion is refuted by greedy quantified captures, and D1 must
not be built on it.** Hard evidence:

### 0.1 The tagged generator does longest-match overrun — by design, and correctly so for greedy

`generateTaggedDFAMatching` (`DFAUnrolledBytecodeGenerator.java:1522-1630`) is the body of
`findMatchFrom` for `DFA_*_WITH_GROUPS`. At **every** accepting state it records the position and
clones the tag vector (`longestPos = pos`; `savedTags = tags.clone()`, lines 1550-1568) and then
**keeps consuming** (falls through to read the next char at 1571+). It stops only at end-of-input or a
dead state (1576-1578, 1625-1626) and returns the **longest** accept. The boolean/non-capture paths do
the same via `findLongestMatchEnd` (`generateGreedyStateCode:1919`, overwrites `longestMatchEndVar` per
accept then continues) and the switch generator's `findMatchEnd`.

### 0.2 Two patterns share one DFA topology but need opposite accept behavior

| Pattern | Input | DFA shape | JDK span | Longest-match | First-accept |
|---|---|---|---|---|---|
| `(fo\|foo)` | `"foo"` | accept-after-`fo` **has** an outgoing `o` edge | `[0,2)` "fo" | `[0,3)` ✗ | `[0,2)` ✓ |
| `(a)+` | `"aaa"` | accept-after-`a` **has** an outgoing `a` self-loop | `[0,3)` "aaa" | `[0,3)` ✓ | `[0,1)` ✗ |

Both are "accepting state with outgoing transitions" — the exact predicate
`dfaHasAcceptingStateWithTransitions` (`PatternAnalyzer.java:1255`) tests. The boolean
`DFAState.accepting` (`DFA.java:302`) cannot tell them apart, yet one needs a cut and the other needs
the overrun. **A blanket first-accept rule (C5.2's probe) breaks `(a)+`; the existing longest-match
rule breaks `(fo|foo)`.**

`(a)+` is not a hypothetical: it bypasses the 765 decline (`containsAlternation`=false;
`containsOptionalQuantifier`=false — `hasOptionalInsideRepeatingGroup:1086` returns false because the
group child has no `min=0` quantifier) and routes straight to `DFA_*_WITH_GROUPS`, where it is matched
correctly **today** by longest-match. It is also generated in the 80k-check fuzzer. So longest-match is
load-bearing and cannot be globally replaced.

### 0.3 Why C5.2 missed it

C5.2's `TaggedDfaFindSpanProbeTest` tested five patterns — all "cut" cases (alternation with the
shorter alternative first). It never included a greedy-continue case with the same topology, so its
blanket "stop at first accept" rule was never falsified. The probe was correct *about its sample*; the
sample was biased. **The real signal is per-accepting-state**, exactly the `acceptIsPriorityCut` flag
the C5 plan anticipated as "Outcome B."

### 0.4 The correct rule (= PikeVM's accept rule, made static)

At an accepting DFA state, **cut** (commit and stop) iff the **highest-priority live thread is the one
that accepts** — i.e. the lowest-rank accepting NFA state in the DFA state's ordered closure has rank
≤ the lowest-rank NFA state with a consuming out-transition. Otherwise **continue** (longest/greedy).

- `(fo|foo)` accept-after-`fo`: the `fo`-thread (first alternative, higher priority) has reached
  accept; the `foo`-continuation is lower priority → **cut**.
- `(a)+` accept-after-`a`: greedy "consume another `a`" is the higher-priority thread → **continue**.

This is precisely PikeVM's clist-truncation rule (kill lower-priority threads once a higher-priority
thread matches), which is why PikeVM is span-correct (0/76k). D1 ports it into a **static per-DFAState
flag** computed from the C1 ordered closure, so the O(n) DFA can make the same decision without
simulating threads.

### 0.5 Two decline sites, not one

| Site | Path | `buildDFA` | Catches |
|---|---|---|---|
| `PatternAnalyzer.java:765-778` | tagged (capturing) | `buildDFA(nfa, true)` | `(fo\|foo)`, `(a\|ab)`, … |
| `PatternAnalyzer.java:909-915` | standard (non-capturing) | `buildDFA(nfa)` | `(?:fo\|foo)`, … |

The non-capturing site matters for `match()`/`findMatch()` **end position** (`(?:fo|foo)` on `"foo"`
→ JDK end 2, longest-match end 3). Boolean `matches()`/`find()` are already correct on both paths
(`matches()` requires whole input; `matchesAtStart` already returns on first accept), so D1's behavior
change is confined to the **match-end / span-recording** paths. D1 addresses both decline sites with
the same flag.

---

## 1. Tasks

### D1.0 — Empirical disambiguation (TDD RED): pin the greedy counterexample

Before any production change, convert § 0.2 into a failing/locking test. Extend the C5.2 simulator
(or add `TaggedDfaPriorityCutProbeTest` beside `TaggedDfaFindSpanProbeTest`) with:

- a **cut** case (`(fo|foo)`/"foo", already green under blanket first-accept), and
- a **continue** case (`(a)+`/"aaa", `(ab)+`/"abab") that the blanket first-accept rule gets **wrong**
  (asserts the blanket rule diverges from JDK), and that a **per-state cut rule** (cut only when the
  top-rank thread accepts) gets **right**.

This test is the executable statement of the corrected verdict and the regression guard for D1.3.
Derive every expected span from `java.util.regex.Pattern` in-test (never hard-code).

**Acceptance:** test demonstrates blanket-first-accept ✗ on `(a)+` and per-state-cut ✓ on both classes.

### D1.1 — Compute the per-state priority-cut flag in `SubsetConstructor`

Add `boolean acceptIsPriorityCut` (name TBD) to `DFAState`, set during `buildDFA(nfa, true)` (and the
non-tagged `buildDFA(nfa)` if D1.4 covers site 909) using the **C1 ordered closure ranks** already
threaded through C2:

- For each accepting DFA state, find the lowest rank among its accepting NFA states (`acceptRank`) and
  the lowest rank among NFA states that have a consuming out-transition (`continueRank`).
- `acceptIsPriorityCut = (acceptRank <= continueRank)`.
- Anchored-acceptance states (`acceptanceAnchorConditions` non-empty, `DFA.java:258-299`) keep their
  existing gating; the cut flag is orthogonal and combines with it.

**Propose the field shape before coding** (per repo rule: propose helpers first). Prefer a single
`boolean` on `DFAState`; no change to `DFATransition`. Keep allocation-free.

**Acceptance:** unit test asserts the flag is `true` for `(fo|foo)` accept-after-`fo` and `false` for
`(a)+` accept-after-`a`.

### D1.2 — StructuralHash HARD RULE (mandatory)

`acceptIsPriorityCut` is bytecode-affecting (it changes the generated accept handling, D1.3), so per
cross-cutting rule 3 it **must** be hashed:

- Hash the flag in `StructuralHash.computeDFATopologyHash` (`StructuralHash.java:123`) alongside the
  existing per-state fields.
- Add a `StructuralHashTest` distinctness case: two patterns with identical `(id,type)` topology but
  different cut flags (`(fo|foo)` vs a same-shaped non-cut pattern) **must** hash differently; the case
  must **fail when the hash change is reverted**. Mirror `groupAtDifferentPositions_…`
  (`StructuralHashTest.java:77`).

**Acceptance:** `StructuralHashTest` green incl. new case; new case red on revert.

### D1.3 — Honor the flag in the generators (the behavior change)

At a **priority-cut** accept, commit (`longestPos`/`savedTags`, or `longestMatchEnd`/`lastAccepting`)
and **jump to exit** instead of falling through to consume more. At a **non-cut** accept, keep the
current longest-match. Apply at every accept site the survey enumerated:

- `DFAUnrolledBytecodeGenerator`: `generateTaggedDFAMatching:1550-1578` (tagged spans),
  `generateGreedyStateCode:1919` / `generateFindLongestMatchEndMethod:1875` (non-tagged end),
  `generateFindBoundsFromMethod:2508`.
- `DFASwitchBytecodeGenerator`: `findMatchEnd` (greedy end, ~1709) and its tagged/bounds equivalents.
- Leave `matches()` and `matchesAtStart()` boolean paths unchanged (§ 0.5 — already correct).

**Acceptance:** D1.0 probe green via the real generated code (runtime test on `(fo|foo)` and `(a)+`);
all existing generator/DFA tests green.

### D1.4 — Narrow / retire the decline; re-point routing

- **Site 765 (tagged):** remove the `alternationPriorityConflict` branch for the class D1.1–D1.3 now
  prove correct; route to `DFA_UNROLLED_WITH_GROUPS` / `DFA_SWITCH_WITH_GROUPS` within state caps,
  `PIKEVM_CAPTURE` as the explosion fallback (mirror C4's structure at `PatternAnalyzer.java:780-811`).
  Keep the decline only for any residual sub-case the flag cannot certify (document which, if any).
- **Site 909 (standard, non-capturing):** decide in the same task — if the non-tagged generators honor
  the flag (D1.3), retire it analogously; else leave it and record why. Do not silently flip one site
  and forget the other.
- Update `RuntimeCompiler` handling of the `alternationPriorityConflict` flag only as needed.
- Update routing tests (`PikeVMRoutingTest` and any test asserting these patterns route to
  `OPTIMIZED_NFA`) to the new strategy.

**Acceptance:** routing tests green; the retired patterns assert a `DFA_*_WITH_GROUPS` strategy.

### D2 — Representatives + final gates (#43)

- Add to `StrategyCorrectnessMetaTest.strategyPatterns()` (the `m.put(strategy, new Spec(pattern,
  inputs))` map, ~lines 67-193) representatives for the newly-routed class mapped to their actual
  strategy: `(fo|foo)`, `(a|ab)`, `(a|ab)(bc|c)`, `(aa|a)a`, **plus** a greedy non-cut lock
  (`(a)+` and/or `(ab)+`) so the longest-match-preserving half is pinned too. Each `Spec` gets the
  standard 5 inputs (full match, embedded, non-match, empty, non-ASCII).
- Run the gates **in order** (all must be 0/green):
  1. `StrategyCorrectnessMetaTest` — `-Dreggie.metatest.enforce=true` (8 API methods).
  2. Differential fuzzer `zeroDivergenceGate_enforcedViaProperty` — `-Dreggie.fuzz.enforceZero=true`
     (≥76k checks at 0).
  3. `StructuralHashTest` incl. the D1.2 case.
  4. Oracle cross-check (PikeVM == TDFA == JDK) on the retired set.
  5. Benchmark: native `find()` recovers DFA speed over the previous `OPTIMIZED_NFA`/JDK fallback on
     the retired class — record the delta.
  6. `./gradlew build` green.
- `./gradlew spotlessApply` before any commit and any push.

**Acceptance:** all six gates green; deltas recorded in § 3 close-out. **Stop and wait for input.**

---

## 2. Files in scope (strict)

- **edit:** `reggie-codegen/.../automaton/DFA.java` — add `acceptIsPriorityCut` to `DFAState`.
- **edit:** `reggie-codegen/.../automaton/SubsetConstructor.java` — compute the flag from C1 ranks.
- **edit:** `reggie-codegen/.../analysis/StructuralHash.java` — hash the flag (HARD RULE).
- **edit:** `reggie-codegen/.../codegen/DFAUnrolledBytecodeGenerator.java`,
  `DFASwitchBytecodeGenerator.java` — honor the flag at accept sites (D1.3).
- **edit:** `reggie-codegen/.../analysis/PatternAnalyzer.java` — narrow/retire the decline at 765 (and
  909), re-point routing.
- **maybe-edit:** `reggie-runtime/.../RuntimeCompiler.java` — only if the flag handling requires it.
- **add:** `reggie-codegen/.../automaton/TaggedDfaPriorityCutProbeTest.java` (D1.0); flag-computation
  unit test (D1.1).
- **edit (tests):** `StructuralHashTest`, `StrategyCorrectnessMetaTest`, `PikeVMRoutingTest`.
- **edit (close-out only):** this doc.
- **NO scope creep:** lazy quantifiers stay out (assert never routed to the priority TDFA); named
  groups / variable-width lookaround / backrefs stay on their current paths.

---

## 3. Risks & mitigations

- **Cache poisoning** (flag not hashed) → D1.2 HARD RULE + fail-on-revert test.
- **Breaking greedy quantified captures** (`(a)+`, `(ab)+`) by over-cutting → D1.0 counterexample test
  + the D2 non-cut representative; the fuzzer already exercises them at 0.
- **Flag computed wrong** (rank comparison inverted) → unit test at D1.1 with both polarities; PikeVM
  oracle cross-check at D2.4.
- **Forgetting the second decline site (909)** → D1.4 forces an explicit decision on both.
- **State explosion** if the flag forces state splits → it is a per-state boolean, not a split key; no
  new states. Caps + PikeVM fallback (C4) remain the backstop.

---

## 4. Sequencing

```
D1.0 (RED counterexample) ─► D1.1 (flag in SubsetConstructor) ─► D1.2 (HARD RULE hash)
   ─► D1.3 (generators honor flag) ─► D1.4 (retire decline + routing) ─► D2 (reps + 6 gates)
                                   ▲
        PikeVM (Phase B, 0/76k) is the span oracle throughout ─┘
```

Land as one commit or a small series (D1.1+D1.2 substrate+hash, D1.3+D1.4 behavior+routing) with all
D2 gates green. **Do not commit unless asked.**

---

## 5. Close-out (fill on completion)

- D1.1 flag polarity verified: `(fo|foo)` cut=`__`, `(a)+` cut=`__`.
- D1.2 StructuralHash case: distinct hashes `__` / `__`; red-on-revert confirmed `__`.
- D1.4 decline sites retired: 765 `__`, 909 `__`; residual decline (if any): `__`.
- D2 gates: meta-test `__`, fuzzer `__`/≥76k, StructuralHashTest `__`, oracle `__`, benchmark Δ `__`,
  build `__`.
