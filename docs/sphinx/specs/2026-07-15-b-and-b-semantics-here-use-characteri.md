# Spec: b-and-b-semantics-here-use-characteri

## Problem
The runtime `\b`/`\B` (`WORD_BOUNDARY`/`NON_WORD_BOUNDARY`) anchor checks in `PikeVMMatcher`,
`BackrefBacktrackMatcher`, and the Laurikari transition-cache classifier in `LaurikariDFACache`
classify "word" characters with `Character.isLetterOrDigit`, which admits Unicode
letters/digits and excludes `_`. This diverges from the ASCII `[A-Za-z0-9_]` word-char definition
used everywhere else in Reggie (`CharSet.WORD`, the generated `isWordChar` helpers) and from Java's
own `\b` semantics for `_`.

Separately, the annotation-processor code-generation path
(`ReggieMatcherBytecodeGenerator`'s `SPECIALIZED_FIXED_SEQUENCE` case) never calls
`FixedSequenceBytecodeGenerator.generateBoundaryHelperMethods`, unlike the runtime-compiler path
(`RuntimeCompiler`), so compile-time-generated matchers for patterns like
`@RegexPattern("\\bfoo")` reference a private static `isBoundary` helper that is never emitted,
which will fail at class verification/load time.

Finally, two test files embed literal U+2028/U+2029/U+0085 characters directly in Java string
literals instead of using `\uXXXX` escapes.

## Correct behaviour
- `\b`/`\B` classify a character as a "word" character iff it is ASCII `[A-Za-z0-9_]`
  (`Character.isLetterOrDigit` matches on Unicode letters/digits, and it excludes `_` — neither is
  correct here). This must hold identically in every place that evaluates or pre-classifies
  `WORD_BOUNDARY`/`NON_WORD_BOUNDARY`:
  - `PikeVMMatcher.checkAnchor` (used directly by `BitStateMatcher` and `LaurikariCaptureNfaStep`,
    not duplicated by them).
  - `BackrefBacktrackMatcher.checkAnchor` (its own duplicated mirror of `PikeVMMatcher`'s anchor
    semantics).
  - `LaurikariDFACache.lookaheadClass`, which pre-buckets the not-yet-consumed character into
    `LOOKAHEAD_WORD`/`LOOKAHEAD_NEWLINE`/`LOOKAHEAD_OTHER` for a cache keyed on
    `PikeVMMatcher.checkAnchor`'s outcome — it must bucket exactly the same set of characters as
    `LOOKAHEAD_WORD` that `checkAnchor` treats as word characters, or the cache serves a stale
    result for one class after computing it for the other.
  - For example, `/\B\w+/` on `"_a"` must find a match starting at position 0 (no boundary between
    `_` and `a`), consistently across `PikeVMMatcher`, `BackrefBacktrackMatcher`, `BitStateMatcher`,
    and `LaurikariDfaMatcher`.
- Compile-time-generated matchers (annotation processor) for `SPECIALIZED_FIXED_SEQUENCE` patterns
  with a leading and/or trailing `\b`/`\B` must emit the `isWordChar`/`isBoundary` helper methods,
  exactly like the runtime-compiler path already does, so `matches()`/`find()`/`findFrom()` resolve
  correctly for patterns such as `@RegexPattern("\\bfoo")`, `@RegexPattern("foo\\b")`, and
  `@RegexPattern("\\bfoo\\b")` (and their `\B` variants).
- The affected test files must use Unicode escapes (`\u2028`, `\u2029`, `\u0085`) instead of literal
  non-ASCII characters, with the same characters being tested and existing descriptive comments
  preserved.

## Constraints
- `PikeVMMatcher.checkAnchor`'s signature and its direct callers (`BitStateMatcher`,
  `LaurikariCaptureNfaStep`) must not change.
- `BackrefBacktrackMatcher.checkAnchor` keeps its private, class-local mirror of the anchor logic
  (existing project convention noted in its own Javadoc: "Mirrors `PikeVMMatcher`'s anchor
  semantics") — only its word-char classification changes.
- `LaurikariDFACache`'s ASCII fast-path split (values `0-127` cached directly, `c >= 128` always
  falls back) is unchanged; only which of the 128 ASCII values classify as `LOOKAHEAD_WORD` changes.
