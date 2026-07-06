# Design: BitState capture engine

**Date:** 2026-07-03 (revised after adversarial review)
**Branch:** `perf/eliminate-slow-paths`
**Status:** design — no engine code written
**Precursor:** `doc/2026-07-03-bitstate-capture-engine-feasibility.md`

> **Revision note.** An adversarial review found two load-bearing algorithmic errors and an
> incomplete wiring story in the first draft. Fixed here: §7.5 retracts the false "anchor bookkeeping
> not needed" claim (a `(^a?){3}` counterexample disproves it); §4 replaces the O(n²) "search from
> each start" with a single unanchored O(n·m) pass; §2/§6.1 correct the false "annotation-processor
> path is untouched" claim and enumerate the real strategy-switch touch points; §5/§8 fix the
> visited-bitmap allocation model and the budget arithmetic.

## 1. Goal

Add a bounded-backtracking submatch engine (`BitStateMatcher`) for the ambiguous capturing
patterns that today fall to `PikeVMMatcher` (`PIKEVM_CAPTURE`) and run 20–60× slower than
`java.util.regex`: COMMAND, URL auth/query, SQL `(?i)(?m)`. BitState is linear-time and
ReDoS-safe (a `(pc, pos)` visited set), but avoids the PikeVM's per-character thread-set
bookkeeping and capture-array copying, which is the measured bottleneck (see memory
`project_capture_bottleneck_cpu_bound`). Target: move affected capture ratios from
**0.02–0.16× → ~0.5–1× JDK**.

Non-goals for v1: atomic groups / possessive quantifiers, backreferences, lookaround. These stay
on their current engines; BitState declines them (§6) and the router leaves them untouched.

## 2. Where it sits

`PIKEVM_CAPTURE` is compiled through the **runtime** compiler as an interpreter over the shared
`NFA` — not bytecode. `reggie-runtime/.../runtime/RuntimeCompiler.java:151-152` (`PikeVMEntry.newMatcher`)
does `new PikeVMMatcher(nfa, pattern)`, cached via `PIKEVM_NFA_CACHE`. `BitStateMatcher` is a sibling
interpreter constructed the same way — but it needs its **own** cache/entry (or a generalized
engine-factory entry), because `PikeVMEntry.newMatcher` hardcodes `new PikeVMMatcher`.

**The strategy decision is shared, so BitState is not additive-only.** Both the dynamic
`compile()` path and the annotation-processor precompiled path
(`RuntimeCompiler.java:292-315`, `compileFromPrecompiled`) route through
`PatternAnalyzer.analyzeAndRecommend()`. Introducing a `BITSTATE_CAPTURE` strategy therefore changes
what *both* paths select: a capturing pattern with `\b` that today resolves to `PIKEVM_CAPTURE` would
be re-routed to the new arm. Every strategy-switch site must gain a BITSTATE arm before the enum
value is emitted, or a currently-working `@RegexPattern` would throw at build time (§6.1). (The
bytecode generators — `OnePassBytecodeGenerator` etc. — are only reached for bytecode-backed
strategies; a new *interpreter* strategy does not need one, but it still touches the shared analyzer.)

Strategy tiering (in `PatternAnalyzer.MatchingStrategy`, `reggie-codegen/.../analysis/PatternAnalyzer.java:3043`):

```
ONEPASS_NFA         unambiguous, single path                fastest, narrow eligibility
BITSTATE_CAPTURE    ambiguous, stateCount × span small   ← NEW
PIKEVM_CAPTURE      ambiguous, any size                      linear fallback, always correct
```

BitState is preferred over PikeVM only when the per-input budget holds; otherwise it **delegates to
`PikeVMMatcher` at runtime** (§8). Correctness of the fallback is already established, so the only
new correctness surface is BitState itself plus the *decision* of when to fall back.

## 3. Algorithm

Depth-first backtracking over the NFA, made linear by a visited bitmap keyed on `(stateId, pos)`.
DFS explores epsilon children in NFA insertion order (= Perl priority), so the first accept reached
is the leftmost-greedy match — identical to how `PikeVMMatcher.addThread` orders `getEpsilonTransitions()`.

### 3.1 Job stack (no JVM recursion)

A recursive backtracker risks `StackOverflowError` on long inputs, so use an explicit stack with two
frame kinds:

```
EXPAND(int stateId, int pos)   // explore this state
RESTORE(int slot, int val)     // on pop, unconditionally set caps[slot] = val
```

