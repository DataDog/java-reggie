# Current State

## Active investigation
"Make the backend regex engine replacement ready"

## Current hypothesis
(none active — hyp-unanchored-find-prefilter CONFIRMED 2026-09-18: the full R1/R2/R2b/R1b
prefilter arc is delivered and measured, no-match sweep 24x, acceptance gate passed.)

## Standing rules (user directives)
- BENCHMARKS RUN ON workspace-jb (ssh alias; 16-core idle Linux, repo at
  ~/go/src/github.com/DataDog/java-reggie, benchmark worktrees (ALL re-measured
  same-day 2026-09-17, controls flat): /tmp/r1-base=5210bac, /tmp/r1-new=38eeb62 R1,
  /tmp/r2=b5cf63e R2, /tmp/r2b=97ec426 R2b, /tmp/r3=32a9e26 (post-correctness); rust engine built per worktree via
  :reggie-benchmark:buildRustEngine). Local mac is ±2.5x noisy from gradle daemons —
  do not cite local numbers. Command shape:
  ./gradlew :reggie-benchmark:jmh -Pjmh.args="RealCorpusScanBenchmark -wi 3 -i 5 -f 2".
- One-off fetchers never committed (dodo-cli rule from cost work).
- Box setup gotcha (2026-09-18): workspace-jb /tmp worktrees get wiped; the ~/go repo fetches
  fail (SSH pubkey mismatch) — transfer commits via `git bundle create /tmp/x.bundle <branch>`
  + scp, then `git fetch /tmp/x.bundle <branch>:refs/heads/box-tip` and worktree from there.
  Local probes live in /tmp/prefilter (wiped on the mac too — regenerate from cairn node text
  when needed).
- Scope reminder: logs-backend regex = grok on JDK (re2j not landed; rust serves
  apm-processing/dd_sds). The rust lane is a reference, not a logs-backend surface.

## R2 status (COMPLETE through R2b, 97ec426)
Same-day box series (controls flat): no-match 15,571us (base) -> 2,475 (R1) -> 2,070 (R2) ->
983 (R1b+ci) = 15.8x; reggie 1.79x AHEAD of rust no-match, 1.48x behind on matched (338us),
2x ahead of jdk matched. Hybrid no-match collapsed 582->58us (R1b '-' fact + ci-facts made
the reinjection DFA unnecessary for the corpus). REMAINING no-match: BitState 384us (dominant),
generated ~475us spread, NameEnriching 61us. Matched residual = span-extraction families
(PikeVM capture-lists, BitState greedy spans) — separate work item. q-lazydfa-findfrom-leftmost RESOLVED (222c8e6): plain closures restart matchStart+1,
self-anchoring closures use findFromUnion; box flat. CORRECTNESS ITEM A COMPLETE.
B (local half) LANDED (32a9e26): 3 real divergences found+fixed via real-input parity
(nullable-tail spans, backref suffix/end-anchor, parse-refusal fallback bypass); 0
divergences now; battery committed. NEXT: B-remaining = shadow rollout (needs reggie
0.4.0 publish + logs-backend PR — team/owner decision), or C (matched-side span
families), or D (upstream PR of the whole arc). C TRANCHE 1 DONE (10b1a43+2db164b):
 find() fix (2 real divergences, kept) + RealFindParityTest find-gate (kept) +
hybrid-anchored-admission attempt MEASURED-NEGATIVE and reverted (q-hybrid-anchored-admission
has the re-entry points: LazyDFA/RD dfa-half ~420ns/char on alternation shapes uninvestigated;
whole-line anchored .*$ shapes gain nothing from narrowing). C next: that LazyDFA/RD lane cost,
or the lazy-family (stack-frame/kind-message) which needs priority-correct DFA bounds anyway.
Box after revert: matched 336.5us / no-match 955us (baseline), controls flat.
THEN (same day): d-caret FIXED (5c3587b: scanForMisplacedStartAnchor AlternationNode case;
plus the OPTIMIZED_NFA-decline fix, staged, commit blocked on 1Password signing — needs
user unlock). A partial: hybrid admission for UNANCHORED patterns landed (6ad33a0: exclude
requiresStartAnchor||\b; box matched 320-327 / no-match 943-955 flat, +6 hybrid patterns
- start-anchored flips are net-negative: matched-loss AND no-match-win, box net +67us).
B attempted (lazy loop fast-consume in BitState): all gates green but box JMH flat — the
corpus lazy families don't consume (stack-frame exits empty via optional tail; cost is
unanchored-seed DFS overhead). ROLLED BACK. A+B converge on: priority-correct + fast
captureless DFA find (LazyDFA/RD lane) as the next lever. Fuzz seeds now standing: 777,
48879, 131071 (all zero findings on the final state).