- No behavior change to any anchor type other than `WORD_BOUNDARY`/`NON_WORD_BOUNDARY`.
- `RuntimeCompiler`'s existing `needsBoundaryHelpers()`-guarded call to
  `generateBoundaryHelperMethods` is the reference implementation the annotation-processor path
  must mirror — do not change `RuntimeCompiler` itself.
- Test-string literal changes are source-representation-only; the runtime `String` contents tested
  must be byte-for-byte identical to before.

## Scope

### Primary fixes
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java:1339-1350` —
  inconsistent word-char predicate — add an ASCII `[A-Za-z0-9_]` `isWordChar` helper and use it in
  `WORD_BOUNDARY`/`NON_WORD_BOUNDARY`.
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/BackrefBacktrackMatcher.java:266-277`
  — same defect class in the mirrored `checkAnchor` copy — add the same ASCII predicate locally and
  use it.
- `reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java`
  (`SPECIALIZED_FIXED_SEQUENCE` case, ~line 360-374) — dual-path codegen divergence — call
  `fixedGen.generateBoundaryHelperMethods(cw, getJavaClassName())` under the same
  `needsBoundaryHelpers()` guard `RuntimeCompiler` uses.
- `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LaurikariAnchorLookaheadFuzzTest.java:369-371`
  — literal Unicode line/paragraph separator/NEL characters — replace with `\u2028`/`\u2029`/`\u0085`
  escapes.

### Auto-expanded sibling fixes
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariDFACache.java:382-385`
  (`lookaheadClass`) — same word-char-predicate defect class — evidence: this method's Javadoc
  (lines 373-376) explicitly documents that its `LOOKAHEAD_WORD` classification must match
  `PikeVMMatcher.checkAnchor`'s `WORD_BOUNDARY` operand for the not-yet-consumed character, and its
  body still calls `Character.isLetterOrDigit`. FLOW: `LaurikariDfaMatcher`/`LaurikariCaptureNfaStep`
  drive `LaurikariDFACache.step`, which for `anchorSensitive` states calls `lookaheadClass` to pick
  a cache bucket before delegating to `PikeVMMatcher.checkAnchor` via `lookupOrCompute`.
  PRECONDITION: a `\b`/`\B`-anchored pattern reaches an `anchorSensitive` DFA state away from
  `regionEnd`. REACHABLE: yes — this is the exact code path exercised by
  `LaurikariAnchorLookaheadFuzzTest`. CONCLUSION: must be fixed in the same pass as
  `PikeVMMatcher.checkAnchor`, or the cache silently serves wrong transitions for `_`-adjacent
  input once `checkAnchor` itself is corrected.
- `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LaurikariDfaMatcherTest.java:366` —
  same defect class as the primary literal-Unicode-characters item — evidence: the line contains
  literal U+2028 and U+0085 characters in a `String[]` literal, in the same PR's added test code.
  FLOW: N/A (test data literal, not an execution path). PRECONDITION: none — the literal characters
  are present in the file regardless of any runtime path. REACHABLE: yes — the file compiles and
  runs as part of the existing test suite today. CONCLUSION: same fix (escape the characters)
  applies for the same readability/encoding-robustness reason.

## Assumptions
- Resolved at ≥90% confidence: `LaurikariDFACache.lookaheadClass` should call a shared,
  package-private `PikeVMMatcher.isWordChar` rather than duplicating the ASCII predicate a third
  time, since `LaurikariDFACache` is in the same package (`com.datadoghq.reggie.runtime`) and its
  sole purpose for this method is to pre-compute `PikeVMMatcher.checkAnchor`'s outcome.
- Resolved at ≥90% confidence: `BackrefBacktrackMatcher.checkAnchor` keeps its own private
  duplicate helper (rather than calling `PikeVMMatcher`), consistent with its existing "mirrors"
  convention and its already fully-duplicated `checkAnchor` method body.
- Resolved at ≥90% confidence: the trailing descriptive comments (`// LINE SEPARATOR`, etc.) in the
  two test files are preserved verbatim; only the character literal changes to its escape.
