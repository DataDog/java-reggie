# Design: structurally-pinned backreference boundary detection (single forward pass, no backtracking)

Date: 2026-07-06
Status: design, not yet implemented. Grounded in main repo, branch
`perf/eliminate-slow-paths`.

## 1. Problem statement

Backreference patterns whose group boundary is provably unambiguous from the grammar alone
— the group's content charset is disjoint from what immediately follows it — should never
need a retry/backtracking loop at match time: the first candidate boundary found by a plain
forward scan is *provably* the only one that could ever satisfy the pattern. Today, none of
reggie's backreference strategies exploit this:

- `SPECIALIZED_BACKREFERENCE` / HTML tag-close `<(\w+)>.*</\1>`:
  `BackreferenceBytecodeGenerator.generateHTMLTagFindFromMethod` generates a retry loop over
  `indexOf("</", pos)` occurrences (doc example, `BackreferenceBytecodeGenerator.java:68-83`)
  — it probes each `"</"` and retries on backref mismatch, even though the `\w+` group's
  content and the literal `<`/`/` delimiters that follow it are disjoint character sets and
  the boundary is therefore never actually ambiguous.
- `SPECIALIZED_BACKREFERENCE` / repeated-word `\b(\w+)\s+\1\b`:
  `generateRepeatedWordFindFromMethod` (`BackreferenceBytecodeGenerator.java:898-1037`) wraps
  the scan in an outer `for (start = startPos; ...)` loop with a `continueLoop` label that
  restarts word-boundary scanning from `start+1` on any failure — a linear rescan, not a
  computed jump. (Its `matches()` counterpart, `generateRepeatedWordMatchesMethod`, is
  already a clean single forward pass with no retry — because the pattern is anchored to a
  full match, there is only one candidate boundary to consider in the first place.)
- `VARIABLE_CAPTURE_BACKREF` `(.*)\d+\1`:
  `VariableCaptureBackrefBytecodeGenerator.java:47-73` is explicitly documented as
  "Longest-First Backtracking" — `while (groupEnd >= groupMinLen) { ...; groupEnd--; }` — a
  genuine O(n²)-ish backtracking loop over capture length, guarded only by a ReDoS iteration
  cap (`BacktrackConfig.checkLimit`, line 57), not by any boundary-disjointness proof.
- `FIXED_REPETITION_BACKREF` `(a)\1{8,}`:
  `FixedRepetitionBackrefBytecodeGenerator.java:40-66` is the closest existing strategy to
  the target shape already — a single forward verification loop, no retry — but it is a
  counted greedy-match loop over repetitions, not a general boundary-pinning proof, and it
  explicitly declines (falls through to `OPTIMIZED_NFA_WITH_BACKREFS`) the instant a non-empty
  suffix follows the backreference (`PatternAnalyzer.java:794`, comment "B6"; regression test
  `PikeVMRoutingTest.java:244-251`).

None of the four detectors (`detectHTMLTagPattern`, `detectRepeatedWordPattern`,
`detectFixedRepetitionBackref`, `detectVariableCaptureBackref`) compute or exploit
"is the group's content-charset disjoint from what follows it" as a general condition —
each is a hand-hardcoded pattern-shape match, and the codegen for even the two shapes that
happen to have this property (tag-close, repeated-word) still emits retry/rescan code for
their `find`/`findFrom` variants.

## 2. What already exists to build on

`PatternAnalyzer.java` already has a FIRST-set / follow-set / disjointness toolkit, built
for a *different* purpose (proving a greedy capturing group doesn't need backtracking
against the DFA path — not backreferences), and not currently wired into any backreference
detector:

- `getFirstCharSet(RegexNode node)` (`PatternAnalyzer.java:2527-2566`) — the CharSet of the
  first character a node/subtree can match; handles `CharClassNode`, `LiteralNode`,
  `GroupNode`, `QuantifierNode`, `ConcatNode`, `AlternationNode`, `AnchorNode`.
- `getSuffixFirstCharSetSkippingNullable(ConcatNode concat, int fromIndex)`
  (`PatternAnalyzer.java:2590-2603`) — a genuine follow-set computation: unions the
  first-char-sets of successive concat children from `fromIndex` onward, skipping past
  nullable children.
- `getGreedyGroupCharSet(RegexNode node)` (`PatternAnalyzer.java:2460-2492`) and
  `getNodeCharSet(RegexNode node)` (`PatternAnalyzer.java:2494-2521`) — the full CharSet a
  node/group can match.
