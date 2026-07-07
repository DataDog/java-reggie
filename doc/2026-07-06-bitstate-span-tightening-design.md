# Design: bound capture extraction to the located match span (BitState/PikeVM span tightening)

Date: 2026-07-06
Status: design, not yet implemented. This is the concrete design for the item already
named and deferred as **P3 "span tightening"** in
`doc/2026-07-03-bitstate-capture-engine-design.md:194-199,341-354` and flagged as available
groundwork in `doc/2026-07-03-bitstate-capture-engine-feasibility.md:71-78`. Nothing here
duplicates those docs; this specifies *how* to implement P3, grounded in the current code
(main repo, branch `perf/eliminate-slow-paths`, and worktree
`/tmp/java-reggie-bitstate-capture-engine-p1`, branch `fix/bitstate-capture-engine-p1`).

## 1. Problem statement

`PikeVMMatcher.findMatchResultFrom` (`PikeVMMatcher.java:862-962`) and
`BitStateMatcher.findMatchFrom`/`match` (worktree `BitStateMatcher.java:197-233`) both run
their full capture-tracking search over `[fromPos, input.length())` — the entire remaining
haystack — even after a match has been proven to exist and (implicitly) end well before the
end of the input. Concretely:

- `PikeVMMatcher.findMatchResultFrom` only uses `findDfa`/`rejectDfa` as a **boolean
  existence pre-check** (lines 864-886): if no match exists anywhere at/after `fromPos`, it
  returns `null` without running the thread simulation. If a match *does* exist, the full
  capture-tracking thread simulation still scans from `fromPos` with all threads' capture
  arrays live, character by character, until the winning thread's live-thread count drops
  to zero (`matched && clistSize == 0`, line 959) — the DFA's information about where the
  match *ends* is discarded.
- `BitStateMatcher.search()` (`search(input, scanStart, spanEnd, ...)`) always receives
  `spanEnd = input.length()` from both call sites (`match`: `search(input, 0, len, len)`;
  `findMatchFrom`: `search(input, seed, len, -1)`), and its visited-bitmap budget check
  (`stateCount × (spanLen+1) > BUDGET_CELLS`) is sized against
  `input.length() - scanStart` — the full remaining haystack, not any narrower located span.

For long inputs with an early match (the common case for IAST/log-line extraction: the
interesting match is near the start, the rest of the line/payload is irrelevant trailing
content), both engines do strictly more work than necessary, and BitState's budget-based
fallback-to-PikeVM decision (`overBudget()`) is made against an artificially large span,
causing it to bail out to the slower PikeVM path more often than it needs to.

## 2. What already exists to build on

- `LazyDFACache.findFrom(String, int, NfaStep)` (`LazyDFACache.java:148-179`) already
  computes, lazily, the **leftmost match start position** for a candidate search beginning
  at a given offset (doc comment, `LazyDFACache.java:146`: "returns the leftmost match start
  position (0-based), or -1"). It restarts DFA state 0 on `DEAD` and returns as soon as
  `accepting[dfaState]` goes true.
- `PikeVMMatcher` already builds and holds `findDfa`, `matchesDfa`, `rejectDfa` — three
  `LazyDFACache` instances over the same NFA, constructed once per matcher
  (`PikeVMMatcher.java:251-333`) and reused across calls.
- `BitStateMatcher` already reuses `PikeVMMatcher.computeFirstByteFilter`
  (`PikeVMMatcher.java:569-595`) for seed-skipping; it has no reference to `LazyDFACache`
  today (confirmed absent from the worktree's `BitStateMatcher.java`).
- `BitParallelGlushkovRuntime` already builds and uses a **reverse** automaton
  (`GlushkovAutomaton.followReverse`) for the existing RE2-style forward-DFA-finds-end,
  reverse-DFA-finds-start trick used elsewhere in the codebase — so the general
  "forward finds one boundary, reverse finds the other" pattern is precedented in this
  codebase, just not wired into `PikeVMMatcher`/`BitStateMatcher`'s capture-extraction path.

**What's missing**: `LazyDFACache.findFrom` proves a match *starts* somewhere, and (per its
construction) restarts on dead states rather than tracking greedy/leftmost-longest
extension of one specific winning path — it has no mode that reports a match **end**.
Building that is the actual new work this design specifies. (A previously-tried, related
but distinct optimization — "seed-jump," skipping straight to the DFA-located start position
— was tried and reverted per `doc/2026-07-03-bitstate-capture-engine-feasibility.md:22`,
because the existing `firstByteAscii` prefilter already makes the pre-match prefix free.
That is not this proposal: seed-jump narrows where the search *starts*; this proposal
narrows how far the search is allowed to run once *started*, i.e. it bounds the *end*, which
prefiltering cannot do.)

## 3. Proposed design

### 3.1 Phase 1: a boolean forward pass that also reports match end

Add a new mode to the existing DFA machinery — not a new abstraction, an extension of
`LazyDFACache` — that, given a known match *start* position, runs the DFA forward and
tracks the greedy/leftmost-longest accepting position reached (the standard "run until dead,
remember the last accepting state seen" DFA-based longest-match algorithm; this is
information the boolean `matches`/`findFrom` methods already compute internally as
`accepting[dfaState]` transitions but do not currently retain past the first hit).

Proposed method: `LazyDFACache.findEnd(String input, int start, int limit, NfaStep step)`
— walks forward from `start`, tracks `lastAccept = -1` on each `accepting[dfaState]==true`
step, returns `lastAccept` (or `-1` if no accepting state was ever reached, which should not
happen if `findFrom` already proved a match starts at `start`). This is symmetric to the
existing `findFrom` (which walks forward from an unknown start looking for the first
position where a match becomes provable) but answers "how far does *this* match extend"
rather than "does a match exist."

Caveat to carry into the implementation and its tests, directly relevant to reggie's
leftmost-**greedy** semantics (not POSIX leftmost-longest): a boolean DFA's accepting-state
set does not by itself disambiguate *which* greedy path an NFA-based capture engine would
have taken to the same endpoint when multiple paths reach the same end position — but for
the purpose of this design, the DFA-located end is only used as an **upper bound** on how
far the capture engine needs to search (see 3.2/3.3), not as the authoritative match result.
The capture engine (PikeVM thread sim or BitState DFS) still performs the actual
greedy-priority resolution; it is simply told not to scan past a proven-safe upper bound.
This sidesteps the POSIX-vs-greedy correctness pitfall identified in the research
(Borsotti & Trofimovich, SPE 2021, showing Cox's two-phase split is unsound *only* when the
phase-1 pass is treated as authoritative for capture content under POSIX semantics — here it
is never treated as authoritative, only as a bound).

### 3.2 Phase 2a: `PikeVMMatcher.findMatchResultFrom` — bound `regionEnd`

In `findMatchResultFrom` (`PikeVMMatcher.java:862-962`), after the existing boolean
existence pre-check at lines 864-886 confirms a match exists starting at some position ≥
`fromPos`, add a call to the new `findEnd` mode using `findDfa`/`rejectDfa` (whichever is
already being used for the existence check) to compute an upper bound on the match's end
position. Thread that bound through as the loop's effective `regionEnd` (replacing the
implicit `input.length()` ceiling used today), so `stepChar`/`addThread` never process
characters past a proven-unreachable-for-this-match position. The thread simulation's own
logic (thread death, capture updates, `sinkGroups` handling per the existing dotall-sink
fast-forward from commit `4499480`) is otherwise unchanged.

