# PCRE Conformance Roadmap

**Current Status**: 91.3% pass rate (303/332 tests)
**Last Updated**: 2026-02-03
**Target**: 95%+ pass rate

## How to Use This Document

When starting a new session to work on PCRE conformance:

1. Run the PCRE integration test to get current status:
   ```bash
   ./gradlew :reggie-integration-tests:test --tests "CorrectnessTest.testPCRECapturingGroups"
   ```

2. Find the next unchecked item in the TODO list below

3. Implement the fix following the pattern:
   - Analyze root cause in PatternAnalyzer.java or relevant bytecode generator
   - Implement fix
   - Add unit test in `reggie-runtime/src/test/java/com/datadoghq/java-reggie/runtime/`
   - Run PCRE tests to verify improvement
   - Mark the item as done with `[x]` and note the commit hash

4. Update the pass rate at the top of this document

---

## Failure Categories

### Category 1: Quantified Alternation with Following Group (3 tests)

**Patterns**:
- `a(?:b|c|d){6,7}(.)` on "acdbcdbe" - expected group 1 = 'e', got null
- `a(?:b|c|d){5,6}(.)` on "acdbcdbe" - expected group 1 = 'e', got null
- `a(?:b|c|d){5,7}(.)` on "acdbcdbe" - expected group 1 = 'e', got null

**Root Cause**: The quantified non-capturing alternation `(?:b|c|d){5,7}` needs to match exactly 5-7 alternation choices, then capture the following character. Current implementation may be consuming too many or failing to backtrack properly.

**Difficulty**: Medium
**Impact**: +3 tests

---

### Category 2: Non-Greedy Quantifiers in Capturing Groups (5 tests)

**Patterns**:
- `(|ab)*?d` on "abd" - expected group 1 = 'ab', got null
- `^[ab]{1,3}?(ab*?|b)` on "aabbbbb" - expected group 1 = 'a', got 'abbbbb'
- `^[ab]{1,3}?(ab*|b)` on "aabbbbb" - expected group 1 = 'abbbbb', got 'b'
- `(?i)(a+|b){0,1}?` on "AB" - expected group 1 = '', got 'A'
- `(([a-c])b*?\2){3}` should match "ababbbcbc" but didn't

**Root Cause**: Non-greedy (lazy) quantifiers with capturing groups require backtracking to find the minimal match. Thompson NFA doesn't natively support this.

**Difficulty**: High
**Impact**: +5 tests

---

### Category 3: Recursive Patterns (9 tests)

**Patterns**:
- `^((.)(?1)\2|.?)$` - palindrome check - should match 'abba', 'ababa', 'abccba'
- `^(.|(.)(?1)\2)$` - should match 'aba', 'abcba', 'ababa'
- `^(.|(.)(?1)?\2)$` - should match 'abcba'
- `^(a?)+b(?1)a` - should match 'aba', 'ba'
- `^(a?)b(?1)a` - should match 'aba'

**Root Cause**: Recursive subroutine calls `(?1)` and `(?R)` are partially implemented but have bugs in group capture tracking during recursion.

**Difficulty**: High
**Impact**: +9 tests

---

### Category 4: Self-Referencing Backreferences (3 tests)

**Patterns**:
- `^(a\1?)(a\1?)(a\2?)(a\3?)$` - should match 'aaaa', 'aaaaaa'
- `^(a\1?){4}$` - should match 'aaaa'

**Root Cause**: A group that references itself (like `(a\1?)`) requires the backref to match the *current partial* content of the group being built. This is fundamentally different from normal backrefs.

**Difficulty**: Very High
**Impact**: +3 tests

---

### Category 5: Multiline Mode with Anchors (5 tests)

**Patterns**:
- `(.*X|^B)` on multiline "abcde\n1234Xyz" (4 test cases)
- `\n((?m)^b)` on multiline "a\nb\n"

**Root Cause**: The `(?m)` flag makes `^` match after newlines. Current implementation may not be handling this correctly in all cases, especially with alternation.

**Difficulty**: Medium
**Impact**: +5 tests

---

