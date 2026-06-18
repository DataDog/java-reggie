# O(n) drain — RE2 two-pass (boundary DFA + anchored span capture) — approach B

Status: DRAFT (design, pre-implementation) — for the design→adversarial-review→improve loop
Date: 2026-06-18
Builds on:
- `2026-06-18-on-drain-design-prep.md` (research; approach B is the §"(B) RE2 two-pass" escalation)
- `2026-06-18-on-drain-pikevm-design.md` (approach A — single-pass PikeVM find-all, now IMPLEMENTED)

## Round-1 adversarial review + measured outcome (2026-06-18) — full B is NOT benchmark-justified
The review (evidence-dense, measured) reframed the whole escalation, and a cheap partial-B was then
implemented and confirmed:
- **3 of 4 "gap" scenarios produce ZERO matches** (URL_QUESTION_RUN, URL_QUERY, COMMAND_BLANK_LINES);
  the 4th (COMMAND_SINGLE_TOKEN) produces one. So the §0 "per-match allocation" premise is a
  **non-factor**; the cost is **per-char scan**. Pass 2 (reverse-DFA START recovery, BitState, OnePass)
  is essentially **never exercised** on the benchmark → **full B (B1 reverse-DFA + BitState) is not
  justified by this workload.** Build it only if a match-dense workload appears.
- **The cheap win (IMPLEMENTED + MEASURED):** a fast-reject in `findMatchResultFrom` — when `findDfa`
  is present and `!findCanMatchEmpty` and `findDfa.findFrom(input, fromPos) < 0`, return null without
  the thread sim. Sound (DFA tracks all starts → no false negatives; ^-over-acceptance at fromPos>0 is
  only a false positive). Result @2048B: **URL_QUESTION_RUN 191→8.5µs, URL_QUERY 179→8.5, URL_AUTHORITY
  161→8.5 — reggie now BEATS re2j ~15× on all three** (was ~1.5× behind). SQL still beats re2j ~3×.
  Smoke fuzz 0, runtime suite green. ~3-line change, zero new infra.
- **Allocation hypothesis REFUTED:** PikeVM uses the allocating default `findBoundsFrom`, but with 0–1
  matches there's nothing to save (measured no faster). Don't bank ROI on an alloc-free bounds API.
- **OQ-2 (greedy-END) REFINED:** the EXISTING approach-A forward engine already returns the correct
  greedy END/START (0 diffs over 20k give-back-stressing inputs). The give-back risk applies only to a
  hypothetical *bare first-accept* bounds DFA — so keep bounded-PikeVM END confirmation if a bounds DFA
  is ever built.
- **The ONLY remaining gap is COMMAND** (`findDfa=null`: `(?m)^`+`\b` rejected by `findDfaEligible`),
  losing to re2j ~1.8×. To win it the fast-reject needs an **anchor-aware boolean find DFA** that admits
  `(?m)^`/`\b`/`$` (OQ-5 — the single net-new piece). SQL already wins, so this is COMMAND-only.
  **Recommendation: prototype the COMMAND anchor-aware DFA (measure state-count growth + speed); treat
  reverse-DFA/BitState/OnePass as deferred (not benchmark-justified).**

## 0. Why this escalation exists (the constant-factor gap)
Approach A is DONE and killed both O(n²) sources: the per-start retry inside `findMatchResultFrom`
(`PikeVMMatcher.java:478`) became a continuing left-to-right pass with lowest-priority re-seeding
(`seedStart`, `PikeVMMatcher.java:538-543`), and `findAll`'s per-match relaunch
(`ReggieMatcher.findAll`, `ReggieMatcher.java:653-669`) is now linear. URL_QUESTION_RUN and
COMMAND_BLANK_LINES dropped from ~130k–153k µs to ~150–191 µs (linear). **But on those two scenarios
Reggie is still ~1.5× behind re2j** (Reggie 191/152 µs vs re2j 125/84 µs at 2048 B). Reggie already
BEATS re2j on SQL (~2.5×).

The residual gap is a **constant factor**, not complexity:
1. **Per-char cost.** Approach A pays a *thread simulation* per input char: `stepChar`
   (`PikeVMMatcher.java:559`) iterates every live thread × its transitions and runs an epsilon-closure
   DFS (`addThreadToNlist`, `PikeVMMatcher.java:686`) that copies a capture array
   (`System.arraycopy`, `:733`) per thread per step. re2j's `find()` advances a **single lazy-DFA
   state** per char (`s = next[bytemap[c]]`) with no per-thread work — far fewer instructions/byte.