`EXPAND` frames drive the search; `RESTORE` frames undo capture writes as control leaves a subtree
(§3.4). Capture-by-value per pushed job is rejected — copying the capture array per frame is exactly
the PikeVM cost we are removing — so BitState keeps a single mutable `caps` array and reverses the
writes via `RESTORE` frames (§3.3). Encoding: a `long[]` with a tag bit, or parallel `int[]`s with a
kind flag (§12).

### 3.2 Visited set

`boolean[] visited` (or a `long[]` bitset) of size `stateCount × (span + 1)`, indexed
`stateId * (span + 1) + pos` (absolute `pos`, so one bitmap serves an unanchored left-to-right pass —
see §4). Before expanding a `(stateId, pos)` job, if its bit is set, skip; else set it. This caps
total expansions at `stateCount × (span + 1)` — the linear-time / ReDoS guarantee. Because DFS visits
the highest-priority path to any `(stateId, pos)` first, "first visit wins" keeps the correct
leftmost-first capture assignment — a later, lower-priority visitor of the same `(stateId, pos)` is
correctly pruned.

**Limitation that matters for §7.5:** the dedup key is the *NFA state id*, and a bounded quantifier
(`{n}`) unrolls into **distinct** state copies. Two paths that are semantically "the same position of
the same loop" but sit in different unrolled copies have *different* `stateId`s, so the visited set
does **not** collapse them. This is precisely why the anchored-nullable-body case in §7.5 is a real
correctness gap, not a non-issue.

### 3.3 Captures

One mutable `int[] caps` (size `2 * (groupCount + 1)`). BitState reuses the **slot arithmetic** of
`PikeVMMatcher.updateCaptures` (`caps[2g]=pos` on enter, `caps[2g+1]=pos` on exit) but *replaces* its
scheme: `updateCaptures` is copy-on-write — it copies into `scratchCaptures[depth+1]` per DFS depth so
each simultaneously-live thread keeps its own array (`PikeVMMatcher.java:1234-1248`). BitState instead
mutates a **single** `caps` array along the one active DFS path and records an **undo log**
(`int[] undoSlot, int[] undoVal`): before overwriting a slot, push its prior value; on backtrack,
replay to restore. On the first accept, snapshot `caps` into the preallocated `winCaptures` (reusing
the deferred-build result path, §12) and stop.

### 3.4 Step

Two frame kinds share the stack: `EXPAND(sid, pos)` and `RESTORE(slot, val)`. A `RESTORE` frame is
**unconditional** — it runs whenever it is popped, even if the `EXPAND` that queued it was later
skipped as visited — so a capture write is always undone exactly when its subtree is done. This is the
undo *boundary* between sibling branches (the thing that would otherwise let `(a)|(b)` leak group 1's
span into the group-2 branch).

```
boolean search(input, scanStart, spanEnd):
  clearVisited(); resetUndo()             // see §4/§5 on the single unanchored pass
  push EXPAND(startState.id, scanStart)
  while stack not empty:
    frame = pop
    if frame is RESTORE(slot, val): caps[slot] = val; continue   // always runs
    (sid, pos) = frame
    if visited(sid, pos): continue        // RESTORE frames already queued below still run
    mark visited(sid, pos)
    s = statesById[sid]
    if s.anchor != null:
      if !checkAnchor(s.anchor, input, pos, 0, spanEnd): continue   // reuse existing logic
      pushEpsilonChildrenReversed(s, pos); continue
    // enter/exit group: for each slot written, first push RESTORE(slot, caps[slot]), then set caps[slot]=pos
    applyGroupWritesWithUndo(s, pos)
    if isAccept[sid]: snapshot caps → winCaptures; return true       // leftmost-first
    if !s.getEpsilonTransitions().isEmpty(): pushEpsilonChildrenReversed(s, pos); continue
    // consuming leaf
    if pos < spanEnd:
      char c = input.charAt(pos)
      for tr in s.getTransitions() (reversed):    // usually one; highest priority pops first
        if tr.chars.contains(c): push EXPAND(tr.target.id, pos + 1)
  return false
```

Children are pushed in reverse priority order so the highest-priority child pops first (LIFO ⇒ DFS
priority = Perl leftmost-first). `RESTORE` frames for a state's own group writes are pushed *before*
its children, so they pop *after* the whole subtree — restoring `caps` when control leaves that
branch.