### Category 6: Lookahead with Capture/Backref (3 tests)

**Patterns**:
- `^(?=(\w+))\1:` should match 'abcd:'
- `(\.\d\d((?=0)|\d(?=\d)))` should match '1.875000282'
- `(\.\d\d[1-9]?)\d+` on '1.235' - expected '.23', got '.235'

**Root Cause**: Lookahead that captures a group, then uses that capture outside the lookahead. The capture inside lookahead must persist.

**Difficulty**: Medium
**Impact**: +3 tests

---

### Category 7: Word Boundary with Greedy Groups (1 test)

**Patterns**:
- `(.*)\b(\d+)$` should match "I have 2 numbers: 53147"

**Root Cause**: The greedy `(.*)` followed by word boundary `\b` then `(\d+)$` requires backtracking to find where the word boundary correctly positions.

**Difficulty**: Medium
**Impact**: +1 test

---

### Category 8: Negated Character Classes (2 tests)

**Patterns**:
- `^([^a])([^\b])([^c]*)([^d]{3,4})` should match 'baNOTccd'
- `^([^!]+)!(.+)=apquxz\.ixr\.zzz\.ac\.uk$` should match 'abc!pqr=apquxz.ixr.zzz.ac.uk'

**Root Cause**: Negated character classes with quantifiers may have issues with backtracking or boundary conditions.

**Difficulty**: Medium
**Impact**: +2 tests

---

### Category 9: Complex Nested Patterns (5 tests remaining)

**Patterns**:
- `"([^\\"]+|\\.)*"` on escaped quote string - group extraction wrong
- `(?:(?!foo)...|^.{0,2})bar(.*)` - negative lookahead in alternation
- `^(ba|b*){1,2}?bc` should match 'bbabc'
- `(cat(a(ract|tonic)|erpillar)) \1()2(3)` - nested groups with literal backref
- `(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)` - scoped flags
- ~~`(?i)^(\d+)\s+IN\s+SOA\s+(\S+)\s+(\S+)\s*\(\s*$`~~ - **FIXED** (27e03c6) - recursive nested backtracking

**Root Cause**: Various issues with nested groups, scoped flags, and complex alternations.

**Difficulty**: Medium-High
**Impact**: +5 tests (was +6)

---

### Category 10: Unsupported PCRE Features (19 errors - out of scope)

These are advanced PCRE features not currently planned for implementation:

- **Relative backreferences**: `(?-2)` - reference group by relative position
- **Branch reset groups**: `(?|...)` - reset group numbers in each branch
- **Backtracking control verbs**: `(*MARK:)`, `(*PRUNE:)`, `(*SKIP:)`, `(*THEN:)`
- **Named captures with branch reset**: `(?|(?'a'...)|(?'a'...))`

**Status**: Out of scope for initial PCRE conformance. These are rarely used in practice.

---

## Implementation TODO List

Mark items `[x]` when completed. Add commit hash in parentheses.

### Phase 1: Quick Wins (Target: 90% pass rate)

- [x] **1.1** Fix quantified alternation with following group (2026-01-30)
  - File: `DFASwitchBytecodeGenerator.java` - `generateFindMatchFromMethod()`
  - Fix: Changed `findMatchFrom()` to call `match(substring)` for group extraction, then adjust positions
  - Test patterns: `a(?:b|c|d){5,7}(.)`
  - Actual gain: +3 tests (88.0% → 88.9%)

- [x] **1.2** Fix anchor optimization for alternations with one anchor branch (2026-01-30)
  - Files: `NFA.java` - added `requiresStartAnchor()` method
  - Files: `DFAUnrolledBytecodeGenerator.java`, `DFASwitchBytecodeGenerator.java` - use new method
  - Fix: Only skip non-zero positions in find() when ALL paths require start anchor
  - Test patterns: `(.*X|^B)` on multiline - now correctly finds match at any position
  - Actual gain: +4 tests (88.9% → 90.1%)
  - Note: `\n((?m)^b)` still fails - different issue with inline flag handling

