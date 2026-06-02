# Phase C5 — Determinization-cost guard + native `find()` prerequisite probe

**Parent:** `phase-c-priority-ordered-tdfa-tasks.md` § Task C5.
**Prereqs landed:** C1 (`f926402`, #37), C2 (`f926402`, #38), C3 (`f926402`, #39), C4 (`c8a8b31`, #40).
**Tracker:** task **#41**.

**Goal (two independent halves):**
1. **C5.1 — determinization-cost guard:** *confirm* (not add) that the existing DFA state caps + the
   C4 `StateExplosionException → PIKEVM_CAPTURE` fallback already bound worst-case tagged-DFA
   determinization, and that C2's priority selection did **not** inflate state counts. Add a guard
   test only if a regression is found.
2. **C5.2 — native `find()` prerequisite probe:** *characterize* whether the C2 priority-correct
   tagged DFA, executed with a scanning `find()` and a priority-cut accept rule, reproduces JDK group
   spans for the **alternation-boundary class** (`(fo|foo)`, `(a|ab)`, `(a|ab)(c|)`). This is the gate
   that decides whether **Phase D / #42** (retiring the `alternationPriorityConflict` decline) can
   proceed — and, if so, what accept-handling Phase D must implement.

**Out of scope (do NOT touch):**
- The `alternationPriorityConflict` decline at `PatternAnalyzer.java:765-778` — retiring it is **D1
  (#42)**. C5.2 only *probes* what D1 would need; it changes no routing.
- Any `SubsetConstructor` / `DFA.java` algorithm change (C2 is done; this is verification + a probe).
- `PIKEVM_CAPTURE` routing (settled in C4).

---

## 0. Evidence — current state (commit `c8a8b31`)

### 0.1 Determinization is already capped in three places

| Cap | Location | Effect |
|---|---|---|
| `stateCache.size() > 10000` → `StateExplosionException` | `SubsetConstructor.java:193-194` | hard ceiling on tagged-DFA states during `buildDFA(nfa, true)` |
| `DFA_UNROLLED_STATE_LIMIT = 20` | `PatternAnalyzer.java:36` | inline-DFA routing threshold |
| `DFA_SWITCH_STATE_LIMIT = 300` | `PatternAnalyzer.java:37` | switch-DFA routing threshold; above it → `OPTIMIZED_NFA` (non-ambiguous) or `PIKEVM_CAPTURE` (capture-ambiguous, `PatternAnalyzer.java:803-811`) |
| `StateExplosionException` catch | `PatternAnalyzer.java:859-872` | pure-regular anchor-free → `PIKEVM_CAPTURE`; else `OPTIMIZED_NFA` (C4.3) |

C5.1's claim from the master plan: **"No new cap needed unless C2 inflates state counts."** The only
open risk is that C2's winning-thread selection could split DFA states that the old lowest-id tiebreak
merged (master plan § Risks: "State explosion from priority splitting"). C5.1 must *measure* this, not
assume it.

### 0.2 The alternation-boundary class never reaches the DFA-with-groups path today

`(fo|foo)` and `(a|ab)` are caught at `PatternAnalyzer.java:765`:

```java
if ((containsAlternation(ast) || containsOptionalQuantifier(ast))
    && dfaHasAcceptingStateWithTransitions(dfa)) {        // ← (fo|foo): accept after "fo" still has 'o' edge
  ... return OPTIMIZED_NFA; r.alternationPriorityConflict = true;
}
```

`dfaHasAcceptingStateWithTransitions` (`PatternAnalyzer.java:1255`) fires because the merged DFA for
`(fo|foo)` has an accepting state (after `fo`) that **still has an outgoing `o` transition** (toward
`foo`). This is the leftmost-**longest** (DFA-natural) vs leftmost-**greedy-priority** (Perl/Java)
conflict in its purest form:

- **JDK:** `Pattern.compile("(fo|foo)").matcher("foo").find()` → match `"fo"` `[0,2)`, group(1)=`"fo"`
  (first alternative wins; the engine does **not** extend to `"foo"`).
- **Naive DFA (longest-accept):** keeps consuming `o`, accepts `"foo"` `[0,3)` — **divergent**.

So the C5.2 probe's real question is: *does the C2 priority-correct DFA carry enough information to
stop at the highest-priority accept (`"fo"`) instead of running to the longest accept (`"foo"`)?*

### 0.3 We already have an anchored tagged-DFA simulator to extend

`SubsetConstructorPriorityTest.simulateTaggedDfa(dfa, nfa, input)`
(`reggie-codegen/.../automaton/SubsetConstructorPriorityTest.java`) mirrors the
`DFAUnrolledBytecodeGenerator` tagged path **for `matches()` (anchored, `matchStart=0`)**:
start-state `GroupAction`s at pos 0, per-transition `TagOperation`s (START=pre-increment `i`,
END=post-increment `i+1`). It returns `tags[2*g]/tags[2*g+1]` or `null` on reject.

C5.2 needs a **`find()`-scanning** sibling that (a) tries each start position left-to-right, and (b)
applies a **priority-cut accept rule** so an accepting state commits even when outgoing transitions
exist. Native `find()` in the generator is `findFrom(input, startPos)`
(`DFAUnrolledBytecodeGenerator.java:636-648`); the probe mirrors its scanning contract without
compiling bytecode.

### 0.4 Why this is a *probe*, not an implementation

C2.4 of the master plan committed the **highest-priority accepting thread's tag vector** on accepting
states. That fixes *which thread's spans* are reported. It does **not** by itself decide *where
scanning stops* when an accepting state has outgoing edges — that is the accept-overrun the 765 guard
detects. C5.2 determines which of two outcomes holds, and reports it as the C5 deliverable:

- **Outcome A (probe passes):** the C2 DFA + a "commit at first accept reached by the highest-priority
  thread" rule reproduces JDK spans. → Phase D can retire the 765 decline by implementing that
  accept-cut in the find/matches loop. C5.2 hands D1 a working reference simulator.
- **Outcome B (probe fails):** the post-C2 `DFA`/`TagOperation` data does **not** carry which accept
  belongs to the highest-priority thread vs a continuation thread. → Phase D additionally needs an
  explicit per-state **accept-priority signal** (e.g. an `acceptIsPriorityCut` flag on `DFAState`).
  C5.2 documents exactly what is missing.

Either outcome is a successful C5.2 — the point is a **non-speculative, JDK-backed answer**, not a
routing change.

---

## 1. Tasks

### C5.1 — Determinization-cost guard (measure, then confirm-or-add)

**C5.1.a — Measure state counts across the repro + corpus.**
Add a small, deterministic test (codegen module) that builds `buildDFA(nfa, true)` for the C2/C4
repro set (`(.)?b`, `(a)?b`, `(fo|foo)`, `(a|ab)`, `(a|ab)(c|)`, `(aa|a)a`, plus the 13 Track-1 repros
referenced in C2.1) and records `dfa.getStateCount()`. Assert each stays **well under** `10000` (the
`SubsetConstructor` ceiling) and document the observed counts inline. Purpose: prove C2's priority
selection did not blow up state counts vs the pre-C2 baseline.

> **Repro-set sourcing:** reuse the exact pattern list already encoded in
> `SubsetConstructorPriorityTest` (C2) rather than re-deriving it, to keep the two tests in lockstep.

**C5.1.b — Confirm the explosion fallback is wired (no new cap).**
Cross-check that the `StateExplosionException` catch (`PatternAnalyzer.java:859`) routes the
pure-regular subclass to `PIKEVM_CAPTURE` (landed in C4.3) — this is the *only* guard the master plan
requires for >cap determinization. Confirm via the existing `DFAStateBudgetFallbackTest`
(`reggie-runtime/.../DFAStateBudgetFallbackTest.java`) still passes; extend it with one assertion for
a pure-regular capturing pattern if it does not already cover that branch. **Do not** lower the
`10000` ceiling or add a new cap unless C5.1.a surfaces a regression.

**Acceptance:** state-count test green with counts recorded; explosion fallback test green. **If
C5.1.a finds inflation past a routing cap that the old code stayed under → STOP and report** (that
means C2 changed determinization cost and the master plan's "no new cap needed" assumption is void).

### C5.2 — Native `find()` prerequisite probe (TDD, characterization)

**C5.2.a — Build the JDK oracle expectations first (RED-able fixtures).**
For each alternation-boundary pattern × inputs, compute the JDK reference with
`java.util.regex.Pattern` (overall match span + group(1) span via `find()`):

| Pattern | Input | JDK `find()` match | JDK group(1) |
|---|---|---|---|
| `(fo|foo)` | `"foo"` | `[0,2)` `"fo"` | `[0,2)` |
| `(fo|foo)` | `"xfooy"` | `[1,3)` `"fo"` | `[1,3)` |
| `(a|ab)` | `"ab"` | `[0,1)` `"a"` | `[0,1)` |
| `(a|ab)` | `"xaby"` | `[1,2)` `"a"` | `[1,2)` |
| `(a|ab)(c|)` | `"abc"` | *(record from JDK)* | *(record)* |

Encode these as a data-driven table; the JDK values are the source of truth (compute them in-test,
do not hard-code by hand — `Pattern`-derive them so the table can never drift).

**C5.2.b — Extend the tagged-DFA simulator to scanning `find()` with a priority-cut accept.**
In the codegen module (new `TaggedDfaFindSpanProbeTest`, or a helper added beside
`SubsetConstructorPriorityTest.simulateTaggedDfa`), add `simulateTaggedDfaFind(dfa, nfa, input)`:

- Outer loop `start = 0 .. input.length()`: run the anchored tagged-DFA simulation from `start`
  (reuse the existing `simulateTaggedDfa` stepping logic, parameterized by `matchStart`).
- **Priority-cut accept rule:** when the simulation enters an accepting `DFAState`, *commit the
  current tag vector and stop extending* — i.e. take the **first** accept reached, not the longest.
  (This is the candidate rule Phase D would implement; the probe tests whether it yields JDK spans.)
- Return the leftmost start position that accepts, with its committed tag vector; `null` if none.

This loop is the explicit subject under test — it encodes the *hypothesis* about what native `find()`
should do, and the assertion against the C5.2.a oracle confirms or refutes it.

**C5.2.c — Assert + record the outcome.**
Run `simulateTaggedDfaFind` against the C5.2.a table:

- **All green → Outcome A.** Document in the plan close-out: "the C2 DFA + first-accept priority-cut
  reproduces JDK spans for the alternation-boundary class; Phase D may retire the 765 decline by
  implementing this accept-cut in the find/matches loop. Reference simulator:
  `TaggedDfaFindSpanProbeTest`." Mark the probe a **PASS / Phase-D-unblocked**.
- **Any red → Outcome B.** Capture the exact divergence (pattern, input, expected vs simulated span)
  and inspect whether the post-C2 `DFAState.groupActions` / transition `TagOperation`s distinguish the
  highest-priority accept from a continuation accept. Document precisely what additional signal Phase D
  must add (e.g. `DFAState.acceptIsPriorityCut`), and mark the probe **PASS / Phase-D-needs-signal**
  with the gap recorded. **Do not** invent that signal here — that is D1's implementation work.

> A failing assertion in C5.2.c is **not** a C5 failure — it is the probe doing its job. The C5
> deliverable is the documented, JDK-backed answer plus a reusable reference simulator. Only a
> *crash*, a *non-deterministic* result, or an inability to derive the JDK oracle is a C5.2 failure.

**Acceptance:** `TaggedDfaFindSpanProbeTest` is deterministic and green *as written* (its assertions
encode whichever outcome holds — A asserts equality, B asserts the documented divergence as a pinned
known-gap), and the close-out states Outcome A or B with evidence.

### C5.3 — Suite + spotless + close

- `./gradlew :reggie-codegen:test :reggie-runtime:test` — all green.
- `./gradlew spotlessApply` (mandatory pre-commit/pre-push; see memory `feedback_spotless`).
- Record the C5.1 state counts and the C5.2 Outcome (A or B) in this plan's close-out section.
- Mark task **#41 → completed**. **Stop and wait for input** — do not roll into C6 (#44) or D1 (#42).

---

## 2. Files in scope (strict)

- **add:** `reggie-codegen/.../automaton/TaggedDfaStateCountTest.java` *(or fold into an existing
  codegen DFA test)* — C5.1.a state-count measurement.
- **add:** `reggie-codegen/.../automaton/TaggedDfaFindSpanProbeTest.java` — C5.2 find()-scanning probe
  + priority-cut simulator. *(Alternatively extend `SubsetConstructorPriorityTest` with the
  `find()` helper — prefer a separate file so the C2 anchored tests stay focused.)*
- **maybe-edit:** `reggie-runtime/.../DFAStateBudgetFallbackTest.java` — only if C5.1.b finds the
  pure-regular explosion branch uncovered.
- **edit (close-out only):** this plan doc — record measured state counts + C5.2 Outcome.
- **NO production change** expected. If C5.1.a forces a cap change, that is a deviation → STOP and
  report before editing `PatternAnalyzer.java` / `SubsetConstructor.java`.
- **NO change:** `PatternAnalyzer.java:765-778` (Phase D / #42), `DFA.java`, `SubsetConstructor.java`
  (algorithm), routing of any kind.

---

## 3. Risks & mitigations

- **C2 inflated state counts past a routing cap** → C5.1.a measures directly; STOP-and-report if a
  pattern that previously fit `DFA_SWITCH_STATE_LIMIT` now exceeds it.
- **Probe simulator drifts from the real generator** → mirror `DFAUnrolledBytecodeGenerator`'s tagged
  stepping exactly (reuse C2's `simulateTaggedDfa` core) and `findFrom`'s scanning contract; the
  simulator is a *reference for D1*, so fidelity to the generator is the whole point.
- **Hand-written JDK oracle is wrong** → derive every expected span from `java.util.regex.Pattern`
  in-test (C5.2.a), never hard-code.
- **Scope creep into D1** → C5.2 must not edit the 765 guard or add a `DFAState` field; Outcome B only
  *documents* the needed signal. Retirement + signal implementation is D1.
- **Treating a probe RED as a build break** → C5.2's assertions are written to encode the *actual*
  outcome (A or B); a documented known-gap is pinned, not left failing. Only crashes / nondeterminism
  fail C5.

---

## 4. Close-out

- C5.1 measured tagged-DFA state counts (repro set): `(.)?b`=4, `(a)?b`=3, `(fo|foo)`=4, `(a|ab)`=2,
  `(a|ab)(c|)`=4, `(aa|a)a`=4, `(a)b`=3. All well below the 10 000 ceiling and the 300 switch cap.
  C2's priority selection did **not** inflate state counts. No new cap needed.
- C5.1.b: the `StateExplosionException → PIKEVM_CAPTURE` path for pure-regular capturing patterns is
  exercised by code review only (C4.3); no deterministic lightweight test pattern exists. Documented
  via comment in `DFAStateBudgetFallbackTest`.
- C5.2 Outcome (as originally recorded): "A — Phase-D-unblocked. … No additional `DFAState`
  accept-priority signal is required." **This conclusion is CORRECTED below — it does not hold.**
  The C2 priority-correct tagged DFA with a *commit-on-first-accept* rule does reproduce JDK
  leftmost-first group spans for the five probed alternation-boundary `(pattern, input)` pairs
  (`TaggedDfaFindSpanProbeTest`), but those five are all *cut* cases — the probe set was biased and
  never exercised a greedy-continue case.
- **C5.2 Outcome — CORRECTED to B (per-state signal required).** Commit-on-first-accept is **not** a
  rule D1 can apply blanket. Counterexample: `(a)+` on `"aaa"` builds a DFA with the *identical*
  topology to `(fo|foo)` — an accepting state with an outgoing transition — yet requires the
  **longest** accept (`[0,3)`), whereas `(fo|foo)` on `"foo"` requires the **first** accept (`[0,2)`).
  Boolean `DFAState.accepting` (`DFA.java:302`) cannot separate them. The generators
  (`generateTaggedDFAMatching`, `findLongestMatchEnd`, switch `findMatchEnd`) currently take the
  longest accept, which is correct for `(a)+` and wrong for `(fo|foo)`. The probe's first-accept rule
  is correct for `(fo|foo)` and wrong for `(a)+`. Neither blanket rule is universally correct, so a
  per-state signal is mandatory. (`(a)+` is matched correctly today because it bypasses the line-765
  decline — `containsAlternation`=false, `containsOptionalQuantifier`=false — and rides the
  longest-match `DFA_*_WITH_GROUPS` path; the fuzzer exercises it.)
- Phase D (#42) prerequisite verdict: **unblocked, but via Outcome B, not A.** D1 must retire the
  `alternationPriorityConflict` decline at **both** sites (`PatternAnalyzer.java:765` capturing,
  `:909` non-capturing) by introducing a per-state **`acceptIsPriorityCut`** flag — PikeVM's
  accept-cut rule made static (cut iff lowest accepting NFA-state rank ≤ lowest consuming-out-edge
  rank, i.e. `acceptRank ≤ continueRank`), computed from the C1 ordered closure, hashed per the
  StructuralHash HARD RULE, and honored at every accept site in both generators. `TaggedDfaFindSpanProbeTest`
  remains a valid reference *for the cut subclass only*. Full breakdown:
  `doc/plans/phase-d-native-alternation-priority-tasks.md` (§ 0 records this same refutation).
