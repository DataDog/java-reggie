# Track 2 — Native Capture at Full Speed: Implementation Plan

**Status:** ready to execute (deferred R&D, no release pressure).
**Decision:** *Both, fully sequenced* — PikeVM generator first (correctness + JDK-elimination, isolated/low-risk), then priority-ordered TDFA (O(n) + native `find()`), then Phase 1a.
**Background:** `doc/temp/prod-readiness/group-capture-fix-design.md` (Track 1/2), `doc/temp/prod-readiness/submatch-extraction-research.md` (techniques), memory `project_reggie_safe_backtracking_investigation`.

---

## 0. What we're fixing and why

Track 1 (commit `6af634a`) made the engine **correct** by *declining* the capture-ambiguous
subclass to JDK: `SubsetConstructor.captureAmbiguous` → `OPTIMIZED_NFA`/`JavaRegexFallbackMatcher`.
That eliminated 13 silent-wrong-answers but at the cost of speed on optional/alternation-bound
capturing groups, and it left two pre-existing delegation classes in place:

- **RICH_API_HYBRID** (`StrategyJdkClassifier`): `SPECIALIZED_LITERAL_ALTERNATION`,
  `FIXED_REPETITION_BACKREF` — native booleans, JDK-delegated group extraction (~0.57–0.62× JDK,
  i.e. the "2× slower than JDK on captures" complaint).
- **The Track-1 over-declines** + the deferred **Phase 1a** (`alternationPriorityConflict`
  patterns like `(fo|foo)`, `(a|ab)`).

**Root cause (verified in code):** `SubsetConstructor.computeTagOperations` (line 694) and
`computeGroupActions` (line 473) pick a group's START/END tag by **lowest NFA-state-id**
(`if (existing == null || op.priority < existing.priority)`, lines 725/757/771/818/827/499), where
`op.priority == NFAState.id` (`DFA.TagOperation.priority`, DFA.java:262). This is a *structural*
heuristic decoupled from **which NFA thread actually wins the match**. The winning thread is
determined by **Perl/Java leftmost-greedy thread priority** (alternation left-first, greedy
match-first), which the NFA *does* encode in epsilon **insertion order** (`ThompsonBuilder`
alternation adds left branch first, line ~113; `*`/`?` add try-match before try-skip, lines
~119–147) — but that order is **discarded** because `SubsetConstructor.computeEpsilonClosure`
(line 208) flattens into an unordered `Set` (and `precomputeEpsilonClosures`, line 195, into
`Map<…,Set>`).

So the fix, at root, is: **make thread priority authoritative and carry it through closure +
capture assignment.** PikeVM does this at runtime (Phase B); TDFA does it at determinization time
(Phase C).

---

## Phase A — Priority-ordered epsilon-closure substrate (shared)

**Goal:** establish a single, tested, priority-ordered ε-closure primitive that both the PikeVM
generator (Phase B) and the TDFA rework (Phase C) consume. No behavior change to existing
strategies in this phase — purely additive.

**Files:**
- `reggie-codegen/.../automaton/NFA.java` — `NFAState.epsilonTransitions` is already an
  insertion-ordered `List` (line 335); confirm `getEpsilonTransitions()` returns it in order
  (line ~350). **No structural change needed**, but add a doc comment that insertion order ==
  Perl thread priority (load-bearing contract).
- `reggie-codegen/.../automaton/ThompsonBuilder.java` — **audit & lock the priority contract**:
  - Alternation (visitAlternation ~107): left-to-right epsilon insertion — already correct.
  - Greedy `?`/`*` (~119–147): try-match epsilon before try-skip — verify the *skip* path priority
    (skip is currently encoded via exit-set membership, not an epsilon; confirm this preserves
    "match-before-skip" once closure is ordered).
  - **Lazy quantifiers `*?`/`+?`/`??`:** the explorer found **no** lazy/greedy distinction in the
    builder. Decide scope: either (a) implement lazy by swapping epsilon order (PikeVM-native), or
    (b) keep lazy on the existing decline path and assert the analyzer never routes lazy-quantified
    captures to the new engines. **Recommend (b) for Phase A/B**, revisit in Phase C.
