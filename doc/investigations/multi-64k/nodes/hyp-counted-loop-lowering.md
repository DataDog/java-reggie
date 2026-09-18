---
id: hyp-counted-loop-lowering
type: hypothesis
status: implemented-and-optimized
depends_on: [find-bounded-quantifier-regression]
supersedes: []
related: [q-prod-readiness, hyp-v2-driver-lowering, find-deadline-coverage-gaps]
tags: [bounded-quantifier, counted-loop, lowering, dfs-backtracker, design, semver, fix-implementation]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Counted-loop lowering for x{n,m} — design VALIDATED by experiment; implementation in progress

## The fix (user directive 2026-09-17: "semver patterns matter, fix before proceeding")

Replace monster-unrolling of bounded quantifiers with a counted-loop representation + a
counter-aware DFS backtracker. All numbers below measured on the real logs-backend semver
pattern (a5ef0fb, Corretto 26).

## Decisive experiment (/tmp/census/semver-loop.txt, LoopProbe.java)
Rewriting {0,256}->{n,} (the EXISTING loop path in buildCountedQuantifier) collapses the
problem: NFA 567,550 -> 104 states (5457x), compile 2,257ms -> 52ms (43x, native BitState),
match 25-400x-slower-than-JDK -> 2.0-2.7x slower (0.28-0.83us/op). Still linear-time native.
=> Loop REPRESENTATION is the fix; only the max-cap enforcement is missing.

## Why PikeVM counters were rejected (routing fix refuted earlier + thread-model analysis)
PikeVM's flat in-list guards key on state id; with counters, same state can legitimately carry
different counts (variable-length loop bodies) — thread-merging under counters + tag
determinism re-opens the capture-correctness problem. Research-grade risk. REJECTED.

## Chosen design: counter-aware priority DFS (extends BackrefBacktrackMatcher's model)
BackrefBacktrackMatcher (NFA-walking, priority-ordered memoized DFS, Perl/JDK semantics by
construction, currently UNWIRED — tests only) already carries per-frame capture vectors; DFS
frames extend naturally with per-loop counters — it is literally JDK's backtracking model
(exact semantics incl. greedy/lazy ordering, captures, and REJECTING >max-iteration inputs).
1. NFAState: add counted-loop marker fields (mirrors enterGroup/backrefCheck pattern):
   `countedLoopId`, `countedLoopMax` (+ min handled by unrolling min copies, which is
   linear and small).
2. ThompsonBuilder.buildCountedQuantifier: bottom-up size estimate; if
   (max-min) x childStates > UNROLL_BUDGET (~5k states) AND max > min: emit min-copies +
   ONE body copy + re-enter marker state (epsilon: [body entry if count<max (count+1),
   after-loop stop branch]) + set NFA.hasCountedLoops. Small quantifiers keep today's
   EXACT unrolled behavior (DFA routes untouched) — only monsters convert. Nested case:
   inner small quantifiers still unroll within the single body copy; the OUTER conversion
   removes the multiplicative blowup (semver final NFA ~2-3k states).
3. Routing: after NFA build, BEFORE PatternAnalyzer's passes: hasCountedLoops -> straight
   to the counter-aware DFS route (analysis passes never see markers; they would
   misread loops as x*). Defense-in-depth guards: DFA/bitstate/pikevm/codegen refuse
   hasCountedLoops NFAs.
4. The DFS matcher: frames gain int[] loopCounts; marker state yields iterate/stop
   successors in greedy or lazy priority order; memo key extended with loopCounts (keeps
   the search finite); optional step budget for absolute bound (see safety note).
5. Safety note (user constraint: no silent ReDoS-able impl): DFS can blow up on
   adversarial nested-ambiguous bounded shapes exactly like JDK — mitigate with a
   per-match step budget (default on, e.g. 1M frames -> dedicated bounded exception, not
   a hang). On benign semver-family inputs the budget never trips. The memo+counters key
   bounds polynomial shapes. DECISION NEEDED at review: default budget value + exception
   type semantics (fail-match vs fail-fast error).

## Expected outcomes (from experiment)
semver: compile <=~100ms (104-state NFA + markers + DFS ctor), match within ~2-3x of JDK
(possibly BETTER than the 2-2.7x BitState result, since DFS on benign greedy paths is
JDK-like), exact semantics including rejection of >256-iteration inputs, captures correct
(priority order = existing capture-parity machinery).

## Implementation checklist — ALL DONE, commit 8e4750c (2026-09-17)
[x] NFAState marker fields + NFA.hasCountedLoops accessor
[x] ThompsonBuilder threshold branch + marker construction + size estimate
[x] Counter-aware DFS (extended BackrefBacktrackMatcher in place)
[x] RuntimeCompiler route 2.5 + COUNTED_LOOP_NFA_CACHE (fresh matcher per compile)
[x] Guards: PikeVMMatcher, BitStateMatcher, SubsetConstructor refuse counted NFAs
[x] Tests: CountedLoopSemanticsTest 9/9 green (all checklist cases incl. budget trip)
[x] Full suites green + spotless clean
[x] Verified: 61ms compile, 10-19x-JDK match, parity-exact (ev-counted-loop-fix-verification)
    + step budget 405ms fast-fail on the JDK-hangs shape (default 2M frames,
    -Dreggie.countedloop.maxSteps)
[x] FOLLOW-UP 677417b (user directive: 'not worse than re2j', research-grounded): join-point-only
    memo + live-key masking + pass-through compression + two-level MemoSet -> re2j PARITY on
    realistic inputs (0.98-1.2x, see ev-re2j-parity-push). Research: re2j expands this pattern to
    419,186 instructions; Hyperscan uses counter machinery instead (our tradeoff).
[x] FOLLOW-UP 750f848 (user directive): DFS allocation TODO fixed — flat int[] memo table
    (generation reset), primitive frame stack, copy-on-write captures, per-thread workspace.
    3.6-8.5x-JDK (was 10-19x); 313ch worst case 113.7 -> 41.2us/op; budget fast-fail intact
    (229ms). Remaining gap is interpreted-walk floor (~1.5-2x more structural headroom known,
    not needed).