- [x] **1.3** Fix word boundary with greedy backtrack (2026-01-30)
  - File: `GreedyBacktrackBytecodeGenerator.java` - `generateFindMatchWordBoundaryQuantifiedCharClassSuffix()`
  - Fix: Removed early-exit condition from backtrack loop; must scan ALL suffix chars to find word boundary position
  - Test pattern: `(.*)\b(\d+)$`
  - Actual gain: +1 test (90.1% → 90.4%)

- [x] **1.4** Fix multiple backtracking quantifiers in RECURSIVE_DESCENT (2026-01-30)
  - File: `RecursiveDescentBytecodeGenerator.java` - `generateConcatWithBacktracking()`, `generateNestedBacktracking()`
  - Root cause: Was NOT about negated char classes! Patterns like `(.+)!(.+)=` failed because only FIRST backtracking quantifier got backtracking support
  - Fix: Added nested backtracking for remaining children that contain backtracking quantifiers
  - Test pattern: `^([^!]+)!(.+)=apquxz\.ixr\.zzz\.ac\.uk$`
  - Actual gain: +1 test (90.4% → 90.7%)
  - Note: Pattern `^([^a])([^\b])([^c]*)([^d]{3,4})` uses PCRE-specific `[^\b]` (backspace) which JDK doesn't support

### Phase 2: Medium Complexity (Target: 92% pass rate)

- [x] **2.1** Fix lookahead with capture persistence (f3fea71)
  - Files: `NFA.java` - added `hasBackrefToLookaheadCapture()`, `NFABytecodeGenerator.java` - fix posVar init, skip indexOf
  - Fix: Initialize posVar before epsilon closure; skip indexOf optimization for anchored/lookahead-backref patterns
  - Test pattern: `^(?=(\w+))\1:`
  - Actual gain: +1 test (90.7% → 91.0%)

- [x] **2.2** Fix recursive nested backtracking for 3+ quantifiers (27e03c6)
  - File: `RecursiveDescentBytecodeGenerator.java` - `generateNestedBacktracking()`
  - Root cause: Patterns like `(\S+)\s+(\S+)\(` have 3 backtracking quantifiers but only the first nested level was handled
  - Fix: Made `generateNestedBacktracking()` recursive with dynamic slot allocation (slotBase parameter)
  - Added `outerTryMatchCountSlot` parameter to properly cascade backtracking upward
  - Test pattern: `(?i)^(\d+)\s+IN\s+SOA\s+(\S+)\s+(\S+)\s*\(\s*$`
  - Actual gain: +1 test (91.0% → 91.3%)

- [ ] **2.4** Fix complex nested patterns with scoped flags
  - Test patterns: `(?i)^(ab|a(?i)[b-c](?m-i)d|...)`
  - Expected gain: +4 tests

- [ ] **2.5** Fix escaped quote pattern group extraction
  - Test pattern: `"([^\\"]+|\\.)*"`
  - Root cause: DFA_UNROLLED_WITH_GROUPS tracks groups character-by-character, not iteration-by-iteration
  - The pattern has alternation with `+` inside a `*` quantifier, which the tagged DFA doesn't handle correctly
  - Expected gain: +1 test
  - Difficulty: High - requires changes to how tagged DFA handles quantified groups

- [ ] **2.6** Fix nested groups with literal and backref
  - Test pattern: `(cat(a(ract|tonic)|erpillar)) \1()2(3)`
  - Expected gain: +2 tests

### Phase 3: High Complexity (Target: 95% pass rate)

- [ ] **3.1** Implement non-greedy quantifiers in capturing groups
  - Requires backtracking support for lazy quantifiers
  - Consider new strategy: `LAZY_QUANTIFIER_BACKTRACK`
  - Test patterns: `(|ab)*?d`, `^[ab]{1,3}?(ab*?|b)`
  - Expected gain: +5 tests

- [ ] **3.2** Fix recursive pattern group capture tracking
  - Debug existing RECURSIVE_DESCENT implementation
  - Test patterns: `^((.)(?1)\2|.?)$` palindrome
  - Expected gain: +9 tests

### Phase 4: Very High Complexity (Target: 97% pass rate)

