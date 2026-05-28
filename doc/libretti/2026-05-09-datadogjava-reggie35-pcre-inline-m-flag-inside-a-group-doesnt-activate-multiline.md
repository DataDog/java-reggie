---
spec_id: REQ-DataDog-java-reggie-35
source: github
source_ref: "DataDog/java-reggie#35"
title: "[pcre] Inline (?m) flag inside a group doesn't activate multiline mode mid-pattern"
status: draft
clarity_score: null
created: 2026-05-09
implementing_session: null
implemented_pr: null
---

# [pcre] Inline (?m) flag inside a group doesn't activate multiline mode mid-pattern

## Summary

When `(?m)` appears inside a capturing group (not at the start of the pattern), the multiline flag is not correctly activated for the surrounding `^` anchor used in that sub-expression.

## Failing PCRE Test

- Pattern: `\n((?m)^b)`
- Input: `"a\nb\n"`
- Expected: matches with group 1 = `b`
- Actual: no match

**Expected gain**: +1 PCRE conformance test (Category 5)

## Root Cause

Phase 1.2 fixed the anchor-optimization issue for patterns where `(?m)` appears globally (e.g., `(.*X|^B)`). However, when `(?m)` is embedded inline inside a sub-group, the flag-propagation logic doesn't update the anchor-matching behavior for `^` in that local scope.

## Implementation Notes

- Phase 1.2 fixed 4 of the 5 multiline-anchor tests; this is the remaining failure
- Difficulty: Medium
- Files likely involved: `RegexParser.java` (flag propagation), NFA anchor handling
