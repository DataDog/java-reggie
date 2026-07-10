# TDFA (tagged-DFA) capture engine — Implementation Plan

**Date:** 2026-07-10
**Design:** [doc/2026-07-10-tdfa-capture-engine-design.md](2026-07-10-tdfa-capture-engine-design.md)
**Status:** not started — this plan is for a go/no-go decision, same as the design doc. Phase 0 is
the only phase authorized to start without a further checkpoint; every later phase is gated on the
previous one's exit criteria (see design §7).

---

## 0. Prerequisites and conventions

- Build/test: `./gradlew :reggie-runtime:test :reggie-integration-tests:test`
- Spotless: `./gradlew spotlessApply` before every push
- Debug tool: `./gradlew :reggie-runtime:debugPattern -Ppattern="..."` to inspect strategy/bytecode
  routing for any pattern touched by this work
- New runtime classes go in `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/`; new tests
  in the sibling `src/test` tree, mirroring `RejectDfaFactoryTest`'s reflection-based access pattern
  for package-private classes
- **Naming: this is `Laurikari*`, not `Tagged*`, on purpose.** The codebase already has an
  unrelated "Tagged DFA" mechanism — `PatternAnalyzer.MatchingStrategyResult.useTaggedDFA`
  (`PatternAnalyzer.java:3420`) drives `DFAUnrolledBytecodeGenerator.generateTaggedDFAMatching`
  for the `DFA_UNROLLED_WITH_GROUPS` strategy (compile-time-unrolled bytecode, inline group
  tracking, bounded to <20-state explicit DFAs — a different technique for a different, smaller
  problem than what this plan builds). Every new class here (`LaurikariNfaStep`,
  `LaurikariDFACache`, `LaurikariDfaMatcher`, `LaurikariEligibility`, ...) is named after the
  algorithm (design §4) specifically to stay unambiguous from `useTaggedDFA`/
  `DFA_UNROLLED_WITH_GROUPS` in grep/search and code review. Do not rename these back to
  `Tagged*` even if it reads more naturally — that's the collision this naming avoids.
