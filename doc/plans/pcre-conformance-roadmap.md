# PCRE Conformance Roadmap

**Current Status**: 98.1% pass rate (257/262 evaluated tests, 364-entry corpus). 102 of the 364
corpus entries error out as unsupported syntax rather than being evaluated pass/fail — the pass
rate describes only the 262 evaluable entries, not the full corpus. See
`doc/agents-fallback-and-limitations.md` for the fallback-condition breakdown.
**Last Updated**: 2026-07-07
**Target**: no fixed percentage — see "Architectural Ceiling" below. 100% is not a goal; the
goal is 100% of the regular-language-representable subset of the corpus, with every non-regular
pattern transparently declined rather than silently mishandled.

## Architectural Ceiling: 100% Is Not the Target

Reggie's core guarantee is **provable O(n) matching with no unbounded backtracking** — every
native strategy is a deterministic automaton (DFA), a Thompson-construction NFA/PikeVM simulation,
or a bytecode-generated variant of one of those. This is a fundamentally different execution model
from PCRE's own engine, which is a *recursive backtracking interpreter* — PCRE's grammar includes
constructs (self-embedding recursive subroutines, backtracking control verbs) that require
unbounded call-stack depth or priority-ordered backtracking to implement correctly, i.e. they
describe **context-free languages, not regular ones**. No amount of engineering effort inside
Reggie's automaton-based model can add support for these without either (a) abandoning the O(n)
guarantee, or (b) delegating to a backtracking interpreter (`compileAllowingFallback()`). This is
not a temporary implementation gap — it is a structural consequence of choosing automata over
backtracking.

