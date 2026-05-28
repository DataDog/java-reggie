# Issue Priority Order

Generated: 2026-05-08

## Group 1 — Correctness bugs with clear scope

1. **[#27](https://github.com/DataDog/java-reggie/issues/27)** — Multiple backreferences to same group produce false positives
   - Impact: HIGH (wrong results, not just false negatives)
   - Fix localized to `NFABytecodeGenerator` and `VariableCaptureBackrefBytecodeGenerator`

2. **[#35](https://github.com/DataDog/java-reggie/issues/35)** — Inline `(?m)` flag inside a group doesn't activate multiline mode mid-pattern
   - Impact: +1 PCRE test, MEDIUM difficulty, narrow scope in `RegexParser.java`

## Group 2 — Lookaround bugs (related code paths, tackle together)

3. **[#30](https://github.com/DataDog/java-reggie/issues/30)** — Only first alternative in lookbehind alternation is checked
   - Impact: MEDIUM, well-scoped NFA fix in `NFABytecodeGenerator`

4. **[#29](https://github.com/DataDog/java-reggie/issues/29)** — Unbounded quantifier after lookbehind always fails
   - Impact: MEDIUM, companion to #30, same `DFA_UNROLLED_WITH_ASSERTIONS` path

5. **[#28](https://github.com/DataDog/java-reggie/issues/28)** — Lookahead inside quantified group produces wrong results
   - Impact: HIGH, same strategy as #29/#30

6. **[#31](https://github.com/DataDog/java-reggie/issues/31)** — Combined lookbehind + lookahead (sandwich pattern) always fails
   - Impact: HIGH — sandwich patterns are very common; fix after #28 and #29 are clean

## Group 3 — PCRE conformance, medium difficulty

7. **[#36](https://github.com/DataDog/java-reggie/issues/36)** — Lookahead combined with nested alternation produces wrong group captures
   - Impact: +2 PCRE tests, MEDIUM difficulty

8. **[#32](https://github.com/DataDog/java-reggie/issues/32)** — Scoped inline flags not supported (`(?i:...)`, `(?m-i:...)`)
   - Impact: +4 PCRE tests, MEDIUM difficulty, parser flag push/pop

9. **[#34](https://github.com/DataDog/java-reggie/issues/34)** — Nested groups with literal digits and backreferences produce wrong captures
   - Impact: +2 PCRE tests, MEDIUM-HIGH difficulty, backref number parsing ambiguity

## Group 4 — Feature pair with dependency

10. **[#41](https://github.com/DataDog/java-reggie/issues/41)** — Atomic groups not supported (`(?>...)`)
    - Impact: MEDIUM difficulty; prerequisite for #42

11. **[#42](https://github.com/DataDog/java-reggie/issues/42)** — Possessive quantifiers not supported (`*+`, `++`, `?+`, `{n,m}+`)
    - Impact: trivial once #41 lands (desugar to atomic group)

## Group 5 — Harder correctness fixes

12. **[#37](https://github.com/DataDog/java-reggie/issues/37)** — Non-greedy (lazy) quantifiers inside capturing groups produce wrong captures
    - Impact: +5 PCRE tests, HIGH difficulty — needs new `LazyQuantifierBytecodeGenerator`

13. **[#33](https://github.com/DataDog/java-reggie/issues/33)** — Escaped-quote pattern group extraction incorrect in `DFA_UNROLLED_WITH_GROUPS`
    - Impact: +1 PCRE test, HIGH difficulty — tagged DFA group tracking

## Group 6 — Large features

14. **[#40](https://github.com/DataDog/java-reggie/issues/40)** — Unicode property escapes not supported (`\p{L}`, `\p{N}`, etc.)
    - Impact: 66+ PCRE tests filtered; MEDIUM-HIGH difficulty — large Unicode category tables

## Group 7 — Architectural / very high difficulty

15. **[#38](https://github.com/DataDog/java-reggie/issues/38)** — Recursive subroutine patterns (`(?1)`, `(?R)`) fail for palindrome-style checks
    - Impact: +9 PCRE tests, HIGH difficulty — needs backtrackable recursion in `RecursiveDescentBytecodeGenerator`

16. **[#39](https://github.com/DataDog/java-reggie/issues/39)** — Self-referencing backreferences not supported (e.g. `(a\1?){4}`)
    - Impact: +3 PCRE tests, VERY HIGH difficulty — possible architectural change to `RecursiveDescentBytecodeGenerator`
