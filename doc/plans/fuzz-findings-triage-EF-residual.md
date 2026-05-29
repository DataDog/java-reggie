# Cat E/F residual: anchor-condition dilution in DFA subset construction

Triage performed against HEAD `218d487` (branch `fix/anchor-semantics`).

## Reproducing divergences

Algorithmic fuzz (seed `0xC0DEFEED_DEADBEEFL`, 500 patterns × 8 inputs)
yields 92 raw findings, 63 unique minimal repros. Of those, three
classes are excluded from this triage:

- Alternation-order divergences (JDK leftmost-first vs Reggie's
  leftmost-longest DFA) — accepted, requires tagged NFA or branch
  priority tracking. Examples: `\A|[c]`, `(.\z)|`, `a{1}(?:1?[^a])$|b?`.
- Self-referencing backref Cat D — accepted (see prior triage doc).

The actionable Cat E/F repros (verified to still diverge on `218d487`):

| Pattern | Input | JDK find() | Reggie find() | matches() agree? |
|---|---|---|---|---|
| `\Z.[a]{1}\|_-` | `_a` | no match | `[0,2)` | no — Reggie `true` |
| `[ca]{2}(Z?^\|\Z)` | `cab` | no match | `[0,2)` | yes |
| `\Z[1]*\|1]` | `1` | `[1,1)` | `[0,1)` | no — Reggie `true` |
| `(1{0,}^\|]{2})` | `1` | `[0,0)` | `[0,1)` | yes |

All four route to `DFA_UNROLLED` via `PatternDebugger.analyze()`.

## Root cause

`SubsetConstructor.buildDFA` (lines 89–152) computes a single
"transition guard" per partition slice by intersecting the source
anchor conditions of every NFA state that contributes a transition for
that slice (`mergeWeakest`, line 103). Intersection is the correct
"weakest precondition" merge for *one* logical path, but it is wrong
when contributors come from alternation branches with *different*
preconditions:

```
\Z.[a]{1}|_- on '_'
  branch 1 ('.') srcCond = {STRING_END}    // \Z propagated via ε-closure
  branch 2 ('_') srcCond = {}              // no anchor
  transitionGuard = {STRING_END} ∩ {} = {}  // anchor lost
```

After the transition fires unconditionally, both branches' NFA-state
continuations land in the post-state. Branch 1 was never "live" at
pos 0 (STRING_END false), but its post-state now appears alive, and
its accept condition can be discharged at a later position. The
pattern accepts incorrectly.

`computeAcceptanceConditions` (lines 293–304) has the same shape:
when *any* accept state in the closure has empty condition,
acceptance is declared unconditional. For `(Z?^|\Z)` after the
outer `[ca]{2}` consumes two chars, the inner alternation's two
accept paths have disjoint conditions (`{START_MULTILINE}` vs
`{STRING_END}`) — the intersection logic again drops to `{}` and
the state is wrongly marked unconditionally accepting.

`containsConsumeKillingAnchor` only prunes `END`/`STRING_END_ABSOLUTE`,
not `STRING_END`/`END_MULTILINE` (intentional — the latter pair admit
a trailing `\n`). The runtime entry guard for `STRING_END` *is*
implemented (`DFAUnrolledBytecodeGenerator.emitTransitionEntryGuard`,
line 3408) and would catch the bad transition, **but only if the
guard reaches it**. The dilution erases it before runtime ever sees
it.

Working anchor-alternation patterns from `AnchorRegressionTest`
(`^[0-9]|q`, `$X|Y`, `$[^a-zA-Z0-9]|^[0-9]`) survive only by
coincidence: their alternation branches have disjoint *leading
consumer char-classes*, so no partition slice ever has contributors
from differing-anchor branches.

## Fix options

### A. Fallback safety net (low effort, conservative)

Extend `FallbackPatternDetector` to detect alternations whose branches
have differing "leading positional anchor profiles":

```
leadingAnchorProfile(branch) = { anchors that must hold at the
                                 entry position for any non-empty
                                 match through the branch }
```

If two branches in an alternation have unequal profiles, route to
`JavaRegexFallbackMatcher` (delegates to `java.util.regex`).

- Catches all four actionable repros.
- Also catches working patterns like `^[0-9]|q` and `$X|Y` —
  perf regression on these (DFA → JDK fallback) but no
  correctness change; `AnchorRegressionTest` still passes via the
  JDK fallback.
- Pure AST walk, no DFA-construction state to thread.

### B. Per-branch DFA state splitting (higher effort, correct)

Track per-NFA-state source conditions through the DFA construction.
When a partition slice has contributors with differing source
conditions, instead of intersecting to a single guard, **split** the
post-state into one DFA state per source-condition equivalence
class. Each child state carries a distinct entry guard.

This is structurally what the original anchor-aware DFA plan
anticipated ("paths with disjoint anchor conjunctions ... reports
back so the strategy selector can fall back to NFA"). The
"reports back" route is option A; the "doesn't fall back" route is
this option.

- Catches the four bugs without falling back any patterns.
- Significant rework of `buildDFA`, `buildDFAWithAssertions`, and
  the state-cache key shape (must include the source-condition
  signature, not just the NFA-state set).
- DFA state count may grow but for these patterns the growth is
  bounded (one state per branch's anchor profile).

### C. Construction-time fallback signal (medium effort)

When `buildDFA` detects a contributor-disagreement at a partition or
accept site, set a flag on the returned DFA. `RuntimeCompiler` /
`ReggieMatcherBytecodeGenerator` check the flag and route to
`JavaRegexFallbackMatcher`. Pure-AST detection from option A is
imprecise (catches working patterns); this detects the actual
condition that triggers the bug.

- Most precise of the three.
- Requires plumbing the flag from `SubsetConstructor` through
  `PatternAnalyzer.MatchingStrategyResult` and into the fallback
  decision in two call sites.

## Recommendation

Option **C** if perf on `^[0-9]|q`-shaped patterns is important;
option **A** if speed of fix dominates. Option B is the correct
long-term direction but is a follow-up.

## Verification

- Add the four repros above to `AnchorRegressionTest` (asserting
  span equivalence with `java.util.regex.Pattern`).
- Re-run `./gradlew :reggie-integration-tests:test` — the fuzz test
  ceiling at 25% will not move (these four are 4/63 unique findings)
  but the `AnchorRegressionTest` cases assert directly.
- Run `./gradlew check` to confirm no broader regression.
