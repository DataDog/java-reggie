# Feasibility study: BitState capture engine

**Date:** 2026-07-03
**Author:** perf investigation (branch `perf/eliminate-slow-paths`)
**Status:** feasibility — no code written

## 1. Problem

`findMatch` (capture extraction) on the ambiguous `PIKEVM_CAPTURE` patterns is 20–60× slower
than `java.util.regex`. Measured on `IastRegexpBenchmark` (JDK 21, throughput ops/ms):

| case | Reggie | JDK | ratio |
|---|---:|---:|---:|
| COMMAND capture `(?s)(?m)^(?:\s*(?:sudo\|doas)\s+)?\b\S+\b\s*(.*)` | 488 | 21123 | 0.023× |
| URL auth capture | 835 | 21148 | 0.040× |
| URL query capture | 298 | 1923 | 0.155× |
| SQL `(?i)(?m)` find | ~180 | ~750 | ~0.24× |

This session established (see memory `project_capture_bottleneck_cpu_bound`) that the cost is
**CPU in the O(n·m) PikeVM thread simulation**, not allocation or match-start location:

- **Seed-jump** (advance to the DFA-located start): measured flat — the `firstByteAscii` prefilter
  already makes the pre-match prefix free. Reverted.
- **Deferred `buildResult`** (build the `MatchResult` once instead of per greedy give-back): cut
  COMMAND allocation 5040 → 112 B/op (45×) but only +33% throughput. Committed (`7678591`).
- **Greedy dotall-sink fast-forward** (skip per-char stepping on a `(?s).*` tail): helps COMMAND
  specifically; does not generalise to URL/SQL.

None of these closes the ~40× gap. The gap is structural: `PikeVMMatcher` runs a Thompson NFA with
a live thread set, recomputing the epsilon closure and copying capture slots every character.
`java.util.regex` runs a single backtracking path. For *unambiguous* patterns `OnePass` already
matches at native speed, but COMMAND/URL/SQL are intrinsically ambiguous (verified via
`debugPattern`: `isOnePassEligible` = false; greedy `\S+` overlaps `(.*)`), so they fall to PikeVM.

## 2. What BitState is

BitState is RE2's bounded-backtracking submatch engine (`bitstate.cc`). It is a depth-first
backtracking search over the NFA — like `java.util.regex` — but made **linear-time and
ReDoS-safe** by a visited set: it never re-explores the same `(instruction, position)` pair.

- **Visited bitmap:** one bit per `(pc, pos)` pair, size `progSize × (spanLen + 1)` bits. A job is
  skipped if its `(pc, pos)` bit is already set. This bounds total work to
  `O(progSize × spanLen)` — no exponential blow-up.
- **Explicit job stack** (not JVM recursion) of `(pc, pos, capture-arg)` frames.
- **Capture array** updated in place along the DFS path; on a successful accept the current capture
  array is the answer (leftmost-first / Perl priority via DFS order, matching JDK and PikeVM).
- **Eligibility budget:** RE2 only uses BitState when `progSize × (spanLen+1) ≤ 256 Kbits`
  (`bitstate.cc: kMaxBitStateBitmapSize`); otherwise it uses the PikeVM (`nfa.cc`). i.e. BitState is
  the fast path for *small problems*, the general PikeVM is the fallback.

Why it beats the PikeVM on small spans: no thread-set bookkeeping, no per-char epsilon-closure
recomputation, no capture-slot array copy per surviving thread. A single mutable capture array and
a bitmap. For a short input the visited bitmap is tiny and mostly cache-resident.

## 3. Fit within Reggie's architecture

Reggie already has a tiered strategy in `PatternAnalyzer.MatchingStrategy`
(`reggie-codegen/.../analysis/PatternAnalyzer.java:3043`). BitState slots in **between `ONEPASS_NFA`
and `PIKEVM_CAPTURE`**:

```
OnePass (unambiguous, single path)                        — fastest, narrow eligibility
  ↓ not one-pass
BitState (ambiguous, small progSize × span)   ← NEW       — near-JDK on short inputs
  ↓ span too large / budget exceeded
PikeVM (ambiguous, any size)                              — linear, always correct
```

Key architectural fit points already verified this session:

- **Match-span restriction is already available.** `PikeVMMatcher.findMatchResultFrom` calls
  `findDfa.findFrom` / `rejectDfa.findFrom` (`LazyDFACache.findFrom` returns the leftmost match
  *start*, `LazyDFACache.java:146`). RE2 runs BitState only over the located `[start, end)` span,
  which is short for IAST payloads — keeping `spanLen` (and the bitmap) small. Reggie can locate the
  start the same way, then hand `[start, end)` to BitState.
- **Reverse-scan precedent.** `BitParallelGlushkovRuntime` already builds and uses a reverse
  automaton (`GlushkovAutomaton.followReverse`) for the RE2 forward+reverse start-finding trick, so
  the pattern of "DFA finds end, reverse finds start, submatch engine runs on the span" is not new
  to this codebase.
- **Interpreter, not codegen.** Like `PikeVMMatcher`, BitState should be a runtime interpreter over
  the existing `NFA` (not a new bytecode generator), reusing `statesById`, `isAccept`,
  enter/exit-group and anchor handling. This is the lowest-risk integration and avoids touching
  `RuntimeCompiler`'s bytecode paths.

## 4. Algorithm sketch (Reggie-flavoured)

```
boolean bitStateSearch(input, spanStart, spanEnd):
    visited = clear bitmap of size stateCount × (spanEnd - spanStart + 1)
    push job(startState, spanStart, freshCaptures)
    while stack not empty:
        (state, pos, caps) = pop
        key = state.id * spanLen + (pos - spanStart)
        if visited[key]: continue
        visited[key] = true
        // epsilon transitions in Perl-priority order, pushed reversed so highest runs first
        for each transition of state (priority order):
            if anchor/group/accept handling ...
            if consuming and pos < spanEnd and tr.chars.contains(input[pos]):
                push job(tr.target, pos+1, caps-with-updates)
        if isAccept[state]:
            record caps; return true   // leftmost-first: first accept in DFS order wins
    return false
```

Capture updates: enter/exit-group states write `caps[2g]/caps[2g+1] = pos` exactly as
`PikeVMMatcher.addThread` does; the difference is BitState mutates one array along a single path and
snapshots on accept, versus PikeVM copying per thread per step.

Anchors (`^ \A (?m)^ \b $ \z`), lookaround, backreferences: handled inline at the corresponding
NFA state, identically to `PikeVMMatcher.checkAnchor`. BitState can therefore cover the patterns
that block the DFA fast paths (`\b`, `$`, lookaround) — which is precisely the COMMAND/SQL family.

## 5. Eligibility guard

Route to BitState (from `PatternAnalyzer`) when:

1. Capturing groups present (else DFA/OnePass already handle it), and pattern is **not** OnePass.
2. No unbounded catastrophic risk: the `stateCount × spanLen` budget is enforced *at runtime* per
   input (like RE2), so eligibility is mostly a "prefer BitState" hint; the runtime falls back to
   `PikeVMMatcher` when `stateCount × (spanEnd - spanStart + 1) > BUDGET_BITS`.
3. Backreferences: BitState *can* support them (backtracking naturally does), but that overlaps the
   existing backref strategies — leave those on their current engines initially.

Runtime budget: RE2 uses 256 Kbits. For IAST, `stateCount` is typically < 200 and spans < a few KB,
so the budget is almost never hit; when it is, PikeVM (already correct) takes over. **No silent
truncation** — log/counter when the fallback fires, per the no-silent-caps rule.

## 6. Correctness considerations

- **Leftmost-first (Perl) semantics:** DFS in transition-insertion order with "first accept wins"
  reproduces JDK/PikeVM priority. Must mirror `PikeVMMatcher`'s transition ordering exactly.
