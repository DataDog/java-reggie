# Implementation plan — capture-cost reduction for PIKEVM_CAPTURE patterns

Status: IMPLEMENTATION PLAN (derived from the 2026-06-17 design + 3 adversarial review rounds)
Date: 2026-06-17
Scope: two independent workstreams — **Phase 1** (boolean-find perf) and **Phase 2** (capture
extraction via tagged DFA). Phase 1 ships first and is independently valuable; Phase 2 is gated
on Phase-1 data and its own correctness proof.

> The design rationale, the refuted alternatives, and the verified counterexamples live in git
> history of this file (design draft + r1/r2/r3 review). This document is the **task breakdown**.
> Constraints carried from review are inlined as **[invariant]** / **[gate]** notes.
> New types/methods/fields are marked **[PROPOSE]** — do not create them until signed off
> (per repo rule: propose helpers before adding them).

---

## 0. Baseline tasks (do before any code)

**T0.1 — Re-baseline routing.** For each of the six IAST patterns
(`COMMAND, URL_JDK, SQL_ANSI, SQL_MYSQL, SQL_POSTGRESQL, QUERY_OBFUSCATOR` from
`IastRegexpBenchmark`), record `RuntimeCompiler.compile(p).getClass()` on the **current
branch**. Reason: review found COMMAND/URL routed to `JavaRegexFallbackMatcher` in one build,
not `PIKEVM_CAPTURE`. The whole plan assumes these reach `PikeVMMatcher`; verify it.
- Output: a table {pattern → matcher class → strategy → **Phase-1 G1 impact** → **Phase-2 wiring
  impact**}. The stop-condition is **per-phase**, not Phase-2-only:
  - If **COMMAND** is not on `PikeVMMatcher`, it cannot be improved by T1.1/T1.2/T1.3 (PikeVM-only)
    — before starting T1.x, either drop COMMAND from G1's success set (and from the "COMMAND-boolean"
    Phase-1 scope) **or** re-route COMMAND to PikeVM first. It also loses its Phase-2 wiring point.
  - If **URL** is not on `PikeVMMatcher`, Phase 2 has no wiring point for it — flag and reduce the
    Phase-2 eligible set accordingly (possibly to {COMMAND} → see G-entry).
  - Confirm **SQL×3 + QOBF are on PikeVM** (the load-bearing Phase-1 assumption); if any are not,
    re-scope G1.
- Acceptance: table committed to this doc's appendix.

**T0.2 — Profile the boolean hot path.** Run async-profiler (or JFR) on `reggieSqlAnsiNoMatch`
and `reggieQueryObfuscatorNoMatch`. Attribute cost across the three candidate sources, each of
which maps to a different lever: (i) per-character `O(stateCount)` `Arrays.fill`
(`resetNlist`/`swapLists`/`resetClist`) → T1.1; (ii) the `findStartFrom` restart loop → T1.2;
(iii) the **recursive epsilon-closure DFS in `addThread:423`** → *neither T1.1 nor T1.2 address
this* — if it dominates, add a closure-memoization lever rather than forcing the win onto
T1.1/T1.2. Confirm the capture `System.arraycopy` is *not* dominant (8 bytes for `groupCount==0`).
**This profile sets the priority/stop criteria for T1.1/T1.2 (see Phase-1 intro) — run it before
committing to lever order.**
- Acceptance: flamegraph + a per-source attribution (i/ii/iii) committed to the appendix.

Group counts (verified, fixed): SQL×3 + QOBF = **0** capturing groups; COMMAND = **1** (`(.*)`);
URL_JDK = **3** (2 named + 1 unnamed). → Phase 1 covers SQL×3+QOBF+COMMAND-boolean; Phase 2
targets COMMAND `group(1)` and URL named groups.

---

## Phase 1 — cut per-character `O(stateCount)` boolean-scan cost

[invariant] No new matcher class, no codegen tables, no `StructuralHash` change. Reuses the
already-fuzz-validated `PikeVMMatcher`. Every task gated on **boolean + span/offset parity** vs
current PikeVM (find() returns offsets, not just booleans — see G1).

**Lever order is set by T0.2, not hardwired.** T1.1 (epoch guards) and T1.2 (prefilter) are both
unconditional algorithmic wins targeting *different* input classes (T1.1 helps all inputs; T1.2
helps no-match), so neither is pre-cut — but T0.2's per-source attribution decides which to do
first and when to stop. If T0.2 attributes the dominant cost to the `addThread` closure DFS
(source iii), add a closure-memoization lever before assuming T1.1/T1.2 suffice.

