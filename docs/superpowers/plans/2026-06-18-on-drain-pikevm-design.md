# O(n) drain — single-pass continuing-cursor PikeVM find-all (approach A)

Status: DRAFT (design, pre-implementation) — for the design→adversarial-review→improve loop
Date: 2026-06-18
Builds on: `2026-06-18-on-drain-design-prep.md` (research-backed; approach A chosen)

## Round-1 review: approach VALIDATED; resolutions + prerequisites (2026-06-18)
12 findings; the loop **confirmed the approach is sound** (both "blocker" finalization concerns were
refuted under the §3.2 monotonic invariant). Resolutions folded into §3:
- **MUST-FIX-FIRST (prerequisite to any code):** the ≥50k fuzz **never exercises `findAll`/multi-match**
  — it only diffs single find/match/matches at start=0. Extend `RegexFuzzOracle.check()` to iterate
  JDK `Matcher.find()` (all non-overlapping matches, empty-match advance-by-one) and diff the full
  list (group-0 + every group span per match) against `reggie.findAll`, plus a `findMatchFrom(start>0)`
  case. Land this **before** implementing — otherwise the gate is blind to exactly the path we change.
- **Re-seed unconditionally** (v1's "stop re-seeding" rule was wrong) — §3.2.
- **Anchor origin pinned to absolute 0**; resume is NOT `findMatchFrom(input,e)`; `^`/`\A`/`(?m)^`
  findAll **intentionally changes toward JDK** (fixes a latent re-anchoring bug) → JDK is the oracle
  for start anchors — §3.3/§3.3a.
- **Zero-length-accept prune re-keyed:** `pruneAnchorDerivedAtStart` must trigger on the accepting
  thread's OWN origin (`caps[0]==pos`), not a global `pos==tryPos`/`regionStart`; and
  `clistViaMultipleAnchors` must become **per-thread** state (add `nlistViaMultipleAnchors`, copy in
  `swapLists`, propagate in `stepChar`/`addThreadToNlist`). If that's too invasive for v1,
  **anchor-derived-empty patterns** (those that can set `clistViaMultipleAnchors` — anchors in/adjacent
  to nullable quantifiers, e.g. `(^a?){3}`) are **ineligible → per-start fallback** (§5 floor).
- **Scope:** switch **all four** entry points (find/findFrom/findMatch/findAll) to the continuing pass
  (the per-start quadratic A1 hits single find too). Pin the `findMatchFrom(start>0)` anchor origin
  used by `split()`/`replaceAll` and add a differential test.
- Dedup ordering invariant (carry-over-clist-first-then-seed) made explicit — §3.2.

## 1. Goal
Make `findAll` / the IAST tokenizer drain **O(n·m)** (m = NFA states ≈ 30–100 for IAST), eliminating
the two O(n²) sources, so Reggie beats re2j on `IastTokenizerDrainBenchmark`. Correctness:
identical match spans + group offsets to the current PikeVM (leftmost-first), verified by fuzz.

## 2. The two O(n²) sources being removed (verified)
- **A1 — per-start retry inside `findMatchFrom`:** `findStartFrom` does `for start=fromPos..len:
  tryFindMatchAt(start)`, re-running the thread sim from each start. On many-start-candidate inputs
  (`?`×n vs URL) a single `findMatchFrom` is O(n²). (Cox regexp3: the PCRE per-position anti-pattern.)
- **A2 — `findAll` re-scan per match:** `ReggieMatcher.findAll` loops `findMatchFrom(input,pos)` per
  match, re-launching the automaton from each match end.

## 3. The design: one continuing left-to-right pass
A new driver (`findAllPass`) that walks the input **once**, monotonic cursor, replacing the per-start
loops for find/findFrom/findMatch/findAll. Reuses the existing thread machinery (`addThread` priority
DFS + `inClist` dedup + per-thread `updateCaptures`, `stepChar`, `swapLists`).

### 3.1 Unanchored prefix + Mark priority (the core fix)
At each position `pos`, **seed the start-state thread at LOWEST priority** (appended after all
existing clist threads) — the `.*?` prefix with RE2's "Mark" separator. Effect:
- a new match may *begin* at any position (unanchored search), and
- threads that started earlier have **higher priority** → **leftmost-first** is preserved.
- **`inClist` dedup-by-PC collapses the N start positions into ≤ m live threads** → the work is
  O(n·m), NOT O(n²). (This is precisely what fixes `?`×n: all the `?`-starts merge by PC.)
Anchors on the seeded start (`^`, `\A`, `\b`, `$`) are evaluated by the existing `checkAnchor(...,
pos, ...)` inside `addThread` — so this pass handles anchored patterns natively (unlike the boolean
DFA), covering COMMAND (`\b`/`^m`) and URL.

### 3.2 Leftmost-first match finalization — with the monotonic invariant (r1)
**The leftmost-first correctness rests on one invariant** (Round-1 made it explicit and proved the
finalization rule equivalent to per-start under it): with lowest-priority re-seeding (§3.1) + PC-only
`inClist` dedup (first-added-wins, no `caps[0]` tie-break, `addThread:601`),
**priority index order == seed order == non-decreasing `caps[0]` (group-0 start)**. Every surviving
higher-priority thread therefore has a group-0 start ≤ the accepting thread's.

Rule: when an accepting thread is reached, record it as best and **cut all strictly-lower-priority
threads** (they are later-or-equal starts → can't beat it); higher-priority non-accepting threads
keep running and may overwrite `best` with a longer end (greedy give-back, equal start). Finalize on
clist-empty. This is exactly today's `tryFindMatchAt` `clistSize=t` truncation, hoisted into the
continuing pass — equivalent **because of the invariant above**.

**Re-seed UNCONDITIONALLY (corrects v1).** v1 said "stop re-seeding once a match is in progress" —
that is wrong (it can miss a leftmost match that begins after an earlier doomed attempt). Instead,
re-inject the start thread at lowest priority at **every** position, exactly as the boolean DFA
already does (`findStepClosure` unconditionally unions the start closure each char,
`PikeVMMatcher:287-298`). It's safe: a later, lower-priority start that merges by PC is dropped by
dedup, so it cannot pollute the earlier in-progress match's captures. Cursor jump/reset (§3.3) is
reserved strictly for **finalized** matches.

[invariant] The within-pass ordering is load-bearing: carry the existing clist threads (earliest
starts, highest priority) into the new `inClist` generation **first**, then `addThread(startState,
pos)` for the new position. `addThread:601` is first-added-wins with no `caps[0]` tie-break, so this
order is what makes group-0 start leftmost. Comment + assert it.

### 3.3 Non-overlapping find-all advance + anchor origin (r1)
After finalizing `[s,e)`: emit it; set the cursor to `e`; reset thread lists; resume the pass from
`e`. Empty match (`e==s`): advance by one (`e+1`) — the existing `advancePos` rule. Monotonic cursor
→ one pass, O(n·m).

**[invariant — anchor origin, r1] `regionStart` stays pinned to the absolute search origin (0 for
findAll over the whole input) for the ENTIRE pass and across all re-seeds and cursor jumps.** The
resume cursor `e` is the seed/scan `pos`, **never** `regionStart`. Consequence: **do NOT implement
resume as `findMatchFrom(input, e)`** — that sets `regionStart=e` (`PikeVMMatcher:497`) and would let
`^`/`\A` spuriously match at every post-match cursor. Thread an immutable `absoluteOrigin` through
`initClist`/`addThread`/`checkAnchor`, independent of the moving cursor.

### 3.3a Anchor semantics: this INTENTIONALLY changes `^`/`\A` findAll toward JDK (r1)
This is **not** a behavior-identical refactor for start anchors. Today Reggie's `findAll` re-anchors
`^`/`\A`/`(?m)^` at each restart (`regionStart` moves to the prior match end) — a **latent bug**:
e.g. `^a` over `"aaa"` yields `[0,1)[1,2)[2,3)` today, but JDK yields just `[0,1)`. With
`regionStart` pinned to 0, the single pass matches **JDK**. So for start anchors the **oracle is JDK,
not current PikeVM** (they disagree; "match both" is unsatisfiable). `$`/`\Z`/`\z` (regionEnd=len)
and `\b` are unaffected; `(?m)^` also reads regionStart and is fixed the same way.

### 3.4 Captures
Per-thread capture arrays already populate group offsets (`updateCaptures`); the finalized
(highest-priority) thread's captures ARE the match's group spans — unchanged from `tryFindMatchAt`.
No new capture machinery, no tagged DFA.

## 4. Where it plugs in
- New `findAllPass`/`findAllInto` driving `findAll`, and a single-match variant for
  `findFrom`/`findMatchFrom` (find = first emitted match). The existing per-start `findStartFrom`/
  `tryFindMatchAt` are removed or kept only as a fallback for ineligible patterns.
- Boolean `find()`/`matches()` keep their T1.4/T1.6 DFA fast paths (already O(n), faster than PikeVM).
  This design targets the **capture/find-all** paths.
- PikeVM-resident (string-keyed, no StructuralHash/dual-path).

## 5. Eligibility & fallback
The single-pass PikeVM is correct for any pattern that compiles to the NFA (it's the same engine,
restructured) — including anchors and named groups (`NameEnrichingMatcher` wraps it). Lazy
quantifiers (LDAP/Oracle) still fail to compile — separate blocker, unchanged. No JDK fallback
introduced. If any pattern class is found unsafe under the single-pass rule, it falls back to the
current per-start path (correctness floor).

## 6. Correctness obligations
1. **Span + group equality** with the current PikeVM (`findMatchFrom`) and JDK, for find AND findAll,
   across the IAST patterns + edge inputs. (The single pass must produce identical leftmost-first
   results — it's a perf refactor, not a semantics change.)
2. **Non-overlapping find-all** matches JDK `Matcher.find()` iteration + `advancePos` (empty-match
   advance-by-one).
3. **Anchors** (`^`, `^m`, `\b`, `$`, `\A`, `\Z`, `\z`) evaluated per-position in the continuing pass
   give the same matches as today.
4. **The catastrophic inputs** (`?`×n, `\n`×n) are now O(n·m): pin a perf assertion / the drain
   benchmark.
5. **≥50k zero-divergence fuzz at the 18-finding baseline** — and ensure the fuzz exercises `findAll`
   (multi-match), not just single find/match. (If it doesn't, that's a gap to close first.)

## 7. Risks / open questions (seed the review)
1. **Leftmost-first finalization correctness (§3.2):** is the "record best + cut lower priority +
   keep higher + finalize on clist-empty" rule exactly equivalent to today's per-start
   `tryFindMatchAt` for every case (greedy give-back, optional groups, anchors at match start)? This
   is the highest-risk part — the single pass must not change which match/spans win.
2. **Re-seeding interaction (§3.1/§3.2):** when to stop/resume injecting the start thread relative to
   an in-progress match, so later starts don't corrupt the current leftmost match's captures, yet a
   match starting after a failed earlier attempt isn't missed.
3. **Anchors + unanchored prefix:** seeding the start thread mid-input must correctly fail `^`/`\A`
   (only pos 0 / after `\n` for `^m`) via `checkAnchor` — does `addThread`'s anchor handling compose
   with per-position re-seeding? (The T1.4 anchor work is DFA-side; this is PikeVM-side.)
4. **`clistViaMultipleAnchors` / zero-length-accept machinery:** the existing pruning
   (`pruneAnchorDerivedAtStart`) was built for per-`tryFindMatchAt` start semantics — does it carry
   over to the continuing pass, or does it need re-derivation?
5. **Does the existing fuzz cover `findAll`?** If only single find/match is fuzzed, multi-match
   correctness is unguarded — must add findAll differential coverage before relying on the gate.
6. **Per-`find()` (single match) vs find-all:** should `findFrom`/`findMatch` also switch to the
   single-pass (fixing A1 for single find on `?`×n), or only `findAll`? (A1 affects single find too.)