`checkAnchor` (`PikeVMMatcher.java:1287-1326`) is already `private static` and handles
`^ \A (?m)^ $ \z (?m)$ \b` plus CRLF nuances — expose it (move to a shared util or a package-visible
static) so both engines call the identical implementation.
Anchor origin is pinned to absolute `0` (the `regionStart` argument), matching PikeVM's find semantics.

### 3.5 Anchors block the DFA, not BitState

The reason COMMAND/SQL cannot use the lazy DFA is `\b` / `$`-in-alternation
(`PikeVMMatcher.findDfaEligible` rejects them). BitState evaluates these inline via `checkAnchor`,
so it covers exactly the family the DFA fast path cannot — its intended niche.

## 4. Search integration (find vs match)

### 4.1 Unanchored find must be a SINGLE pass, not per-start restarts

The `search()` above is anchored (one seed at `scanStart`). Naively re-running it "from every
candidate start" would be **O(stateCount × n²)** and would break the linear-time promise that is the
whole reason for the engine — so v1 does **not** do that. (The first draft mis-cited PikeVM as
working that way; it does the opposite: `findMatchResultFrom` makes one left-to-right pass and
re-seeds the start thread at every position into the *same* thread list, with `inClist` dedup
collapsing duplicates → O(n·m).)

BitState mirrors that structure with a single unanchored pass:

- **One** visited bitmap over `[scanStart, len]`, indexed by absolute `pos`, **never cleared**
  between seeds. Seeding the start state at position `p` and at position `p+1` share the bitmap, so a
  `(stateId, pos)` reached from an earlier seed is not re-expanded for a later seed — this is exactly
  what bounds the whole unanchored scan to `O(stateCount × span)`, not `O(stateCount × span²)`.
- Implement it as an implicit `.*?` prefix: the start state carries a lowest-priority self-loop that
  advances `pos` without consuming into the pattern, so all start positions are explored in one DFS
  in leftmost-first priority. The first accept is the leftmost match; its `caps` snapshot is the
  result.
- The existing first-char prefilters (`firstByteAscii`, `singleFirstCharAscii`) still skip
  impossible seed positions cheaply before BitState runs.

### 4.2 API entry points

`matches(input)`: `search(input, 0, len)` anchored at 0, accept required at `pos == len` (end-anchored
accept check, as `runMatches` does). `find`/`findFrom`: the §4.1 unanchored pass, boolean/positional,
no capture snapshot needed. `findMatch`/`findMatchFrom`: the §4.1 pass with the `caps` snapshot.

**Full required override set.** `ReggieMatcher` declares `matches`, `find`, `findFrom` abstract, but
`PikeVMMatcher` also implements `match`, `findMatch`, `findMatchFrom`, `matchBounded`,
`matchesBounded` (and inherits `findAll`, `findMatchInto`). `BitStateMatcher` must override **all** of
these; any left un-overridden silently falls through to the JDK-delegate base (correct-but-slow),
which would quietly defeat the optimization for that entry point. v1 override set:
`matches, find, findFrom, match, findMatch, findMatchFrom, matchBounded, matchesBounded`.

### 4.3 Span tightening (follow-up, P3)

`findDfa`/`rejectDfa.findFrom` (`LazyDFACache.findFrom` returns the leftmost start,
`LazyDFACache.java:146`) can pre-locate `[start, …]` so BitState runs over a tighter span, shrinking
the visited bitmap. This is a P3 optimization *on top of* the §4.1 single-pass structure, not a
replacement for it.

## 5. Data structures & memory

Unlike `PikeVMMatcher`, whose arrays are all input-independent and preallocated in the constructor,
BitState has **one structure that scales with input length** — the visited set — so it cannot be
fully constructor-preallocated. Only the input-independent structures are:

| structure | size | allocation | purpose |
|---|---|---|---|
| `int[] caps`, `int[] winCaptures` | `2 × (groupCount + 1)` | constructor | live slots + winner snapshot |
| `int[] undoSlot`, `int[] undoVal` | ≤ live DFS depth ≤ `stateCount` | constructor (cap `stateCount`) | backtrack undo log |
| DFS stack (`long[]` or parallel `int[]`) | grows to ≤ `stateCount × span` | grown per search | EXPAND/RESTORE frames |
| `int[] visited` (generation-stamped) | `stateCount × (span + 1)` | **grown per search** to the budget | `(stateId, pos)` visited set |

The visited set is the one genuinely per-input allocation (span = input length, unknown at
construction). Use a generation-stamped `int[]` grown lazily to `min(needed, BUDGET_CELLS)` and reused
across calls, so the O(cells) `Arrays.fill` clear is avoided when a search is short.