All file paths below: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java`
unless noted.

**T1.1 — Epoch (generation-counter) thread guards.** Replace `boolean[] inClist`/`inNlist`
(cleared by `Arrays.fill` per char in `resetClist:566-567`, `resetNlist:617`, `swapLists:628`)
with `int[] clistEpoch`/`nlistEpoch` + an `int epoch` counter; "present" ≡ `epoch[id]==epoch`.
Per-char reset becomes `epoch++` (O(1)) instead of O(stateCount) fill.
- **[PROPOSE]** fields `int[] clistEpoch, nlistEpoch; int clistGen, nlistGen;` replacing the two
  boolean arrays; private helpers `markClist(id)/inClist(id)` etc.
- Remove the dead pre-loop at `:624-626` (immediately overwritten by the `:628` fill it replaces).
- [invariant] **`swapLists` obligation:** under epochs, `swapLists` must bump `clistGen` (O(1)
  invalidation of the old clist generation) and re-stamp exactly the moved nlist ids into the new
  clist generation, then bump `nlistGen` to clear nlist membership. The `addThread`
  stamp-before-read order (guard read `:369`; presence writes `:373/:394/:442`) and the
  `willUpdateGroupEntry` reads (`:422/:488`) must observe the same per-generation membership the
  boolean arrays gave.
- [invariant] The `pruneAnchorDerivedAtStart` sticky write (`inClist[id]=true`, `:598`) is **not**
  re-read mid-scan (`addThread` runs only from `initClist` after `resetClist`, never mid-scan), so
  a naive `epoch++` is behavior-preserving there — no re-stamp needed. Document this rather than
  assuming a re-entry bug.
- [invariant] `clistViaMultipleAnchors` and the anchor/skip flags keep their exact semantics —
  only the membership-guard representation changes.
- Tests: existing PikeVM unit + differential suites green; **pin
  `PikeVmCaptureRegressionTest.anchorInRepeatedGroup` (`1|(0|^a?){3}`)** as the guard for this
  refactor (exercises the anchored zero-length-accept path); add a `stateCount >> liveThreads`
  parity test.
- Acceptance: green suites; profiler shows the `Arrays.fill` frames gone.

**T1.2 — Required-first-char prefilter — ✅ DONE (verified + measured, 2026-06-17).**
`findStartFrom`/`findMatchResultFrom` skip start positions whose char cannot begin any match.
- Implemented in `PikeVMMatcher`: constructor computes `firstByteAscii[128]` from the start-state
  epsilon closure (crossing anchor states; collects consuming-transition chars) + `prefilterUsable`
  (false when the pattern can match empty, or when every ASCII char can start — so it self-disables
  for `\S+`/`.*` leads like COMMAND). Allocation-free per match; O(1) skip check.
- [invariant ✓] Sound: skips only positions where no consuming transition accepts the char and the
  pattern can't match empty; never changes which start wins. Non-ASCII positions conservatively
  never skipped. Anchors compose (a kept position failing `^`/`\b` still fails correctly inside
  `tryFindAt`).
- Correctness: ≥50k zero-divergence fuzz at the 18-finding baseline → **zero new divergences**.
- Perf (de-risk probe predicted skip-rates; gains tracked them): **SQL_ANSI no-match 78→~3000
  ops/ms (~38×), now ~5× FASTER than JDK**; QOBF no-match 17→29 (~1.7×); match variants
  unregressed. SQL MySQL/PostgreSQL share the structure → expected like ANSI.
- **Residual:** QOBF stays ~9× behind JDK — its cost is the 3007-state per-position closure on the
  un-skipped 55%, *not* start-count. Needs a different lever (T0.2 source iii: closure memoization,
  or route the 0-group alternation to a boolean DFA). Tracked, not done.

**T1.3 — Capture-free boolean path (cleanup, low priority).** When the call needs only a boolean
(`matches`/`find`/`findFrom`), skip the per-thread `int[2*(groupCount+1)]` allocation and the
`updateCaptures` copy (`:334,:631`). Expected **minor** for `groupCount==0` (8-byte copy) — do
it only if T0.2 attributes nonzero cost to it.
- [invariant] The boolean answer never depends on capture slots, so this is unconditionally safe;
  but the thread *scheduling* (priority DFS, anchor handling) must be untouched.
- Acceptance: green parity suites.

**T1.4 — self-anchoring boolean find() DFA for QOBF — ✅ DONE (verified + measured, 2026-06-17).**
First attempt (naive `LazyDFACache.findFrom` over the raw NFA) was reverted after the parity gate
exposed two unsoundnesses (empty-match, and the consume-past-later-start bug — witness
`(a|b){2,3}x` find `"ababx"`→false; JDK=true). The **correct** version, now shipped in
`PikeVMMatcher`: boolean `find()` for anchor/assertion/backref-free patterns uses a **self-anchoring
lazy DFA** whose step re-injects the start-state closure each char (an implicit `.*?` prefix), so
all start positions are tracked in one O(n) pass. Empty-matchable patterns short-circuit to `true`.
`findFrom()` (position), `matches()`, `findMatch()`/`match()`/`group` all stay on the
priority-correct thread simulation (spans unchanged).
- Correctness: full runtime/codegen/integration suites green; **≥50k zero-divergence fuzz at the
  18-finding baseline → zero new divergences** (the prior-attempt bugs are gone).
- Perf: **QOBF no-match 29→~6700 ops/ms (~231×), now ~29× faster than JDK**; **QOBF find(match)
  692→~47000 ops/ms (~68×), ~7.7× faster than JDK.** SQL is anchor-ineligible → untouched (still
  beats JDK via T1.2). **QOBF residual gap CLOSED.**
- Lesson reinforced: the 7-input de-risk passed but missed both bugs; only the ≥50k fuzz gate
  validated the correct version. De-risk with the fuzz, not a handful of inputs.

> **Discovered latent issue — INVESTIGATED 2026-06-17:** `LazyDFACache.findFrom` is unsound for
> general unanchored find (restart-on-DEAD skips viable later starts; witness `(a|b){2,3}x`/`ababx`,
> proven at the component level by the reverted T1.4). **Verification of shipped exposure:** I
> reached `LAZY_DFA` via `[ab]*a[ab]{350}` and find() was *correct* there — because `LAZY_DFA` is
> only reached by **star-led, self-anchoring** patterns (DFA explosion requires star-driven
> nondeterminism; the `[ab]*` lead is a `.*?` prefix that makes findFrom's restart moot). Every
> non-self-anchoring candidate routed to `DFA_TABLE` (correct find) or `PIKEVM_CAPTURE` (alternation
> intercept at `:1098`, correct find). **Verdict:** real component defect, but **not observably
> reachable via current `LAZY_DFA` routing** → low production risk. Fixing it (a correct
> unanchored boolean search with a `.*?` start self-loop) is the prerequisite for T1.4-done-right.

**[gate] G1 (Phase-1 exit):**
1. Differential fuzz: **boolean AND span/offset parity** (group-0 start+end for `find()`, all
   groups for `match()`) across the existing corpus. T1.2 changes *which* start position is
   examined, so boolean-only parity is insufficient — assert spans via the fuzz oracle's span
   comparison (`RegexFuzzOracle.java:138` match groups, `:167` find span). Note the oracle compares
   vs **JDK**; JDK span-parity is a *sound proxy* for PikeVM start-selection parity because PikeVM
   is leftmost-greedy (`PikeVMMatcher.java:24`) — exactly the leftmost-first invariant T1.2 must
   preserve — and T0.1 has confirmed these patterns are on the PikeVM path (no JDK-vs-JDK masking).
   Optional belt-and-suspenders: a before/after PikeVM span snapshot over the T0.1-confirmed
   PikeVM-path patterns.
2. `IastRegexpBenchmark` (boolean `find()`) vs JDK/RE2J: report ops/ms before/after; require a
   material improvement on the 4 zero-capture patterns + COMMAND.
3. `spotlessApply`; full suite green.
If G1 closes the gap to JDK, **Phase 2 may be unnecessary for the measured corpus** — record and
decide.

---

## Phase 2 — tagged-DFA capture extraction (COMMAND + URL only)

[invariant] Only for patterns with capturing groups whose captures are *read*
(`findMatch`/`group(n)`). Default **off** behind a flag until G2 passes.

### 2.0 Key facts the implementation relies on (verified)
- The full `NFA` is already at runtime on the PikeVM path (`PikeVMMatcher` fields `nfa:40`,
  `statesById:72`, `isAccept:75`, priority-ordered `getEpsilonTransitions()`). No "ship metadata"
  surface needed for the NFA itself.
- `SubsetConstructor` already resolves **marker selection by rank** (`computeTagOperations`,
  `computeGroupActions`, `dfaStateOrdering`, `buildRankMap`, C2.4 family) — the hard
  disambiguation. **But** its methods are `private`/package-private and live in
  `…codegen.automaton`, and `TagOperation`/`GroupAction` carry **no input position**.
- **A correct capture-extracting tagged DFA already exists in codegen**:
  `DFAUnrolledBytecodeGenerator:1647-1660` binds START→pre-consume `p`, END→post-consume `p+1`,
  and overrides zero-width accept-state groups via `emitAcceptStateGroupActions:1686-1710`.
  → Strongly prefer **extending eligibility of that existing path** over building a new replay
  matcher (see T2.6).

### 2.1 Two correctness faults that MUST be closed first (proven by execution in review)

**Fault 1 — carried-forward positions.** A marker frozen on a self-loop/revisited transition
re-fires every iteration; binding it to the transition index gives the loop position, but PikeVM
sets a boundary once and carries it forward (`System.arraycopy:334`). Exhibited by the existing
**print-only probe `RefuteProbeTest`** (`reggie-codegen/.../automaton/RefuteProbeTest.java` — it
prints to a file and does **not** assert): `(a*)(a*)`/`aaa` and `(a+)(a*)b`/`aaab` both want
`g2=[3,3)`, the §3.4 transition-index replay gives `[2,3)`. The ordering-stability gate is
**silent** on this.

**Fault 2 — the stability gate does not exist and is insufficient.** The path-dependent-priority
decline (counterexample family `(a|ab)(bc|c)` vs `(ab|a)(c|bc)`) is not implementable as a
"conflict on revisit" check today: `buildDFA` computes ordering only at first interning
(`:160-181`) and reuses it on revisit (`:159,:205`). And even when built, it does **not** catch
Fault 1.

### 2.2 Task breakdown

**T2.1 — Pin the regression corpus (do first; no production code).** Add construction-level +
end-to-end tests asserting JDK/PikeVM-exact spans for the §"Correctness obligations" list below.
Includes `RefuteProbeTest`'s cases, **promoted from print-only probe to asserted tests** (the
probe currently prints without asserting — author the assertions fresh here).
- Acceptance: tests exist and currently fail (or are `@Disabled` with reason) — they define G2.

**T2.2 — Implement the stability gate (Fault 2).** In `SubsetConstructor.buildDFA`, on a revisit
(`target != null`, `:159/:205`) recompute the candidate ordering via
`computeTransitionOrdering(currentOrdering, targets, chars)` and diff against
`dfaStateOrdering.get(targets)`; on mismatch mark the pattern **capture-unstable**.
- [invariant] **Pin the diff predicate — NOT raw `List.equals`.** `computeGroupActions:685-739`
  and `computeHasPriorityConflictTransition:330-346` depend only on the *min-rank holder per
  (group,action)* and the *minAcceptRank holder* — not full element order. Diff those keyed
  holders, not the whole list; a full-list compare over-declines on benign reorderings that
  preserve every key's min-rank holder, shrinking the eligible set and breaking the "stable
  patterns must not fire" acceptance below.
- **[PROPOSE]** field/result `boolean captureOrderingUnstable` on the build result, surfaced to
  `PatternAnalyzer` (do NOT conflate with existing `computeHasPriorityConflictTransition:330-346`).
- [invariant] No behavior change for non-Phase-2 patterns (the flag is read only by the Phase-2
  eligibility predicate). Over-approximation direction: false-"unstable" is *safe* (T2.3's `not
  captureOrderingUnstable` clause makes the pattern ineligible → falls back to PikeVM);
  false-"stable" is the correctness bug — bias toward declaring unstable.
- Tests: **both** mirror families `(a|ab)(bc|c)` AND `(ab|a)(c|bc)` set the flag, plus one
  3-branch path-dependent witness; **named negative controls** (`(a)(b)`, `(a|b)(c|d)`) must
  *not* set it (so the gate can't pass by flagging everything).

**T2.3 — Implement the eligibility predicate (Fault 1 + Fault 2).** **[PROPOSE]**
`eligibleForMultiPassTDFA(nfa, analysis)` in `PatternAnalyzer`:
- capturing groups present; no backref/lookaround/conditional/reluctant — reuse
  **`PatternAnalyzer`'s own private feature-presence predicates** (`hasBackreferences:1281`,
  `hasLookaround:1686`, `hasConditionals:1813`, `NonGreedyQuantifierDetector:4779`), callable
  directly since the new method is in the same class. (Do **not** wire `FallbackPatternDetector.
  needsFallback` — its only public method is strategy-conditional and returns null for
  PIKEVM_CAPTURE even on backref/lazy patterns; it is not a feature-presence source.)
- **not** `captureOrderingUnstable` (T2.2);
- **carried-forward-position restriction**: decline groups inside unbounded quantifiers and
  nullable groups following a loop (the `(a*)(a*)`/`(a+)(a*)` shape) — unless T2.5(A) is adopted.
- Verify COMMAND (`(.*)` — single trailing group, no following nullable) and URL (groups in
  alternation branches, not loops) pass.
- **Acceptance is a verdict, not a note:** if COMMAND declines, that is a Phase-2 **STOP/no-build**
  for this corpus (recorded), not a silent pass. Feeds the G-entry gate below.

**[gate] G-entry (after T2.3, before any engine code in T2.4+):** the explicit go/no-go the
plan previously left scattered.
- Eligible set **empty** → **STOP Phase 2** (recorded no-build); do not write T2.4+.
- Eligible set = **{COMMAND} only** (URL fails T2.2 or T0.1) → fall to the COMMAND-only
  build/no-build decision (Rollout) *before* writing engine code; the heavy machinery (gate,
  predicate, binding, Route A/B, register model) must be justified by one trailing group, or cut.
- Proceed to T2.4+ only if COMMAND is eligible **and** a **cheap pre-engine perf probe** shows an
  expected capture-extraction win over PikeVM for at least one eligible target. (The OR-with-
  "URL survives" is vacuous on this rung — URL-survives is definitionally true when reached — so
  require a positive perf signal instead.) The cheapest probe is the one-tag COMMAND baseline in
  T2.9 (Phase-1 boolean scan for match-end + single entry-tag read for `group(1).start`) plus a
  back-of-envelope URL estimate. This gates the Route-A/B decision and any T2.7/T2.9 build.

**T2.4 — Position-binding layer.** Provide START→`p` / END→`p+1` binding + zero-width accept
override, reusing the semantics already in `DFAUnrolledBytecodeGenerator:1647-1660,:1686-1710`.
- If extending the existing tagged-DFA path (T2.6 route), this is mostly **reuse**, not new code.
- If building a runtime replay matcher (T2.7 route), **[PROPOSE]** add a position class to the
  consumed tag-op data (a `boolean trailing`/type-derived flag) so the matcher can pick `p` vs
  `p+1`; `TagOperation` itself can stay unchanged if the matcher derives it from `type`.

**T2.5 — Resolve Fault 1 (pick one, gated on T2.3 eligibility; default: option (B)):**
*("Route A/B" is reserved for the T2.6/T2.7 engine choice; T2.5's choices are (A)/(B).)*
- **(B) eligibility restriction (default):** rely on T2.3 to decline carried-forward-position
  shapes → those fall back to PikeVM. Cheap and sound. **Start here** — COMMAND/URL are already
  verified (T2.3) not to hit the carried-forward shape, so this needs no benchmark to choose.
- **(A) register model** (Borsotti–Trofimovich): add set/copy register semantics so a tag value
  is copied, not re-derived, across transitions. General, correct, but real work —
  **[PROPOSE]** register fields on the tag-op representation. Build only if T2.3's restriction
  excludes a pattern we must support.

**T2.6 — Route A (recommended): extend the existing tagged-DFA-with-groups codegen** to accept
the Phase-2-eligible patterns currently sent to PIKEVM_CAPTURE. This reuses the already-correct
position binding + accept override and the structural-hash/codegen machinery.
- [invariant] **Dual-path + StructuralHash rule applies here**: any new NFA/DFA-derived field
  baked into codegen must be mixed into `StructuralHash` and produced identically by
  `RuntimeCompiler` and `ReggieMatcherBytecodeGenerator`. (This is the one route where the
  structural-hash obligation bites — see AGENTS.md Structural Hash Rule.)
- Risk: eager DFA construction state-explosion on these patterns (the reason they were on
  PikeVM). Bound with the existing DFA-size cap; on overflow, fall back to PikeVM.

**T2.7 — Route B (alternative): multi-pass replay matcher.** Only if T2.6 explodes.
**[PROPOSE]** `MultiPassTDFAMatcher` (runtime pkg, sibling of `HybridMatcher`):
- Pass 1: lazy-DFA boolean scan finds span `[s,e)`, records DFA-state id per position into a
  **reusable per-matcher `int[] path`** grown on demand (per-call/cached instance like
  `PikeVMMatcher`; not `ThreadLocal`).
- Pass 2: walk recorded path over `[s,e)`, apply each transition's tag ops with T2.4 binding into
  one `int[2*(groupCount+1)]` slot array. No per-thread arrays.
- Tag-op tables: compute lazily as `LazyDFACache` interns states → requires exposing the ordering
  machinery as a **runtime-visible public component** (extract from `SubsetConstructor`, caveat
  §2.0). **[PROPOSE]** that extraction.
- [invariant] StructuralHash is **moot** for this route (PikeVM-resident, string-keyed in
  `PIKEVM_NFA_CACHE`, returns before `StructuralHash.compute`, `RuntimeCompiler.java:480-483` vs
  `:510-511`). Metadata passed to ctor, not baked into a hash-keyed class.

**T2.8 — Wiring + flag + engine provenance.** Route eligible patterns (T2.3) to the chosen engine
behind a default-off flag in `RuntimeCompiler`; everything else stays on PikeVM. Named groups need
no engine change — `NameEnrichingMatcher` (`RuntimeCompiler.java:121`) wraps any matcher.
- **[PROPOSE] engine-provenance deliverable (load-bearing for G2.1/G2.3):** tag each produced
  span/`MatchResult` with the engine that produced it (PikeVM vs the new TDFA/replay engine),
  exposed so tests can assert it. Without this positive "came from the new engine" signal, the
  G2.1 route assertions and the G2.3 executed-counter cannot be evaluated for must-run obligations
  (the `eligible==false` branch only covers *declined* patterns). The same tag is the signal the
  T2.9/G2.3 counter increments on.

**[gate] G2 (Phase-2 exit, all required).** *Anti-vacuity is the theme: every behavioral item
must prove the new engine actually ran on the eligible set.*
0. **Non-empty eligible set incl. COMMAND** (G-entry passed). If the set is empty, G2 is a STOP,
   not a pass — a vacuously-green suite where everything fell back to PikeVM does **not** clear G2.
1. Every §"Correctness obligations" pinned test green (T2.1), and for each, a **route assertion in
   the same test method** (provenance + span value asserted on one invocation, so a tagged-but-
   wrong span cannot pass): the span was produced *either* by the new engine (engine-tagged result,
   T2.8) *or* by PikeVM with `eligibleForMultiPassTDFA==false`. The Fault-1 cases `(a*)(a*)`/`aaa`,
   `(a+)(a*)b`/`aaab` are the **declined** branch here under default option (B) — assert
   `eligible==false` + PikeVM-produced, not new-engine.
2. Stability gate (T2.2): `captureOrderingUnstable==true` asserted on **both** mirror families
   and the 3-branch witness; **false** on the negative controls.
3. Differential fuzz vs **both** PikeVM and `HybridMatcher.findMatch` (zero divergence) on the
   capture path. This harness **does not exist** (`FuzzRunner` wires only `RegexFuzzOracle` vs
   JDK) — building it is part of T2.9. Requirements:
   (a) **force the Phase-2 engine ON** for the eligible set (the default-off flag would otherwise
   compare PikeVM-vs-PikeVM and pass vacuously);
   (a′) the harness **MUST NOT reuse `RegexFuzzOracle` or compile with `allowJdkFallback()`** —
   compile the eligible set native-only so any internal fallback throws/skips loudly (satisfying
   (c)); the only comparison oracles are PikeVM and `HybridMatcher.findMatch`; every (pattern,input)
   result carries the T2.8 engine tag so a JDK-vs-JDK agreement path is structurally impossible;
   (b) **per-target** assertion, not a corpus-global counter: each must-run target (COMMAND, and
   each surviving URL named group) was executed *by the new engine* on ≥N inputs that yield a
   non-empty captured `group(n)` (a single trivial eligible like `(a)` must not satisfy this);
   (c) declined patterns **fail or skip-with-loud-log**, never silent fallback;
   (d) pin config: reuse `BASE_SEED`/altSeed, state pattern count + inputsPerPattern, mirror the
   `≥50k` floor (`AlgorithmicFuzzTest.java:183-185`); extend `RandomInputGenerator.ALPHABET` with
   capture-relevant chars (`: / @ ? # & = space tab`); add the 6 literal IAST patterns as a fixed
   differential corpus.
   (Supplementary to the T2.1 pinned span tests, which remain the load-bearing correctness check.)
