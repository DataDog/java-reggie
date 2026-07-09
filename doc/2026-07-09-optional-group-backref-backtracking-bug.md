# `OptionalGroupBackrefBytecodeGenerator` correctness bugs

Status: **confirmed, not fixed**. Found while evaluating whether
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

## Suggested fix ordering (not started)

1. Bug 1 first — it's the correctness-load-bearing one and blocks trusting
   this generator for anything else. Fix requires the group-match branch to
   retry the skip-group path on suffix failure (real backtracking, not a
   single greedy attempt), and to clear stale capture state on that retry.
2. Bug 2 — straightforward once fixed: prefix is a fixed-width (today,
   single-literal-char/string) match before the optional group; add it to
   both generated methods' scan.
3. Bug 3 — straightforward: add a `pos == len` check when `hasEndAnchor` is
   true, mirroring the existing `hasStartAnchor` handling.

No code changes made in this document.
