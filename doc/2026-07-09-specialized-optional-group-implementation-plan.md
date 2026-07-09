# `SPECIALIZED_OPTIONAL_GROUP` implementation plan

Status: **ABANDONED**. An adversarial review of this plan found that its
central reuse premise is false: `OptionalGroupBackrefBytecodeGenerator`'s
matching core has no backtracking between the optional group and the suffix
(greedy-only) and gives wrong results on exactly the pattern shape this plan
targets, plus unrelated dropped-prefix and dropped-end-anchor bugs in the
same shipped code. See
[`2026-07-09-optional-group-backref-backtracking-bug.md`](2026-07-09-optional-group-backref-backtracking-bug.md)
for the full bug report. Do not resume this plan by relaxing its scope
guards — the reuse target itself needs to be fixed or replaced first. The
rest of this document is kept as a record of the (invalidated) plan; do not
treat any of its phases as ready to execute.

Original framing follows, for history only:

Status: **plan only, not started**. Follows on from
[`2026-07-09-specialized-optional-group-investigation.md`](2026-07-09-specialized-optional-group-investigation.md)
(why `(a)?b`/`(?<x>a)?b` route to `BITSTATE_CAPTURE` today, and why that gap is
real and worsens under scale per
[`2026-07-09-benchmark-methodology-redesign.md`](2026-07-09-benchmark-methodology-redesign.md)).

## Goal

Route `(a)?b`-shaped patterns (single-literal/literal-string optional group,
followed by a single-literal-char or literal-content-group suffix) to a new,
dedicated codegen strategy that never enters `BitStateMatcher`, closing the
0.095x-0.272x-vs-JDK gap identified in the scaled benchmark.

## What already exists (evidence, not speculation)

`OptionalGroupBackrefBytecodeGenerator.generateMatchAtPosition` /
`generateMatchAtPositionForFind` (`OptionalGroupBackrefBytecodeGenerator.java:680-1189`)
already implement, as real exercised code:
- an optional capturing group matched at a **runtime** `posVar`, not a
  compile-time constant position
- group-span capture into `starts[]`/`ends[]` for that group
- a literal-char or literal-group-content suffix match immediately after it

This is driven by `detectOptionalGroupBackref` (`PatternAnalyzer.java:4334-4515`),
which builds an `OptionalGroupBackrefInfo` carrier — but only when the pattern
also contains a quantified backreference to that group
(gate at `PatternAnalyzer.java:4475`, and both call sites additionally require
`hasBackrefs` — `PatternAnalyzer.java:372`, `:782`).

Three concrete gaps block `(a)?b` (no backref) from using this path today:
1. The `hasBackrefs`/`backrefGroupNumbers.isEmpty()` gates reject any
   pattern without a backreference.
2. Content after the optional group but before a backref is categorized into
   a `middle` bucket (`PatternAnalyzer.java:4467-4469`) that the generator
   never implements (`OptionalGroupBackrefBytecodeGenerator.java:1357-1358`:
   `// TODO: Handle middle nodes`). Relaxing gate 1 alone would silently drop
   `b` in `(a)?b` into this dead bucket.
3. Char-class optional-group content is accepted by the detector's shape
   check (`isSingleCharOrCharClass`, `PatternAnalyzer.java:3780`, returns
   `true` for `CharClassNode`) but not actually handled by the generator —
   `extractLiteralChar` returns `-1` for `CharClassNode`
   (`PatternAnalyzer.java:3838`), so neither of the generator's two dispatch
   branches (`isSingleChar && literalChar >= 0` / `literalString != null`)
   fires. Real coverage today is literal-char/literal-string group content
   only.

None of these are blockers for the two benchmarked patterns
(`(a)?b`, `(?<x>a)?b` — literal-char group, literal-char suffix) — they define
this plan's v1 scope precisely.

## Design

Reuse data, share codegen, keep the new generator thin. No new pattern-info
carrier class: `OptionalGroupBackrefInfo` already has the right shape
(`prefix`, `optionalGroups`, `backrefEntries`, `suffix`, `suffixLiteralChar`,
`suffixGroupLiteral`/`suffixGroupNumber`) — for the no-backref case
`backrefEntries` and `middle` are simply always empty.

### Phase 1 — Detector: extract shared walk, add no-backref mode

File: `PatternAnalyzer.java`.

1. Rename the existing body of `detectOptionalGroupBackref` to a private
   `detectOptionalGroupCore(RegexNode ast, boolean requireBackref)`. Keep
   `detectOptionalGroupBackref(ast)` as `return detectOptionalGroupCore(ast, true);`
   — zero behavior change for existing callers/tests.
