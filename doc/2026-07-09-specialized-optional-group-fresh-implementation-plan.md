# `SPECIALIZED_OPTIONAL_GROUP` implementation plan (from scratch, no reuse)

Status: **plan only, not started**. Supersedes the abandoned
[`2026-07-09-specialized-optional-group-implementation-plan.md`](2026-07-09-specialized-optional-group-implementation-plan.md),
whose reuse premise was disproven — see
[`2026-07-09-optional-group-backref-backtracking-bug.md`](2026-07-09-optional-group-backref-backtracking-bug.md)
for the three confirmed bugs in the code that plan tried to reuse.

**Revised after adversarial review of this plan** (see revision notes marked
below) — one real algorithm bug was found and fixed in the sketch itself
(matches() vs find() full-consumption conflation), plus several missing
steps (a project-wide strategy-coverage meta-test, the `PatternInfo`
interface contract, `PatternDebugger` output) and citation corrections.

## Goal (unchanged)

Route `(a)?b`-shaped patterns to a dedicated codegen strategy that never
enters `BitStateMatcher`, closing the 0.095x-0.272x-vs-JDK gap from the
scaled benchmark
([`2026-07-09-benchmark-methodology-redesign.md`](2026-07-09-benchmark-methodology-redesign.md)).

## Why fresh, not reuse

`OptionalGroupBackrefBytecodeGenerator`'s matching core is greedy-only (no
retry when the suffix fails after the optional group matched — Bug 1 in the
linked report) and separately drops prefix content and end-anchors (Bugs 2,
3). None of these are backref-specific; reusing that code would import all
three into the new strategy. Writing the two-branch decision directly is
less code than the extraction-and-adaptation the abandoned plan proposed,
and avoids depending on code with unresolved defects.

## v1 scope — explicit, deliberately narrow

Pattern shape, as a `ConcatNode`:

```
(anchor-start)? (optional-group) (suffix-literal-char) (anchor-end)?
```

Included:
- exactly one optional capturing group (`min=0,max=1` `QuantifierNode`
  wrapping a capturing `GroupNode`), content restricted to a **single
  literal char** (not char class, not literal string, not alternation).
- suffix restricted to a **single literal char** (not string, not group).
- optional `^`/`\A` before the group and/or `$`/`\z` after the suffix,
  correctly enforced (this is deliberately in scope from the start — Bug 3
  was "computed but never enforced," so v1 enforces what it claims to
  support rather than silently accepting and ignoring anchors).
- named capturing group (`(?<x>a)?b`) — handled identically to unnamed;
  name-to-index resolution is generic. **Correction from review**: the
  generic wrap-on-construction logic is at `RuntimeCompiler.java:667-678`
  (specifically the `if (!matcher.embedsNameMap())` block around line 676),
  reached by any strategy dispatched through the main
  `switch (result.strategy)` (`RuntimeCompiler.java:954`) — not line 724,
  which is a narrow helper (`tryCompileLinearTokenSequence`) specific to
  `LinearTokenSequenceMatcher` and irrelevant here. Re-verify both line
  numbers at implementation time.

