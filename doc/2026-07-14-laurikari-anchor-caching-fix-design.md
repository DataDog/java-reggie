# Laurikari anchor-caching fix: recovering the `find()` regression from TDFA Phase 2

## Context

Commit `274488d` ("feat: TDFA Phase 2 — end-anchor/\b eligibility for Laurikari DFA (KNOWN
PERF REGRESSION)") extended `LaurikariEligibility` to allow `END`, `STRING_END`,
`STRING_END_ABSOLUTE`, `END_MULTILINE`, and `WORD_BOUNDARY` anchors into the Laurikari
tagged-DFA (TDFA) capture engine. It gated this via a per-DFA-state `anchorSensitive` flag:
any interned state whose NFA-subset touches one of these 5 anchor types never populates
`LaurikariDFACache.asciiTables` — it always falls through to a live NFA-closure recomputation
(`LaurikariCaptureNfaStep.addClosure`, which calls `PikeVMMatcher.checkAnchor` per candidate
state).

This was expected to be low-risk: anchor-free states stay byte-identical, and only a small
anchor-adjacent slice of states would lose caching. **That assumption is false for `find()`-mode
scanning.** Measured via `IastRegexpBenchmark`'s SQL-tokenizer patterns (`\b\d+`,
`(?m)...--.*$`) at LONG scale:

| Benchmark | pre-Phase-2 (PikeVM fallback) | post-274488d |
|---|---|---|
| SqlAnsiFind | ~0.83–0.86x RE2J | 0.22x RE2J (0.0477 vs 0.2150 ops/ms) |
| SqlMysqlFind | ~0.83–0.86x RE2J | 0.22x RE2J (0.0436 vs 0.2017 ops/ms) |
| SqlPostgresqlFind | ~0.83–0.86x RE2J | 0.22x RE2J (0.0439 vs 0.1999 ops/ms) |

~4x worse, for exactly the pattern class this extension was meant to help.

## Root cause (verified against current code)

`LaurikariCaptureNfaStep.addClosure` adds an anchor-bearing NFA state to the closure's output
subset **unconditionally** — the `checkAnchor` gate only prunes that state's *onward* epsilon
edges when it fails. So a DFA state's `anchorSensitive` classification depends only on
epsilon-reachability of an anchor-bearing state, not on whether the anchor actually fires at
that position.