## What I'm doing now
- BRANCH FINALIZED: Draft PR #129 opened (DataDog/java-reggie#129, base main, label AI): 61 commits,
  178 files, template followed, perf tables in description. Build+jacocoVerify green on final tree.
  Remaining = OWNER actions: review/merge PR #129, release.sh minor (0.4.0 + SSM), logs-backend PR
  (ReggieRegexPatternSupplier shadow rollout wiring).
293d0e6: R1b 1-char facts -> char indexOf intrinsic (LdapNoMatch LONG 7.3x, matrix clean-sweep for reggie; corpus no-match ~9% relative). e61fdd9: 4-ENGINE BENCHMARK LANES (re2j corpus lane + rust IAST lanes + benchmark corpus copy
sync 513->528) — workspace-jb nohup run results in doc/temp/bench-2026-09-18-53a9425/:
per-pair reggie 0.49/0.25us m/nm, rust 0.62/0.66, jdk 1.81/11.97, re2j 6.58/5.14 (492/513 served)
— re2j SLOWEST on matched (13x reggie), only beats jdk on no-match: quantifies the re2j-never-landed
decision; IAST matrix: reggie fastest nearly everywhere, rust only leads scan-prefix SHORT no-match,
re2j loses everywhere except LdapNoMatch. 53a9425: HybridMatcher type-gate REMOVED — all nfa-halves context-search from the DFA leftmost start (span re-match = null fallback); closes the generated-half anchor-context corner; box flat (matched 179.8±2.8 / no-match 642.7±17.0, controls flat); fuzz 15/0-reg, batteries 0-div, 106 hybrids. NFA findFrom jump FIXED + corpus EXTENDED (1283fec): everyMatchStartsWith prefix gating, corpus 513->528 (+15 logs,synthetic, +9 inputs, hybrids 106), batteries 0-div, fuzz 15/0-reg, box matched 180.7±5.4 / no-match 636.4±3.2 = NEW BASELINE. CI divergence-gate failure FIXED (ff34f90: hybrid span re-match anchor context; fuzz 29->15, corpus 0-div, 102 hybrids, box matched 180.2). Branch release-ready again; remaining = OWNER actions (merge to main, release.sh minor with SSM, logs-backend PR). NOTE: .gitlab-ci.yml:47 still pins reggie.fuzz.maxFindings=28 while the source budget is 37 — kept (stricter caught a real regression) but expect a legit corpus shift to trip it again. LANE 3 CODE WORK COMPLETE (5a826bd JIT gate + changelog). LANE 2 COMPLETE THROUGH 5e9abbf (RD give-back hybrid: matched 182.4 / no-match 641.9, 102 hybrids). Earlier: LANE 2 COMPLETE THROUGH ae3a2c4 (lazy + pruning + alternation retries): matched 340->187.6us (−45%, 1.21x FASTER THAN RUST), no-match 665->695 (flat/noise). LANE 2 LAZY PRUNING LANDED (7a73126+502181d): lazy-aware captureless retry + RE2 leftmost-first pruning — matched 313.7->225.3us total (−28%). LANE 1 MAIN TRANCHE LANDED (6b583a8): JIT-size DFA_SWITCH codegen + hybrid re-admission +
BitState nfa-half. Box: matched 313.7 (win), no-match 665 (29% faster) — first simultaneous
win; arc no-match total 23.4x. LANE ORDER: 1 (remaining: OPTIMIZED_NFA monster splitting +
priority-correct lazy DFA find), 2 (matched-side span families: PikeVM capture-lists, BitState
greedy spans), 3 (shadow rollout: reggie 0.4.0 + logs-backend PR). Branch pushed through 772b726
(docs). Landed this arc: R1 prefilter
(38eeb62, no-match 9.1x), alternation-priority PikeVM re-route coverage lift (6caf5db,
refusals 10->7), BytecodeDebugger actual-routing fix (6e9b8eb), R2-RD linear find()
(b5cf63e, no-match 2.66ms total 12.7x; reggie 1.19x ahead of rust on no-match; blended
corpus fastest). NEXT: R2b HybridMatcher lane (unanchored .*-prefix, ~40% of the 2.66ms
residual; reverse-DFA or .*?-prefixed single-pass DFA for its boolean find), optional R1b
(1-char facts landed as R1b). Methodology rule: generated methods need 10k+ invocations
to reach C2 — probes must warm 12k/measure 4k or use JMH; box drifts across days — always
normalize with same-run rust/jdk controls. Scratch tools in /tmp/prefilter: ZAnchor3 (jdk-vs-reggie
  find-span matrix, incl. greedy \Z/$/\z families), ZAnchorM (matches() matrix),
  BRepro (the three real-input parity bug repros), RealInputParityProbe (the battery
  harness — takes corpus TSV + inputs file; TSV needs the RealCorpusScanBenchmark
  unescape), LengthProbe