- **Two distinct correctness oracles are in play, not one** — the design doc's §6 says
  "`PikeVMMatcher` is the oracle," which is correct for isolating this-engine-specific bugs
  quickly, but the project's actual system-of-record fuzz harness
  (`reggie-integration-tests/.../AlgorithmicFuzzTest.java` +
  `.../fuzz/RegexFuzzOracle.java`) uses **JDK `Pattern` as the oracle**, per
  `RegexFuzzOracle.java:30`'s class doc ("The JDK is the oracle; any divergence is reported...").
  This plan uses both, at different stages:
  - **Fast, engine-isolating checks** (Phases 0–1, run constantly during development): new engine
    vs `PikeVMMatcher`, same call, same input — cheap, in-process, no fuzz-harness plumbing needed.
  - **System-of-record validation** (Phase 1 exit, Phase 2): once the new engine is wired to
    actually receive real patterns, extend `AlgorithmicFuzzTest`'s existing pattern-generation
    window to include its eligible shapes and let the existing JDK-oracle harness catch anything
    the PikeVMMatcher-only comparison missed (this also protects against the class of bug where
    the new engine and `PikeVMMatcher` agree with each other but both diverge from JDK — unlikely
    given `PikeVMMatcher`'s current fuzz baseline (see below), but not provably impossible).
  - `usePosixLastMatch` patterns are explicitly excluded from eligibility (design §5) — their
    correctness is unaffected by this work and continues to be covered by whatever already exercises
    `HybridMatcher`.
- **Current fuzz baseline is NOT zero** — `AlgorithmicFuzzTest.java:64` declares
  `KNOWN_FINDINGS_BUDGET = 28` for the currently-active 25k–50k pattern window (two tracked bugs,
  B-CGG-1/B-SQG-1, per that file's own class-doc history). The `project_reggie_prod_readiness`
  memory note this plan and its design doc both cited ("fuzzer currently at zero known
  divergences") is stale relative to that constant — **re-check the live value of
  `KNOWN_FINDINGS_BUDGET` before treating any number in this plan as current**; every "must not
  regress the baseline" criterion below means "must not increase whatever that constant is at
  the time," not literally zero.
- No commits without being asked; this plan produces code across several PRs, one per phase at
  minimum (see §5 below) — do not batch phases into a single PR.

---

## 1. Phase 0 — boolean/localization-only single-tag spike

**Goal** (design §7): prove a `LaurikariDFACache` with exactly one tag ("leftmost live start
position") gives sound, tight localization for `find()` on `SQL_ANSI`-shaped patterns, without
committing to full capture semantics yet.

**Explicitly NOT this phase's job** (see design §7's "necessary but not sufficient" caveat):
proving the multi-tag state-space is tractable. That's Phase 0.5. Do not read a Phase 0 pass as
evidence for Phase 1's feasibility.

### Task 0.1 — `LaurikariNfaStep` interface

**File (new):** `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariNfaStep.java`

```java
/**
 * One tagged-NFA step: given active (state, register-mapping) pairs and a character, returns the
 * next active state ids together with the register operations each transition carries.
 *
 * <p>Phase 0 uses exactly one tag (register slot 0 = "candidate start position"); later phases
 * generalize {@code registerCount}. Kept as a separate interface from {@link NfaStep} rather than
 * changing NfaStep's shape, matching how {@link RejectDfaFactory}/{@link PikeVMMatcher} already
 * keep multiple independent NfaStep-shaped lambdas per purpose.
 */
@FunctionalInterface
interface LaurikariNfaStep {
  /**
   * @param curStates active NFA state ids (subset)
   * @param curRegs curStates[i]'s register file, i.e. curRegs[i] is state curStates[i]'s tag-0
   *     value (the candidate start position that reached it, or -1 if none has)
   * @param c the character being consumed
   * @return next active state ids and their per-state register files, merged/deduped per the
   *     priority rule in design §4 (earlier-priority arrival's register wins on merge)
   */
  LaurikariStepResult apply(int[] curStates, int[] curRegs, int c);
}
```

**New small value type**, same file or `LaurikariStepResult.java`:
```java
final class LaurikariStepResult {
  final int[] states;
  final int[] regs; // regs[i] corresponds to states[i]
  LaurikariStepResult(int[] states, int[] regs) { this.states = states; this.regs = regs; }
}
```

**Completion criterion:** compiles; no behavior yet.

### Task 0.2 — `LaurikariDFACache` (single-register specialization)

**File (new):** `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariDFACache.java`

Model directly on `LazyDFACache` (read that class in full before starting — this task is a
close structural port, not a fresh design):
- State key changes from `StateSetKey` (subset only) to a new `LaurikariStateSetKey` that hashes/equals
  on `(sortedStates, perStateRegisterValue)` pairs — two states with the same subset but different
  register values are different DFA states (design §5's "State-space bounding" paragraph).
- Keep `DEFAULT_CAP`, `UNCACHED`/`DEAD`/`FALLBACK` sentinels, and the `frozen` freeze-on-cap-hit
  behavior verbatim — same lazy-materialize discipline, same fallback contract.
- `findFrom`/`findEnd` equivalents don't apply in the same shape here — Phase 0's actual entry
  point is closer to a direct replacement for `RejectDfaFactory`'s self-anchoring find, but instead
  of unioning a fresh `startAll` closure every step (which erases attribution, design §1/§3), each
  merge keeps the **smaller** register value in a genuine tie (that's the whole point: this DFA can
  answer "which start is this" because ties resolve by an explicit rule instead of by an
  unconditional union).
- Do **not** implement the general `N`-tag register-merge machinery here — hardcode single-register
  (`int`, not `int[]`) for this phase; Task 0.5.x is where it generalizes. Keeping Phase 0's
  implementation deliberately narrow is what makes it fast to build and easy to reason about; don't
  let scope creep from "this will need to generalize eventually" pull Phase 1's machinery in early.

**Completion criterion:** unit tests (new `LaurikariDFACacheTest`, modeled on
`RejectDfaFactoryTest`'s reflection-based access pattern) covering:
- Simple literal, alternation with multiple leading chars (same base cases as
  `RejectDfaFactoryTest` — confirms the plain-subset side of this still works).
- **The adversarial case that killed the windowed-scan prototype** (design §1): a pattern with an
  unbounded-width literal/comment construct (reuse `SQL_ANSI`'s `'...'`/`/*...*/` shape, or a
  minimal standalone repro) over an input where the real match's content spans wider than
  `BUDGET_CELLS`'s implied window — must correctly report the true leftmost start, not silently
  narrow past it.
- A case exercising genuine merge ties (two distinct start positions both reaching the same NFA
  state at the same position) — confirms the "smaller register wins" rule, not just the trivial
  single-candidate path.

### Task 0.3 — Spike harness / measurement

Not a shippable matcher yet — a throwaway (but committed, since it's the evidence this phase exists
to produce) test or small `main()` that:
1. Builds `LaurikariDFACache` over `SQL_ANSI`'s NFA (reuse `IastRegexpBenchmark.SQL_ANSI`'s pattern
   string, or the compiled NFA if more convenient).
2. Runs it over the LONG-scale input from `IastRegexpBenchmark` (confirm branch has the rescale —
   it does now, this branch is rebased onto `perf/bitstate-hot-loop-flattening`).
3. Reports: (a) the localized start/end bounds returned vs. the real match's actual bounds (must be
   exact for a single-tag scheme — there's no ambiguity to approximate away), (b) total distinct
   DFA states materialized, (c) wall-clock vs. the current full-`PikeVMMatcher`-punt path.

**Exit criteria for Phase 0** (verbatim from design §7 — do not relax these):
- (a) correct localization on the adversarial unbounded-width-literal case.
- (b) state count for `SQL_ANSI`-class patterns stays within a budget comparable to
  `LazyDFACache.DEFAULT_CAP` (4096).
- (c) measurable win over the current full-`PikeVMMatcher`-punt on the LONG-scale IAST benchmarks
  (re-run `IastRegexpBenchmark`'s `SqlAnsiFind`/`SqlMysqlFind`/`SqlPostgresqlFind` at LONG scale;
  compare against the numbers already recorded in the design doc's §1 table).

**If Phase 0 fails (b) specifically** (state count blows up even for one tag): stop here. That's a
worse signal than anything Phase 0.5 could add — if a single scalar tag already blows up the state
space for `SQL_ANSI`-class patterns, multi-tag will not do better. Fall back to design §9's cheaper
mitigations (raise `BUDGET_CELLS`, or document the cliff) instead of proceeding.

---

## 2. Phase 0.5 — intermediate checkpoint: 2–3 independent tags

**Goal** (design §7): the cheapest available signal on whether real multi-tag state-space blowup is
tractable, before committing to Phase 1's full `2 * (groupCount + 1)`-tag machinery. This phase
exists specifically because Phase 0's single total-ordered tag cannot exercise cross-tag merge
conflicts at all (design §7's "necessary but not sufficient" analysis) — don't skip it under
schedule pressure; skipping it converts this plan back into the un-derisked version the adversarial
review flagged.

### Task 0.5.1 — Generalize register file to `int[]` (small, fixed N)

Extend `LaurikariDFACache`/`LaurikariNfaStep` in place (or throw away Phase 0's implementation and
rebuild this specific class if the single-register specialization doesn't generalize cleanly —
acceptable either way, since that code's job was to be a fast, disposable spike, not a foundation)
to carry a small fixed-size `int[]` register file per state instead of one scalar. Conflict
resolution: **whole-mapping priority discard**, exactly as design §4 specifies — when two arrivals
reach the same target NFA state, the lower-priority arrival's entire register mapping is dropped,
not merged tag-by-tag. This is the same rule as Phase 1; Phase 0.5's job is to validate it doesn't
explode the state space at small N, not to invent a different rule.

### Task 0.5.1a — Hand-derive tags for exactly the three Task 0.5.2 patterns

**This task exists to break a circularity**: Task 0.5.2's patterns are real capturing-group
patterns, but the *general* mechanism for deriving a tag id from an arbitrary `NFA.NFAState
.enterGroup`/`exitGroup` and emitting the matching register op is Phase 1's Task 1.1/1.2 —
which is gated on Phase 0.5 passing. Do not pull that general mechanism forward. Instead, for
each of the three fixed patterns below, hand-pick which NFA transitions carry which of the 2-3
tags, hardcoded per pattern (a short `switch`/`if` on the pattern string, or three separate
tiny test-only builder methods — throwaway code, not shipped past this phase):
- `(a+)(b+)`: 2 tags, one pair (open/close) *per group is overkill for this checkpoint's purpose* —
  use exactly 2 tags total, one for group 1's close position and one for group 2's open position
  (the two boundaries that actually move independently under backtracking); that's enough to
  exercise cross-tag merge conflicts without needing a full 4-tag (2-group × open/close) model yet.
- `(ab)+`: 2 tags — the loop body's single group's open and close, re-set on every iteration
  (tests the loop-back re-set semantics directly, Laurikari's flagged subtlety).
- `((a)|())*`: 3 tags — outer group open/close plus inner group open (the inner group's close is
  the interesting nullable case: does it get correctly left unset when the empty alternative wins).

Task 1.1/1.2 in Phase 1 **generalize this hand-rolled, pattern-specific derivation into an
automatic, pattern-independent mechanism** driven by `enterGroup`/`exitGroup` directly — this task
is intentionally the narrow, disposable version of that work, scoped to exactly three known
patterns, not a preview of the general case.

### Task 0.5.2 — Adversarial multi-tag test patterns

Specifically chosen to exercise cross-tag divergence (design §7's "ahead on one tag, behind on
another" scenario):
- Two independent, non-nested capturing groups: `(a+)(b+)` over inputs where greedy backtracking
  forces re-evaluation of both groups' boundaries.
- One group inside a quantifier: `(ab)+` — tests the loop-back register semantics Laurikari's paper
  flags as the classic subtlety (design §4's algorithmic-soundness review flagged this as the
  highest-risk correctness surface for exactly this reason).
- Nested groups with an empty-body iteration option: `((a)|())*` — exercises the same
  `groupBodyNullable`-class scenario `PikeVMMatcher`'s trailing-empty-iteration rebind logic exists
  to handle correctly today (design §6).

### Task 0.5.3 — Measure and report

Same three metrics as Phase 0 (state count, correctness vs. `PikeVMMatcher` on the adversarial set,
wall-clock), but the state-count number is the one that actually matters here — compare its growth
rate from 1→2→3 tags. Roughly linear-in-practice growth is a green light for Phase 1; anything that
looks combinatorial already at N=3 is a stop signal (extrapolate, don't wait to hit the wall at
N=`2*(groupCount+1)` for a real multi-group pattern).

**Exit criteria for Phase 0.5:**
- State-space growth from Phase 0 (N=1) through this checkpoint (N=2-3) stays within a workable
  budget (no fixed number given in the design — this is a judgment call informed by the actual
  measured curve, not a threshold to hit).
- All three adversarial patterns in Task 0.5.2 produce capture output identical to `PikeVMMatcher`.
- If either fails: stop here, same fallback as Phase 0's failure path (design §9). Do not proceed to
  Phase 1 on the strength of Phase 0 alone (that's precisely the mistake the adversarial review
  caught).

---

## 3. Phase 1 — full capture tags

**Precondition:** Phase 0 **and** Phase 0.5 both passed their exit criteria.

### Task 1.1 — Tag numbering

Derive `2 * (groupCount + 1)` tags from `NFA.NFAState.enterGroup`/`exitGroup` (nullable `Integer`
group-number fields — not `GroupEntry`/`GroupExit`, which don't exist; see design §4's corrected
wording). Tag `2*g` = group `g`'s open, `2*g+1` = group `g`'s close, matching
`PikeVMMatcher.java:192`'s existing `slotCount = 2 * (groupCount + 1)` layout exactly, so the
eventual `MatchResult` construction can reuse `PikeVMMatcher`'s/`BitStateMatcher`'s existing
`buildCaptureResult`-shaped helpers without a slot-numbering translation layer.

### Task 1.2 — Full priority-ordered register-merge determinization

Generalize Phase 0.5's `int[]`-register machinery to the real tag count. Determinization loop
structure (design §4):
1. For each `(DFA-state, char)` pair not yet cached, compute consuming transitions from every NFA
   state in the current subset, in the same priority order `PikeVMMatcher.addThread` already
   walks epsilon closures — grep `PikeVMMatcher.java` for `addThread` and replicate its traversal
   order exactly, don't re-derive priority ordering from scratch.
2. For each NFA transition consumed, compute the register ops it carries: `copy` for pass-through
   tags, `set-to-current-pos` for the tag(s) matching this transition's `enterGroup`/`exitGroup`
   (if any), `set-unset` where applicable (e.g. re-entering a group after a prior non-participating
   alternation branch).
3. When two arrivals target the same NFA state in the new subset, keep only the higher-priority
   arrival's full register mapping (whole-mapping discard, not per-tag merge — design §4).
4. Intern the resulting `(subset, register-mapping)` pair via `LaurikariStateSetKey`, exactly as
   Phase 0.5 already does.

**Do not add a minimization pass** (design §5's explicit guardrail) — if this task's state-space
growth needs a second lever beyond cap-and-fallback, that's a separate, later investigation
(operation-aware minimization, not classical subset-equivalence), not something to reach for here
under time pressure.

### Task 1.3 — Eligibility gate

**New function**, e.g. `LaurikariEligibility.isEligible(NFA nfa)` (or a static method alongside the
new matcher class) implementing design §5's gate exactly:
- No assertions, no backrefs (same check as `RejectDfaFactory.build`'s guard / `PikeVMMatcher
  .noAssertionsOrBackrefs`).
- No atomic groups (same check as `findDfaEligible`).
- Anchors: `START`/`STRING_START`/`START_MULTILINE` handled via the reinject-closure split
  (port `PikeVMMatcher`'s `reinjectClosureIds`/`reinjectAfterNlClosureIds` construction); end
  anchors (`$`/`\Z`/`\z`/`END_MULTILINE`) and `\b` handled as per-step/per-accept checks against the
  caller-supplied `regionEnd`, **not** folded into subset/register identity (design §3) — reuse
  `PikeVMMatcher.checkAnchor`'s exact switch-case semantics rather than reimplementing anchor logic.
- **`usePosixLastMatch` explicitly excluded** — this is a separate check from the above (grep
  confirms `findDfaEligible`/`noAssertionsOrBackrefs` never reference it; do not assume the
  backref/lookaround/atomic-group checks cover it). Wire this from
  `PatternAnalyzer.MatchingStrategyResult.usePosixLastMatch` (`PatternAnalyzer.java:3426`) if the
  eligibility check runs at the `PatternAnalyzer` layer, or thread the flag through to wherever the
  NFA-level gate runs otherwise.

**Test:** a dedicated `LaurikariEligibilityTest` with one case per exclusion reason (lookahead,
backref, atomic group, `\b`/end-anchor inside a loop — mirroring `findDfaEligible`'s own
loop-reachability check — and `(a|a)+`-shaped `usePosixLastMatch` patterns), confirming each is
correctly rejected, plus positive cases confirming ordinary capturing patterns pass.

### Task 1.4 — `LaurikariDfaMatcher`

**New file:** `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariDfaMatcher.java`,
implementing `ReggieMatcher` (mirror `BitStateMatcher`'s shape: constructor takes `(NFA nfa, String
pattern)`, lazily-constructed `PikeVMMatcher fallback`, `fallbackCount()` observability method for
test pinning, same as `BitStateMatcher.fallbackCount()`). At this phase, **do not wire it into
`PatternAnalyzer`/`RuntimeCompiler` routing yet** — that's Phase 2. Build and test it standalone,
constructed directly in tests the way `RejectDfaFactoryTest`/a hypothetical `LaurikariDFACacheTest`
already do.

### Task 1.5 — Fast correctness checks (dev-loop oracle: `PikeVMMatcher`)

Property-based/parameterized test comparing `LaurikariDfaMatcher` against `PikeVMMatcher` directly,
same pattern + input, across:
- `find`/`findFrom`/`match`/`matches`/`findMatch`/`findMatchFrom` — every `ReggieMatcher` method
  `LaurikariDfaMatcher` implements.
- The Phase 0.5 adversarial set (Task 0.5.2) plus new cases: alternation branches with shared
  prefixes but different capture groups (design §6), unrolled-quantifier multi-anchor paths
  (mirroring what `PikeVMMatcher.clistViaMultipleAnchors` exists to handle).
- Anchor-boundary cases specifically re-testing the `regionEnd`-vs-scan-bound separation (design
  §6's "do not re-introduce that class of bug" — this is the same bug class fixed once already this
  session in `BitStateMatcher.search`; write the equivalent test here proactively rather than
  waiting to rediscover it).

### Task 1.6 — System-of-record validation (oracle: JDK, via existing fuzz harness)

**Task 1.6a — build the matcher-injectable oracle variant first.** `RegexFuzzOracle.check(String
pattern, String input)` (`RegexFuzzOracle.java:97`) hard-codes
`Reggie.compile(pattern, ReggieOptions.builder().allowJdkFallback().build())` to produce the
`ReggieMatcher` it exercises — there is no parameter or seam to inject a caller-supplied matcher
instance, and since Phase 2 hasn't wired `LaurikariDfaMatcher` into strategy selection,
`compile()` can never route to it yet. Task 1.6 cannot proceed without first adding an overload (or
sibling class, e.g. `RegexFuzzOracle.checkAgainst(String pattern, String input, ReggieMatcher
candidate)`) that runs the same JDK-oracle comparison logic against an explicitly-supplied matcher
instead of one obtained via `compile()`. This is new, unscoped-elsewhere plumbing — treat it as its
own small task, not an assumed detail of Task 1.6b below.

**Task 1.6b — extend the fuzz window.** Using Task 1.6a's new oracle entry point, extend
`AlgorithmicFuzzTest`'s pattern-generation window (see that file's `BASE_SEED`/window-advance
convention, already documented in its own class doc) to include `LaurikariEligibility`-eligible
shapes, exercised directly against `LaurikariDfaMatcher` instances built by hand in the test (not
through the full compile pipeline, since Phase 2 hasn't wired routing). This is the check that
would catch a bug where the new engine and `PikeVMMatcher` happen to agree with each other but both
diverge from JDK.

**Exit criteria for Phase 1:**
- Task 1.5 and Task 1.6b both introduce zero *net-new* divergences on top of whatever
  `KNOWN_FINDINGS_BUDGET` currently is in `AlgorithmicFuzzTest.java` at the time this phase runs
  (re-check the live constant — see §0's note; do not assume it is still 28, and do not assume it is
  zero).
- `LaurikariEligibilityTest`'s full exclusion matrix passes, including the `usePosixLastMatch` case.

---

## 4. Phase 2 — integration

**Precondition:** Phase 1 passed.

### Task 2.1 — Choose and wire the integration point

Design §7 defers this decision to Phase 1 findings; this task is where that decision gets made and
executed. Two concrete options, both already scoped in the design:
- **Option A**: wire into `BitStateMatcher`'s existing decision point — when a call would exceed
  `BUDGET_CELLS` (`BitStateMatcher.java:61`) and the pattern is `LaurikariEligibility`-eligible, try
  `LaurikariDfaMatcher` before falling all the way through to `PikeVMMatcher`. Smallest-blast-radius
  option: only changes behavior for calls that were already hitting the fallback cliff.
- **Option B**: a new `MatchingStrategy` value (mirror the `COUNTING_GLUSHKOV` enum-addition
  pattern used for a prior new strategy: `PatternAnalyzer.java`'s `MatchingStrategy` enum at line
  3335, plus the exhaustive switches in `RuntimeCompiler.isNfaBacked()`/`generateBytecode()` and
  `PatternDebugger.main()` — see `BITSTATE_CAPTURE`'s existing switch arms, e.g.
  `PatternDebugger.java:103`, as the concrete template). Larger blast radius, but makes the new
  engine a first-class, directly-selectable strategy rather than a fallback-of-a-fallback.
  **This option also triggers AGENTS.md's dual-path rule** ("bytecode-generation changes must
  update both `RuntimeCompiler.java` and `ReggieMatcherBytecodeGenerator.java`") —
  `reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java`
  has a `default: throw new IllegalStateException("Unknown strategy: " + strategy)` catch-all
  (around line 741) that would fire the first time `PatternAnalyzer` recommends the new strategy
  for an `@RegexPattern`-annotated (compile-time) pattern, unless this file gets an explicit carve-out
  — follow `BITSTATE_CAPTURE`'s existing precedent there (folds into the `PIKEVM_CAPTURE` branch,
  `ReggieMatcherBytecodeGenerator.java:108-115`: "v1 intentionally does not add a [...]-backed
  codegen path for the annotation-processor pipeline") rather than building full compile-time
  codegen support in this phase.

Recommendation if no strong signal emerges from Phase 1: start with **Option A** — it composes
trivially with the existing fallback architecture (design §2's goal of "keep the existing fallback
pattern" is satisfied for free), requires no `PatternAnalyzer`/enum/bytecode-dual-path changes, and
directly targets the motivating problem (design §1's fallback cliff) without opening the larger
question of where TDFA ranks against every other native strategy. Option B can be revisited later
as a separate, smaller follow-up once Option A is shipped and validated in practice.

### Task 2.1a — Confirm `HybridMatcher` disjointness against the real strategy table

Design §5 explicitly requires this, not an assumption: "Phase 2 ... should confirm this stays
disjoint in `PatternAnalyzer`'s eventual strategy table rather than assume it." Task 1.3's
`usePosixLastMatch` exclusion is necessary but was decided in Phase 1, before real routing
existed — it is not sufficient on its own to call this confirmed. Concretely: once Task 2.1's
integration point is wired, take every pattern already routed to `HybridMatcher` today via
`RuntimeCompiler.shouldUseHybrid` (patterns where `PatternAnalyzer.hasGroupsInRepeatingQuantifiers`
sets `usePosixLastMatch = true` — e.g. `(fo|foo)`, `(a|ab)`, `(.1[1])+`, `(.c)+`; existing coverage
in `PikeVMRoutingTest`/`DfaUnrolledGroupAndFindRegressionTest`, which assert routing to
`DFA_UNROLLED_WITH_GROUPS`/`usePosixLastMatch`, not literally to a `shouldUseHybrid` string — grep
for `usePosixLastMatch`/`DFA_UNROLLED_WITH_GROUPS` in those files, not `shouldUseHybrid`) through
the new integration point and confirm none of them get redirected to `LaurikariDfaMatcher` instead
of `HybridMatcher`. This is a real test to write and run, not a design-review checkbox.

### Task 2.2 — Observability

`fallbackCount()`-equivalent (already present on `LaurikariDfaMatcher` per Task 1.4) plus, if Option A
is chosen, a way to distinguish "served by TDFA" from "served by BitState's own bounded DFS" from
"punted all the way to PikeVMMatcher" for the same kind of reflection-based test verification used
throughout this investigation (e.g. `BitStateMatcher.fallbackCount()`'s existing test-only accessor
pattern).

### Task 2.3 — Re-run the motivating benchmark

Re-run `IastRegexpBenchmark`'s `SqlAnsiFind`/`SqlMysqlFind`/`SqlPostgresqlFind` at all three scales
and confirm the LONG-scale loss documented in the design doc's §1 table is closed (reggie/RE2J back
above 1.0x at LONG, not just improved). If the open, unattributed `LdapFind`/`UrlAuthFind`/
`UrlQueryFind` degradation from the same benchmark run (design §1) turns out to share this
mechanism, re-run those too and note it — but do not assume it does; that's still an open question
this plan does not resolve.

**Exit criteria for Phase 2 / overall project:**
- Chosen integration point wired, tested, `spotlessApply`-clean.
- Task 2.1a's `HybridMatcher` disjointness check passes with zero pattern reassigned away from it.
- Benchmark re-run confirms the win is real end-to-end, not just at the isolated matcher level.
- Zero *net-new* divergences on top of `AlgorithmicFuzzTest`'s `KNOWN_FINDINGS_BUDGET` at the time
  (re-check the live constant, same caveat as Phase 1's exit criteria).

---

## 5. Sequencing and PR boundaries

One PR per phase minimum, each independently reviewable and each leaving the tree in a shippable
state (even if the shipped state is "new dead code behind an eligibility gate that nothing routes
to yet," for Phases 0/0.5/1):
1. Phase 0 PR: `LaurikariNfaStep`, `LaurikariDFACache` (single-register), spike harness/test, measured
   results reported in the PR description (not just "looks fine" — the actual numbers against the
   exit criteria).
2. Phase 0.5 PR: register-file generalization to small fixed N, the hand-derived per-pattern tag
   assignments (Task 0.5.1a), adversarial tests, growth-curve measurement reported in the PR
   description.
3. Phase 1 PR: full tag count, eligibility gate, `LaurikariDfaMatcher`, the fuzz-oracle
   matcher-injection seam (Task 1.6a), and both correctness passes (Task 1.5 fast dev-loop + Task
   1.6b fuzz-harness).
4. Phase 2 PR: integration wiring, the `HybridMatcher` disjointness check (Task 2.1a), and
   benchmark re-run results in the PR description.

Do not open Phase N+1's PR before Phase N's exit criteria are confirmed and reported — that
ordering is the entire point of the phased design (design §7's core fix for the "Phase 0 doesn't
de-risk Phase 1" gap).

---

## 6. Kill criteria (any phase)

Stop and fall back to design §9's cheaper mitigations (raise `BUDGET_CELLS`, or document the
residual cliff) if, at any phase boundary:
- State-space growth is combinatorial rather than roughly linear in tag count (Phase 0.5's core
  question, but re-check at Phase 1 with the real tag count too — Phase 0.5 extrapolates from N≤3,
  it doesn't prove N=`2*(groupCount+1)` behaves the same).
- Correctness divergences against either oracle (`PikeVMMatcher` or JDK) can't be driven to zero
  **within 2 engineer-weeks of focused debugging on a single divergence, or after 3 independent
  root-cause attempts on it, whichever comes first** — priority/merge correctness was flagged
  (design §6) as the highest-risk correctness surface, and Laurikari-family tag semantics are
  genuinely subtle; a persistent, unexplained divergence is a stronger stop signal here than in
  most of this codebase's other native-strategy work, precisely because there's no existing local
  implementation to diff against when something goes wrong. (These numbers are a starting default,
  not derived from any historical data point in this codebase — whoever runs this phase should
  adjust them explicitly, in writing, rather than silently drifting past an unstated threshold.)
- The measured end-to-end win (Phase 2, Task 2.3) doesn't materialize even after Phase 1 passed in
  isolation — possible if the integration point chosen in Task 2.1 doesn't actually intercept the
  calls that were hitting the cliff (verify Task 2.2's observability confirms real routing before
  concluding a lack of speedup is the engine's fault, not the wiring's).
