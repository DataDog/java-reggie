---
spec_id: REQ-DataDog-java-reggie-30
source: github
source_ref: "DataDog/java-reggie#30"
title: "[bug] Only first alternative in lookbehind alternation is checked"
status: draft
clarity_score: null
created: 2026-05-10
implementing_session: null
implemented_pr: null
---

# [bug] Only first alternative in lookbehind alternation is checked

## Description
When a lookbehind assertion contains an alternation (`(?<=a|b)c`), only the first alternative is considered. Subsequent alternatives are silently ignored, causing false negatives.

## Reproduction
```java
ReggieMatcher m = Reggie.compile("(?<=a|b)c");
m.find("ac"); // returns true  — correct
m.find("bc"); // returns false — WRONG, should be true
m.find("xc"); // returns false — correct
```

## Root cause
The `OPTIMIZED_NFA_WITH_LOOKAROUND` strategy processes lookbehind alternations but only evaluates the first branch. When the first alternative fails, the NFA does not try remaining alternatives in the lookbehind.

## Current mitigation
`FallbackPatternDetector` detects an `AssertionNode(lookbehind)` whose `subPattern` directly contains an `AlternationNode`, and falls back to `java.util.regex`.

## Fix direction
In `NFABytecodeGenerator` lookbehind handling: after the lookbehind subpattern fails for one alternative, iterate over all remaining alternatives rather than short-circuiting on the first failure.

## Impact
Medium — incorrect false negatives for patterns using lookbehind alternatives.