2. **Per-match allocation.** The drain loop calls `findMatchFrom` (`PikeVMMatcher.java:375`) which
   builds a `MatchResultImpl` with two fresh `int[]` per match (`buildResult`,
   `PikeVMMatcher.java:883-891`) plus an `Arrays.copyOf` of the win captures (`:503`). re2j's
   `find()` mutates an internal cursor — zero allocation per match in the count loop.

Approach B is RE2's actual architecture: a **fast DFA pass finds match boundaries `[start,end)`**
across the whole input (the cheap per-char step), then an **anchored capture engine runs only over
each matched span** (work proportional to the matched text, not the whole input). The DFA pass is
where we recover the per-char gap vs re2j; the anchored capture (OnePass/BitState) is where we drop
the per-thread-closure and per-match-allocation overhead.

## 1. Goal
Make Reggie's capture / find-all drain **match or beat re2j on every IAST tokenizer pattern that
routes to PIKEVM_CAPTURE** — COMMAND, URL (wrapped in `NameEnrichingMatcher`), and both SQL dialects
— on `IastTokenizerDrainBenchmark` (512–2048 B, malformed payloads), while keeping the SQL win.
LDAP is **excluded** (lazy quantifier `.*?` does not compile — a separate blocker, untouched here).

Hard constraints (non-negotiable, in priority order):
1. **Correctness = JDK.** Match spans + every group offset, for find AND findAll, must equal
   `java.util.regex.Matcher` exactly (JDK is the oracle — there is a parallel capture-correctness
   effort; this design must not regress it and must be gated on the same fuzz). Leftmost-first,
   non-overlapping, empty-match advance-by-one.
2. **Linear.** O(n) total for the boundary pass + O(Σ|span|) for capture = O(n) on disjoint spans.
   No reintroduction of either O(n²) source.
3. **Beat re2j on the four PIKEVM_CAPTURE IAST patterns.** Otherwise the escalation has no payoff and
   we stay on approach A.
4. **No new external dependencies; allocation-free drain loop; PikeVM-resident** (string-keyed via
   `RuntimeCompiler`'s `PIKEVM_NFA_CACHE`, `RuntimeCompiler.java:158`/`:461` — NO StructuralHash,
   per the existing PIKEVM_CAPTURE convention).

Approach B is **strictly opt-in and eligibility-gated** (§6). Any pattern that does not qualify
stays on the now-correct approach-A single pass (`findMatchResultFrom`) — that is the correctness
floor. We add a faster path; we do not replace the safety net.

## 2. The two-pass structure adapted to Reggie

```
drainB(input):
  pos = 0
  while pos <= len:
    # PASS 1 — boundary DFA: find next match region [s,e) at/after pos, single continuing scan
    if !boundaryScan.next(input, pos, region):   # fills region.start, region.end
        break
    s = region.start; e = region.end
    # PASS 2 — anchored span capture over input[s..e) ONLY
    captureEngine.captureAnchored(input, s, e, slots)   # writes group offsets into slots[]
    emit(s, e, slots)            # or just count / fill caller's int[] (allocation-free)
    pos = (e > s) ? e : e + 1    # non-overlapping advance-by-one on empty match
```

Two engines, two responsibilities:

### 2.1 Pass 1 — boundary DFA (`[start,end)` for every match, one continuing scan)
This is the crux and the part the prep doc flagged as the **Round-1 #6 gap**: the existing T1.4
`findDfa` gives a boolean / scan-START, NOT the exact `[start,end)`.

- **END is the easy half.** A forward DFA that re-injects the start closure each char (the
  self-anchoring construction already implemented as `findStep`/`findStepClosure`,
  `PikeVMMatcher.java:174`/`:287-298`) reaches an accepting state exactly when *some* substring
  ending at the current position matches. The **leftmost match END** is the first position at which
  the *highest-priority* (earliest-started) thread accepts; for a forward leftmost-longest/greedy
  scan it is where the accepting set is reached and can no longer be extended without losing the
  leftmost start. In RE2 terms: forward DFA scans to the accepting position = match END.
