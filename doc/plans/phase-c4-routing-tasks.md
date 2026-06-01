# Phase C4 — Narrow the Track-1 decline; re-point routing to DFA

**Parent:** `phase-c-priority-ordered-tdfa-tasks.md` § Task C4.
**Prereqs landed:** C1 (`f926402`), C2 (`f926402`), C3 (`2c7d5b0`). The priority-ordered TDFA now
binds group spans to the winning thread, so `DFA_*_WITH_GROUPS` is span-correct for the pure-regular
capture-ambiguous subclass that Phase B parked on `PIKEVM_CAPTURE`.

**Goal:** recover O(n) DFA speed for that subclass by routing it to `DFA_UNROLLED_WITH_GROUPS` /
`DFA_SWITCH_WITH_GROUPS`, keeping `PIKEVM_CAPTURE` only as the state-explosion fallback. Tighten the
three tests that currently hard-code the PikeVM destination.

This is a **routing-only change** — no algorithm change (that was C2), no hash change (C3 proved the
hash already separates these DFAs). Stay strictly inside the capture-ambiguous block; the adjacent
`alternationPriorityConflict` decline is **Phase D / #42**, out of scope here.

---

## 0. Evidence — what routes where today (commit `2c7d5b0`)

The capture-bearing path (`nfa.getGroupCount() > 0`, `PatternAnalyzer.java:690`) builds a tagged DFA
at `:747` and then decides, **in order**:

| Line | Guard | Destination today |
|---|---|---|
| 749-763 | anchor-condition diluted / misplaced anchors | `OPTIMIZED_NFA` (`anchorConditionDiluted`) |
| **765-778** | `(containsAlternation \|\| containsOptionalQuantifier) && dfaHasAcceptingStateWithTransitions` | `OPTIMIZED_NFA` (`alternationPriorityConflict`) — **Phase D, NOT C4** |
| **780-791** | `isCaptureAmbiguous && !hasNamedGroups && !hasAnchorInNfa` | **`PIKEVM_CAPTURE`** ← **C4 re-points this** |
| 792-803 | `isCaptureAmbiguous` (named groups or anchors) | `OPTIMIZED_NFA` (`captureAmbiguous`) — leave as-is |
| 806-837 | non-ambiguous | `DFA_UNROLLED_WITH_GROUPS` (<20) / `DFA_SWITCH_WITH_GROUPS` (<300) / `OPTIMIZED_NFA` |
| 838-849 | `StateExplosionException` | `OPTIMIZED_NFA` ← **C4.2 re-points the pure-regular subclass to PikeVM** |

### 0.1 The reachable subclass is narrow (bounds the blast radius)