4. Capture-returning benchmark (T2.9) shows the new engine **beats PikeVM** on COMMAND/URL; abandon
   any pattern where it does not.
5. `spotlessApply`; full suite green; if Route A, StructuralHash dual-path verified.

**T2.9 — Capture-returning benchmarks + the PikeVM/Hybrid differential harness (G2.3).** Extend
`IastRegexpBenchmark` with `findMatch`+`group(n)` variants for COMMAND and URL (today it is
boolean-only). Baselines: PikeVM `findMatch`/`match`, `HybridMatcher.findMatch`, JDK group
extraction. Also build the missing PikeVM/Hybrid-vs-new-engine differential harness G2.3 names.
- For the **COMMAND-only** justification experiment, the cheap baseline is **not** "compute a fixed
  group(1) start offset" — COMMAND's group(1) starts after an *optional* `sudo/doas` prefix + a
  variable `\b\S+\b` token + `\s*`, so the start is not a free offset. The legitimate cheap
  baseline is: reuse the Phase-1 boolean scan to find match-end, set `group(1).end = match-end`,
  and read `group(1).start` as the **single entry tag** the NFA sets on the trailing group (a
  one-tag special case). Compare *that* against the full TDFA in this benchmark — that is the real
  "is the heavy machinery worth it for one trailing group" test.

