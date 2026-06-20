# Changelog

## [Unreleased]

## [0.4.0] - 2026-06-20

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