A pattern reaches line 780 only if it is capture-ambiguous **and slips past the 765 guard**. The 765
guard catches alternation/optional patterns whose DFA has an accepting state with further outgoing
transitions — i.e. `(fo|foo)`, `(a|ab)`, `(a|ab)(c|)`. Those go to `OPTIMIZED_NFA` and **never reach
780**; retiring that decline is **D1 (#42)**, explicitly deferred.

The patterns that *do* reach 780 are capture-ambiguous-via-bypass with no accepting-state fan-out —
empirically `(a)?b` and `(.)?b` (the optional group has a bypass path, but the accept state after `b`
has no outgoing edge, so 765 does not fire). These are exactly the two patterns the current tests
pin to PikeVM. Confirmed: `PikeVMRoutingTest` asserts both → `PIKEVM_CAPTURE` and is green today.

### 0.2 The DFA is already built and span-correct here

At line 780 the tagged DFA already exists (`dfa` from `:747`), so `dfa.getStateCount()` and the
`DFA_UNROLLED_STATE_LIMIT=20` / `DFA_SWITCH_STATE_LIMIT=300` thresholds (`PatternAnalyzer.java:36-37`)
are available with no extra construction. C2 made this DFA's group spans match PikeVM/JDK.

### 0.3 Downstream `captureAmbiguous` flag is untouched by C4

`MatchingStrategyResult.captureAmbiguous` is consumed by `RuntimeCompiler.java:351` (JDK fallback) and
`ReggieMatcherBytecodeGenerator.java:126` (build-time rejection). It is set **only** at line 802 — the
named-group/anchor branch C4 does not modify. The pure-regular subclass never set it (it returned
`PIKEVM_CAPTURE` directly), so re-pointing it to DFA changes no downstream flag semantics. → **C4.3
chooses option (a): leave the detector (`SubsetConstructor.hasCaptureAmbiguity`) and the result flag
as a conservative safety net; routing alone decides.** No change to `DFA.java` / `SubsetConstructor`.

---

## 1. Tasks

### C4.1 — RED: flip the routing assertions (TDD, watch-them-fail)

Before touching `PatternAnalyzer`, update the routing expectations so they fail against current code:

- **`PikeVMRoutingTest`** (`reggie-runtime/.../PikeVMRoutingTest.java:27-40`): change the two
  `assertEquals(..., PIKEVM_CAPTURE, routeOf("(a)?b"/"(.)?b"))` cases to expect
  `DFA_UNROLLED_WITH_GROUPS`. Rename the tests to reflect the new destination
  (`captureAmbiguousRoutes_toDfaWithGroups`). Leave `pikeVMCaptureMatcher_matchesCorrectly` (`:43`)
  behavioral assertions intact (they validate `matches`/`find` and stay green through either route);
  rename it to drop the "pikeVM" implication.
- Run → both routing tests **fail** (still route to `PIKEVM_CAPTURE`). This proves the test bites.

Acceptance: the two flipped assertions are RED for the right reason (actual `PIKEVM_CAPTURE`).

### C4.2 — GREEN: insert the DFA branch before PikeVM

In `PatternAnalyzer.java`, inside `if (dfa.isCaptureAmbiguous())` at `:780`, **before** the
`PIKEVM_CAPTURE` return, add — guarded by the *same* `!hasNamedGroups(ast) && !hasAnchorInNfa(nfa)`
predicate already on line 781:

```java
if (!hasNamedGroups(ast) && !hasAnchorInNfa(nfa)) {
  int stateCount = dfa.getStateCount();
  if (stateCount < DFA_UNROLLED_STATE_LIMIT) {
    return new MatchingStrategyResult(
        MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa, null, true,
        requiredLiterals, null, needsPosixSemantics);
  } else if (stateCount < DFA_SWITCH_STATE_LIMIT) {
    return new MatchingStrategyResult(
        MatchingStrategy.DFA_SWITCH_WITH_GROUPS, dfa, null, true,
        requiredLiterals, null, needsPosixSemantics);
  }
  // explosion fallback: too many states for inline/switch DFA → PikeVM (O(n·m), correct spans)
  return new MatchingStrategyResult(
      MatchingStrategy.PIKEVM_CAPTURE, null, null, false,
      requiredLiterals, null, needsPosixSemantics);
}
```

Mirror the `useTaggedDFA=true` / `needsPosixSemantics` arguments used by the non-ambiguous DFA returns
at `:808-826`. The named-group/anchor `captureAmbiguous` fallback at `:792-803` stays unchanged.

Run → `PikeVMRoutingTest` GREEN. (Watch-it-pass.)

Acceptance: `(a)?b`/`(.)?b` route to `DFA_UNROLLED_WITH_GROUPS`; build compiles.

### C4.3 — GREEN: PikeVM stays the explosion fallback on `StateExplosionException`

`PatternAnalyzer.java:838-849`'s catch currently returns `OPTIMIZED_NFA`. For the pure-regular
capturing subclass it should fall to `PIKEVM_CAPTURE` (C4.2 of the master plan), not JDK. In the
catch block, when `!hasNamedGroups(ast) && !hasAnchorInNfa(nfa)`, return `PIKEVM_CAPTURE`; otherwise
keep `OPTIMIZED_NFA`.

> **Verify-or-defer:** this catch fires only when tagged-DFA determinization throws. Constructing a
> compact pattern that is (a) capture-ambiguous, (b) pure-regular, and (c) state-exploding is hard. If
> no deterministic test pattern exists, implement the branch (it is the correct fallback) and **`log`
> in the plan close-out that the explosion fallback is exercised by code review only, not a unit
> test** — do not fabricate a flaky large-pattern test. Decide during C4.1 whether a repro exists.

Acceptance: explosion path for the pure-regular subclass yields `PIKEVM_CAPTURE`; named/anchor
subclass unchanged.

### C4.4 — Repair the two cross-impacted tests broken by the new route

These currently encode "(a)?b / (.)?b → PIKEVM_CAPTURE" as a premise and will break once C4.2 lands:

1. **`StrategyCorrectnessMetaTest.java:197-201`** keys `(.)?b` under `PIKEVM_CAPTURE` in the
   representative-routing map → `representativeRouting` will report it misrouted to
   `DFA_UNROLLED_WITH_GROUPS`. Re-key `(.)?b` under `DFA_UNROLLED_WITH_GROUPS` (its span-correctness
   against the JDK oracle is now *more* valuable — it exercises the C2 priority path end-to-end). For
   the now-orphaned `PIKEVM_CAPTURE` key: there is **no compact representative left** (only the
   explosion fallback routes there). Handle it the way the suite already handles unreachable strategy
   keys — either drop the `PIKEVM_CAPTURE` entry with an inline note that it is now an
   explosion-only fallback, or add it to the existing "intercepted/now-routes-elsewhere" skip set
   (`captureAmbiguousIntercepted`, `:311`). Prefer the documented drop; match the file's convention.
2. **`StructuralHashTest.java:142-153`** — `pikevmCapture_producesDistinctHashFromDfaWithGroups` uses
   `(a)?b` as its "PIKEVM_CAPTURE side". After C4 both `(a)?b` and `(a)b` route to
   `DFA_*_WITH_GROUPS`, so the test's **comment is now a hallucination**. The two DFAs are still
   structurally distinct, so the assertion still holds — but fix the comment to state both are now
   DFA-with-groups and the test guards DFA-topology distinctness (not strategy distinctness). Confirm
   the assertion still passes; if strategy-distinctness coverage is still wanted, note it migrates to
   the meta-test routing map (item 1), which is the authoritative routing guard.

Acceptance: `StrategyCorrectnessMetaTest` representative-routing GREEN; `StructuralHashTest` GREEN
with a truthful comment.

### C4.5 — Full suite + spotless + close

- `./gradlew :reggie-codegen:test :reggie-runtime:test` (or full `build`) — all green.
- Span sanity (informal, full gate is C6): the now-DFA-routed `(a)?b`/`(.)?b` produce JDK-identical
  group spans via `Reggie.compile(...)` — covered by `PikeVMRoutingTest.pikeVMCaptureMatcher...`
  behavioral assertions and the meta-test correctness pass.
- `./gradlew spotlessApply` (mandatory pre-commit/pre-push).
- Mark task **#40 → completed**. **Stop and wait for input** — do not roll into C5/C6.

---

## 2. Files in scope (strict)

- **edit:** `reggie-codegen/.../analysis/PatternAnalyzer.java` — block at `:780-791` (+ insert), catch at `:838-849` (C4.3)
- **edit:** `reggie-runtime/.../PikeVMRoutingTest.java` — flip + rename (C4.1)
- **edit:** `reggie-runtime/.../StrategyCorrectnessMetaTest.java` — re-key `(.)?b`, handle PIKEVM_CAPTURE key (C4.4.1)
- **edit:** `reggie-codegen/.../analysis/StructuralHashTest.java` — fix B5 comment (C4.4.2)
- **no change:** `DFA.java`, `SubsetConstructor.java`, `StructuralHash.java` (per 0.3 — routing-only),
  `RuntimeCompiler.java`, `ReggieMatcherBytecodeGenerator.java` (their `captureAmbiguous` path is untouched),
  the `:765-778` `alternationPriorityConflict` decline (Phase D / #42)

If implementation surfaces a pattern that reaches 780 yet whose DFA spans diverge from the JDK oracle,
**stop and report** — that would mean C2 is incomplete, and routing must not be widened over it.

---

## 3. Risks & mitigations

- **A pattern reaching 780 has spans the TDFA gets wrong** → mitigated: C2's `SubsetConstructorPriorityTest`
  + the meta-test correctness pass now run `(.)?b` through `DFA_UNROLLED_WITH_GROUPS` against the JDK oracle.
- **`PIKEVM_CAPTURE` becomes unreachable by tests** → expected: it is now an explosion-only fallback.
  Keep it routable in code (C4.3); document the loss of a compact representative in C4.4.1 rather than
  inventing a fragile huge-pattern test.
- **Scope creep into the `alternationPriorityConflict` decline** → the 765 guard is Phase D; C4 must not
  touch it. `(fo|foo)`/`(a|ab)` stay on `OPTIMIZED_NFA` until D1.
- **Cache fragmentation** → none: C3 proved the topology hash already separates these DFAs; no hash change.