Budget: cap `stateCount × (span + 1)` at `BUDGET_CELLS`. RE2's reference bound is 256 Kbits ≈ **256K
`(pc,pos)` cells ≈ 32 KB** as a bitset (one bit per cell); a generation-stamped `int[]` of the same
cell count is ~1 MB, so if memory matters use a true bitset (`long[]`) rather than an `int[]` stamp,
or set `BUDGET_CELLS` lower. IAST `stateCount` is typically < 200 and spans are short, so the budget
almost never binds; when it does, fall back to PikeVM (§8) and `log()`/increment a counter — **no
silent truncation**.

## 6. Eligibility (`isBitStateEligible`)

`isBitStateEligible` is an **AST-level** predicate (like `isOnePassEligible`, `PatternAnalyzer.java:90`),
so it must use the AST predicates, not runtime `NFA` fields. Route to `BITSTATE_CAPTURE` when:

1. The pattern has capturing groups (else DFA/OnePass already win) and is **not** OnePass-eligible.
2. No backreferences — `!hasBackreferences(ast)` (`PatternAnalyzer.java:1590`) — deferred; overlaps
   existing backref strategies.
3. No lookaround — `!hasLookaround(ast)` (`PatternAnalyzer.java:1995`) — deferred; sub-automaton
   evaluation is out of v1 scope.
4. No atomic groups / possessive quantifiers — `!hasAtomicGroups(ast)` (`PatternAnalyzer.java:2158`)
   and `!hasPossessiveQuantifiers(ast)` (`:2168`) — deferred; see §7.4. (The runtime
   `atomicEntry/atomicExit` fields in §7.4 are the *runtime* manifestation; eligibility is decided on
   the AST.)
5. No anchored quantifier wrapping a nullable `^`/`\A`/`(?m)^` body (e.g. `(^a?){n}`, `(?m)(^x?)+`)
   until §7.5 is resolved — this is the one anchor shape BitState does not yet match JDK on.
6. Other anchors `^ \A (?m)^ $ \z (?m)$ \b` are allowed (this is the point of the engine).

Everything else the router already sends to `PIKEVM_CAPTURE` continues to.

### 6.1 Routing & wiring touch points

Because `analyzeAndRecommend()` is shared (§2), a new `BITSTATE_CAPTURE` enum value must be handled at
**every** strategy-switch site before it is ever emitted, or a currently-working pattern throws.
Concretely, this touches:

- **`PatternAnalyzer`**: the `MatchingStrategy` enum (`:3043`); `isBitStateEligible`; the routing
  decision that today falls through to `PIKEVM_CAPTURE`.
- **`RuntimeCompiler` (runtime path)**: the `compile()` L1 fast-path and its PIKEVM cache lookups
  (`:213-239`); the PIKEVM early-return in `compileInternal` (`:498-506`); the anchor-dilution guard
  that is currently `PIKEVM_CAPTURE`-specific (`:469-470`) — decide whether BitState shares it; a new
  `BITSTATE_NFA_CACHE` + entry type (or generalize `PikeVMEntry` at `:142-152` into an engine factory,
  since `newMatcher` hardcodes `new PikeVMMatcher`).
- **Annotation-processor path**: `compileFromPrecompiled` (`:292-315`) resolves a precompiled
  strategy — either add a `compileBitState` resolver arm, **or** guarantee the AP never emits
  `BITSTATE_CAPTURE` (route AP-eligible patterns to `PIKEVM_CAPTURE` and only pick BitState in the
  dynamic path). v1 chooses the latter to minimize the AP surface; document it explicitly.
- **Classifiers/detectors**: any `StrategyJdkClassifier` / `FallbackPatternDetector` / `isNfaBacked`
  switch that enumerates strategies must gain a BITSTATE arm (BitState is NFA-backed, non-fallback).

## 7. Semantics to replicate — correctness obligations

BitState traverses the same `NFA` as PikeVM, so most semantics come for free, but the following
must be matched exactly and each has a dedicated differential test (§9).

### 7.1 Leftmost-first (Perl) priority
DFS in `getEpsilonTransitions()` insertion order with "first accept wins" reproduces
JDK/PikeVM greedy-vs-lazy and alternation priority. The child push order must be reversed so the
first (highest-priority) child pops first.

### 7.2 Group captures
Enter/exit writes identical to `updateCaptures`. POSIX "last match wins" for repeated groups falls
out of the DFS naturally (later iterations overwrite), matching PikeVM.