### 3.3 Phase 2b: `BitStateMatcher.search()` — bound `spanEnd`

In `BitStateMatcher.match`/`findMatchFrom` (worktree `BitStateMatcher.java:197-233`),
replace the hardcoded `len` (`input.length()`) passed as `spanEnd` with the same
DFA-located end bound, computed once per call before invoking `search()`. This directly
shrinks the visited-bitmap budget check (`stateCount × (spanLen+1) > BUDGET_CELLS`), which
is currently sized against the full remaining haystack — a tighter `spanEnd` both reduces
DFS work and makes `BITSTATE_CAPTURE` eligible to stay in-budget (rather than falling back
to PikeVM via `overBudget()`) for more real-world inputs, since `BUDGET_CELLS` is a fixed
constant and `spanLen` is currently inflated by irrelevant trailing input.

`BitStateMatcher` does not currently hold a `LazyDFACache` reference; this requires wiring
one in (mirroring how it already borrows `computeFirstByteFilter` from `PikeVMMatcher` — the
class already has a precedent for sharing DFA-adjacent machinery from `PikeVMMatcher` rather
than duplicating it).

### 3.4 Fallback behavior

If the DFA-based end-bound computation itself is not eligible for a given pattern (e.g. the
NFA doesn't support the boolean DFA path used to build `findDfa`/`rejectDfa` — some patterns
never get one constructed, see `PikeVMMatcher.java:251-333`), phase 1 is simply skipped and
both engines fall back to today's behavior (`spanEnd = input.length()`), preserving current
correctness and performance characteristics unconditionally. This makes the change strictly
additive/opt-in per pattern, not a behavior change for patterns without a DFA.

## 4. Correctness baseline to preserve

- `PikeVmCaptureRegressionTest.java`, `PikeVMMatcherTest.java`, `PikeVMAnchorFindTest.java`,
  `PikeVMNullableAlternationTest.java` (main repo and worktree,
  `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/`) — group-span correctness for
  PikeVM; must pass unchanged since phase 2a only *bounds* the search region, never changes
  which capture the thread sim reports for a given match.
- `BitStateMatcherTest.java` (both repo locations) — DFS capture correctness; must pass
  unchanged for the same reason.
- `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/BitStateEligibilityTest.java`
  — eligibility-gate unit tests; unaffected by this change (span tightening happens after
  routing, not as part of the eligibility decision).
- The worktree's `reggie-benchmark/.../BitStateCaptureBenchmark_*` JMH suite (COMMAND/URL/SQL
  and the newly added LogLine/HttpRequestLine/KeyValueLazy patterns) is the throughput
  baseline to re-measure once this lands — expect the largest wins on inputs where the match
  is short relative to the haystack (e.g. `LOG_LINE_MATCH`, where the match spans most of a
  short line, will show less improvement than a pattern matching only a short prefix of a
  much longer input).

## 5. Explicitly out of scope

- POSIX leftmost-longest semantics — not supported by reggie today; the soundness argument
  in §3.1 depends on this remaining true. If POSIX mode is ever added, this design's
  phase-1/phase-2 split needs re-review against Borsotti & Trofimovich's counterexample.
- Reverse-scan start-location (the `BitParallelGlushkovRuntime`/`GlushkovAutomaton.followReverse`
  machinery) — this design only extends the *forward* DFA to report an end bound; it does
  not add a new reverse-scan capability to `PikeVMMatcher`/`BitStateMatcher`.
- Seed-jump to the DFA-located start — already tried and reverted (feasibility doc, line 22);
  not reopened by this design.