- **START is the hard half.** The forward self-anchoring DFA collapses all start positions by PC
  (that's *why* it is O(n)), so it **loses which start** produced the accept — it only knows "a
  match ends here." RE2 recovers START with a **reverse DFA**: run a DFA built over the *reversed*
  program backward from the known END; the position where the reverse DFA accepts is the leftmost
  START of the match ending at END. Two sub-options for Reggie:

  - **(B1) Reverse-DFA START (true RE2).** Build a reversed NFA (reverse every transition; swap
    start/accept roles; anchors flip: `^`↔`$` semantics under reversal) and a second `LazyDFACache`
    over it. Forward scan → END; reverse scan from END leftward → START. *Net-new:* a reverse-NFA
    builder and reverse anchor handling. This is the literature-faithful path and the only one that
    is provably leftmost-correct for all eligible patterns.
  - **(B2) Reuse the self-anchoring boolean DFA for scan-START, then forward-confirm END.** The
    existing `findDfa.findFrom` (`LazyDFACache.java:145`) already returns a **leftmost scan-start**
    (it restarts `matchStart=pos+1` on DEAD, `:166-168`). But that start is the *DFA restart
    position*, not necessarily the true leftmost match start under priority (it can over- or
    under-shoot for patterns with a prefix that can match empty, or with anchors) — this is exactly
    the Round-1 #6 caveat. **B2 is only sound for a restricted pattern class** (no leading nullable,
    no interior `^`/`\A`); for the IAST patterns this must be proven per-pattern (§7 OQ-3), and if it
    can't be, B2 falls back to B1 or to approach A.

  **Recommendation: implement B1 (reverse DFA).** B2's scan-start is the documented gap; relying on
  it for correctness re-opens #6. The reverse DFA is more surface but is the only construction that
  closes #6 cleanly. B2 may be kept as a *fast pre-filter* (cheap rejection of regions) but never as
  the authoritative START.

- **Anchors in the boundary scan.** The boundary DFA MUST evaluate `^`/`\A`/`(?m)^`/`$`/`\Z`/`\z`/`\b`
  with the **absolute origin pinned to 0** and `regionEnd = len` for the whole pass — identical to
  the approach-A invariant (`findMatchResultFrom` pins origin 0, `PikeVMMatcher.java:524`; anchor
  origin invariant §3.3 of the approach-A doc). The current `findDfaEligible`
  (`PikeVMMatcher.java:189-223`) **rejects all of `$`, `\b`, `(?m)^`, assertions, backrefs** —
  meaning the existing DFA infra cannot even be built for COMMAND (`(?m)^ … \b\S+\b`), URL (leading
  `^`), or SQL (`(?m)` + `$` + `\b`). **The boundary DFA needs an anchor-aware step** (carry
  position into the closure, evaluate `checkAnchor` per byte) — this is the single largest piece of
  net-new DFA work and the reason approach B is "much more surface" (prep doc §B). See §7 OQ-5.

### 2.2 Pass 2 — anchored span capture (the ladder)
Over `input[s..e)` only, extract group offsets. RE2's dispatch ladder, in increasing generality and
cost — first that is eligible wins:

- **OnePass** (anchored, ≤ 1 viable alternative per byte): a single deterministic walk, group slots
  written inline, no thread list, no backtracking. **Reggie already has
  `OnePassBytecodeGenerator`** (`reggie-codegen/.../codegen/OnePassBytecodeGenerator.java:86`) whose
  generated `matches()` is exactly this single-`int`-state machine with inline group tracking
  (`:43-75`, `:110-130`). **Assess reuse (§4.2):** the generator emits a whole-input *matches()*
  state machine; we need an **anchored capture over `[s,e)`** that records group offsets. Two paths:
  (a) extend OnePass codegen to emit a `captureAnchored(input, s, e, int[] slots)` entry, or
  (b) a small **runtime** OnePass interpreter over the NFA (no codegen, PikeVM-resident, string-keyed)
  that walks the unique path and writes slots. Given the PIKEVM_CAPTURE "no StructuralHash / fresh
  matcher per compile" convention (`RuntimeCompiler.java:189`/`:268`), **(b) a runtime OnePass is the
  better fit** — codegen OnePass lives on the StructuralHash/dual-path side and would fight the
  convention. The codegen class is a **reference for the algorithm and the OnePass eligibility test**,
  not a drop-in. See §7 OQ-4 for whether the IAST patterns are actually OnePass.