- `reggie-codegen/.../automaton/SubsetConstructor.java` — add (do not replace yet):
  - `orderedEpsilonClosure(NFAState start)` → `List<NFAState>` (priority-ordered, dedup keeping
    first occurrence = PikeVM rule). Mirror `computeEpsilonClosure` (line 208) but use a
    priority-respecting traversal (DFS following `getEpsilonTransitions()` in order) and a
    `LinkedHashSet` for first-wins dedup.

**Tasks:**
- A1. Audit `ThompsonBuilder` and write a unit test asserting epsilon insertion order for
  alternation and greedy quantifiers encodes left-first / match-first priority.
- A2. Implement `orderedEpsilonClosure` + unit tests (a few hand-built NFAs: `a|b`, `(a)?b`,
  `(a|ab)`), asserting the ordered thread list matches expected Perl priority.
- A3. Document the "epsilon order = thread priority" contract in `NFA.java` and `ThompsonBuilder`.

**Gate:** new unit tests green; **no** change to any existing strategy routing; full build green;
fuzzer still 0 (nothing wired in yet).

---

## Phase B — PikeVM NFA-with-capture bytecode generator (isolated, correct)

**Goal:** a new bytecode generator that runs Reggie's existing Thompson NFA as a lock-step PikeVM
with per-thread capture slots, giving **correct native captures** for any pure-regular pattern.
Leftmost-greedy falls out of thread priority (Phase A order) — no determinization, ReDoS-safe
O(n·m). This *replaces JDK delegation* for the Track-1-declined subclass and the RICH_API_HYBRID
group-extraction path.

**New strategy:** `PIKEVM_CAPTURE` in `PatternAnalyzer.MatchingStrategy` (enum ~line 2128).

**Files (new + edits):**
- NEW `reggie-codegen/.../codegen/PikeVMCaptureBytecodeGenerator.java` — model on the structure of
  `OnePassBytecodeGenerator.java` / `DFAUnrolledBytecodeGenerator.java`. Emits:
  - lock-step `clist`/`nlist` over NFA program PCs (one thread per PC → bounded by program size);
  - per-thread `int[] saved` capture slots (`2k`/`2k+1` = start/end of group k);
  - priority-ordered `addThread` using Phase-A ordered closure (first thread at a PC wins);
  - `save` ops at `enterGroup`/`exitGroup` states; cut lower-priority threads on accept.
  - Reusable, **allocation-free** capture buffer (per `ReggieMatcher` instance, reset per call;
    matches the project's allocation-free rule and the research doc's `CaptureLocations` note).
- `reggie-codegen/.../analysis/PatternAnalyzer.java` — routing: where Track 1 currently sets
  `captureAmbiguous` → fallback (lines ~603–607 backref path, ~775–785 lookaround path, and the
  `dfa.isCaptureAmbiguous()` route), **prefer `PIKEVM_CAPTURE`** for the *pure-regular* subset
  (no backrefs, no recursion, no variable-width lookaround). Keep JDK fallback only for genuinely
  non-regular constructs. Also route `SPECIALIZED_LITERAL_ALTERNATION` and the regular case of
  `NESTED_QUANTIFIED_GROUPS` here.
- `reggie-codegen/.../analysis/StrategyJdkClassifier.java` — classify `PIKEVM_CAPTURE` as
  **NATIVE** (remove the corresponding RICH_API_HYBRID/FULL_FALLBACK entries it now covers).
- `reggie-runtime/.../RuntimeCompiler.java` — wire `PIKEVM_CAPTURE` to the new generator; it is
  thread-safe per the NFA-backed `NFA_CLASS_CACHE` fresh-instance rule (already established for
  NFA-backed matchers).