### 7.3 Empty-iteration / zero-width loops
The `(pc, pos)` visited set prevents infinite epsilon loops. Empty-group rebind semantics (e.g.
`(a?)*`, `(.)+` trailing empty iteration) are handled in PikeVM via `groupBodyNullable`
(`computeGroupBodyNullable`) and the `willUpdateGroupEntry` propagation in `addThread`. BitState
must reproduce the JDK-observable outcome here — this is the **subtlest** shared-semantics area and
needs targeted tests (`(a?)*`, `(a*)*`, `(.)+`, `(|a)*`).

### 7.4 Atomic groups — EXCLUDED in v1
PikeVM models possessive/atomic behaviour with `atomicEntry/atomicExit`, `clistAtomicPos`, and the
post-step pruning in `pruneAtomicClistInitial`/`pruneAtomicNlist`. In a backtracker this maps to
"commit and discard backtrack points at the atomic exit", which is implementable but error-prone.
v1 declines atomic patterns (eligibility §6.4); they stay on PikeVM.

### 7.5 Anchor-derived zero-length-loop pruning — OPEN CORRECTNESS GAP (v1 excludes it)
> The first draft claimed this machinery was "NOT needed under DFS." **That is wrong — retracted.**

PikeVM carries `anchorFollowedBySkip` / `clistViaMultipleAnchors` (`addThread`, `PikeVMMatcher.java:1093-1157`)
plus `pruneAnchorDerivedAtStart` to reproduce JDK's rule that a consuming path reached through
**multiple anchor firings across an unrolled nullable quantifier** must not override a zero-length
match. Concrete counterexample (verified against JDK):

- Pattern `(^a?){3}`, input `"a"` → **JDK** captures group `[0,0)` (zero-length); a greedy
  "first-accept-wins" DFS captures `[0,1)`. They disagree.

Why DFS does *not* fix this for free: `{3}` unrolls into distinct `^`-state copies (§3.2), so those
paths have different `stateId`s and the `(stateId, pos)` visited set does not collapse them; DFS
happily explores the consuming copy first and accepts it. The priority order alone does not encode
JDK's anchor-derived suppression — that is exactly why PikeVM needed dedicated bookkeeping.

**Resolution for v1:** exclude anchored quantifiers wrapping a nullable `^`/`\A`/`(?m)^` body from
eligibility (§6.5); such patterns stay on PikeVM. A later version may port the
`clistViaMultipleAnchors` / `pruneAnchorDerivedAtStart` rule into the backtracker. Either way the §9.3
cases (`(^a?){3}`, `(?m)(^x?)+`, `\A{3}a`) are hard **merge-gate** assertions: BitState must equal
JDK/PikeVM on their captures, or be excluded.

## 8. Fallback

`BitStateMatcher` holds a lazily-constructed `PikeVMMatcher` over the same NFA. At the start of a
search, if `stateCount × (spanLen + 1) > BUDGET`, delegate the entire call to the PikeVM instance.
This keeps BitState purely a fast path and inherits PikeVM's proven correctness for the large-input
tail. The delegation point is per top-level API call (`matches`/`find`/`findMatch`), so no partial
state crosses engines.

## 9. Test plan

The gate is **differential agreement with `PikeVMMatcher` (same NFA) and `java.util.regex`** on
captures, not just booleans.

1. **Unit / correctness** (new `BitStateMatcherTest`): the affected production patterns
   (COMMAND, URL, SQL) with representative inputs; `matches`/`find`/`findMatch` + all group spans.
2. **Semantics corner cases**: greedy vs lazy (`a+?`, `a*?`), alternation priority (`a|ab`),
   empty loops (§7.3 list), anchors (`^ $ \b \A \z (?m)`), CRLF at `$`, zero-length matches.
3. **Anchored-quantifier fuzz** (§7.5): assert BitState == PikeVM on captures for
   `(^a?){3}`-family patterns.
4. **Differential fuzz harness**: extend the existing `StrategyCorrectnessMetaTest` / fuzz
   infrastructure so every `BITSTATE_CAPTURE`-eligible generated pattern is checked against PikeVM
   and JDK. **BitState must agree bit-for-bit on all group spans** or it is a bug.
5. **Budget/fallback**: a pattern+input exceeding the budget must return the identical result to
   pure PikeVM, and the fallback counter must increment.
6. **ReDoS/perf guards**: a pathological input (`(a|a)*` shape) must remain linear (visited set
   bounds work); a JMH case for COMMAND/URL/SQL capture confirming the target ratio.