Concretely (check any pattern's classification with `./gradlew :reggie-runtime:debugPattern -Ppattern="..."`):

- **Self-embedding recursive subroutines** (palindrome-style `(?1)`/`(?R)` where the recursive
  call appears inside a structure that recurses on both sides of a comparison, e.g.
  `^((.)(?1)\2|.?)$`) throw `UnsupportedPatternException` with the explicit message *"pattern is
  context-free, not regular"*. This is by design (`RuntimeCompiler.fallbackOrThrow`), not a bug to
  fix. **Not all recursive-subroutine patterns fall in this bucket** — `(?1)` calls that don't
  self-embed (e.g. `^(a?)+b(?1)a`) compile natively via `RECURSIVE_DESCENT` with no exception; only
  the genuinely context-free subset is declined. See Category 3 below for the split.
- **Backtracking control verbs** (`(*MARK)`, `(*PRUNE)`, `(*SKIP)`, `(*THEN)`) are inherently
  imperative backtracking-engine directives with no automaton equivalent — permanently out of
  scope (Category 10).
- Some declines are **not** permanent architecture limits but simply haven't had the fix applied
  yet — e.g. the alternation-priority conflict between DFA longest-match and PCRE's leftmost-first
  semantics (Category 9) could plausibly be resolved by routing to a PikeVM-backed strategy
  (Thompson priority order matches leftmost-first) instead of a DFA-backed one, and the
  capture-ambiguous backref/group-span decline (Category 9) is exactly the class of problem the
  rich-API-hybrid design (lazy JDK delegation for group-span extraction only, keeping the fast
  boolean path native) already solves for other strategies — it just hasn't been extended to this
  pattern shape. These stay in the TODO list, not the ceiling.

**This document does not yet have an exact count of how many of the 364 corpus entries are
permanently out of scope (context-free/backtracking-verb) vs. fixable-in-linear-time bugs** — that
audit is real follow-up work, not a number to guess at here. Until that audit exists, treat any
"97%" or similar target as aspirational shorthand for "everything regular," not a literal
percentage of the raw 364-entry denominator.

## Engine Architecture (Context for Fix Difficulty)

Reggie is not a single algorithm — it's a **strategy dispatcher** that picks one of 28+
bytecode-generated matcher shapes per pattern (`PatternAnalyzer.analyzeStrategy()`), spanning three
families:

- **DFA-backed** (`DFA_UNROLLED`, `DFA_SWITCH`, `DFA_UNROLLED_WITH_GROUPS`, ...): subset-construct
  a deterministic automaton at compile time; stateless, shared/cached instances. Longest-match by
  construction — this is *why* Category 9's alternation-priority conflict exists, not a bug in one
  generator.
- **NFA/PikeVM-backed** (`OPTIMIZED_NFA`, `PIKEVM_CAPTURE`, `BITSTATE_CAPTURE`, ...): Thompson
  construction simulated without backtracking (Pike's algorithm — parallel thread simulation, or
  bitset-based state tracking); naturally leftmost-first, supports lazy quantifiers and capture
  groups that DFA determinization can't represent.
- **Bounded-backtracking bytecode generators** (`GREEDY_BACKTRACK`, `RECURSIVE_DESCENT`,
  `OPTIMIZED_NFA_WITH_BACKREFS`, `PINNED_BACKREFERENCE`, ...): generate backtracking *bytecode*,
  but only for pattern shapes `PatternAnalyzer` has proven have bounded retry counts (e.g.
  `(.*)suffix`, fixed-arity backreferences) — this is why these strategies can still claim O(n) /
  ReDoS-safety despite the name "backtrack": the backtracking is shape-restricted at compile time,
  not the general unbounded kind PCRE's interpreter does.

Full strategy list and dispatch flow: `AGENTS.md` → `doc/ARCHITECTURE.md` (Component Details) and
`doc/agents-fallback-and-limitations.md` (fallback trigger conditions). When triaging a failure
category below, the fix difficulty depends on which family the pattern currently routes to — check
with `./gradlew :reggie-runtime:debugPattern -Ppattern="..."` before assuming a category is a
"deep engine bug" vs. "route to a different existing strategy."

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

This category has two distinct root causes:

**2a. Architectural ceiling — declined by design**:
- `(|ab)*?d` on "abd" — `RECURSIVE_DESCENT` throws `UnsupportedPatternException`: *"lazy
  quantifier: requires shortest-match semantics not supported by this strategy"*. The
  zero-width-alternative + lazy-quantifier combination requires trying the shortest match first
  and backing off, which this strategy family declines rather than mishandles. A fix would mean
  routing this shape to a PikeVM-backed strategy instead (see Engine Architecture above); no fix
  exists yet.

**2b. Real bug — compiles natively, wrong result**:
- `^[ab]{1,3}?(ab*?|b)` on "aabbbbb" — routes to `BITSTATE_CAPTURE`, compiles without error, but
  returns the wrong group span (got 'abbbbb', expected 'a'). This is implementable in linear time
  (`BITSTATE_CAPTURE` already handles the pattern shape, just incorrectly) — a genuine fix
  candidate, not an architecture question.
- `^[ab]{1,3}?(ab*|b)`, `(?i)(a+|b){0,1}?`, `(([a-c])b*?\2){3}` — routing and pass/fail status not
  yet classified.

**Difficulty**: 2a — not applicable (declined by design). 2b — Medium (existing strategy, wrong
group-span logic).
**Impact**: 2a's pattern belongs outside the pass-rate denominator (permanently declined); 2b's
patterns are real +N candidates once classified.

---

### Category 3: Recursive Patterns (9 tests)

`RuntimeCompiler` distinguishes self-embedding (context-free) recursion from simple, non-embedding
subroutine calls, so this category has two distinct root causes:

**3a. Architectural ceiling — self-embedding recursion, declined by design**:
- `^((.)(?1)\2|.?)$` (palindrome), `^(.|(.)(?1)\2)$`, `^(.|(.)(?1)?\2)$` — all three throw
  `UnsupportedPatternException`: *"recursive subroutine requires intra-call backtracking: pattern
  is context-free, not regular — use compileAllowingFallback() for JDK delegation"*. These
  patterns describe balanced/palindromic structures — provably not regular languages.
  `compileAllowingFallback()` is the correct and only answer for users who need this; no native fix
  is possible without dropping the O(n) guarantee.

**3b. Real bug — compiles natively**:
- `^(a?)+b(?1)a`, `^(a?)b(?1)a` — both route to `RECURSIVE_DESCENT` without error (a simple,
  non-self-embedding subroutine reference — this is a regular-language-representable pattern).
  Match/group-span correctness against JDK is not yet confirmed; that confirmation is the
  remaining TODO here.

**Difficulty**: 3a — not applicable (declined by design, permanent). 3b — unknown until confirmed.
**Impact**: 3a's 3 patterns belong outside the pass-rate denominator (permanently declined); 3b's
2 patterns are a real, smaller TODO.

---

### Category 4: Self-Referencing Backreferences (3 tests) — resolved

**Patterns**:
- `^(a\1?)(a\1?)(a\2?)(a\3?)$` - matches 'aaaa', 'aaaaaa'
- `^(a\1?){4}$` - matches 'aaaa'

Both route to `RECURSIVE_DESCENT` and pass in `testPCRECapturingGroups` (not present in either the
Failures or Errors list). This is not an architectural ceiling case — a self-referencing backref
within a single group is regular (bounded per-iteration state), unlike Category 3a's cross-branch
palindrome recursion. The "What is NOT Supported" table further down is corrected accordingly.

**Difficulty**: resolved, no further work.

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

### Category 9: Complex Nested Patterns (5 tests remaining) — 2 of 5 are architecture-tied, not generic bugs

**Patterns**:
- `"([^\\"]+|\\.)*"` on escaped quote string — routes to `OPTIMIZED_NFA`, **declines** with
  `UnsupportedPatternException`: *"alternation priority conflict: DFA longest-match vs NFA
  first-alternative"*. This is a real architecture gap, not an unfixable ceiling: DFA-backed
  strategies determinize to longest-match by construction (see Engine Architecture note above),
  which conflicts with PCRE's leftmost-first alternation semantics for this pattern shape. Fix
  candidate: route this shape to a PikeVM-backed strategy instead of the DFA/NFA path currently
  selected — Thompson priority order is leftmost-first natively. Not yet attempted.
- `(cat(a(ract|tonic)|erpillar)) \1()2(3)` — routes to `OPTIMIZED_NFA`, **declines** with
  `UnsupportedPatternException`: *"capture-ambiguous group bindings: group spans require
  java.util.regex semantics"*. This is exactly the class of problem the rich-API-hybrid design
  (lazy JDK `Pattern` delegation for group-span extraction only, on strategies whose boolean
  match/`find()` stay native and fast) was built to solve for other backref/lookahead strategies —
  it hasn't been extended to this `OPTIMIZED_NFA` pattern shape yet. Fix candidate: extend hybrid
  dispatch here, don't attempt native span-tracking (that's the approach explicitly rejected for
  other strategies as reintroducing backtracking complexity).
