# Implementation plan: bound capture extraction to the located match span

Date: 2026-07-06
Status: implementation plan for `doc/2026-07-06-bitstate-span-tightening-design.md` (P3 span
tightening). Grounded against the actual current code on branch `perf/eliminate-slow-paths`
(main repo), re-verified line-by-line — see "Corrections to the design doc" below before reading
the task list, since a couple of the design doc's mechanics don't work exactly as narrated and the
plan below routes around that.

Note on tooling: `PikeVMMatcher.java` contains a literal NUL byte inside a Javadoc comment
(illustrating a `[\x00-￿]` char-class range at the point in the file discussing
`computeSinkGroups()`). Plain `grep`/`grep -n` on macOS silently treats the file as binary and
returns zero matches with no warning — always use `grep -a` against this file.

## 0. Corrections to the design doc

The design doc's grounding (§2) is substantially accurate — `findDfa`, `matchesDfa`, `rejectDfa`
(`PikeVMMatcher.java:138,155,167`) and `findMatchResultFrom` (`PikeVMMatcher.java:862-962`) all
exist as described. Three points need correction before implementing:

1. **BitStateMatcher does not currently reuse `computeFirstByteFilter`.** The design doc (§2,
   bullet 3) claims it does; grep confirms `BitStateMatcher.java` only references
   `PikeVMMatcher.checkAnchor` (static) and mentions `PikeVMMatcher` in doc comments — there is no
   `computeFirstByteFilter`/`firstByteAscii` prefilter in `BitStateMatcher` today. Not a blocker,
   just don't rely on that precedent existing.
2. **`BitStateMatcher.search()`'s actual signature** is `search(String input, int scanStart, int
   spanEnd, boolean unanchored, boolean requireFullMatch)` (`BitStateMatcher.java:261-262`), called
   from `matches`/`find`/`findFrom`/`match`/`findMatchFrom`/`matchesBounded`/`matchBounded`
   (`BitStateMatcher.java:133-212`), each currently passing `input.length()` as `spanEnd`. This
   matches the design's intent (§3.3) even though the doc's cited call-site shapes are stale.
3. **The self-anchoring re-injection is baked into the DFA's per-character step, not into
   `findFrom`'s restart-on-DEAD loop.** `findStepClosure`/`findStepClosureMultiline`
   (`PikeVMMatcher.java:490-504`) already union in `reinjectClosureIds`/`reinjectAfterNlClosureIds`
   on *every* transition — `LazyDFACache.findFrom`'s restart-on-DEAD (`LazyDFACache.java:170-175`)
   is essentially a defensive no-op for this specific step function (a self-anchoring DFA's next
   state is only literally `DEAD` if the reinjected closure itself is empty, which doesn't happen
   for any pattern that reaches this eligibility gate). This matters because it means **phase 1
   does not need to reconstruct a match start before computing an end bound** — see §1 below, this
   simplifies the design doc's proposed `findEnd(input, start, ...)` signature.

## 1. Phase 1 — `LazyDFACache.findEnd`

Add one new method, symmetric to `findFrom` but tracking the *last* accepting position instead of
the first, and never restarting on `DEAD` (once the self-anchoring union dies, no continuation of
any tracked start — including ones reinjected up to that point — survives, so stopping is sound,
per the union-superset argument below):

```java
/**
 * Upper bound on how far a match already proven to exist at/after {@code start} can extend.
 * Walks the DFA forward from state 0 over {@code input[start, limit)} using the SAME
 * self-anchoring step function as {@link #findFrom}, tracking the last position at which
 * {@link #accepting} was observed true. Never restarts on DEAD — once dead, no tracked
 * continuation (of {@code start} or any later re-injected start) survives.
 *
 * <p>Because {@code nfaStep}'s closure re-injects a fresh start at every position (self-anchoring,
 * see {@code PikeVMMatcher.findStepClosure}), the returned bound is a union over ALL match
 * attempts beginning at or after {@code start} — a strict superset of the single leftmost-greedy
 * match's continuations. This makes the bound sound as an upper bound (it can only be ≥ the true
 * match end, never <), but NOT authoritative for which capture path was taken — callers must still
 * run the real capture engine to resolve priority. See design doc §3.1.
 *
 * @return the last position (0-based, exclusive-end style: pos+1 after a consuming step) at which
 *     an accepting state was reached, or {@code start} if state 0 itself is accepting (empty
 *     match), or {@code -1} if no accepting state was ever reached before DEAD/limit.
 */