(complexity fingerprint), SpanParityProbe (JDK span parity battery), RefusalProbe,
GCProbe, SweepProbe + MatchSweepProbe (per-pattern bucket timing, now C2-CONVERGED:
warm 12k/measure 4k), RustMatchProbe (reggie/rust/jdk per-pattern, needs benchmark
classes + RUST_REGEX_ENGINE_LIB ABSOLUTE path), HybridProbe2/StrategyProbe/ChainProbe
(routing via RuntimeCompiler.describeRouting — never unwrap by hand), SweepRecon
(JMH-shape sweep reconciler). Package-private reflection (PrefilteringMatcher.delegate,
HybridMatcher.dfaMatcher): put the probe in package com.datadoghq.reggie.runtime under
/tmp/prefilter/cpr and load from there. New-test convention: engine-class-reflecting
tests must unwrap via EngineRouting.unwrap — the R1 prefilter now wraps most matchers. Notebook 15576172 = cost write-up; repo cairn
multi-64k has the full evidence files.

## Open questions
- q-z-anchor-span-bug: PRE-EXISTING $\Z span divergence (see node) — fix separately.
- R2 (single-pass .* scan) is the next improvement: the no-match sweep is now
  dominated by no-literal .*-prefix patterns (3.7ms of which ~2.5ms is that family).
- q-real-input-validation: synthetic inputs vs real grok traffic (shadow seam).
- q-real-input-validation: synthetic inputs vs real grok traffic (shadow seam).


## Coverage status (6caf5db)
reggie native 506/513 logs-backend patterns (98.6%); 7 refusals are honest
(nullable-capture B16, anchor-in-quantifier x2, anchor-dilution x2, empty-class
divergence, alt-priority+anchor-dilution); allowJdkFallback covers all 513.

## R1 status
LANDED (38eeb62): no-match sweep 9.1x faster on workspace-jb; reggie 1.17x behind
rust, 12x faster than JDK, matched-mode unchanged. Projections are now measurements.

## Confirmed findings (don't re-derive)
- find-refusal-set-parity (rust/reggie refusal sets disjoint: 8 new refusals without
  fallback, 0 with allowJdkFallback; reasons + follow-up in node)
- find-backend-prod-regex-cost (prod shares, cost baselines, revised $ model)
- find-rust-engine-crossover (real-mix engine relationships, counted wall, dd_sds verdict)

## Ruled out (don't re-investigate)
- dead-bitstate-lazy-fastconsume (BitState lazy loop fast-consume: flat on box,
  corpus lazy families don't consume — see node)
- dead-group-boundary-fusion (eager AND deferred variants: net-negative, reverted)
- dd_sds/Rust-regex replacement by reggie for CPU reasons (measured 3-5x rust advantage
  on the real mix, survives FFI marshalling)