- `(?:(?!foo)...|^.{0,2})bar(.*)` on 'foobar crowbar etc' — compiles natively and produces the
  wrong group 1 span; confirmed as a real bug in `testPCRECapturingGroups`'s Failures list, not a
  decline. No known architectural angle; ordinary bug-fix candidate.
- `^(ba|b*){1,2}?bc`, `(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)` — status not yet checked against
  `debugPattern` or the corpus test's pass/fail/error lists; no architectural angle known yet.
- ~~`(?i)^(\d+)\s+IN\s+SOA\s+(\S+)\s+(\S+)\s*\(\s*$`~~ - **FIXED** (27e03c6) - recursive nested backtracking

**Difficulty**: The two architecture-tied patterns need a strategy-routing change (Medium — reuse
existing PikeVM/hybrid infrastructure, don't build new machinery); the other three are
Medium-High pending individual triage.
**Impact**: +5 tests (was +6)

---

### Category 10: Unsupported PCRE Features (19 errors) — split by whether it's a ceiling or just unwritten

- **Backtracking control verbs**: `(*MARK:)`, `(*PRUNE:)`, `(*SKIP:)`, `(*THEN:)` — **architectural
  ceiling**. These are imperative directives to a backtracking interpreter's control flow; there is
  no automaton equivalent. Permanently out of scope regardless of engineering effort (see
  "Architectural Ceiling" above).
- **Relative backreferences**: `(?-2)` — reference group by relative position. Not a ceiling —
  purely syntax sugar over an absolute-index backref, which Reggie already supports. Unwritten,
  not unwritable; low-difficulty parser-level TODO if it turns out to matter.
- **Branch reset groups (numbered)**: `(?|...)` — now supported via `RECURSIVE_DESCENT`; see
  `BranchResetCorrectnessTest`.
- **Branch reset groups (named)**: `(?|(?'a'...)|(?'a'...))` — named-capture variant not yet
  supported. Not a ceiling — the numbered form already works, this is the same mechanism with name
  resolution added at the parser level.

**Status**: Backtracking verbs are permanently out of scope. Relative backrefs and named
branch-reset groups are low-priority unwritten features, not architecture limits — deprioritized
because they're rare in practice, not because they're impossible.

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

### Phase 3: High Complexity — split per Categories 2 and 3 above; no fixed target

- [ ] **3.1** Fix `^[ab]{1,3}?(ab*?|b)`-style lazy-quantifier group-span bug (Category 2b)
  - `BITSTATE_CAPTURE` already compiles this natively — wrong group span, not a missing feature
  - Debug existing strategy's group-span logic for lazy quantifiers, no new strategy needed
  - Test pattern: `^[ab]{1,3}?(ab*?|b)`
  - Expected gain: up to +4 tests, pending individual re-check of the other Category 2 patterns
  - **Not in scope**: `(|ab)*?d` — this one is declined by design (Category 2a, architectural
    ceiling), do not attempt to "fix" it; a fix here means routing to a different existing
    strategy family (e.g. PikeVM) if one is found to support it, not new backtracking machinery

- [ ] **3.2** Re-verify and fix non-self-embedding recursive-subroutine patterns (Category 3b)
  - `^(a?)+b(?1)a`, `^(a?)b(?1)a` — both compile via `RECURSIVE_DESCENT` with no exception; check
    whether the match/group result is actually correct against JDK, fix if not
  - Expected gain: up to +2 tests
  - **Not in scope**: `^((.)(?1)\2|.?)$` and the other self-embedding palindrome patterns —
    declined by design (Category 3a, architectural ceiling, context-free not regular); "debugging
    the existing RECURSIVE_DESCENT implementation" will not fix these, they are correctly declined

### Phase 4: Reconciliation, not "very high complexity" — see Category 4 above

- [x] **4.1** Implement self-referencing backreferences (2026-05-07)
  - Added `PatternAnalyzer.hasSelfReferencingBackref()` compile-time predicate
  - `visitGroup`: write partial-open sentinel (`groups[start]=pos`, `groups[end]=-1`) before calling child, guarded by `hasSelfReferencingBackref`
  - `visitBackreference`: detect partial-open state and return zero-length match (C-03, atomic with C-01)
  - `visitQuantifier`: emit per-iteration partial-open write at top of both min-loop and greedy loop for self-referencing child groups (C-02)
  - Test patterns: `^(a\1?){4}$`, `^(a\1?)(a\1?)(a\2?)(a\3?)$`
  - Actual gain: +3 tests

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
| 2026-05-07 | (pending) | Implement self-referencing backreferences (Phase 4.1) | ~92.2% |

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

## Current Status Summary

### Pass Rate: 98.1% (257/262 evaluated tests, 364-entry corpus)

`testPCRECapturingGroups` reports 257 passed, 5 failed, 102 errors. "Failed" means the pattern
compiled natively and produced a wrong result (real bugs); "Errors" means the pattern was declined
(`UnsupportedPatternException`) or rejected at parse time — a mix of permanent architectural
ceiling and unwritten-but-writable syntax (see split above and below).

### What IS Supported

| Feature | Status | Notes |
|---------|--------|-------|
| Basic regex syntax | ✅ Full | Literals, character classes, anchors, quantifiers |
| Capturing groups | ✅ Full | Numbered groups, nested groups |
| Non-capturing groups | ✅ Full | `(?:...)` |
| Backreferences | ✅ Mostly | `\1`, `\2`, etc. (some edge cases not supported) |
| Self-referencing backrefs | ✅ Full | `(a\1?){4}` — routes to `RECURSIVE_DESCENT`, passes in the corpus |
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
| Subroutines | ⚠️ Partial | `(?1)`, `(?R)` — non-self-embedding calls work (`RECURSIVE_DESCENT`); self-embedding/context-free recursion is a permanent architectural ceiling, see below |
| Conditionals | ⚠️ Partial | `(?(1)yes|no)` — basic cases work; `(?(1)\1)` per-iteration conditional-backref combination fails (see Remaining Failures) |
| Atomic groups | ✅ Full | `(?>...)` — routes to `PIKEVM_CAPTURE` |
| Possessive quantifiers | ✅ Full | `*+`, `++`, `?+` — routes to `PIKEVM_CAPTURE` |
| Unicode properties | ✅ Full | `\p{L}`, `\p{N}` — routes to `STATELESS_LOOP` |

### What is NOT Supported

**Permanent architectural ceiling** (automaton model can't represent these — not a fix backlog):

| Feature | Reason |
|---------|--------|
| Self-embedding recursive subroutines | `^((.)(?1)\2|.?)$` — context-free, not regular; throws `UnsupportedPatternException` by design. Non-self-embedding `(?1)` calls (e.g. `^(a?)+b(?1)a`) are NOT in this bucket — they compile natively, see Category 3 |
| Backtracking control verbs | `(*PRUNE)`, `(*SKIP)`, `(*MARK)`, `(*THEN)` — imperative backtracking-interpreter directives, no automaton equivalent |

**Unwritten but not unwritable** (real TODO backlog, no architecture change needed):

| Feature | Status | Notes |
|---------|--------|-------|
| Branch reset groups (named) | ⚠️ Partial | `(?|(?'a'aaa)|(?'a'b))` — numbered form works via `RECURSIVE_DESCENT`; named-capture variant currently rejected at parse time with "Duplicate group name" (the parser's duplicate-name check runs before branch-reset dedup logic) |
| Relative backrefs | ❌ unwritten | `(?-2)` — parser currently reports "Expected ':' or ')' after modifiers", i.e. it isn't recognized as a backref form at all yet; syntax sugar over absolute-index backrefs, which already work |
| Scoped inline flags | ❌ unwritten | `(?i:...)` — global flags only |

### Remaining Failures (5 patterns compile natively, wrong result)

Live output from `testPCRECapturingGroups`:

| Pattern | Expected | Category |
|---------|----------|----------|
| `(abc)\100` | matches 'abc@' (octal escape `\100` = '@') | New — octal-escape-adjacent-to-backref parsing, not yet in a category above |
| `(abc)\1000` | matches 'abc@0' | Same root cause as above — octal escape length/boundary handling |
| `(?:(?!foo)...|^.{0,2})bar(.*)` | group 1 = ' etc' on 'foobar crowbar etc' | Category 9 — confirmed real bug, not a decline |
| `^(a(?(1)\1)){4}$` | matches 'aaaaaaaaaa' | New — conditional + backref per iteration, distinct from the working `(a\1?){4}` self-referencing-backref case |
| `^(\D*)(?=\d)(?!123)` | matches 'ABC445' | Category 6 — lookahead combination |

### Remaining Errors (102 patterns declined or parse-rejected)

The error list spans the two known permanent-ceiling categories (self-embedding recursion,
backtracking verbs) and the known unwritten-syntax cases (relative backrefs, named branch-reset)
documented above, plus three decline reasons not yet assigned to a category in this document:

- `capturing group with nullable content and nullable outer quantifier: PIKEVM_CAPTURE diverges;
  TDFA POSIX last-match span also incorrect` — the single largest cluster in the error list.
- `anchor condition diluted in DFA construction` — second-largest cluster.
- `lookahead inside quantified group` — smaller cluster.

These three have not yet been triaged into ceiling-vs-bug the way Categories 2, 3, and 9 were in
this update. That triage is the next concrete step, not a number to guess at here.

### Plan Forward

There is no fixed percentage target (see "Architectural Ceiling" above). The plan is to work
category-by-category, separating each remaining failure into "declined by design — leave it" vs.
"compiles natively and is wrong — fix it," the same way Categories 2, 3, and 9 were split:

1. Triage the three untriaged decline-reason clusters (nullable-content/PIKEVM divergence,
   anchor-dilution in DFA construction, lookahead-inside-quantified-group) into ceiling-vs-bug,
   using `debugPattern` on representative corpus entries from each cluster.
2. Check `^(ba|b*){1,2}?bc` and `(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)` (Category 9's two remaining
   unclassified patterns) against `debugPattern` and the corpus test's pass/fail/error lists.
3. Fix the 5 confirmed real bugs listed under "Remaining Failures": the octal-escape parsing pair
   (`(abc)\100`, `(abc)\1000`), the negative-lookahead-in-alternation capture bug, the
   conditional-plus-backref-per-iteration bug, and the lookahead-combination bug.
4. For the two Category 9 architecture-tied patterns, prototype the PikeVM-routing fix (alternation
   priority) and the rich-API-hybrid extension (capture-ambiguous backref) — both reuse existing
   infrastructure rather than requiring new strategies.
5. Once the three untriaged clusters are resolved, a meaningful percentage target becomes possible
   — one that explicitly excludes the permanent-ceiling patterns from the denominator (see
   Architectural Ceiling), not 100% of the raw 364-entry corpus.

**Permanently out of scope** (architectural ceiling, restated from above):
- Self-embedding recursive subroutines (context-free, not regular)
- Backtracking control verbs (`*PRUNE`, `*SKIP`, `*MARK`, `*THEN`)