---

## Correctness obligations (Phase-2 pinned tests, T2.1)

Verified spans (JDK/PikeVM-exact), pinned as unit tests — not left to fuzz discovery:
1. Leftmost-first capture *positions* (not POSIX longest).
2. Last-iteration-wins in `+`/`*`, incl. nullable trailing rebind: `(a)*`/`aaa`→`g1=[2,3)`;
   `(a|b)*`/`abab`→`g1=[3,4)`; `((a)|(b))*` over `ab/ba/abb/bab/bba`; `((a)b)*`/`abab`→`g1=[2,4)
   g2=[2,3)`; `(a*)*`/`aaa`.
3. Empty-width / non-participating: `(a)|(b)`/`a`→`g2=[-1,-1)`; `(a*)(a*)`/`aaa`→`g1=[0,3)
   g2=[3,3)`.
4. Priority path-dependence: `(a|ab)(bc|c)` / `(ab|a)(c|bc)` on `abc` (the T2.2 gate witness).
5. Carried-forward position (Fault 1): `(a*)(a*)`/`aaa`→`g2=[3,3)`; `(a+)(a*)b`/`aaab`→`g2=[3,3)`
   (handled by T2.5A or declined by T2.3).
6. find() offsets + anchored (`^`,`\A`,`$`,`\b`) + leading-junk.
7. DFA freeze → PikeVM fallback stays correct (= today).

