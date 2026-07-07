# Changelog

## [1.0.0] - Unreleased

### 1.0.0 pre-release checklist
- [x] Fuzz divergence budget ≤ 30 (currently 28 — all remaining gaps are group-span on adversarial inputs)
- [x] Zero boolean divergences (no false positives / false negatives on real-world patterns)
- [x] `StrategyCorrectnessMetaTest` 0 mismatches
- [x] PCRE conformance 100% (53/53)
- [x] Thread-safety contract documented: `RuntimeCompiler` is thread-safe; matcher instances are not
- [x] Thread-safety stress test for `RuntimeCompiler` concurrent compile/match (`RuntimeCompilerTest.testConcurrentCompilation`, `DfaMatcherConcurrencyTest`)

### Changes since 0.3.0
- fix: route B-CGG-1 (negated CharClass in SPECIALIZED_CONCAT_GREEDY_GROUP) to JDK fallback — eliminates false negatives for patterns like `[1]([^b]{2})`
- fix: route B-SQG-1 (inner quantifier min>1 in SPECIALIZED_QUANTIFIED_GROUP) to JDK fallback — eliminates false positives for patterns like `(c{2}){1,}`
- fix: fuzz divergence budget 34 → 28 (B-CGG-1 + B-SQG-1 guards)
- fix: atomic groups and possessive quantifiers (#92)
- fix: DFA_UNROLLED_WITH_GROUPS group-span divergences A1+A2 routed to PIKEVM_CAPTURE (#90)
- fix: per-config backref NFA, cache collision fixes, CRLF anchors, fallback guards (#89)
- fix: DFA assertion evaluation for sandwich lookaround and lookahead-in-quantifier (#87, #88)
- fix: POSIX aliases, inline flag regression tests, backref digit disambiguation (#88)

## [Unreleased]

- feat: ReggieOption/@RegexPattern fallback substrate + PIKEVM routing groundwork
- feat: split oversized DFA-switch bytecode to avoid method-too-large failures
- feat: enable zero-divergence fuzz gate permanently (divergence budget 18→78→69 as capture oracle expanded then Class A nullable-alternation ratcheted back)
- fix: anchor/alternation PIKEVM routing + B5/B12 backref support
- fix: promote anchor+alternation and diluted-start-anchor patterns to PIKEVM_CAPTURE
- fix: route non-capturing alternationPriorityConflict patterns to PIKEVM_CAPTURE
- fix: guard empty-branch alternations from PIKEVM_CAPTURE
- fix: evaluate PikeVM start-anchors against search-region origin in find()
- fix: route anchorConditionDiluted patterns to OPTIMIZED_NFA
- fix: PikeVM named-group and anchor support; remove TDFA capture-ambiguity fallback
- fix: PikeVM leftmost-first for nullable/optional/leading-end-anchor alternations
- fix: guard anchor-in-quantifier patterns in FallbackPatternDetector
- fix: guard outer-quantifier-on-capturing-group backref patterns
- fix: guard empty/nullable group backref and fix group-span delegation
- fix: guard remaining fuzz divergences; gate divergence count at 0
- fix: narrow nullable guard and update stale strategy assertions
- refactor: remove dead always-null fallback hooks
- test/docs: anchor-in-quantifier spike — failing tests + route-or-keep analysis
- test/docs: lookahead engine spike — failing tests + root-cause classification
- test/docs: backref engine gaps spike — failing tests + feasibility map

## [0.3.0] - 2026-05-30

- #48 feat: support self-referencing backreferences in RECURSIVE_DESCENT
- #45 [feature] Named group extraction: add group(String name) to MatchResult
- #47 [feature] split(String input, int limit) — add limit parameter to split()
- #46 [feature] Stateful streaming replacement: appendReplacement / appendTail equivalent
- #27 [bug] Multiple backreferences to same group produce false positives
- #35 [pcre] Inline (?m) flag inside a group doesn't activate multiline mode mid-pattern
- #30 [bug] Only first alternative in lookbehind alternation is checked
- #29 [bug] Unbounded quantifier after lookbehind always fails to match
- #36 [pcre] Lookahead combined with nested alternation produces wrong group captures
- #67 feat: lazy DFA cache (R1+R2) over OPTIMIZED_NFA for large anchor-free patterns
- #68 Improve runtime compatibility, capture extraction, and token-sequence execution

## [0.2.0] - 2026-05-08

- #48 feat: support self-referencing backreferences in RECURSIVE_DESCENT
- #45 [feature] Named group extraction: add group(String name) to MatchResult
- #47 [feature] split(String input, int limit) — add limit parameter to split()
- #46 [feature] Stateful streaming replacement: appendReplacement / appendTail equivalent

## [0.1.0] - 2026-05-07

- First public release.