- `requiresBacktrackingForGroups(RegexNode node)` (`PatternAnalyzer.java:2427-2454`,
  javadoc at 2345-2355) — **this is exactly the "disjoint follow-set ⇒ no backtracking
  needed" logic this design needs**, already implemented, just scoped to greedy-capture
  (not backreference) patterns: "if the greedy group's char set does NOT overlap with the
  following element's first char, then the DFA can handle it correctly... `([a-z]+)@...`: no
  backtracking needed... `([bc]*)(c+d)`: overlaps, backtracking needed." Built on
  `CharSet.intersects`/`CharSet.isDisjoint` (`reggie-codegen/.../automaton/CharSet.java:592`).

**What's missing**: none of this is applied to the question "is a *backreferenced* group's
content charset disjoint from what follows the backreference (or from what follows the
group's own closing position, for the group-boundary case)". That's the new predicate this
design adds — a reuse/extension of existing utilities, not new automata theory.

## 3. Proposed design

### 3.1 New compile-time predicate: `hasPinnedBackrefBoundary`

Add a detector (naturally placed alongside the existing backref detectors in
`PatternAnalyzer.java`, near `detectFixedRepetitionBackref`/`detectVariableCaptureBackref`)
that, given a capturing group `G` referenced by a backreference `\N` later in the same
concat, checks:

1. `G`'s content charset (`getGreedyGroupCharSet`/`getNodeCharSet`) is disjoint
   (`CharSet.isDisjoint`) from the first-char-set of whatever immediately follows `G` in the
   concat (`getSuffixFirstCharSetSkippingNullable`, starting just after `G`) — i.e. the
   group's *own* closing boundary is unambiguous, the same condition
   `requiresBacktrackingForGroups` already proves for the non-backreference case.
2. (For the tag-close/delimiter shape specifically) if there's separator content between `G`
   and `\N`, that separator's charset is likewise disjoint from `G`'s own charset, so the
   scan-to-boundary logic can't be confused by the group's content reappearing inside the
   separator.

If both hold, the backreference's satisfaction becomes a pure post-hoc check: scan forward
to the (now provably unique) group-closing boundary, capture `G`, then do a plain `O(k)`
`regionMatches`/equality check against the backreference site — no retry, no rescan, no
backtracking loop, because there is no second candidate boundary to fall back to.

This generalizes what `detectHTMLTagPattern`/`detectRepeatedWordPattern` already hardcode
for two specific shapes, into a shape-independent structural test — the disjointness
argument for `<(\w+)>` (word-chars vs. literal `<`/`/`) and for `(\w+)\s+` (word-chars vs.
whitespace) is literally the general disjointness predicate applied to those two literal
grammars.

### 3.2 Routing

Insert the new predicate into `PatternAnalyzer`'s existing backreference routing chain
(`PatternAnalyzer.java:757-836`), checked *before* `VARIABLE_CAPTURE_BACKREF`'s detector
(since a pinned-boundary pattern is a strict subset of what `VARIABLE_CAPTURE_BACKREF`
currently catches, and should be routed to the new faster strategy instead) and alongside
`SPECIALIZED_BACKREFERENCE`'s hardcoded shapes (which the new general predicate should
subsume for tag-close and repeated-word specifically, once verified equivalent — see §5).

New strategy name (tentative): `PINNED_BACKREFERENCE` — added to the `MatchingStrategy`
enum (`PatternAnalyzer.java:3185-3227`) alongside the existing backref strategies.

### 3.3 Codegen

New generator (or an extension of `BackreferenceBytecodeGenerator`) emitting, for both
`matches()` and the unanchored `find()`/`findFrom()` variants:

1. Forward-scan `G`'s content to its charset boundary (single pass, no retry — this is safe
   *because* of the disjointness proof, not despite it).
2. Scan the separator (if any) similarly.
3. Single `regionMatches`/char-by-char equality check of length `G.length()` against the
   backreference site.
4. On mismatch: for `matches()`/anchored contexts, fail immediately (there is no other
   candidate — this is what `generateRepeatedWordMatchesMethod` already does correctly
   today). For unanchored `find()`, advance the *outer* candidate start position past the
   failed attempt using the existing seed/prefilter machinery
   (`computeFirstByteFilter`/`nextSeed`), not a rescan-from-`start+1` retry loop — because a
   fresh `G` boundary at a different start position is a genuinely different candidate, not
   a retry of the same one.

