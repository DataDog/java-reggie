---
spec_id: REQ-DataDog-java-reggie-29
source: github
source_ref: "DataDog/java-reggie#29"
title: "[bug] Unbounded quantifier after lookbehind always fails to match"
status: implementing
clarity_score: 85
created: 2026-05-10
implementing_session: impl-20260510-175457
implemented_pr: null
---

# [bug] Unbounded quantifier after lookbehind always fails to match

## Description
A lookbehind assertion followed by an unbounded quantifier (`+`, `*`, `{n,}`) always returns false, even for inputs that should match.

## Reproduction
```java
ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
m.find("3abc"); // returns false — WRONG, should be true
m.find("abc");  // returns false — correct

// Bounded quantifier works:
Reggie.compile("(?<=\\d)[a-z]{1,4}").find("3abc"); // true — correct
```

## Root cause
In the `DFA_UNROLLED_WITH_ASSERTIONS` path, the lookbehind position is not correctly propagated as the starting position for the unbounded quantifier's loop. The loop starts at an incorrect offset and immediately fails.

## Current mitigation
`FallbackPatternDetector` detects a `ConcatNode` where a lookbehind `AssertionNode` is immediately followed by a `QuantifierNode` with `max == -1` and falls back to `java.util.regex`.

## Fix direction
After a lookbehind assertion succeeds, the following quantifier loop must start from the correct post-lookbehind position, not from the start of the assertion check.

## Impact
Medium — affects patterns common in tokenization and text extraction.
