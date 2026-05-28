---
spec_id: REQ-DataDog-java-reggie-36
source: github
source_ref: "DataDog/java-reggie#36"
title: "[pcre] Lookahead combined with nested alternation produces wrong group captures"
status: implemented
clarity_score: 72
created: 2026-05-11
implementing_session: impl-20260511-102846
implemented_pr: "https://github.com/DataDog/java-reggie/pull/59"
---

# [pcre] Lookahead combined with nested alternation produces wrong group captures

## Summary

Two PCRE tests involving lookahead assertions nested inside alternations or combined with digit-range character classes produce incorrect group captures.

## Failing PCRE Tests

1. Pattern `(\.\d\d((?=0)|\d(?=\d)))` on input `1.875000282`
   - Inner `(?=0)` / `\d(?=\d)` alternation inside a capturing group fails to record the correct group 2 value.

2. Pattern `(\.\d\d[1-9]?)\d+` on input `1.235`
   - Expected group 1 = `.23`, actual = `.235`
   - The `[1-9]?` optional class greedily consumes one character that should be left to `\d+`.

**Expected gain**: +2 PCRE conformance tests (Category 6, remaining after Phase 2.1)

## Root Cause

These are backtracking/greedy edge cases in patterns where a lookahead sits inside an alternation within a capturing group. The NFA/DFA grouping boundary isn't preserved correctly during lookahead evaluation and the greedy quantifier does not backtrack into the optional class.

## Implementation Notes

- Difficulty: Medium
- Files likely involved: `NFABytecodeGenerator.java`, lookahead handling in `PatternAnalyzer.java`