Explicitly excluded from v1 (detector returns `null`, pattern falls through
to existing routing unchanged):
- any AST content before the optional group other than a start anchor
  (no prefix support — this is what caused Bug 2; simplest fix is "don't
  have a prefix feature" rather than "have one and get it wrong").
- more than one optional group in the pattern.
- char-class or multi-char-literal content in the optional group, or a
  multi-char/char-class suffix.
- any backreference anywhere in the pattern (that's `OPTIONAL_GROUP_BACKREF`'s
  territory, unaffected by this work).
- `CASE_INSENSITIVE`/`UNICODE_CASE` flags (single-literal-char matching
  under those flags needs its own char-vs-char comparison logic; deferred
  rather than silently mishandled).

Each exclusion is enforced by an explicit check in the detector, not by
absence of a code path — the goal is "returns null (safe, falls through)"
for anything outside scope, never "silently produces a wrong answer for
something that looks in-scope."

## Data model

New, minimal carrier — do **not** reuse `OptionalGroupBackrefInfo`. Bugs 2
and 3 were both "field exists, nothing reads it"; a purpose-built class with
only the fields this generator actually uses makes that bug class
structurally impossible (no `prefix`/`middle`/`backrefEntries` fields to
silently ignore).

```java
final class SpecializedOptionalGroupInfo implements PatternInfo {
  final int groupNumber;      // the optional group's capture index (1..n)
  final char groupChar;       // literal char the optional group matches
  final char suffixChar;      // literal char required immediately after
  final boolean hasStartAnchor;
  final boolean hasEndAnchor;

  @Override
  public int structuralHashCode() { ... } // required by PatternInfo; see below
}
```

**Correction from review**: every existing `*Info` carrier implements
`PatternInfo` (`PatternAnalyzer.java`'s analysis package,
`PatternInfo.java:22-31` defines the single required method
`structuralHashCode()`, used for bytecode-cache keying — "two patterns with
the same structural hash should generate equivalent bytecode modulo literal
values/charsets"). `SpecializedOptionalGroupInfo` must implement this too;
the plan's original sketch omitted it. Structural hash should exclude
`groupChar`/`suffixChar` (literal *values*) but include `groupNumber`,
`hasStartAnchor`, `hasEndAnchor` (structural shape) — mirror an existing
simple `*Info` class's `structuralHashCode()` implementation rather than
inventing the convention from scratch.

`groupCount` for `MatchResultImpl` construction is always 1 (the optional
group is the only capturing group in scope for v1) — no group-position
bookkeeping loop needed, unlike the reused code's generic multi-element walk.

## Detector

New method `detectSpecializedOptionalGroup(RegexNode ast)` in
`PatternAnalyzer.java`, independent of `detectOptionalGroupBackref` (no
shared parsing helper this time — the shapes are different enough, and
independence means this work cannot destabilize the shipped backref
strategy while it still has open bugs).

Sketch:
1. Reject immediately if `hasBackreferences(ast)`.
2. Require top-level `ConcatNode`. Walk children left to right:
   - optional leading `AnchorNode` (START/STRING_START) → note
     `hasStartAnchor`, consume it.
   - next child must be a `QuantifierNode(min=0,max=1)` wrapping a capturing
     `GroupNode` whose child is a `LiteralNode` → capture `groupNumber`,
     `groupChar`. Anything else (no group here, group not capturing, group
     content not a single literal, quantifier bounds different) → return
     `null`.
   - next child must be a `LiteralNode` → `suffixChar`. Anything else
     (including a second optional group, another literal, a nested group)
     → return `null`.
   - optional trailing `AnchorNode` (END/STRING_END) → note `hasEndAnchor`,
     consume it.
   - if any children remain unconsumed → return `null` (this is what makes
     "no prefix, exactly one optional group" structural rather than
     best-effort).
3. Return the populated `SpecializedOptionalGroupInfo`.

## Codegen

New file `SpecializedOptionalGroupBytecodeGenerator.java`. Implements the
two-branch decision directly — this is the part the abandoned plan got
wrong by trying to avoid writing it:

```
tryMatchAt(pos, requireFullConsumption):
    if hasStartAnchor && pos != 0: no match at this position
    if pos < len && input.charAt(pos) == groupChar:
        afterGroup = pos + 1
        if afterGroup < len && input.charAt(afterGroup) == suffixChar:
            afterSuffix = afterGroup + 1
            if !requireFullConsumption || afterSuffix == len:
                MATCH: group1 = [pos, afterGroup), overall = [pos, afterSuffix)
        // suffix failed after taking the group — fall through, do NOT return here
    if pos < len && input.charAt(pos) == suffixChar:
        afterSuffix = pos + 1
        if !requireFullConsumption || afterSuffix == len:
            MATCH: group1 = unset (-1,-1), overall = [pos, afterSuffix)
    no match at this position
```

**Correction from review (real bug, caught by adversarial review before any
code was written)**: the original sketch used `!hasEndAnchor || afterSuffix
== len` as the completion condition for *every* caller, conflating two
different requirements:
- `matches()`/`match()` must **always** require full-string consumption —
  that's what `matches()` means, independent of whether the pattern has an
  explicit `$`. The original sketch would have made `matches()` on `(a)?b`
  wrongly accept `"abx"` (no `$` in the pattern → `hasEndAnchor` false →
  sketch would stop requiring `afterSuffix == len`).
- `find()`/`findFrom()` only require full consumption when `hasEndAnchor` is
  true (an explicit `$`); otherwise they accept a match ending mid-string.

Fixed by parameterizing `tryMatchAt` with `requireFullConsumption`:
callers from `generateMatchesMethod`/`generateMatchMethod` pass `true`
unconditionally; callers from `generateFindMethod`/`generateFindFromMethod`
pass `hasEndAnchor`. Get this distinction right in the actual bytecode (two
call sites into the same shared per-position check, with a different value
plugged into the completion condition), not by duplicating the whole method
body per caller.

The explicit "fall through, do NOT return here" comment marks exactly the
retry Bug 1 was missing — call this out in the generator's own class
javadoc so a future reader can see the bug this class was written to avoid.

`find()`'s outer scan-position loop: when `hasStartAnchor` is true, don't
rely on `tryMatchAt`'s internal `pos != 0` check alone (which would still be
correct, just wasteful — it would scan and fail every position after 0).
Restrict `find()`'s loop to try only `pos == start` directly in the
generated code, since position 0 (or `start`) is the only position that can
ever match. This is a perf note, not a correctness one, but is trivial to
get right at generation time and the whole point of this strategy is
beating JDK on speed.

Methods to generate for real (v1): `matches`, `find`, `findFrom`, `match`,
`findMatch`, `findMatchFrom`. Do **not** override `matchesBounded`,
`matchBounded`, `findBoundsFrom` in v1 — confirmed
(`ReggieMatcher.java:311` for `matchesBounded`, `:332` for `matchBounded`,
`:485` for `findBoundsFrom` — corrected from the plan's original imprecise
"200-260ish" citation) that the base class's default implementations for
these three are correct (they delegate to `matches`/`match`/`findMatchFrom`
respectively, just with an extra substring allocation), so leaving them
un-overridden is a deliberate, documented perf deferral, not a silent
correctness gap. State this explicitly in the class javadoc so it isn't
mistaken for an oversight later.

## Routing

New `MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP` enum value.

**Correction from review**: "insert before the B16 guard cascade" is an
imprecise locator — the routing cascade between the earlier fixed-shape
checks and B16 itself (roughly `PatternAnalyzer.java:872-1131` as of the
last read; re-verify at implementation time, these lines will have drifted)
contains several intervening checks (a `OnePass` attempt, a
`groupCount > 0` block, a DFA-construction try block, an
`isCaptureAmbiguous` guard) before B16 is even reached. Do not place the new
check relative to "the B16 guard" as a single landmark; place it as its own
top-level `if (!hasBackrefs) { ... }` block immediately after the last of
this file's earlier fixed-shape detector checks
(`detectFixedSequence`/`detectBoundedQuantifierSequence`) and before any of
the intervening OnePass/DFA/capture-ambiguity machinery runs at all — i.e.
as early in `doAnalyze` as the earlier specialized detectors, not merely
"somewhere before B16." Confirm at implementation time, by reading the
surrounding code directly rather than trusting this plan's prose, exactly
which checks currently run first for `(a)?b` today (trace it, don't assume),
and that the new check is reachable for both named and unnamed groups — the
abandoned plan's insertion point had this ambiguous/possibly-unreachable for
the named case (a review finding on that plan).

## Dispatch

`RuntimeCompiler.java`: new `case SPECIALIZED_OPTIONAL_GROUP:` constructing
`SpecializedOptionalGroupBytecodeGenerator` from
`SpecializedOptionalGroupInfo`. Also required (missed in the original
sketch of this plan, caught by review):
- `PatternDebugger.java`'s strategy-specific-details `switch` (around lines
  59-126) has no `default` case and prints nothing for unrecognized
  strategies — add a `case SPECIALIZED_OPTIONAL_GROUP:` there too so
  `debugPattern` output is useful for the new strategy, not silently blank.
- **`StrategyCorrectnessMetaTest.everyStrategyHasRoutableRepresentative`**
  (`reggie-runtime/src/test/java/.../StrategyCorrectnessMetaTest.java:347`):
  this test asserts every `MatchingStrategy` enum value has a registered
  representative pattern in `strategyPatterns()`, and fails at build time
  otherwise. Adding the enum value without adding an entry there will break
  the build — treat this as a required step, not an optional nice-to-have,
  and do it in the same change that adds the enum value.

No other changes needed (confirmed `FallbackPatternDetector.needsFallback`
has no strategy-gated checks that would apply to a new enum value).

## Tests

Write tests **before or alongside each phase**, not batched at the end (the
abandoned plan's phase 6 test-last ordering was itself a confirmed finding).
**Correction from review**: `AGENTS.md:50-52` is the "Adding a regex
feature" workflow bullet, which lists a single linear pipeline ending in
"... → integration test → benchmark → ..." — it does not itself mandate
per-phase test-first ordering. The actual "failing test first" language is
`AGENTS.md:53`, under the *separate* "Fixing a bug" bullet. Since this work
is a new feature, not a bug fix, `AGENTS.md`'s own documented workflow for
this case is the phase-then-integration-test pipeline, not test-first — the
test-before-code preference stated here is this plan's own choice (a
reasonable one, given the reuse plan's problems), not something `AGENTS.md`
independently requires for feature work. State it as our own precaution,
not as an `AGENTS.md` mandate.

Minimum matrix, deliberately targeting the three bug shapes so they can't
silently reappear:
- **Bug-1 shape** (group content overlaps suffix): `(a)?a` on `"a"` (match,
  group unset), `"aa"` (match, group=`[0,1)`), `(a)?b` on `"aab"` (the
  original boundary case).
- **No-prefix enforcement**: `x(a)?b` — detector must return `null` (falls
  through to whatever strategy currently handles it; assert it is NOT
  `SPECIALIZED_OPTIONAL_GROUP` and that the result is still correct via
  whatever path it does take).
- **Anchors**: `^(a)?b$` against a grid including `"ab"`, `"xab"`, `"abx"`,
  `""`, compared directly against `java.util.regex.Pattern` — this is the
  exact shape Bug 3 got wrong, so assert equality with JDK, not just "some
  plausible answer."
- **Named group**: `(?<x>a)?b`, verify `group("x")` in both the
  group-present and group-absent branches.
- **Multiple matches**: repeated `find()`/`findAll()` over an input with
  several non-overlapping occurrences.
- **Out-of-scope shapes fall through correctly**: `(ab)?c` (literal-string
  group — not v1), `([a-z])?b` (char class — not v1), `(a)?(b)?c` (two
  optional groups — not v1). Assert `detectSpecializedOptionalGroup` returns
  `null` for each and that matching still produces correct results via
  whatever strategy they do route to (regression check, not just a routing
  assertion).
- Routing assertion: `(a)?b` and `(?<x>a)?b` now select
  `SPECIALIZED_OPTIONAL_GROUP`, not `PIKEVM_CAPTURE`/`BITSTATE_CAPTURE`.

## Benchmark verification

Re-run `AllStrategyVsJdkBenchmark`'s `optimizedNfa`/`pikevmCapture` cases at
all three scales once routing changes. Target: ratio >= 1x (beat JDK) at all
three scales, not just an improvement over the 0.095x-0.272x baseline.

## Process note

Run this plan through the same adversarial-review workflow used on the
abandoned one *before* starting implementation — it caught a false premise
last time at zero cost to production code; do the same check here before
writing `SpecializedOptionalGroupBytecodeGenerator.java`.
