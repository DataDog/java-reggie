# Changelog

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