public int findEnd(String input, int start, int limit, NfaStep nfaStep)
```

Implementation mirrors `findFrom`'s cache-lookup loop (`LazyDFACache.java:153-179`) exactly, with
two changes: (a) on `DEAD`, `break` instead of restart; (b) instead of returning on the first
`accepting[dfaState]`, record `lastAccept = pos + 1` and keep going. Needs a `FALLBACK` handling
sibling — mirror `nfaFallbackFindFrom` (`LazyDFACache.java:295-323`) with a `nfaFallbackFindEnd`
that keeps stepping the raw NFA state-set forward from the frozen position, tracking last-accept,
until the fallback loop ends (no restart there either).

**Tests** (`LazyDFACacheTest.java`, following existing `TWO_STEP`-style fixtures):
- Match ends before `limit`: bound equals the true end, not `limit`.
- Pattern with a `(?s).*`-style non-dying tail: bound equals `limit` (never goes DEAD).
- Empty-match-eligible start state: bound includes `start` itself.
- Cache-freeze mid-scan (small `cap` constructor, mirroring existing freeze tests at
  `LazyDFACacheTest.java:82,91,145,286`): fallback path exercised and produces the same bound as
  the non-frozen path for the same input.
- Bound is monotonically ≥ the position an equivalent hand-rolled NFA thread-sim would report for
  a single fixed leftmost start (differential test against a small `PikeVMMatcher`/`Pattern`
  instance over a handful of patterns) — this is the soundness property the whole design leans on,
  worth pinning down with an explicit test rather than trusting the proof alone.

## 2. Phase 2a — `PikeVMMatcher`: bound the scan, not the anchor semantics

**Critical correctness point the design doc doesn't spell out:** `regionEnd` in
`findMatchResultFrom`/`findPosFrom` is used for two *different* things that must not be conflated:
1. The true end of the input, threaded into `checkAnchor` (`PikeVMMatcher.java:1287-1330`, e.g.
   `$`/`\z`/`\b` peek at `input.charAt(regionEnd-1)` / compare `pos == regionEnd`) and into the
   dotall-sink extension (`winCaptures[1] = regionEnd`, `PikeVMMatcher.java:942`).
2. The loop's iteration upper bound (`for (pos = fromPos; pos <= regionEnd; pos++)`,
   `PikeVMMatcher.java:890`).

Shrinking (1) to the DFA-computed bound would make `$`/`\b`/`\z` fire against a truncated string
that isn't the real input end — silently wrong captures for any pattern with a trailing anchor.
The plan therefore introduces a **second, separate** loop-limit variable and leaves every
`regionEnd` argument to `checkAnchor`/`stepChar`/the sink extension untouched at `input.length()`.

Concretely, in `findMatchResultFrom` (`PikeVMMatcher.java:862-962`):
- Keep `int regionEnd = input.length();` unchanged.
- After the existing fast-reject block (lines 877-886), when it does *not* return `null` (a match
  exists), compute:
  ```java
  int scanLimit = regionEnd;
  if (findDfa != null && !findCanMatchEmpty) {
    int bound = findDfa.findEnd(input, fromPos, regionEnd, findStep);
    if (bound >= 0) scanLimit = bound;
  } else if (rejectDfa != null) {
    int bound = rejectDfa.findEnd(input, fromPos, regionEnd, rejectStep);
    if (bound >= 0) scanLimit = bound;
  }
  ```
  (`bound < 0` can't actually occur here since existence was just proven, but guard anyway rather
  than assume — cheap and avoids a landmine if the two DFA calls ever drift out of sync.)
- Change the loop header from `pos <= regionEnd` to `pos <= scanLimit`, and the two `if (pos ==
  regionEnd) break;` early-exits (lines 950, and inside the accept block if present) to `if (pos ==
  scanLimit) break;`. Everything else — `stepChar(ch, pos + 1, input, 0, regionEnd)`,
  `checkAnchor`, the sink-extension block — keeps passing `regionEnd` (true input length)
  unchanged.
- Apply the identical change to `findPosFrom` (`PikeVMMatcher.java:750-793`), which has the same
  loop shape and the same `regionEnd` double-duty; same `scanLimit` pattern, same untouched
  `regionEnd` elsewhere.

`runMatches`/`runMatchResult`/`matches()` are **out of scope** — whole-region match semantics
requires scanning the entire region by definition; there is no "match end" to bound short of the
region itself.

**Tests to add/re-run** (per design §4, now grounded in actual file names):
- `PikeVmCaptureRegressionTest.java`, `PikeVMMatcherTest.java`, `PikeVMAnchorFindTest.java`,
  `PikeVMNullableAlternationTest.java` (`reggie-runtime/src/test/java/.../`) — must pass unchanged.
- New targeted test: a pattern with a trailing `$`/`\b` matched against a long input where the
  match itself is short and near the start (the exact scenario `scanLimit` shrinks for) — asserts
  the anchor still fires correctly, i.e. regression-proofs the regionEnd/scanLimit split above.
- New targeted test: unanchored `find()` on a long haystack with an early short match plus a
  *second*, non-winning later match — confirms `scanLimit` (derived from the union-of-all-starts
  bound) doesn't accidentally truncate before the leftmost winner's true end when a later
  candidate's shorter continuation would have produced an earlier DEAD.

## 3. Phase 2b — `BitStateMatcher`: build and wire its own end-bound DFA

`BitStateMatcher` has no DFA today and cannot borrow `PikeVMMatcher`'s instance fields (the
`fallback` field is a *lazily constructed, full* `PikeVMMatcher` — instantiating it just to reuse
its DFA would defeat the point of avoiding PikeVM's overhead). The eligibility/construction logic
that builds `findDfa`/`rejectDfa` (`findDfaEligible`, `noAssertionsOrBackrefs`,
`sortedEpsilonClosure`, `sortedUnion`, the closure-building block in the constructor,
`PikeVMMatcher.java:352-391,409-517,251-333`) operates purely on the `NFA` plus arrays each matcher
already derives independently in its own constructor (`statesById`, `stateCount`,
`nfa.getAcceptStates()`) — nothing here actually depends on PikeVM-specific runtime state
(thread lists, captures, atomic tracking).

**Proposed new helper (needs sign-off before coding, per project convention — this is exactly the
kind of "additional helper" that should be proposed rather than added silently):** extract that
construction logic into a small new package-private class, e.g.
`reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/NfaEndBoundDfa.java`, with:
- A static factory `static NfaEndBoundDfa build(NFA nfa)` returning `null` when ineligible (same
  eligibility rule as `findDfaEligible`/`noAssertionsOrBackrefs`, reused verbatim), else an object
  exposing `LazyDFACache dfa()` and `NfaStep step()` (whichever of the find/reject shape applies).
- `PikeVMMatcher`'s constructor is refactored to call this factory instead of inlining the same
  logic, so there is exactly one implementation. This is the only touch to `PikeVMMatcher`'s
  existing (hot, delicate) code in phase 2b — everything else in phase 2a/2b is additive.
- `BitStateMatcher`'s constructor calls the same factory once, stores the result, and
  `search()`'s callers (`match`, `findMatchFrom`, `matches`, `find`, `findFrom`,
  `matchesBounded`/`matchBounded`) compute a `spanEnd` bound the same way phase 2a does
  (`BitStateMatcher.java:133-212`, replacing each `input.length()` argument to `search(...)` with
  `min(input.length(), boundOrInputLength)`), guarded by budget/exceedsBudget as today.

This refactor touches `PikeVMMatcher`'s constructor, which is explicitly called out in this
project's guidance as risky to modify without care (hot path, heavily comment-documented
invariants around anchor eligibility). **Recommend doing phase 2a and its tests first, landing and
verified independently, before starting the phase 2b refactor** — keeps the risky
constructor-refactor isolated from the (already independently valuable) PikeVM win, and gives a
known-good baseline to diff against if the extraction regresses something.

**Tests:**
- `BitStateMatcherTest.java` (both `reggie-runtime` locations referenced in the design) — capture
  correctness must pass unchanged.
- New unit test for `NfaEndBoundDfa.build`: same eligibility inputs as
  `findDfaEligible`/`noAssertionsOrBackrefs` produce the same yes/no as today's inlined checks
  (differential test against `PikeVMMatcher`'s existing behavior, e.g. via reflection or by
  comparing `findDfa != null`/`rejectDfa != null` post-construction against `NfaEndBoundDfa.build`
  on the same NFA).
- Budget-eligibility test: confirm patterns that previously fell back to PikeVM via `overBudget()`
  purely because of an inflated `spanEnd` (long trailing input after a short match) now stay
  in-budget — a concrete regression test using one of the `BitStateCaptureBenchmark_*` patterns
  (LOG_LINE_MATCH or similar) with a long tail appended.

## 4. Empirical validation (do this before phase 2b, informs whether it's worth doing)

Before investing in the `NfaEndBoundDfa` extraction, verify the premise: run
`BitStateEligibilityTest.java`'s pattern corpus (or a quick throwaway script) through
`findDfaEligible`/`noAssertionsOrBackrefs`-equivalent logic and check what fraction of
BitState-routed patterns would actually get a non-null bound DFA. BitState's own eligibility gate
already excludes atomic groups/possessive quantifiers/backrefs/lookaround (class doc,
`BitStateMatcher.java:49-52`), which strongly overlaps with `findDfaEligible`'s exclusions — if the
overlap is near-total, phase 2b's expected win is broad; if it's small, the ROI may not justify the
constructor refactor and 2b could be deprioritized independently of 2a.

## 5. Benchmark re-measurement

Re-run the `reggie-benchmark` `BitStateCaptureBenchmark_*` JMH suite (COMMAND/URL/SQL plus
LogLine/HttpRequestLine/KeyValueLazy) after phase 2a and again after phase 2b, per design §4.
Expect the biggest deltas on patterns where the match is short relative to a long haystack tail
(HttpRequestLine/KeyValueLazy-style); expect little to no change on LOG_LINE_MATCH where the match
already spans most of a short line (design §4's own prediction, unchanged by the corrections
above).

## 6. Explicitly out of scope (unchanged from design doc §5)

- POSIX leftmost-longest semantics.
- Reverse-scan start-location (`BitParallelGlushkovRuntime`/`followReverse`).
- Seed-jump to the DFA-located start (tried and reverted; §3.1's soundness argument in this plan
  reconfirms why start-skipping is a distinct, not-reopened optimization from end-bounding).

## 7. Suggested landing order

1. `LazyDFACache.findEnd` + unit tests (§1) — self-contained, zero risk to existing callers.
2. `PikeVMMatcher` `scanLimit` wiring in `findMatchResultFrom`/`findPosFrom` + regression tests
   (§2) — the regionEnd/scanLimit split is the main correctness risk; land and verify (existing
   suites green + the two new anchor/multi-match tests) before touching anything else.
3. Empirical validation pass (§4) — cheap, decides whether §3 is worth it.
4. If justified: propose `NfaEndBoundDfa` extraction explicitly, get it confirmed, then implement
   phase 2b (§3) as an isolated follow-up, not bundled with step 2.
5. JMH re-measurement (§5) after each of steps 2 and 4 land.