## 10. Phasing

- **P1 — core interpreter**: EXPAND/RESTORE job stack, per-search visited set, capture undo log,
  shared `checkAnchor` helper, the **single unanchored pass** (§4.1), the full override set (§4.2),
  and the shared result-construction helper + `embedsNameMap()→true` (§12). Behind `isBitStateEligible`
  excluding atomic/backref/lookaround **and** the anchored-nullable-body shape (§6.5). Wiring per §6.1
  (v1: AP never emits `BITSTATE_CAPTURE`).
- **P2 — differential gate (merge condition)**: the §9 harness green against PikeVM + JDK on all
  group spans, including the §9.3 anchored-quantifier cases; fix or widen the eligibility exclusion
  for any divergence.
- **P3 — span tightening**: DFA-located span (§4.3) to shrink the visited bitmap for `findDfa`
  patterns, layered on the §4.1 single pass.
- **P4 — follow-ups**: port the anchor-derived pruning rule (§7.5) to lift the §6.5 exclusion; atomic
  groups; evaluate backref/lookaround overlap. Separate designs.

## 11. Risks (ranked)

1. **Anchor-derived zero-length pruning (§7.5)** — a *known* divergence, not a hypothesis; v1
   sidesteps it by excluding the anchored-nullable-body shape from eligibility (§6.5) and gating on
   the §9.3 merge assertions. Residual risk is that the exclusion predicate misses a variant — fuzz
   must catch that.
2. **Empty-iteration / group-rebind semantics (§7.3)** — subtle JDK-compat (`groupBodyNullable`);
   mitigated by targeted tests + fuzz.
3. **Priority fidelity vs PikeVM (§7.1)** — mitigated by exhaustive differential fuzz on all group
   spans.
4. **Result-construction sharing (§4.2, §12)** — `buildResult`/`winCaptures` are private to the
   `final` `PikeVMMatcher`, and BitState must override `embedsNameMap()→true` or `NameEnrichingMatcher`
   double-wraps the name map. Needs a small extracted helper, not a hand-wave.
5. **Budget/fallback threshold** — correctness is safe (PikeVM fallback), only perf is at stake;
   tune `BUDGET_CELLS` empirically.
6. **Undo-log complexity** — an alternative is capture-by-value per job (simpler, but reintroduces
   copying); measure before choosing. v1 spec is the undo log.
7. **Payoff on COMMAND is unproven** — see §11.1 below.

### 11.1 Payoff caveat

The precursor work established that COMMAND capture is dominated by its ambiguous **prefix**
(`\b`, `\S+`, `sudo|doas`), not a tail, and that COMMAND uses `rejectDfa` (it has `\b`, so no exact
`findDfa`). BitState's win on COMMAND therefore comes from replacing the per-character thread-set +
capture-array copying with a single backtracking path over that short prefix — plausible but **not
yet measured**. The "0.5–1× JDK" target is a projection; the §9.6 JMH case is what validates it, and
if COMMAND stays far below target the engine may still be justified by URL/SQL alone. Do not treat
the projection as a commitment.

## 12. Open questions

- Job-stack encoding: packed `long[]` (stateId<<32 | pos, with a tag bit or separate array for
  RESTORE frames) vs parallel `int[]` — micro-benchmark.
- Visited set representation: true bitset (`long[]`, ~32 KB at the RE2 budget) vs generation-stamped
  `int[]` (~1 MB, but no per-search clear). §5 leans bitset for memory; measure the clear cost.
- Should `matches()` use a reverse/end-anchored formulation to prune, or is forward-with-end-check
  sufficient? (PikeVM uses forward-with-end-check; match it for v1.)

**Result-construction contract (was an open question; now specified).** BitState reuses a single
result-building path: extract the `winCaptures → int[] starts/ends → MatchResultImpl/NamedMatchResultImpl`
logic (currently private `PikeVMMatcher.buildResult`, `PikeVMMatcher.java:1523`) into a shared static
helper or a small base-class method both engines call, and BitState must override
`embedsNameMap()→true` (as `PikeVMMatcher` does, `PikeVMMatcher.java:~1519`) so `RuntimeCompiler`'s
`NameEnrichingMatcher` wrapping (`:123,156,605`) is not applied twice. This is P1 work, not deferred.

Related memory: `project_capture_bottleneck_cpu_bound`, `project_reggie_safe_backtracking_investigation`
(BitState is the concrete realisation of "safe backtracking at full speed"), `project_execution_plan`.
