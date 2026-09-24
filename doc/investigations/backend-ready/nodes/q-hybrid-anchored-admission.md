---
id: q-hybrid-anchored-admission
type: question
status: open
depends_on: [ev-real-input-parity, ev-r2b-landed]
supersedes: []
related: [hyp-unanchored-find-prefilter, ev-lazydfa-nfa-delegate-limit, ev-lane1-jit-hybrid-landed, ev-alternation-retry-landed, ev-leftmost-pruning-landed, ev-lazy-hybrid-landed, ev-word-boundary-find-fix]
tags: [hybrid, anchors, matched-mode, regression, lazydfa, item-c]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Hybrid admission for anchored PIKEVM/BITSTATE patterns (item C) — measured regression, precise re-entry points

## What happened (10b1a43 -> reverted 2db164b)
Removed the blanket `nfaHasAnchor` skip from RuntimeCompiler's hybrid pre-check. Result on
workspace-jb (controls flat): matched sweep 340us -> **687us** (2x regression), no-match
951us -> 671us (improvement). Reverted; the blanket is now a documented PERFORMANCE gate.

## Why it regressed (measured, not inferred)
The flip admitted whole-line anchored patterns — `^\s*(?<suppressed>Suppressed:)?(Caused by:)?\s*((?<kind>[^\s:]+)( ?:\s*(?<message1>.*))?|(?<message2>.+))$` (247us/pair, was 13.5us PikeVM) and
`^(created by )?(\S+\w)(\([^\\)]*\))?( .*)?$` (131us/pair, was 7.9us BitState). HybridSplit
probe (reflection into HybridMatcher.dfaMatcher/nfaMatcher, box /tmp/prefilter/HybridSplit.java):
dfa.findMatchFrom = 67.8us + nfa.match(substring) = 45.5us on a 162-char line — the DFA-half's
span IS the whole line (anchored .*$ shapes), so hybrid pays DFA scan + PikeVM rescan of the
SAME span. Strictly worse than the engine it replaced.

## The two blockers to re-admission
1. **DFA-half cost**: the dfaMatcher for these shapes is the LazyDFA/RD generated lane (methods:
   findBoundsFrom/findMatchEnd/matchesAtStart — ReggieMatcher$hash generated class), running
   ~420ns/char on alternation shapes. Why so slow there is UNINVESTIGATED — note
   DFAUnrolledBytecodeGenerator.findFrom DOES have start-anchor fast path + first-char-skip, so
   the slow lane is a different generator (which one produces findBoundsFrom — LazyDFABytecodeGenerator?
   or the R2-RD RuntimeCompiler lane). First step: identify + profile it (async-profiler,
   HybridSplit harness ready).
2. **Acceptance rule**: admit only when (a) the DFA-half find is cheap AND (b) the match span
   narrows meaningfully vs the region (whole-line anchored .*-$ shapes gain NOTHING from
   narrowing — exclude by shape: unbounded .*/.+ adjacent to $ inside the pattern?). Candidate
   static guard: exclude patterns whose unbounded-dot run reaches $ (whole-line matchers).

## Subtleties that cost hours (recorded so they aren't re-paid)
- The alternation-priority re-route (`result.alternationPriorityConflict`) fires BEFORE the
  hybrid check for `.*)`-end alternations, but the corpus variant with `(?<message2>.+)`
  (one-or-more!) does NOT trip the flag — admitted to hybrid legally. `.+` vs `.*` in the last
  alternation branch decides the flag. My reconstruction probe had `.*` and lied about routing.
- HybridSkipProbe-style probes MUST build the NFA with the REAL group count
  (ThompsonBuilder(true).build(ast, countGroups(pattern))) — a wrong count changes routing
  (learned: probe said dfa=null diluted=false for patterns that route differently in the real
  pipeline). describeRouting + engineChain is the ground truth.
- The corpus semver extractor (the 51.5us PikeVM family) has possessive `([.][0-9]{1,19}+)+`
  and routes PIKEVM even without the anchor skip — its hybrid admission needs its own check.

## What survives from 10b1a43 (kept after revert 2db164b)
- \b word-boundary routing fix (MULTI_GROUP_GREEDY + RECURSIVE_DESCENT declines; 2 real
  divergences fixed, both pre-existing) + WbMatrix probe.
- RealFindParityTest: permanent find-span parity gate (513 corpus x 527 real lines, ~2s;
  first run caught both \b bugs). Battery + the \b hybrid exclusion in RuntimeCompiler.
- Box re-verified after revert: matched 336.5us / no-match 955us (baseline 340/951), controls flat.

## Probes (box + local /tmp/prefilter)
MatchSweepProbe2 (converged per-pattern matched sweep), HybridCensus (routing census),
RealFindParityProbe, HybridSplit (hybrid half-cost split), FlipBisect (routing-flip bisector),
WbMatrix/WbCheck2 (\b matrix), R1-R5 (routing probes). All need asm jars on classpath.

## UPDATE (2026-09-17, 6ad33a0): PARTIAL RESOLUTION — unanchored admission landed
Third measurement (per-pattern classification, C2-converged) split the 10b1a43 flip:
- REGRESSORS (matched 110+63us local) = BOTH whole-line ^...$ patterns (suppressed-kind,
  created-by). Their loss is in find() — the DFA_SWITCH matchesAtStart costs ~140ns/char
  on alternation shapes vs ~25ns/char BitState — NOT just findMatchFrom.
- All unanchored flips = flat/wins.
Admission rule LANDED (6ad33a0): exclude requiresStartAnchor || hasWordBoundaryAnchor;
admit unanchored. Box: matched 327→320-327us (flat), no-match 955us FLAT — the 10b1a43
no-match win (951→671) came from the START-ANCHORED flips too (BitState seeds at every
position and pays the ^-check-per-seed stack churn; the DFA tries pos 0 only). So
start-anchored flips = matched-loss AND no-match-win — NET NEGATIVE on the box sweeps
(10b1a43: matched +347, no-match -280). Rejected; the remaining blocker for them:
DFA_SWITCH matchesAtStart codegen efficiency (140ns/char on these shapes — where the
time goes is NOT yet profiled; HybridSplit/HybridSplit2/DumpGen harnesses ready in
/tmp/prefilter, javap disassembly of the generated class in /tmp/prefilter/dfa-switch.asm).

## ITEM B (lazy families) — attempted, rolled back
-> dead-bitstate-lazy-fastconsume (own node now: implemented, all gates green,
box JMH flat because the corpus lazy families don't pay lazy-consumption costs).