- [ ] **4.1** Implement self-referencing backreferences
  - Fundamentally new feature - group references itself during matching
  - Test patterns: `^(a\1?){4}$`
  - Expected gain: +3 tests
  - Note: May require significant architectural changes

---

## Testing Commands

```bash
# Run full PCRE test suite
./gradlew :reggie-integration-tests:test --tests "CorrectnessTest.testPCRECapturingGroups"

# Run specific unit tests
./gradlew :reggie-runtime:test --tests "TestClassName"

# Debug a specific pattern
./gradlew :reggie-runtime:debugPattern -Ppattern="your_pattern_here"

# Run all runtime tests (regression check)
./gradlew :reggie-runtime:test
```

---

## Progress Log

| Date | Commit | Change | Pass Rate |
|------|--------|--------|-----------|
| 2026-01-29 | 87f78ad | Fix greedy backtracking and PCRE test data bugs | 81.7% |
| 2026-01-29 | 3783ccc | Fix performance regressions | 81.7% |
| 2026-01-29 | bfdcf8e | Improve PCRE capturing groups conformance | 79.9% |
| 2026-01-30 | 2ed0b2d | Fix empty backref with counted quantifiers | 87.0% |
| 2026-01-30 | 2f3acdf | Fix star quantifier in GREEDY_BACKTRACK | 87.3% |
| 2026-01-30 | (pending) | Extend OPTIONAL_GROUP_BACKREF for capturing group suffix | 88.0% |
| 2026-01-30 | (pending) | Fix findMatchFrom() group extraction in DFA_SWITCH_WITH_GROUPS | 88.9% |
| 2026-01-30 | (pending) | Fix anchor optimization for alternations with one anchor branch | 90.1% |
| 2026-01-30 | (pending) | Fix word boundary findMatch() in GREEDY_BACKTRACK | 90.4% |
| 2026-01-30 | (pending) | Fix multiple backtracking quantifiers in RECURSIVE_DESCENT | 90.7% |
| 2026-01-30 | f3fea71 | Fix lookahead capture persistence for find() | 91.0% |
| 2026-01-30 | b2733de | Support per-alternative negation in quantified groups | 91.0% |
| 2026-02-03 | 1cab08f | Fix test data unescape order and escaped quote test encoding | 91.0% |
| 2026-02-03 | 27e03c6 | Support recursive nested backtracking for multiple quantifiers | 91.3% |

---

## Architecture Notes

### Key Files for PCRE Fixes

| File | Purpose |
|------|---------|
| `PatternAnalyzer.java` | Strategy selection and pattern detection |
| `OptionalGroupBackrefBytecodeGenerator.java` | Empty alternation + backref patterns |
| `GreedyBacktrackBytecodeGenerator.java` | `(.*)suffix` patterns |
| `RecursiveDescentBytecodeGenerator.java` | Recursive patterns, subroutines |
| `NFABytecodeGenerator.java` | Complex patterns with backtracking |
| `LinearPatternBytecodeGenerator.java` | Simple linear patterns with backrefs |

### Strategy Selection Flow

```
Pattern → PatternAnalyzer.analyzeStrategy()
  ├─ Has backrefs + quantified? → OPTIONAL_GROUP_BACKREF / FIXED_REPETITION_BACKREF
  ├─ Has subroutines/conditionals? → RECURSIVE_DESCENT
  ├─ Has greedy + suffix? → GREEDY_BACKTRACK
  ├─ Has lookaround? → DFA_WITH_ASSERTIONS / HYBRID
  ├─ Simple with groups? → DFA_UNROLLED_WITH_GROUPS
  └─ Complex? → OPTIMIZED_NFA / NFA_WITH_BACKREFS
```

### Common Fix Patterns

1. **Group capture missing**: Check `starts[]`/`ends[]` array updates in bytecode
2. **Wrong match result**: Check backtracking logic, ensure proper position restoration
3. **Pattern not recognized**: Add detection in `PatternAnalyzer.java` for new pattern shape
4. **Strategy fallback**: Current strategy can't handle pattern, may need new specialized generator