2. In the categorization loop (currently `PatternAnalyzer.java:4464-4471`),
   change the middle-vs-suffix branch to be mode-aware:
   - `requireBackref == true`: unchanged — route to `middle` until a backref
     is found, then to `suffix`.
   - `requireBackref == false`: route directly to `suffix` (there is no
     backref to wait for; `middle` stays permanently empty in this mode,
     matching the fact that it's unimplemented downstream).
3. Change the terminal gate (currently `PatternAnalyzer.java:4475`):
   ```java
   if (optionalGroups.isEmpty()) return null;
   if (requireBackref && backrefGroupNumbers.isEmpty()) return null;
   if (!requireBackref && !backrefGroupNumbers.isEmpty()) return null; // safety: never mix modes
   ```
4. Add `detectSpecializedOptionalGroup(RegexNode ast)` = 
   `return detectOptionalGroupCore(ast, false);` as the new public entry
   point.
5. **v1 scope guard** (keep the new path narrow and correct rather than
   broad and wrong): in `detectOptionalGroupCore`, when `requireBackref` is
   false, additionally require every `OptionalGroupEntry.literalChar >= 0`
   (reject `literalString != null`-only entries and reject char-class content
   for v1 — i.e. `isSingleChar && literalChar >= 0` strictly). This can be
   relaxed in a follow-up once the single-char case is proven; encoding it
   explicitly avoids silently mis-routing `(foo)?bar` or `([a-z])?b` into a
   generator that doesn't handle them (see gap 3 above — the shared codegen
   helper inherits this limitation in phase 2, so the detector must not
   promise more than the codegen delivers).

### Phase 2 — Shared codegen helper

New file: `OptionalGroupMatchCodegen.java` (package
`com.datadoghq.reggie.codegen.codegen`).

Extract `generateMatchAtPosition` and `generateMatchAtPositionForFind` out of
`OptionalGroupBackrefBytecodeGenerator` verbatim into two `static` methods on
this class, taking the same parameters they take today plus the
`OptionalGroupBackrefInfo` they close over. No behavior change — this is a
pure move.

`OptionalGroupBackrefBytecodeGenerator` calls
`OptionalGroupMatchCodegen.generateMatchAtPosition(...)` /
`...ForFind(...)` instead of its own private methods. Run
`OptionalGroupBackrefTest` (or equivalent existing suite — confirm exact name
in phase 6) after this move with no other changes, to prove it's behavior-
preserving before phase 3 builds on it.

The backref-matching block inside the shared method (currently
`OptionalGroupBackrefBytecodeGenerator.java:787-874`) is already a `for`
loop over `info.backrefEntries` — zero iterations when the list is empty, so
it requires no conditional guard to skip cleanly for the no-backref caller.

### Phase 3 — New generator

New file: `SpecializedOptionalGroupBytecodeGenerator.java`.