- **BitState** (bounded backtracking with a visited bitmap, ≤ ~32 KB): for ambiguous-but-small spans,
  a backtracking search keyed on a `visited[nfaState × spanPos]` bit to stay linear in the bitmap
  size. *Net-new* (Reggie has no BitState). Used when OnePass is ineligible but the span × state
  product is under the cap.
- **Full PikeVM fallback** (over the span only): when the span is too big for BitState's bitmap, run
  the existing anchored thread simulation **bounded to `[s,e)`** — `runMatchResult(region, 0,
  len)` semantics but with `regionStart=s`/`regionEnd=e` (the bounded variant already exists:
  `matchBounded`/`runMatchResult`, `PikeVMMatcher.java:385-390`/`:424-448`). Because the span start
  is already known, this is O(|span|) with no per-start retry — it cannot reintroduce A1. This is
  the always-correct floor for capture.

Because pass 1 gives an exact, anchored span, pass 2 never searches for a start — it always runs
*anchored* at `s` and bounded at `e`. That is what makes each engine linear in the span.

## 3. Allocation discipline for the drain loop
- **Allocation-free cursor/bounds API.** `findBoundsFrom(input, start, int[] bounds)` already exists
  on `ReggieMatcher` (`ReggieMatcher.java:443-453`) and on `NameEnrichingMatcher`
  (`NameEnrichingMatcher.java:75-77`) but the default just calls `findMatchFrom` and copies two ints
  out of the freshly allocated `MatchResultImpl` (`:446`). Approach B should provide a **native
  `findBoundsFrom`** that runs pass 1 only (boundaries, no capture) and writes `bounds[0..1]` with
  **zero allocation** — this alone removes the per-match `MatchResultImpl`+`int[]×2` for callers that
  only need boundaries (count loops, `replaceAllLiteral` `ReggieMatcher.java:519-541`, `split`).
- **Capture-into-array API (new surface, minimal).** For drains that need group offsets without a
  `MatchResult`, add an allocation-free `findGroupsFrom(input, start, int[] groupStarts, int[]
  groupEnds)` (or reuse the existing `extractGroups(match, ...)` shape at `ReggieMatcher.java:420-427`)
  that runs pass 1 + pass 2 capture directly into caller-owned arrays. `MatchCursor`
  (`MatchCursor.java`) is the natural public surface — its `findNext()` already advances a single
  `searchPos` cursor (`:80-90`); a B-backed cursor can fill reused buffers instead of allocating a
  `MatchResult` per `findNext()`.
