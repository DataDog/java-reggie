# ONEPASS_NFA findMatch()/findBoundsFrom() O(n²) fix — implementation plan

**Branch/worktree:** `fix/onepass-findmatch-perf` at `.worktrees/onepass-findmatch-perf`

## Problem

`OnePassBytecodeGenerator.generateFindMatchFromMethod` (lines 1088-1145) and
`generateFindBoundsFromMethod` locate the match start via `findFrom` (O(1) extra work — `findFrom`
already delegates to the O(n) `matchFrom` single pass), then **discard the end position** and
re-derive it by looping `matchEnd` from `matchStart` to `input.length()`, calling
`matchesInRange(input, matchStart, matchEnd)` at every candidate length (each call allocates a
substring and re-runs `matches()`). That's O(remaining input) work with allocation, on top of the
O(1) end position `matchFrom` already computed and threw away. Confirmed via ad-hoc benchmark:
`findMatch()` degrades to ~1.6-1.7x slower than JDK as haystack length grows (5k→20k chars),
consistent with the redundant rescan.

Boolean `find()`/`findFrom()` do NOT have this problem — they were already fixed in `6841723`.

## Why the direct fix is safe here (and wouldn't be for other strategies)

`matchFrom` returns as soon as it reaches the *first* accept state reached while scanning forward
(`OnePassBytecodeGenerator.java:918-926`), before checking whether a longer match is possible. For
a strategy handling nullable/optional-tail patterns (e.g. `ab?`), that would return the wrong
(shortest) end position for a greedy match.

**This does not apply to `ONEPASS_NFA`.** `PatternAnalyzer.isOnePassEligible()`
(`PatternAnalyzer.java:90-124`) requires, for every NFA state: (1) no two character transitions
have overlapping char sets, and (2) at most one epsilon transition. Quantifier branch points
(`?`, `*`, `+`) always produce a state with two epsilon transitions (continue-loop vs. exit), so
any pattern with a quantifier is disqualified from `ONEPASS_NFA` before this code path is ever
reached — confirmed empirically: `(a)b?`, `([a-z]+)`, `(a+)`, `([a-z]*)x` all route to other
strategies (`DFA_UNROLLED_WITH_GROUPS`, `SPECIALIZED_GREEDY_CHARCLASS`, `SPECIALIZED_CONCAT_GREEDY_GROUP`,
`RECURSIVE_DESCENT`). Every existing `ONEPASS_NFA` test pattern (`([a-z])(bar)`, `(abc)`, `(a)$`,
`^(a)`, `([a-z])$`) is a quantifier-free, fixed-length concatenation, which structurally has at
most one possible accepting length per start position. So `matchFrom`'s returned position is
*always* the unique correct end for any pattern that can actually reach this strategy — reusing it
directly is correct, not just an optimization with a correctness trade-off.

## Changes

**File:** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/OnePassBytecodeGenerator.java`

1. `generateFindMatchFromMethod` (lines 1088-1145): replace the `matchEnd` trial loop with a
   direct call to `matchFrom(input, matchStart)` to get the end position in one O(match-length)
   pass, then call `matchInRange(input, matchStart, matchEnd)` for the `MatchResult` exactly as
   today (unchanged — this call already only fires once, at the resolved end, no allocation
   change there). Drop the now-unused `matchesInRange` trial loop entirely from this method.
2. `generateFindBoundsFromMethod` (lines 1288+, same O(n²) shape per its own doc comment): same
   fix — call `matchFrom` for the end position directly instead of trial-looping.
3. No changes to `matchFrom`, `findFrom`, `matches()`, or any other generated method — boolean
   `find()` perf (already fixed, 3.5x JDK) is untouched.
4. No `StructuralHash`/`DFAState`/`PatternInfo` field changes — this only changes generated method
   bodies, not compiled pattern metadata, so no structural-hash update needed.

## Tests

**New file:** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/OnePassFindMatchPerfCorrectnessTest.java`

- Confirm routing to `ONEPASS_NFA` for the patterns under test via
  `StrategyCorrectnessMetaTest.routeOf(...)`.
- Behavioral parity vs JDK for `findMatch()`/`findMatchFrom()`/`findBoundsFrom()` on:
  - `([a-z])(bar)` (existing meta-test representative)
  - `(abc)` at various positions within a longer haystack (start, middle, end, no match)
  - `(a)$`, `^(a)`, `([a-z])$`, `^([a-z])` (anchor cases, mirrors `OnePassNfaAnchorFindTest`)
  - A long-haystack case (≥10k chars) with the match near the end, to directly exercise the
    perf-motivated code path and catch any off-by-one in the new direct-end-position logic.
- Existing `OnePassNfaAnchorFindTest` and `StrategyCorrectnessMetaTest` must continue to pass
  unmodified — they already cover `findMatch()` for `ONEPASS_NFA` and will catch a correctness
  regression from this change.

## Verification steps

1. `./gradlew :reggie-codegen:build :reggie-runtime:build` — compiles.
2. `./gradlew :reggie-runtime:test --tests "OnePassFindMatchPerfCorrectnessTest"` — new tests pass.
3. `./gradlew :reggie-runtime:test --tests "OnePassNfaAnchorFindTest" --tests "StrategyCorrectnessMetaTest" --tests "PikeVMRoutingTest"` — no regression.
4. `./gradlew :reggie-integration-tests:test --tests "*AlgorithmicFuzzTest.divergenceGate_enforcedViaProperty" -Dreggie.fuzz.enforce=true -Dreggie.fuzz.maxFindings=28` — fuzz gate still passes at budget 28 (no new divergences).
5. `./gradlew :reggie-benchmark:jmh -Pjmh.args="StrategyBenchmark.*Onepass.* -f 2 -wi 5 -i 5 -bm avgt -tu ns"` — confirm `reggieOnepassFind` unaffected (still ~3.5x JDK) and spot-check `findMatch` improvement if a benchmark method exists for it (add one if not, scoped to this task only if needed for evidence — do not expand into a general benchmark refactor).
6. `./gradlew spotlessApply` before commit.
7. Update `doc/agents-fallback-and-limitations.md`'s `ONEPASS_NFA` known-gap entry: either remove
   it (if fixed) or narrow it further based on actual measured results.

## Out of scope

- No changes to other strategies' find/match generators.
- No new JMH benchmark infrastructure beyond what's needed to verify this specific fix.
- No attempt to relax `isOnePassEligible()` to admit quantified patterns — that's a separate,
  much larger feature (would require a real greedy-longest single-pass algorithm, not this fix).