`find()`'s driving loop (`LaurikariCaptureNfaStep.applyFind`) re-injects a fresh
self-anchoring closure from `startStateId` at *every* character (this is how "restart the match
attempt at every position" leftmost-longest semantics work here). Since `\b` sits directly on
the `\d+` branch reachable by epsilon from `startStateId`, nearly every state interned in
`findCache` ends up `anchorSensitive = true` — measured 13/19 (68%) for `SQL_ANSI`. That defeats
`asciiTables` caching for most of the automaton; live per-character closure recomputation
(including a `checkAnchor` call) is far more expensive than even the `PikeVMMatcher` fallback
this was meant to beat.

Crucially, `matches()`/`match()` use a **separate** cache instance (`anchoredCache`), driven by
plain `apply` with no reinject — so it doesn't suffer this explosion, and `matches()`-only usage
of these patterns does not regress. The two code paths are already architecturally separable,
which the recommended fix (Angle 4 below) exploits directly.

`PikeVMMatcher.checkAnchor`'s actual information dependency for the 5 new types is small and
local, not a general function of absolute position:
- `WORD_BOUNDARY`: `isLetterOrDigit(charAt(pos-1)) != isLetterOrDigit(charAt(pos))` — the
  "prev word-class" bit is already known (it's the just-consumed transition char), the "next
  word-class" bit depends on the *next*, not-yet-consumed character.
- `END`/`STRING_END`/`END_MULTILINE`/`STRING_END_ABSOLUTE`: depend only on `pos == regionEnd`
  and, within 2 characters of `regionEnd`, the literal next 1–2 characters (`\r`, `\n`,
  NEL/LS/PS).

This collapses to a small, enumerable "lookahead class" over the next character(s) — not a
general position signature.

## Angles considered

1. **Narrower eligibility** (exclude anchors epsilon-reachable from start). Rejected — the SQL
   tokenizer's `\b` sits exactly at the leading position of its branch, i.e. precisely the shape
   this extension exists to help. Excluding it defeats the extension's purpose.

2. **Smarter caching key** — fold anchor context into the `asciiTables` transition-cache lookup
   only (not into `LaurikariStateSetKey`/state identity). The plan's original rejection of "bake
   an anchor signature into the cache key" was about baking it into *state identity*, which risks
   state-space blowup (new interned states per context). Widening only the per-transition cache
   dimension for the minority of anchor-sensitive states carries none of that risk.

3. **Two-tier caching** — cache the anchor-blind transition and only recompute near the boundary.
   Not literally sound standalone (the anchor outcome changes which onward epsilon edges are
   followed, so one fixed cached target is wrong), but its refined form — cache **one small table
   per lookahead-class bucket** instead of one table or none — is the same fix as angle 2, just
   arrived at from the other direction. Treat 2+3 as one fix.

4. **Route `find()` for new-anchor-bearing patterns to `PikeVMMatcher`, keep `matches()` on the
   new path.** Cheap and low-risk: `LaurikariDfaMatcher` already keeps `anchoredCache` (used by
   `matches`/`match`) and `findCache` (used by the `find` family) as separate paths, and
   `BitStateMatcher` already has a fallback branch point per call. Pure routing change, no new
   closure logic — near-zero correctness risk. Restores the pre-Phase-2 `find()` baseline
   immediately but forgoes the `find()`-side win for exactly the SQL benchmark class.

5. **Drop anchor-bearing states from the reinject closure.** Not viable — the anchor-bearing
   state's epsilon-reachability from the reinject seed is exactly the pattern's real downstream
   content (`\d+`); it can't be omitted from the subset. Only the caching *decision* around it
   can be made smarter — i.e. angle 2+3.

## Recommendation

Ship angle 2+3 (the lookahead-class fan-out) directly — it supersedes angle 4, so there's no
need for a separate safety-net step: the fan-out attacks the actual caching defeat rather than
routing around it, and its own fast proxy check (test-plan item 1, below) is exactly the gate
that should have caught `274488d`'s regression before a JMH run was needed. Angle 4
(`findEligible` routing `find()` to `PikeVMMatcher` for these patterns) is noted only as a
rollback lever: if the proxy check ever shows a *different* anchor shape blowing up
`anchorSensitiveCount` even with the fan-out in place, add `findEligible` then, as a targeted
kill switch for that shape — don't build it speculatively now.

**The fix: lookahead-class fan-out (angle 2+3).**
Define a bounded `AnchorLookahead` classification computed from `(input, pos, regionEnd)`,
sufficient to make `checkAnchor`'s result for all 5 new types a pure function of
`(anchorType, class)`:
- `END_OF_INPUT` (`pos == regionEnd`)
- `WORD_CHAR` (`charAt(pos)` is letter-or-digit)
- `CR`, `LF`, `NEL_LS_PS`, `OTHER_NONWORD`

For `END`/`STRING_END`'s 2-character-ahead `\r\n` check, restrict the fan-out to interior
positions (`regionEnd - pos > 2`) and force live recomputation for the last 2 characters before
`regionEnd` — O(1) per scan, and removes the only case needing more than 1-character lookahead
from the fast path.

Cache table shape for anchor-sensitive states changes from `int[128]` (indexed by consumed char)
to `int[128][]` (second dimension = lookahead class, allocated lazily, size ≤ 6). Anchor-*free*
states are unaffected — no second dimension allocated, same `asciiTables` code path as today.

### File-by-file sketch

- **`LaurikariDFACache.java`**: add `anchorLookaheadTables[state]` (`int[128][]`, lazily
  allocated) alongside `asciiTables`. `step(state, c, ..., input, pos, regionEnd)` for
  `anchorSensitive[state]` computes the lookahead class first, looks up
  `anchorLookaheadTables[state][c][class]` before falling through to `lookupOrCompute`.
  `cacheEntry` gains an overload keyed by `(state, c, class, value)`. Positions within 2 of
  `regionEnd` always call `lookupOrCompute` directly (never cached).
- **New small helper** (`PikeVMMatcher` or a new package-private `AnchorLookahead` utility):
  `static int lookaheadClass(String input, int pos, int regionEnd)`. The equivalence
  `checkAnchor(anchor, input, pos, 0, regionEnd) == f(anchor, lookaheadClass(input, pos,
  regionEnd))` for all 5 new types is the correctness linchpin and needs its own exhaustive unit
  test.
- **`LaurikariCaptureNfaStep.java`**: no change to closure/register logic — `addClosure`'s
  existing per-occurrence `checkAnchor` call remains the ground truth the cache must reproduce.
- **`LaurikariDfaMatcher.java`**: no routing change — `find`/`findFrom`/`findMatch`/
  `findMatchFrom` keep using `findCache` as today; the fan-out lives entirely inside
  `LaurikariDFACache`'s transition-cache lookup.
- **`LaurikariEligibility.java`**: no change — eligibility stays as extended in `274488d`.

### Test plan

1. **Fast proxy check before any JMH run** (this is the check `274488d`'s postmortem says was
   missing): assert `anchorSensitiveCount` for `findCache` built from `SQL_ANSI`/`SQL_MYSQL`/
   `SQL_POSTGRESQL` drops from ~68% back to near the `anchoredCache` baseline once this fix lands,
   and that `stateCount()` stays the same order of magnitude (the fan-out doesn't touch state
   identity, so no state-space blowup). Assert `anchorLookaheadTables` allocation count is
   bounded by the number of distinct anchor-bearing states × their outgoing anchor edges, not
   proportional to input length. Run this check before any JMH benchmark re-run, per the
   `274488d` postmortem.
2. **Correctness**: exhaustive test proving `lookaheadClass` + cached lookup agrees with live
   `checkAnchor`/`addClosure` for every `(anchorType, class)` combination, including the
   interior/tail-of-input boundary (`regionEnd-1`, `regionEnd-2`, `regionEnd`). Re-run
   `LaurikariHybridDisjointnessTest`, the Task 1.5 anchor-boundary tests (`ce24f15`), and the
   full differential-fuzz suite against `PikeVMMatcher` — zero net-new divergences.
3. **Byte-identical regression guard**: anchor-free patterns must produce identical
   `asciiTables`/`anchorSensitive` state and identical cache hit/miss counts before and after —
   no `int[128][]` allocated at all when `hasNewAnchor == false`.
4. **Performance regression pin**: re-add a benchmark-backed (or cheaper op-count/hit-rate CI
   proxy) assertion that `SqlAnsiFind`/`SqlMysqlFind`/`SqlPostgresqlFind` are at or above the
   ~0.83–0.86x pre-Phase-2 baseline at LONG scale — gate the merge on this explicitly. If this
   still fails after the fan-out lands, that is the signal to reconsider a `findEligible`-style
   rollback lever for the specific shape that still blows up, rather than adding it preemptively.

## Critical files

- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariDFACache.java`
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariCaptureNfaStep.java`
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariDfaMatcher.java`
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java`
- `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LaurikariDFACacheTest.java`