- **Reuse pass-1 / pass-2 scratch across the whole drain.** The boundary DFA's `LazyDFACache` is
  per-matcher and already amortizes across calls (it's a field). Pass-2 OnePass/BitState scratch
  (visited bitmap, slot arrays) must be **per-matcher pre-allocated buffers** sized at construction
  (like PikeVM's `clistCaptures` etc., `PikeVMMatcher.java:123-132`) — **never per-match**. The
  PikeVM convention is "all per-call buffers pre-allocated at construction; no allocation in the hot
  loop" (`PikeVMMatcher.java:35-36`); approach B must hold the same line.
- **`MatchResult` only at the API boundary.** `findAll`/`MatchCursor.next()` that *return*
  `MatchResult` must still build one (public contract), but the benchmark's count drain and the
  redaction drain that only needs spans must have allocation-free paths (above). The benchmark's
  `reggieDrain` (`IastTokenizerDrainBenchmark.java:236-251`) calls `findMatchFrom` + `r.end()`/
  `r.start()` — it should be re-pointed at the bounds API to measure the real B win (note: changing
  the benchmark body is a benchmark change, not production — flag for review).

## 4. Where each piece plugs in (new surface vs reused surface)

### 4.1 Pass 1 boundary DFA — PikeVM-resident
- **Reused:** `LazyDFACache` (`LazyDFACache.java:37`) as the interned-DFA substrate; the
  self-anchoring forward step `findStepClosure` (`PikeVMMatcher.java:287-298`); `sortedEpsilonClosure`
  (`:248`); `transitionTargets` (`:226`); the `NfaStep` lambda shape (`:174-176`).
- **New (in `PikeVMMatcher`, string-keyed, no StructuralHash):**
  - an **anchor-aware boundary step** that threads position through the closure and applies
    `checkAnchor` (`PikeVMMatcher.java:761-790`) per byte — replacing the anchor-free
    `findDfaEligible` gate so COMMAND/URL/SQL anchors are handled (§2.1, §7 OQ-5);
  - a **reverse NFA + reverse `LazyDFACache`** (B1) for START recovery;
  - a `boundaryNext(input, pos, region)` driver: forward scan → END, reverse scan → START, emit,
    advance. This replaces the per-match `findMatchResultFrom` relaunch *for eligible patterns*.

### 4.2 Pass 2 anchored capture — PikeVM-resident
- **Reused as the floor:** bounded thread sim `runMatchResult` with `regionStart=s`/`regionEnd=e`
  (`PikeVMMatcher.java:424`), already correct and already bounded.
- **New:** a **runtime OnePass interpreter** (preferred over reusing `OnePassBytecodeGenerator`,
  §2.2(b)) and a **BitState** interpreter, both PikeVM-resident with pre-allocated scratch. The
  codegen `OnePassBytecodeGenerator` (`OnePassBytecodeGenerator.java:86`) is consulted for the
  algorithm and the OnePass *eligibility predicate* (the "≤1 viable next state" analysis), not
  instantiated.

### 4.3 Dispatch
- New private `drainBEligible` gate in `PikeVMMatcher` (computed once in the ctor, like
  `findDfaEligible`/`prefilterUsable`). On eligible patterns, `findBoundsFrom`/`findMatchFrom`/
  `findAll` route through the two-pass; otherwise they keep the approach-A `findMatchResultFrom`.
- `NameEnrichingMatcher` (URL) is unaffected: it delegates `findMatchFrom`/`findBoundsFrom` straight
  through (`NameEnrichingMatcher.java:70-77`) and only post-enriches names — the two-pass runs in the
  delegate `PikeVMMatcher`, names are attached after (§7 OQ-6).
- `RuntimeCompiler` PIKEVM_CAPTURE caching (`RuntimeCompiler.java:461`) is unchanged: same NFA cache,
  same "fresh matcher per compile," string-keyed.

## 5. Non-overlapping iteration, empty-match, leftmost-first, correctness
- **Leftmost-first boundaries.** The forward DFA's accept = leftmost-first END (the self-anchoring
  re-injection gives the `.*?`-prefix leftmost semantics already proven for boolean find); B1's
  reverse DFA gives the leftmost START for that END. This must equal JDK's `Matcher.find()` greedy
  leftmost-first (the IAST patterns are Perl-mode, greedy — `re2j` defaults match). Verified against
  JDK, not against approach A's PikeVM, where they could disagree (e.g. start anchors — the
  approach-A doc §3.3a already pinned **JDK as the oracle** for `^`/`\A` find-all; B inherits that).
- **Non-overlapping + empty advance.** Same rule as everywhere: `pos = e>s ? e : e+1`
  (`advancePos`, `ReggieMatcher.java:675-677`; benchmark `:248`). The boundary driver owns the cursor;
  the reverse-START must never produce a START < previous `pos` (monotonicity).
- **Captures = JDK.** Pass 2 over the exact `[s,e)` must yield identical group offsets to JDK.
  OnePass/BitState/PikeVM-bounded must all agree with each other and with JDK on every eligible
  pattern — this is the highest-risk correctness surface and is gated on the **extended findAll fuzz**
  (approach-A doc Round-1 MUST-FIX: `RegexFuzzOracle.check()` must iterate JDK `Matcher.find()` over
  all non-overlapping matches and diff group-0 + every group span; B reuses that gate and adds a
  cross-engine diff: B-drain == approach-A-drain == JDK).

## 6. Eligibility & fallback floor
A pattern uses approach B **only if all hold**:
1. It compiled to a PikeVM NFA (PIKEVM_CAPTURE) — no lazy quantifier (LDAP excluded, hard compile
   failure, unchanged).
2. The **boundary DFA can be built**: every anchor/assertion is handled by the anchor-aware step
   (§2.1). If assertions/backrefs/lookaround are present and the step can't model them → ineligible.
   (Current `findDfaEligible` rejects all anchors; the new gate must *positively* admit
   `^`/`\A`/`(?m)^`/`$`/`\Z`/`\z`/`\b` once the anchor-aware step supports them, and reject the rest.)
