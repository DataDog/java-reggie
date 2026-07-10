# `OptionalGroupBackrefBytecodeGenerator` correctness bugs

Status: **fixed**. Found while evaluating whether
`OPTIONAL_GROUP_BACKREF`'s matching core could be reused for a new
backref-free strategy (see the now-abandoned
[`2026-07-09-specialized-optional-group-implementation-plan.md`](2026-07-09-specialized-optional-group-implementation-plan.md)).
These bugs are in shipped code (`MatchingStrategy.OPTIONAL_GROUP_BACKREF`)
and are independent of that abandoned plan — they affect any pattern routed
to this strategy today.

Generator: `reggie-codegen/.../codegen/OptionalGroupBackrefBytecodeGenerator.java`.
Detector: `PatternAnalyzer.detectOptionalGroupBackref` (`PatternAnalyzer.java:4334-4529`).

## Bug 1 (high): no backtracking between the optional group and the suffix

`generateMatchAtPosition`/`generateMatchAtPositionForFind`
(`OptionalGroupBackrefBytecodeGenerator.java:680-786` for the optional-group
match, `:877-926` for the suffix match) greedily consume the optional
group's literal content and never provide a path back to the
group-did-not-participate branch if the suffix match subsequently fails.
Correct PCRE/JDK semantics require exactly that backtrack (try group
present; if the rest of the pattern then fails, retry with the group
absent).

Reproduced on the *existing* shipped strategy using a backref whose count
can be satisfied by zero repetitions, so it exercises this same optional-
group-then-continuation code path: `(a)?a\1{0,3}` on input `"a"`.
- JDK: group 1 is unmatched (`[-1,-1)`) after backtracking from the failed
  greedy attempt to the skip-group branch.
- Reggie: group 1 is reported as `[0,1)` — the greedy attempt's capture is
  never cleared when the match ultimately succeeds via the skip-group path.

This is exactly the class of bug `PatternAnalyzer`'s B16 guard
(`FallbackPatternDetector.hasNullableOuterQuantifierOnCapturingGroup`,
`FallbackPatternDetector.java:1904-1924`) exists to catch for other
strategies — `OPTIONAL_GROUP_BACKREF` is not currently subject to that guard
(it's checked before B16 in the routing cascade,
`PatternAnalyzer.java:372-382`, `:782-794`) and has no equivalent protection
of its own.

## Bug 2 (high): `prefix` is parsed but never matched

The detector collects any AST content before the first optional group into
`OptionalGroupBackrefInfo.prefix` (`PatternAnalyzer.java:4376`, populated at
`:4466`), but neither `generateMatchAtPosition` nor
`generateMatchAtPositionForFind` ever reads it — confirmed by grep, zero
references to `.prefix` in the generator.

Reproduced: `x(a)?\1b` (prefix `x`, optional group `a`, backref, suffix `b`).
- `"aab"` (no `x` prefix — JDK: no match): Reggie matches.
- `"xaab"` (`x` prefix present — JDK: match): Reggie fails to match.

The prefix is silently dropped in both directions.

## Bug 3 (medium): `hasEndAnchor` is computed but never enforced

The detector records whether the pattern ends with `$`/`\z`
(`PatternAnalyzer.java:4363-4369`, stored on `OptionalGroupBackrefInfo`), but
the generator only ever reads `hasStartAnchor`
(`OptionalGroupBackrefBytecodeGenerator.java:555`) — `hasEndAnchor` has zero
consumers.

Reproduced: `^(a)?\1$` on `"aax"` — JDK: no match (trailing `x` violates
`$`). Reggie: matches.

## Scope note

All three reproductions used patterns containing a backref (since that's
what's required to reach `OPTIONAL_GROUP_BACKREF` today), but none of the
three root causes are backref-specific — they live in the group/suffix
matching and prefix/anchor handling shared by the whole detector+generator
pair. Any future reuse of this code (backref-free or otherwise) inherits all
three unless fixed first.

## How each bug was fixed

1. **Bug 1**: `OptionalGroupBackrefBytecodeGenerator` now generates a
   recursive group/absent decision tree (`generateGroupBacktrackTree`) shared
   by all four entry points (`matches()`, `match()`, `find()`/`findFrom()`,
   `findMatch()`). Each optional group's content-present branch is tried
   first; on any downstream failure it falls back to that group's
   absent/empty branch, restoring `pos` and resetting `starts[]`/`ends[]` to
   `-1` so no stale capture survives a backtracked attempt. Backtrack order
   matches PCRE/Java (last-decided group retried first). Bytecode size is
   O(2^N) in the number of optional groups (the backref/suffix/completion
   tail is duplicated once per leaf), so `detectOptionalGroupBackref` now
   caps N at 4 to keep generated methods well under the JVM's per-method size
   limit.

   A related bug surfaced while validating this fix against JDK: a quantified
   backref with `minCount == 0` (e.g. `\1{0,3}`) must be satisfiable by zero
   repetitions even when the referenced group did not participate — Java
   never needs to evaluate `\1` to satisfy a `min=0` quantifier. The
   generator previously failed unconditionally whenever a `(X)?`-form group
   didn't participate, regardless of `minCount`; fixed by gating that failure
   on `minCount > 0` (confirmed against JDK: `(a)?\1{0,3}a` matches `"a"`
   with group 1 unmatched).

2. **Bug 2**: rather than teach the generator to match prefix/middle content,
   `detectOptionalGroupBackref` now rejects any pattern with non-empty prefix
   or middle, so such patterns fall back to a correct strategy (or, if none
   applies, throw `UnsupportedPatternException` unless JDK fallback is
   enabled) instead of silently matching wrong.

3. **Bug 3**: `hasEndAnchor` is now threaded through as
   `requireFullConsumption` on the shared backref/suffix/completion tail —
   `true` unconditionally for `matches()`/`match()`, `info.hasEndAnchor` for
   `find()`/`findMatch()`/`findFrom()`.

Regression coverage: `OptionalGroupBackrefBugFixTest` (reggie-runtime),
covering all three bugs plus the `minCount == 0` case, verified against
`java.util.regex.Pattern` directly. Full existing OGB test corpus
(`OptionalGroupBackrefTest`, `TestEmptyAlternationBackref`,
`TestEmptyAlternationPatterns`, `NullableBackrefTest`,
`BackrefBacktrackMatcherTest`, `StrategyCorrectnessMetaTest`,
`TestOptionalGroupBackrefGroupExtraction`) still passes.