---

## Dependency graph

```
T0.1 ─┬─> T0.2 ─(attribution selects/orders levers)─> T1.x (T1.1/T1.2 order per T0.2; T1.3/T1.4 if attributed) ──> G1
      │                                                                                                            │
      └─(per-pattern routing gates Phase-1 scope & Phase-2 wiring)                                                 ▼ (decide on data)
T2.1 ──> T2.2 ──> T2.3 ──> [G-entry: empty→STOP; {COMMAND}-only→build/no-build] ──┬─> T2.5 ──┐
                                                                                  └─> T2.4 ──┴─> T2.6 (Route A) ─┐
                                                                                             └─> T2.7 (Route B) ─┴─> T2.8 → T2.9 → G2
```
- T0.2 sits **upstream** of T1.x and selects/orders the levers (not a downstream "decide" node).
- T2.1/T2.2/T2.3 are prerequisites for any engine work; **G-entry** is the explicit go/no-go after
  T2.3. Route A vs B is decided after G-entry (does the eligible set survive?) + a quick T2.6
  explosion check.

---

## Rollout

- Phase 1: ships when G1 passes (no flag needed — it's a transparent perf fix with parity gate).
- Phase 2: behind a default-off flag; enable per-pattern only after G2. If the eligible set
  reduces to COMMAND alone (URL fails T2.2), decide build/no-build on the COMMAND-only benefit.

## Open questions (decide during execution)
1. Does G1 alone close the IAST `find()` gap? If yes, Phase 2 may be skipped for this corpus.
2. Does URL pass the T2.2 stability check, or fall back to PikeVM?
3. Route A (extend eager tagged-DFA) vs Route B (replay matcher) — decided by T2.6 explosion check.

## Appendix (filled during T0)

### T0.1 routing table — DONE 2026-06-17 (branch `fix/optimized-nfa-backref-perconfig`)
Method: `debugPattern` CLI for COMMAND/URL (CLI-safe); a throwaway probe driving the identical
`RuntimeCompiler.compile` + `PatternAnalyzer.analyzeAndRecommend` path for all six (the SQL/QOBF
literals are `'`/backtick/`$`-hostile on the CLI). Probe COMMAND/URL rows matched the CLI exactly,
validating the driver.

| Pattern | groups | NFA states | strategy | matcher class | Phase-1 G1 | Phase-2 wiring |
|---|---|---|---|---|---|---|
| COMMAND | 1 | 38 | PIKEVM_CAPTURE | `PikeVMMatcher` | in scope | eligible (verify T2.3) |
| URL_JDK | 3 | 30 | PIKEVM_CAPTURE | `NameEnrichingMatcher`→PikeVM | n/a | eligible (verify T2.2/T2.3) |
| SQL_ANSI | 0 | 97 | PIKEVM_CAPTURE | `PikeVMMatcher` | in scope | n/a (0 groups) |
| SQL_MYSQL | 0 | 109 | PIKEVM_CAPTURE | `PikeVMMatcher` | in scope | n/a |
| SQL_POSTGRESQL | 0 | 107 | PIKEVM_CAPTURE | `PikeVMMatcher` | in scope | n/a |
| QUERY_OBFUSCATOR | 0 | **3007** | PIKEVM_CAPTURE | `PikeVMMatcher` | in scope | n/a |

**Verdict: stop-condition does NOT fire.** All six on the PikeVM path; both phases have wiring
points. QOBF's 3007 states → a 3007-wide `Arrays.fill` per char, strong static support for the
T1.1 epoch-guard lever. URL routes through the `NameEnrichingMatcher` decorator over PikeVM (named
groups), as the plan predicted — Phase-2 work targets the wrapped engine.

### T0.2 profile attribution — DONE 2026-06-17 (JMH `-prof stack`, no async-profiler installed)
Throughput (ops/ms, quick mode): SqlAnsiNoMatch Reggie **78** vs JDK **614** (7.9×); QObfNoMatch
Reggie **17** vs JDK **266** (15.6×). Gap scales with state count → confirms per-char `O(stateCount)`.

Hot frames (% of RUNNABLE samples): per-position **`tryFindAt:244/245` accept-scan over
`clistSize`** (SQL 18.6%, QOBF 31.8%) → the single biggest cost; **`CharSet.contains`←`stepChar`**
(SQL 16.7%, QOBF 7.1%); **`addThread:423` closure DFS** (SQL ~12%, QOBF ~9%). Capture
`System.arraycopy` negligible (0 groups). **`Arrays.fill` (resetNlist/swapLists/resetClist) ≈ 0.6%
— NOT the bottleneck.**

**Verdict / lever re-prioritization (T0.2 supersedes the pre-profile ordering):**
- **T1.1 (epoch guards) demoted** — its target (`Arrays.fill`) is ~0.6%. Optional cleanup only.
- **T1.2 (prefilter) promoted to headline** — skipping dead start positions without building
  clists eliminates the dominant `tryFindAt` scan + `addThread` closure + `CharSet.contains` for
  no-match / leading-junk inputs wholesale.
- **T1.5 — IMPLEMENTED + verified correct, but PERF-NEUTRAL (2026-06-17).** Replaced the
  per-position O(clistSize) accept-scan with an O(1) `clistFirstAccept` index. Correctness:
  runtime+codegen+integration suites green; ≥50k zero-divergence fuzz gate at the 18-finding
  baseline (zero new divergences). Throughput (quick mode, same settings as T0.2): SqlAnsiNoMatch
  76.6 (−1.7%), QObfNoMatch 16.4 (−3.8%) — both **within noise**. Lesson: the T0.2 profile's
  18–32% on `tryFindAt:244/245` was **JIT-inlining line-misattribution** (the enclosing
  `stepChar`/`addThread` work), not the accept-scan; clistSize is small for these patterns. T1.5 is
  retained as a correct O(1) cleanup but is **not** a measured win. → **T1.2 is the real lever**
  (cut `findStartFrom`'s O(len) restart, the cost that line-profiling obscured). NB: verify T1.2
  *empirically*, not by profile line attribution, which misled us once.