3. The **reverse NFA** is constructible (B1) — anchors flip cleanly; assertions/backrefs are not
   reversible → ineligible.
4. At least one **pass-2 engine** applies: OnePass-eligible, OR BitState within the bitmap cap, OR
   the bounded PikeVM floor (always applies, so #4 is really "is B faster than A here" — see §7).

Everything else → **approach-A single-pass `findMatchResultFrom`** (the correctness floor; no JDK
fallback introduced). The two-pass is a perf accelerator layered above the safe path, never a
replacement for it.

Per-pattern intent (to be CONFIRMED by §7 + the fuzz, not assumed):
- **COMMAND** `(?s)(?m)^(?:\s*(?:sudo|doas)\s+)?\b\S+\b\s*(.*)`: leading `(?m)^`, `\b`, one capture
  `(.*)`. Boundary DFA needs `(?m)^` + `\b`. Pass-2 capture is the trailing `(.*)` — likely OnePass
  after the anchored prefix.
- **URL** `^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)`: alternation, leading
  `^` on one branch, two named groups (`NameEnrichingMatcher`). Boundary DFA needs leading `^` +
  alternation. Pass-2 has two branches with captures — OnePass eligibility is **uncertain** (the
  alternation may give >1 viable alt at the first byte `[?#&]` vs `^//`) → likely BitState or bounded
  PikeVM. §7 OQ-4.
- **SQL_ANSI / SQL_MYSQL** (already beating re2j on A): big `(?i)(?m)`-flagged alternation with `$`,
  `\b`, char classes. Boundary DFA needs `(?m)$` + `\b` + case-insensitivity. Must **not regress** the
  existing SQL win — if B isn't faster here, SQL stays on A (eligibility is a *win* gate, not just a
  *correctness* gate).

## 7. Risks / open questions (seed the adversarial review)