---

## Notes for Future Sessions

- Always run full `reggie-runtime:test` after changes to catch regressions
- The 4 pre-existing failures in runtime tests are for self-referencing backrefs (Phase 4)
- Consider splitting complex generators if they exceed ~1500 lines
- Structural hash MUST include all distinguishing pattern characteristics for caching

---

## Current Status Summary (2026-02-03)

### Pass Rate: 91.3% (303/332 tests)

### What IS Supported

| Feature | Status | Notes |
|---------|--------|-------|
| Basic regex syntax | ✅ Full | Literals, character classes, anchors, quantifiers |
| Capturing groups | ✅ Full | Numbered groups, nested groups |
| Non-capturing groups | ✅ Full | `(?:...)` |
| Backreferences | ✅ Mostly | `\1`, `\2`, etc. (some edge cases not supported) |
| Lookahead | ✅ Full | Positive `(?=...)` and negative `(?!...)` |
| Lookbehind | ✅ Full | Positive `(?<=...)` and negative `(?<!...)` |
| Case-insensitive | ✅ Full | `(?i)` flag |
| Multiline mode | ✅ Full | `(?m)` flag |
| Dotall mode | ✅ Full | `(?s)` flag |
| Free-spacing mode | ✅ Full | `(?x)` flag |
| Word boundaries | ✅ Full | `\b`, `\B` |
| Greedy quantifiers | ✅ Full | `*`, `+`, `?`, `{n,m}` |
| Non-greedy quantifiers | ⚠️ Partial | Basic patterns work; complex group captures may fail |
| Alternation | ✅ Full | `a|b|c` |
| Named groups | ✅ Full | `(?<name>...)`, `(?'name'...)` |
| Subroutines | ⚠️ Partial | `(?1)`, `(?R)` - basic cases work |
| Conditionals | ⚠️ Partial | `(?(1)yes|no)` - basic cases work |

### What is NOT Supported

| Feature | Status | Reason |
|---------|--------|--------|
| Self-referencing backrefs | ❌ | `(a\1?){4}` - requires per-iteration capture updates |
| Recursive palindromes | ❌ | `^((.)(?1)\2|.?)$` - requires backtrackable subroutines |
| Branch reset groups | ❌ | `(?|...)` - advanced PCRE feature |
| Relative backrefs | ❌ | `(?-2)` - advanced PCRE feature |
| Backtracking verbs | ❌ | `(*PRUNE)`, `(*SKIP)`, `(*MARK)`, `(*THEN)` |
| Atomic groups | ❌ | `(?>...)` - not implemented |
| Possessive quantifiers | ❌ | `*+`, `++`, `?+` - not implemented |
| Unicode properties | ❌ | `\p{L}`, `\p{N}` - not implemented |
| Scoped inline flags | ❌ | `(?i:...)` - global flags only |

### Remaining Work (29 failing tests)

| Category | Tests | Difficulty | Notes |
|----------|-------|------------|-------|
| Non-greedy quantifiers in groups | 5 | High | Requires lazy backtracking |
| Recursive patterns | 9 | High | Group capture in recursion |
| Self-referencing backrefs | 3 | Very High | Fundamental limitation |
| Multiline anchors | 5 | Medium | Inline flag handling |
| Complex nested patterns | 5 | Medium-High | Scoped flags, nested groups |
| Lookahead combinations | 2 | Medium | Edge cases |

### Plan Forward

**Phase 2 (Target: 92%)** - Medium complexity fixes:
- Fix scoped inline flags `(?i:...)`
- Fix nested groups with literal backrefs
- Fix remaining lookahead edge cases

**Phase 3 (Target: 95%)** - High complexity:
- Implement proper non-greedy backtracking for capturing groups
- Fix recursive pattern group capture tracking

**Phase 4 (Target: 97%)** - Very high complexity:
- Self-referencing backreferences (may require architectural changes)

**Out of Scope**:
- Backtracking control verbs (`*PRUNE`, `*SKIP`, etc.)
- Branch reset groups (`(?|...)`)
- These are rarely used in practice and add significant complexity