- `reggie-processor/.../ReggieMatcherBytecodeGenerator.java` — allow `PIKEVM_CAPTURE` at build time
  (it's NATIVE now, no longer rejected).
- `reggie-codegen/.../analysis/StructuralHash.java` — **HARD RULE check.** PikeVM consumes the NFA
  program, not new DFA fields, so DFA-topology hashing is unaffected. BUT `StructuralHash.compute`
  (line 62) already hashes the strategy; confirm two patterns that differ only by routing to
  `PIKEVM_CAPTURE` vs a DFA strategy get distinct hashes. No new DFAState/DFATransition field ⇒ no
  `computeDFATopologyHash` change expected. Add a `StructuralHashTest` case to lock this in.

**Tasks:**
- B1. Add `PIKEVM_CAPTURE` enum + `StrategyJdkClassifier` NATIVE classification + meta-test
  representative pattern.
- B2. Implement `PikeVMCaptureBytecodeGenerator` (TDD: hand-written tests for `(.)?b`/"b",
  `a{1}()|.`/"a", `(0)?\Z`/"", `(fo|foo)`, `(a|ab)`, `(a|ab)(c|)`, `(aa|a)a` — assert spans == JDK).
- B3. Route the pure-regular declined subclass + `SPECIALIZED_LITERAL_ALTERNATION` to
  `PIKEVM_CAPTURE` in `PatternAnalyzer`; keep non-regular on fallback. Narrow Track-1
  `captureAmbiguous`→fallback to `captureAmbiguous`→`PIKEVM_CAPTURE` where regular.
- B4. Wire `RuntimeCompiler` + processor; update `NFAFallbackPatterns.java` benchmark fixtures that
  were converted to `Reggie.compile()` back to native where now supported.
- B5. `StructuralHash` distinctness test for the new routing.
- B6. Update `StrategyCorrectnessMetaTest`: remove the relevant `captureAmbiguousIntercepted`
  skips that PikeVM now covers; add `PIKEVM_CAPTURE` representatives.

**Gates (all must hold):**
- Extended differential fuzzer `zeroDivergenceGate_enforcedViaProperty` (`-Dreggie.fuzz.enforceZero=true`)
  at **0** over ≥76k checks (the `match()`-span oracle already lands this).
- `StrategyCorrectnessMetaTest` (`-Dreggie.metatest.enforce=true`) at **0** mismatches, all 8 public
  API methods.
- `GroupExtractionBenchmark` + `StrategyBenchmark`: PikeVM native vs prior JDK-delegation — expect
  ≥ parity with JDK on the RICH_API_HYBRID classes (was 0.57–0.62×).
- Full `./gradlew build` green. `spotlessApply` before commit.

---

## Phase C — Priority-ordered TDFA (O(n) + native find())

**Goal:** make `DFA_*_WITH_GROUPS` correct for **all** pure-regular patterns by replacing the
lowest-NFA-id tag heuristic with priority-correct (leftmost-greedy) determinization. Recovers full
DFA O(n) speed for the PikeVM-covered subclass and is the part that lets native `find()` be correct
(prerequisite for Phase D). This is the high-blast-radius surgery — gated by Phase B's correct
engine as an oracle cross-check.

**Files:**
- `reggie-codegen/.../automaton/SubsetConstructor.java`:
  - Replace the set-based closure feeding capture logic with Phase-A `orderedEpsilonClosure`.
  - `computeTagOperations` (694) and `computeGroupActions` (473): replace
    `op.priority < existing.priority` (lowest-id) with **"tag ops of the highest-priority thread
    reaching each target NFA state"** per the ordered closure. On accepting DFA states, commit the
    tag vector of the highest-priority accepting thread.
  - This may require per-DFA-transition reordered tag-op sequences and/or a per-state priority-cut
    marker (Laurikari register indirection if state-count grows).
- `reggie-codegen/.../automaton/DFA.java` — any new DFAState/DFATransition field (priority-cut flag,
  reordered tag-op order) introduced here.
- `reggie-codegen/.../analysis/StructuralHash.java` — **HARD RULE (mandatory).** `computeDFATopologyHash`
  (line 106) currently hashes tag ops as `tagId + type.ordinal()` **in list order** (lines 152–155)
  and group actions as `groupId + type.ordinal()` in order (127–130) — it does **not** hash
  `priority`. Since Phase C changes the *order/selection* of tag ops, the existing order-sensitive
  hash captures reordering **only if** the emitted list actually reorders; if a priority-cut flag or
  priority value becomes bytecode-affecting, **add it to the hash** and add a `StructuralHashTest`
  distinctness case (mirror `groupAtDifferentPositions_produceDifferentHashes`, line 65).
- Generators `DFAUnrolledBytecodeGenerator` / `DFASwitch*` / `DFA_TABLE` — consume the now
  priority-correct tag ops (values change, mechanism same); verify no generator assumes lowest-id.

**Tasks:**
- C1. Swap capture-path closure to `orderedEpsilonClosure`; keep boolean DFA paths untouched.
- C2. Rework `computeTagOperations` to winning-thread selection; rework `computeGroupActions`
  likewise. TDD against the same repro set as B2 **plus** the full 13 Track-1 repros.
- C3. If new DFA field added: update `DFA.java` + `StructuralHash` + `StructuralHashTest` (HARD RULE).
- C4. Narrow/remove the Track-1 `captureAmbiguous` decline: patterns now handled by correct TDFA
  stay on `DFA_*_WITH_GROUPS`; only non-regular remain declined.
- C5. Re-point routing: prefer `DFA_*_WITH_GROUPS` (O(n)) over `PIKEVM_CAPTURE` (O(n·m)) when the
  pattern determinizes within state caps; keep PikeVM as the fallback above caps / on explosion.
- C6. Determinization-cost guard: keep existing DFA state caps + NFA/lazy-DFA explosion fallback.

**Gates:** same as Phase B, **plus** `StructuralHashTest` distinctness for any new DFA field, **plus**
a benchmark showing TDFA recovers DFA-speed over PikeVM on the declined subclass. Phase B's PikeVM
result is used as a second oracle (PikeVM spans == TDFA spans == JDK spans).

---

## Phase D — Phase 1a: native alternation-priority matches()/match()/find()

**Goal:** retire the `alternationPriorityConflict` decline (`PatternAnalyzer` lines ~771, ~863).
With Phase C's priority-correct TDFA, alternation-boundary patterns (`(fo|foo)`, `(a|ab)`) get
correct group bindings, so native `matches()`/`match()`/`find()`/`findMatch()` become safe.

**Files:** `PatternAnalyzer.java` (remove/narrow the `alternationPriorityConflict` guard),
`StrategyCorrectnessMetaTest` (add alternation-priority representatives), oracle already covers it.

**Tasks:**
- D1. Remove the `alternationPriorityConflict` decline for cases Phase C proves correct; keep it
  only where still unproven.
- D2. Meta-test + fuzzer representatives for `(fo|foo)`, `(a|ab)`, `(a|ab)(bc|c)`, `(aa|a)a`.

**Gate:** fuzzer 0, meta-test 0, build green, benchmark showing native find() recovers speed.

---

## Cross-cutting rules (every phase)

1. **`spotlessApply` before every commit and every push** (memory: `feedback_spotless`).
2. **Do not commit unless asked.** Commit messages concise.
3. **StructuralHash HARD RULE:** any new bytecode-affecting DFAState/DFATransition field →
   `StructuralHash.computeDFATopologyHash` + a `StructuralHashTest` distinctness case. Skipping this
   ⇒ level-2 cache poisoning ⇒ silent wrong results.
4. **Two correctness gates are the backstop:** extended differential fuzzer (`match()`-span oracle,
   `RegexFuzzOracle` lines 112–143) at 0 ≥76k checks, and `StrategyCorrectnessMetaTest` enforced at 0
   across all 8 public API methods. No phase lands red.
5. **No external dependencies; allocation-free** generated runtime paths.
6. **Minimal scope:** each phase is independently landable and independently valuable
   (B alone removes JDK delegation; C alone restores O(n); D alone retires the alternation decline).

---

## Sequencing & parallelism

```
A (substrate) ──► B (PikeVM) ──► C (TDFA) ──► D (Phase 1a)
                     │              ▲
                     └── B is the oracle that de-risks C
```

- **A → B → C → D is strictly sequential** at the integration level (each consumes the prior).
- **Within a phase**, the test-authoring tasks (B2 repro tests, C2 repro tests, StructuralHash
  tests) can be drafted in parallel with the generator/closure implementation, then run as the
  TDD red→green loop.
- Land each phase as its own commit (or small commit series) with its gates green before starting
  the next. Update memory `project_reggie_safe_backtracking_investigation` after each phase with
  outcome + any newly-discovered repros.

## Single biggest risk per phase
- **A:** lazy-quantifier priority — mitigated by deferring lazy to the existing decline path (B) and
  revisiting in C.
- **B:** PikeVM O(n·m) perf vs JDK — mitigated by benchmark gate; if a class regresses, keep it on
  JDK until C.
- **C:** determinization correctness / cache poisoning — mitigated by StructuralHash HARD RULE +
  PikeVM-as-oracle + the fuzzer at 0.
- **D:** residual alternation cases C didn't prove — mitigated by keeping a narrowed guard.