1. **Reverse-DFA construction cost & correctness (B1).** Building a reversed NFA and a second
   `LazyDFACache` doubles the DFA infra. (a) Is the per-pattern reverse-NFA build cheap enough to do
   once at compile (it's cached per matcher) and not on the hot path? (b) **Anchor flipping under
   reversal is subtle:** `^`↔`$`, `\A`↔`\z`, `(?m)^`↔`(?m)$`, `\b`↔`\b` (symmetric), and the
   absolute-origin/regionEnd pinning must flip too. Get any of these wrong and START is wrong → wrong
   span → wrong captures. Is there a correctness proof that reverse-DFA-from-END yields exactly the
   JDK leftmost START for the eligible class?

2. **Forward-vs-reverse boundary reconciliation.** The forward DFA picks an END; the reverse DFA
   picks the START for *that* END. For **greedy/longest** semantics the END must be the longest
   match from the leftmost start — but the forward self-anchoring scan reports the *first* accept
   position, which under leftmost-first may be shorter than the greedy end (give-back). Does the
   forward pass need to *continue past* the first accept to find the greedy END (and how does it know
   when to stop without losing the leftmost start)? This is the forward/reverse handshake RE2 gets
   right via "longest-match DFA mode for the boundary pass" — does Reggie's self-anchoring step have a
   longest-match mode, or only leftmost-first? **(This is the sharpest open question — get it wrong
   and B's spans differ from JDK.)**

3. **B2 scan-START soundness (if B2 is used at all).** `LazyDFACache.findFrom` (`:145`) returns the
   DFA *restart* position, documented as not-the-true-leftmost for nullable-prefix/anchored patterns
   (Round-1 #6). For which of COMMAND/URL/SQL — if any — is the restart-start provably equal to the
   JDK START? If none, B2 is dead and B1 is mandatory (raising the surface/cost). Prove or drop B2.

4. **OnePass eligibility for the IAST patterns specifically.** OnePass requires ≤1 viable next state
   per byte. (a) COMMAND's trailing `(.*)` after an anchored prefix is plausibly OnePass — confirm.
   (b) **URL's top-level alternation** (`^//…@` vs `[?#&]…=…`) almost certainly is NOT OnePass at the
   leading byte → it drops to BitState/bounded-PikeVM, eroding the per-char win. (c) SQL's huge
   alternation is definitely not OnePass. **If the IAST patterns mostly fail OnePass, the pass-2 win
   shrinks to BitState vs PikeVM-bounded — is that still enough to beat re2j, or is the win entirely
   in pass 1 (the DFA boundary scan)?** Decide whether BitState is worth building or whether
   pass-1-DFA + pass-2-bounded-PikeVM already beats re2j (smaller surface).

5. **Anchors `^`/`$`/`\b`/`(?m)` in the DFA boundary scan.** This is the largest net-new piece. The
   existing DFA infra (`findDfaEligible`, `:189-223`) *rejects* every anchor except leading `^`/`\A`.
   COMMAND/URL/SQL all carry anchors the current step can't model. Designing an **anchor-aware
   self-anchoring step** (position threaded into the closure, `checkAnchor` per byte, START blocked at
   pos>0 except multiline-after-`\n`, `$`/`\b` needing look-ahead at the current byte) without
   blowing up the DFA state count (anchors fracture states by position-class) is unproven. **Does the
   anchor-aware DFA stay small (bounded states) for these patterns, or does it degenerate?** If it
   degenerates, B's per-char DFA advantage evaporates.

6. **Named groups / `NameEnrichingMatcher` (URL).** Names are attached post-hoc
   (`NameEnrichingMatcher.java:79-87`) over the delegate's `MatchResult`. The allocation-free
   bounds/groups API must still let names be resolved — does the cursor/bounds path lose the
   `nameToIndex` mapping, and does the enrich wrapper force a `MatchResult` allocation per match
   (defeating §3)? Confirm an allocation-free named-capture drain is possible or accept that URL
   (named) keeps `MatchResult` allocation.

7. **Does B actually beat re2j, or just match A?** The whole escalation is justified only if B is
   faster than both A and re2j on the four patterns. If pass-1 anchor-aware DFA degenerates (OQ-5) or
   the patterns fail OnePass (OQ-4), B could end up *slower* than A (two passes + reverse DFA) with no
   payoff. **Define a go/no-go: prototype pass-1 boundary DFA alone (bounds-only `findBoundsFrom`) and
   measure vs re2j `find()` count-drain BEFORE building pass-2/BitState/reverse-DFA.** If the
   bounds-only DFA already beats re2j, much of pass 2's complexity may be unnecessary.

8. **Interaction with the parallel capture-correctness effort.** JDK is the oracle and capture bugs
   are being fixed concurrently on the PikeVM path. B's pass-2 engines must be diffed against the
   *fixed* PikeVM, not a snapshot — sequence B after the capture-correctness fixes land, or B will
   chase a moving oracle.

9. **Empty-match / zero-width spans in pass 2.** When `[s,e)` is empty (`s==e`, e.g. `(.*)`
   matching empty at end), pass-2 capture must produce JDK's empty-match group offsets and the driver
   must advance-by-one (§5). The bounded PikeVM handles this (it's the same `runMatchResult`); OnePass
   and BitState must be checked for zero-width spans explicitly.

## 8. Recommendation / sequencing
1. **Gate first (OQ-7):** prototype the **pass-1 anchor-aware boundary DFA** with a bounds-only
   `findBoundsFrom` (no captures, no reverse DFA yet — use bounded PikeVM to confirm the END is
   right) and benchmark the **count drain** vs re2j on COMMAND/URL/SQL. This is the cheapest probe of
   the entire thesis and answers OQ-5 + OQ-7 before any reverse-DFA/BitState investment.
2. If pass-1 beats re2j: add **reverse-DFA START (B1)** and the **allocation-free bounds/groups API**
   (§3); measure again.
3. Add **pass-2 OnePass / BitState** only where OQ-4 shows they help; otherwise ship pass-1-DFA +
   bounded-PikeVM capture (smaller surface, likely already linear and allocation-disciplined).
4. Keep approach A as the eligibility floor throughout (§6). Re-run the extended findAll fuzz at every
   step; JDK is the oracle.

If the §8.1 gate fails (pass-1 DFA does not beat re2j, or the anchor-aware DFA degenerates), **abandon
B and stay on approach A** — the constant-factor gap is then not worth the two-pass + reverse-DFA
surface, and effort should go to micro-optimizing approach A's `stepChar`/closure instead.