This directly replaces the `indexOf("</", pos)`-retry loop in
`generateHTMLTagFindFromMethod` and the `continueLoop`-rescan in
`generateRepeatedWordFindFromMethod` with single-pass code, and gives `FIXED_REPETITION_BACKREF`-shaped
patterns *with* a non-empty suffix (currently declined per B6,
`PatternAnalyzer.java:794`) a real strategy to route to instead of falling all the way to
`OPTIMIZED_NFA_WITH_BACKREFS`, provided the suffix is disjoint from the repeated unit's
charset.

## 4. `FallbackPatternDetector` interaction

The three existing danger-condition detectors in `FallbackPatternDetector.java` — cross-alternative
backref (`hasCrossAlternativeBackref`, lines 575-615), backref to an ambiguously-nullable
group (`hasAmbiguouslyNullableBackrefGroup`, lines 720-729), and backref to a nullable group
nested in a capturing group (`hasNullableBackrefInsideCapturingGroup`, lines 652-658) — must
still run and exclude the new `PINNED_BACKREFERENCE` strategy exactly as they do today for
the existing native strategies (`AGENTS.md:778-781`). None of these conditions are
implied by or contradict the disjointness predicate — a pattern can have a disjoint
boundary and still be, say, cross-alternative-ambiguous, so both checks are needed and
should be ANDed, not treated as alternatives.

## 5. Correctness baseline to preserve, and equivalence to verify

- `RepeatedWordPatternTest.java`, `FixedRepetitionBackrefTest.java`,
  `FixedRepetitionBackrefMatchResultTest.java`, `VariableCaptureBackrefTest.java`,
  `VariableCaptureBackrefMatchResultTest.java` (all
  `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/`) — must continue to pass
  unchanged; any pattern that currently routes to `SPECIALIZED_BACKREFERENCE`/
  `FIXED_REPETITION_BACKREF`/`VARIABLE_CAPTURE_BACKREF` and now instead routes to
  `PINNED_BACKREFERENCE` must produce byte-identical match/capture results.
- `CrossAltBackrefTest.java`, `NullableBackrefTest.java`, `BackrefDigitAmbiguityTest.java` —
  `FallbackPatternDetector` danger-condition regressions; must still correctly exclude the
  new strategy where applicable.
- `PikeVMRoutingTest.java:244-251` ("B6: FIXED_REPETITION_BACKREF declined when suffix is
  non-empty") — this exact case (`(a)\1{8,}suffix` where suffix overlaps `a`'s charset)
  must still correctly fall through past `PINNED_BACKREFERENCE` if the disjointness test
  fails; add a companion case where the suffix *is* disjoint and confirm it now routes to
  `PINNED_BACKREFERENCE` instead of `OPTIMIZED_NFA_WITH_BACKREFS`.
- `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/StrategySelectionExtendedTest.java`,
  `PatternRoutingPropertyTest.java`/`pbt/PatternRoutingPropertyBasedTest.java` — must be
  extended to confirm the new predicate doesn't steal patterns that genuinely need
  backtracking (i.e., false positives on disjointness are a correctness bug, not just a
  missed optimization — the disjointness proof must be conservative: default to "not pinned"
  on any uncertainty in `getFirstCharSet`/`getSuffixFirstCharSetSkippingNullable`, matching
  how `requiresBacktrackingForGroups` already defaults to `null` on undeterminable cases).
- New unit tests specifically proving equivalence between the new general predicate and the
  two existing hardcoded shapes (`detectHTMLTagPattern`, `detectRepeatedWordPattern`) before
  those hardcoded detectors are considered for removal/subsumption — this design proposes
  adding the general predicate as a *new*, broader strategy; retiring the two hardcoded
  detectors is a follow-up decision, not part of this design, and should only happen once
  the general predicate is proven to reproduce their exact behavior on their existing test
  suites.

## 6. Explicitly out of scope

- A general polynomial algorithm for k≥2 interacting/unpinned backreferences — per the
  research, no such algorithm is known to exist (2026 SETH-based conditional lower bounds);
  `VARIABLE_CAPTURE_BACKREF`'s existing bounded backtracking remains the strategy for the
  genuinely-ambiguous-boundary case (e.g. `(["'])(?:\\\1|.)*?\1`).
- Removing/deprecating `SPECIALIZED_BACKREFERENCE`'s hardcoded detectors — see §5, a
  follow-up decision, not this design.
- Any change to `LINEAR_BACKREFERENCE`, `OPTIONAL_GROUP_BACKREF`, or
  `OPTIMIZED_NFA_WITH_BACKREFS` routing/codegen beyond the new strategy being inserted ahead
  of `VARIABLE_CAPTURE_BACKREF` in the decision order.
