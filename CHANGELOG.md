# Changelog

## [0.4.0] - Unreleased

### 1.0.0 readiness tracking (not yet released; tracked here for visibility)
- [x] Fuzz divergence budget ≤ 30 (currently 28 — all remaining gaps are group-span on adversarial inputs)
- [x] Zero boolean divergences (no false positives / false negatives on real-world patterns)
- [x] `StrategyCorrectnessMetaTest` 0 mismatches
- [x] PCRE conformance 100% (53/53)
- [x] Thread-safety contract documented: `RuntimeCompiler` is thread-safe; matcher instances are not
- [x] Thread-safety stress test for `RuntimeCompiler` concurrent compile/match (`RuntimeCompilerTest.testConcurrentCompilation`, `DfaMatcherConcurrencyTest`)

### Changes since 0.3.0
- perf: JIT-size DFA_SWITCH codegen (state-set bucketing by estimated bytecode size + per-accept anchor helpers) — generated methods over HotSpot's HugeMethodLimit ran INTERPRETED; target shape 21,477 → 2,386 bytecodes, dfa.find 120.6 → 4.7 ns/char
- perf: hybrid re-admission for start-anchored PIKEVM/BITSTATE patterns + hybrid nfa-half mirrors the standalone engine choice (BitState for BITSTATE_CAPTURE originals)
- perf: lazy-aware captureless DFA retry — lazy patterns hybridize via a lazy-aware NFA rebuild with central leftmost-first certification
- perf: RE2 leftmost-first thread pruning in the subset construction (priority-aware captureless retries, anchor-free NFAs) — stack-frame family DFAs go 19 states/13 unresolved conflicts → 12/0 with JDK-identical spans
- perf: priority-aware retry for alternation/optional-quantifier originals and hybrid admission for capture give-back RECURSIVE_DESCENT originals (PikeVM capture half)
- perf: JIT method-size gate — generated classes whose largest method exceeds 8000 bytecodes decline to the JDK fallback when ALLOW_JDK_FALLBACK is set (strict compile keeps the native matcher; refusal set unchanged)
- Benchmarked on the 513-pattern logs-backend corpus (workspace-jb, same-run rust/jdk controls): matched sweep 340 → 182 µs (1.26x faster than rust-regex, 3.5x faster than JDK), no-match sweep 943 → 637 µs (2.7x faster than rust, 52x faster than JDK); hybrid routing 28 → 102 patterns
- fix: find() parity battery caught MULTI_GROUP_GREEDY word-boundary acceptance and RECURSIVE_DESCENT (.*)end no-match (both routed away from the diverging engines)
- fix: hybrid capture extraction re-matches the DFA span as a standalone string — END anchors inside alternation branches now decline the hybrid (out-of-context $/\Z firing at the span boundary)
- fix: hybrid admission mirrors the standalone FallbackPatternDetector guards
- fix: route B-CGG-1 (negated CharClass in SPECIALIZED_CONCAT_GREEDY_GROUP) to JDK fallback — eliminates false negatives for patterns like `[1]([^b]{2})`
- fix: route B-SQG-1 (inner quantifier min>1 in SPECIALIZED_QUANTIFIED_GROUP) to JDK fallback — eliminates false positives for patterns like `(c{2}){1,}`
- fix: fuzz divergence budget 34 → 28 (B-CGG-1 + B-SQG-1 guards)
- fix: atomic groups and possessive quantifiers (#92)
- fix: DFA_UNROLLED_WITH_GROUPS group-span divergences A1+A2 routed to PIKEVM_CAPTURE (#90)
- fix: per-config backref NFA, cache collision fixes, CRLF anchors, fallback guards (#89)
- fix: DFA assertion evaluation for sandwich lookaround and lookahead-in-quantifier (#87, #88)
- fix: POSIX aliases, inline flag regression tests, backref digit disambiguation (#88)
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