Thin wrapper: constructor takes `OptionalGroupBackrefInfo` + class name (same
as `OptionalGroupBackrefBytecodeGenerator`'s constructor). Implements
`generate(ClassWriter cw)` and delegates the actual per-position matching
logic to `OptionalGroupMatchCodegen`. Method skeletons
(`matches`/`find`/`findFrom`/`match`/`findMatch`/`findMatchFrom`/
`findBoundsFrom`/`matchesBounded`/`matchBounded`) are modeled directly on
`OptionalGroupBackrefBytecodeGenerator`'s existing versions of the same
methods (`OptionalGroupBackrefBytecodeGenerator.java:106-1206, 1515-1568`) —
copy-and-simplify, since the no-backref case doesn't need the
`backrefEntries`/`middle` plumbing those methods thread through.

Estimated size: notably smaller than 873-1278 lines, since the hard part
(runtime-pos-tracked optional-group matching) lives in the shared helper, not
duplicated here.

### Phase 4 — Routing

File: `PatternAnalyzer.java`.

Add `SPECIALIZED_OPTIONAL_GROUP` to the `MatchingStrategy` enum
(near `OPTIONAL_GROUP_BACKREF`, `PatternAnalyzer.java:3350`).

Insert the check **before** the B16 guard block, mirroring the existing
precedent of checking a specialized detector ahead of the general
ambiguity/fallback cascade (`PatternAnalyzer.java:782-794` does exactly this
for the backref case, just gated on `hasBackrefs`). Exact insertion point:
immediately before `PatternAnalyzer.java:1131`
(`FallbackPatternDetector.hasNullableOuterQuantifierOnCapturingGroup(ast)`),
inside the same `if (!hasNamedGroups(ast) && !hasAnchorInNfa(nfa))` block —
**no**, actually outside/before that block, since `SPECIALIZED_OPTIONAL_GROUP`
should apply to named groups too (`(?<x>a)?b`) and doesn't depend on the NFA
capture-ambiguity precondition that guards the B16-and-siblings block. Add it
as its own top-level check, guarded only by `!hasBackrefs` (this detector's
own internal gate already enforces "no backref" via
`detectOptionalGroupCore`'s `requireBackref=false` mode, but checking
`hasBackrefs` first avoids the wasted detector call on patterns that will
never match):

```java
if (!hasBackrefs) {
  OptionalGroupBackrefInfo specOptGroupInfo = detectSpecializedOptionalGroup(ast);
  if (specOptGroupInfo != null) {
    return new MatchingStrategyResult(
        MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        null, specOptGroupInfo, false, requiredLiterals);
  }
}
```

Placed before the B16 check so `(a)?b`/`(?<x>a)?b` never reach it — same
"narrow detector wins before falling into the general guard cascade" shape
already used for `OPTIONAL_GROUP_BACKREF`.

### Phase 5 — Runtime dispatch

File: `RuntimeCompiler.java`.

Add a `case SPECIALIZED_OPTIONAL_GROUP:` alongside the existing
`case OPTIONAL_GROUP_BACKREF:` (`RuntimeCompiler.java:1235-1242`), casting
`result.patternInfo` to `OptionalGroupBackrefInfo` and constructing
`SpecializedOptionalGroupBytecodeGenerator` instead. No changes needed to
`FallbackPatternDetector.needsFallback` — verified none of its
strategy-gated checks reference the new enum value, so it's a no-op pass-
through for this strategy (consistent with how other specialized strategies
like `SPECIALIZED_FIXED_SEQUENCE` are also absent from that file). Named-group
enrichment (`NameEnrichingMatcher`) is applied generically based on
`embedsNameMap()`, not per-strategy — no special-casing needed there either.

### Phase 6 — Tests

New test class mirroring the existing `OptionalGroupBackref*Test` naming
convention (confirm exact file name via
`find . -iname '*OptionalGroupBackref*Test*'` before writing — not verified
in this plan). Minimum coverage:
- `(a)?b` on `"b"`, `"ab"`, `"xab"` (find), group span correctness in both
  branches.
- `(?<x>a)?b` — named-group variant, verify name-based group access works
  through the generic `NameEnrichingMatcher` path.
- Boundary: `(a)?b` on empty string, on `"a"` alone (no suffix match — must
  fail), on strings where a greedy match-group-then-fail-suffix must
  backtrack to no-group (e.g. `"aab"` — first `a` should NOT be consumed by
  the optional group if doing so would make the second `a` mismatch `b`;
  confirm exact PCRE-correct expected result before asserting).
- Multiple non-adjacent occurrences via `find()`/`findAll()`.
- Cross-check: run the existing `OptionalGroupBackrefTest` (or equivalent)
  suite after phase 1/2 to confirm zero regressions from the extraction.
- Regenerate the routing decision for `(a)?b` and assert it now selects
  `SPECIALIZED_OPTIONAL_GROUP` instead of `PIKEVM_CAPTURE`/`BITSTATE_CAPTURE`.

### Phase 7 — Benchmark verification

Re-run `AllStrategyVsJdkBenchmark`'s `optimizedNfa`/`pikevmCapture` cases (or
add a `specializedOptionalGroup` pair if the benchmark's strategy-to-pattern
mapping needs updating since the pattern now routes elsewhere) at all three
scales. Confirm ratio improves from the current 0.157x/0.095x and
0.272x/0.145x baselines — target is >=1x (beat JDK), not just "less bad."

### Phase 8 — Docs

Update `2026-07-09-specialized-optional-group-investigation.md`'s
"Conclusion" section to point at this plan and, once implemented, at the
landed commit — do not rewrite the investigation's findings themselves
(they remain accurate history of what was true when written).

## Sequencing / risk notes

- Phases 1-2 are pure refactors (rename + extract-with-no-behavior-change) —
  low risk, should be committed/verified independently before phase 3 adds
  new behavior, so any regression is attributable to a single phase.
- Phase 4's insertion point matters: it must run before the B16 check but
  after this file's earlier fixed-shape checks (`detectFixedSequence`,
  `detectBoundedQuantifierSequence`, etc. at `PatternAnalyzer.java:764-779`)
  in case any of those would otherwise also claim `(a)?b` — not observed in
  the code read so far, but worth a quick check when implementing, since
  `detectFixedSequence`/`detectBoundedQuantifierSequence` are earlier in the
  cascade and take precedence by construction.
- v1's explicit literal-char-only scope guard (phase 1, step 5) is the main
  safety valve against over-promising; do not relax it in the same change
  that lands v1 — relax it later, backed by its own tests, once the
  literal-char case is verified correct and fast.