- **Greedy give-back:** DFS explores the greedy branch first; if it dead-ends it backtracks to the
  shorter alternative — same result as PikeVM's priority cut. Needs a differential test vs PikeVM.
- **Atomic groups / possessive quantifiers:** require pruning the backtrack stack at the atomic
  exit. `PikeVMMatcher` already models these (`atomicEntry/atomicExit`, `clistAtomicPos`); BitState
  must replicate. **Highest-risk area** — recommend excluding atomic patterns from BitState v1.
- **Zero-width loops / empty matches:** the `(pc, pos)` visited set prevents infinite epsilon loops,
  but empty-iteration semantics (e.g. `(a?)*`) must match JDK; reuse `groupBodyNullable`.
- **Verification:** the existing `StrategyCorrectnessMetaTest` / differential fuzz harness must run
  every BitState-eligible pattern against both PikeVM and JDK. This is the gate.

## 7. Integration points (concrete)

| Concern | File / method |
|---|---|
| New strategy enum value `BITSTATE_CAPTURE` | `PatternAnalyzer.java:3043` (enum), routing near `:816`/`:1376`–`1404` |
| Eligibility predicate `isBitStateEligible()` | `PatternAnalyzer.java` (mirror `isOnePassEligible` at `:90`) |
| Compiler wiring (`case BITSTATE_CAPTURE`) | `RuntimeCompiler.java` (mirror `:1098` COUNTING_GLUSHKOV, `:1009` OnePass) |
| New runtime engine `BitStateMatcher extends ReggieMatcher` | `reggie-runtime/.../runtime/BitStateMatcher.java` (new) |
| Span location + fallback to PikeVM | reuse `LazyDFACache.findFrom` (`:146`) for `[start,end)`; fall back by delegating to `PikeVMMatcher` |
| Differential tests | `StrategyCorrectnessMetaTest`, fuzz harness |

## 8. Effort & risk

- **Effort:** ~1–2 focused units. Core interpreter (~250 LOC) mirrors `PikeVMMatcher.addThread` /
  `checkAnchor` closely; the visited bitmap + job stack is the genuinely new part. Routing +
  eligibility + wiring is mechanical (existing templates). Tests are the bulk of the effort.
- **Risk (ranked):**
  1. Atomic-group / possessive backtracking correctness → mitigate by excluding from v1.
  2. Priority-order fidelity vs PikeVM (greedy/lazy, alternation) → mitigate by exhaustive
     differential fuzzing against PikeVM (same NFA, must agree bit-for-bit on captures).
  3. Budget/fallback correctness → PikeVM fallback is already correct, so the only risk is *when*
     to fall back, not correctness of the fallback.

## 9. Expected payoff

BitState targets exactly the patterns that block the DFA fast paths and are ambiguous:
COMMAND, URL auth/query, SQL `(?i)(?m)`. For their short IAST inputs (spans of tens of chars), the
visited bitmap is a few hundred bytes and the search is a handful of backtracks. RE2's own numbers
put BitState within ~1.5–3× of a hand-written matcher on such inputs. Projected: capture ratios
move from **0.02–0.16× → roughly 0.5–1× JDK** for the affected patterns. This is the only lever
identified that plausibly reaches parity; allocation/start-location tuning cannot.

## 10. Recommendation

Proceed with a **v1 scoped to non-atomic capturing patterns**, interpreter-based, with a runtime
`stateCount × span` budget and PikeVM fallback, gated behind exhaustive differential fuzzing vs
PikeVM. Defer atomic-group support and any backref overlap to a follow-up. Land it behind the
existing routing so non-eligible patterns are untouched.

Related: `project_capture_bottleneck_cpu_bound`, `project_execution_plan`,
`project_reggie_safe_backtracking_investigation` (this is the concrete realisation of the
"safe backtracking at full speed" R&D).
