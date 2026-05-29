---
spec_id: REQ-DataDog-java-reggie-27
source: github
source_ref: "DataDog/java-reggie#27"
title: "[bug] Multiple backreferences to same group produce false positives"
status: draft
clarity_score: null
created: 2026-05-08
implementing_session: null
implemented_pr: null
---

# [bug] Multiple backreferences to same group produce false positives

## Description
When a pattern references the same capturing group more than once (e.g. `(\w+)\s+\1\s+\1`), the engine returns incorrect results. The second backreference check is not enforced, causing false positives.

## Reproduction
```java
ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1\\s+\\1");
m.find("go go stop"); // returns true — WRONG, should be false
m.find("go go go");   // returns true — correct
```

## Root cause
Patterns selected by `OPTIMIZED_NFA_WITH_BACKREFS` and `VARIABLE_CAPTURE_BACKREF` strategies do not correctly validate the second occurrence of a backreference to the same group. The group capture state is not properly threaded through the second backref check.

## Current mitigation
`FallbackPatternDetector` detects this condition and falls back to `java.util.regex`. Patterns with 2+ references to the same group in these strategies are transparently delegated.

## Fix direction
- `NFABytecodeGenerator`: ensure group capture state persists across multiple backref checks for the same group number
- `VariableCaptureBackrefBytecodeGenerator`: validate all backreferences, not just the first

## Impact
High — incorrect match results (false positives) for multi-backref patterns.
