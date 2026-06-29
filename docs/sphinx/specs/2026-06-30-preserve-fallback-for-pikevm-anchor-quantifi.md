# Spec: preserve-fallback-for-pikevm-anchor-quantifi

## Problem

`ReggieMatcherBytecodeGenerator.resolveRealization()` returns `DELEGATE_PIKEVM` for any
pattern whose `PatternAnalyzer` result is `PIKEVM_CAPTURE` **without first consulting
`FallbackPatternDetector.needsFallback()`**. This means patterns that trigger the B3a route
in `PatternAnalyzer` (anchor-only capturing group, e.g. `($)`) but which also carry a
quantifier on that group (e.g. `($){2}`, `(^)?`) reach the compile-time PIKEVM path even
though `FallbackPatternDetector` marks them unsafe via the B2
(`hasAnchorInQuantifierInCapturingGroup`) and B3 (`hasAnchorInQuantifier`) KEEP-PERMANENT
guards.

The same gap exists in `RuntimeCompiler.compilePikeVm()` (called by generated stubs): it
only applies the B16 nullable-group-content guard, missing B2/B3.

The runtime `Reggie.compile()` path is correct: it calls
`FallbackPatternDetector.needsFallback(ast, PIKEVM_CAPTURE)` at line 494 and falls back to
JDK when non-null.

## Correct behaviour

1. When `@RegexPattern` annotation processing routes a pattern to `PIKEVM_CAPTURE`:
   - `resolveRealization()` must call `FallbackPatternDetector.needsFallback(ast, PIKEVM_CAPTURE)`.
   - If the result is non-null, the pattern requires JDK fallback:
     - If `allowJdkFallback` is set → return `DELEGATE_FALLBACK`.
     - Otherwise → throw `UnsupportedOperationException` with the fallback reason, consistent
       with the existing `needsJdk` error message format.
   - If the result is null → return `DELEGATE_PIKEVM` as before.

2. `RuntimeCompiler.compilePikeVm()` must apply the full `needsFallback()` guard (not just
   the ad-hoc `hasNullableGroupContentWithNullableQuantifier` check). If the result is
   non-null → throw `UnsupportedPatternException` with the reason string.

3. Patterns like `($)` (no quantifier on the anchor-only group) remain unaffected:
   `hasAnchorInQuantifier` returns false for them → `needsFallback()` returns null →
   they continue to reach `DELEGATE_PIKEVM`.

## Constraints

- No changes to `FallbackPatternDetector` or `PatternAnalyzer`.
- Error message format in `resolveRealization()` must be consistent with the existing
  `needsJdk` path (folding the PIKEVM fallback reason into the `needsJdk` boolean or
  reusing the same throw is preferred).
- `FallbackPatternDetector` is already imported in both files — no new dependencies.

## Scope

### Primary fixes

- `reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java:103`
  — missing fallback guard on PIKEVM_CAPTURE early-exit: add `needsFallback()` check
  before returning `DELEGATE_PIKEVM`.

### Auto-expanded sibling fixes

- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java:299`
  — partial guard (B16 only) in `compilePikeVm()`: replace `hasNullableGroupContentWithNullableQuantifier`
  check with full `FallbackPatternDetector.needsFallback(ast, MatchingStrategy.PIKEVM_CAPTURE)` call.
  FLOW: annotation-processor-generated stub calls `compilePikeVm()` directly, bypassing
  `RuntimeCompiler.compile()` which has the correct full guard.
  PRECONDITION: pattern has PIKEVM_CAPTURE result from PatternAnalyzer AND triggers B2 or B3.
  REACHABLE: yes — `($){2}` at an `@RegexPattern` site reaches `compilePikeVm()` with no B2/B3 check.
  CONCLUSION: medium-severity defensive guard that catches patterns slipping through Fix 1 if any
  future code path creates a PIKEVM stub without going through `resolveRealization()`.

## Assumptions

- `FallbackPatternDetector.needsFallback(ast, PIKEVM_CAPTURE)` subsumes
  `hasNullableGroupContentWithNullableQuantifier` (confirmed: B16 guard at
  `FallbackPatternDetector.java:287` includes `strategy == PIKEVM_CAPTURE`).
- Fix 1 folds the PIKEVM fallback check into the existing `needsJdk` boolean rather than
  adding a separate throw, to keep uniform error message formatting.
