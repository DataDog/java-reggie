# backend-ready

_Make the backend regex engine replacement ready_

- status: done
- repo: datadog--java-reggie / branch: feat-dd-backend-check
- last_commit: 293d0e6b8e45e9cba3ceccba59ca5ab4e5771a60

## INDEX

# Investigation Index

## Findings (confirmed)
- find-jit-hugemethodlimit | Generated DFA_SWITCH/NFA methods over 8000 bytecodes (HugeMethodLimit) run INTERPRETED — proven 20.8x/17.8x with -XX:-DontCompileHugeMethods; root: 100-state buckets sized vs 64KB hard limit + inline accept-anchor blocks; corpus blast radius 5 classes, sweeps ~0, real-traffic tail | [jit, hugemethodlimit, dfa-switch, codegen] | MEASURED prod impact: logs-processing JDK regex 5.60% CPU ($413K/yr, ~100% grok-driven, re2j 0.00% not landed in prod), prof-analyzer 1.00%, apm-processing 0.38% (its heavy regex is Rust dd_sds — not replaceable); f revised to ~2x central -> ~$210K/yr central ($207-280K range), ~89% in logs-processing; notebook 15576172 | [prod-measurement, continuous-profiler, cloud-cost, savings-estimate, grok]
- find-refusal-set-parity | reggie vs rust refusal sets DISJOINT on the 513 corpus: 8 reggie-only (1.6%, all correctness guards: 4x alternation-priority, 2x anchor-dilution, nullable-capture, anchor-in-quantifier), 2 shared, 12 rust-only that reggie serves; with allowJdkFallback ZERO functional refusals (fallback is R1-prefiltered too); LIFT LANDED (6caf5db): 3 alternation-priority rules re-route to PikeVM, refusals 10->7 (native 506/513); anchor-dilution NOT lifted (measured diverger in class); user correction absorbed: logs-backend = grok-on-JDK, rust serves apm — parity question only matters for a rust-surface replacement; PIKEVM-JUDGED: 4 of 8 lift candidates (0 JDK-oracle findings each); 4 honest (nullable-capture/anchor-in-quantifier/empty-class — need backtracking for byte-identical spans) | [refusals, coverage, migration, jdk-fallback]
- find-rust-engine-crossover | REAL-corpus 3-engine smoke (513 logs-backend patterns x 6 lines, FFM incl. marshal, zero divergences): rust 3-5x faster than reggie at median (11-54x NOMATCH totals), reggie 2.1x faster than JDK on matched pairs (median) -> f revised; rust counted-quantifier wall real ({0,256} fails even at 256MB NFA); dd_sds replacement would RAISE cpu (architectural case only) | [rust-regex, real-corpus, smoke-test, f-revision, dd_sds]

## Hypotheses
- hyp-unanchored-find-prefilter | OPEN: R1 literal prefilter for unanchored find() + R2 single-pass .* scan to lift fleet f above 2x (~$100K/yr per 1x of f at logs scale); PROJECTIONS INVALID until a sound literal extractor is built (quick one returned impossible 0/513); acceptance = RealCorpusScanBenchmark NOMATCH sweeps | [prefilter, memchr, single-pass, roadmap]

## Dead ends
- dead-bitstate-lazy-fastconsume | BitState lazy char-class loop fast-consume: gates green but box JMH flat — corpus lazy families don't consume (stack-frame's .*? exits empty via optional tail; cost = unanchored-seed DFS overhead); local probe wins were JVM noise | [bitstate, lazy-loop, perf, measured-negative]
- dead-group-boundary-fusion | REFUTED/REVERTED (measured net-negative): eager + deferred fusion both built+tested; removes only ~10 pops on realistic paths while a 5th opsStack array taxes every push/pop; helps only degenerate 5-char; reverted to 677417b; short-input floor needs a hybrid engine, not fusion | [fusion, dfs, perf, measured-negative]

## Evidence
- ev-r1b-char-intrinsic | 293d0e6: 1-char prefilter facts now scan with the char indexOf intrinsic (String overload's first-char scan is 5.7x slower on x86; mac probes mask this — NEON); LdapNoMatch LONG outlier 276 -> 2,026 ops/ms (7.3x, re2j 358) — reggie now leads every shape in the 4-engine matrix; corpus no-match -1.7% raw / ~9% relative | [prefilter, intrinsic, simd, 293d0e6]
- ev-4lane-bench-results | e61fdd9: 4-engine matrix (re2j corpus lane + rust IAST lanes + corpus-copy sync) — per-pair reggie 0.49/0.25µs m/nm vs rust 0.62/0.66, jdk 1.81/11.97, re2j 6.58/5.14 (re2j slowest on matched, 13x reggie: the never-landed decision quantified); ONE engine-beats-reggie outlier: LdapNoMatch LONG re2j 361 vs reggie 276 ops/ms (jdk 90, rust 17) — follow-up target | [benchmark, re2j, rust, 4-engine, e61fdd9]
- ev-hybrid-context-rematch | ff34f90: CI divergence-gate 29>28 failure root-caused (11 branch-introduced hybrid divergences: standalone-string span re-match fired $/\Z/\z out-of-context + trusted an inverted pruned-DFA end) and fixed — PikeVM/BitState halves now search from the DFA's leftmost start in the full input; raw-finding cross-check vs main's oracle was the decisive method; fuzz 29->15 (0 regressions), corpus 0-divergence + 102 hybrids unchanged, box matched 180.2µs flat | [hybrid, anchors, context, ff34f90, ci]
- ev-jit-gate-landed | 5a826bd: exact classfile method-size scan + fallback-only JIT gate (2 monster patterns decline to JDK under fallback; strict contract unchanged); full build+jacocoVerify green; changelog updated; REMAINING = merge to main + release.sh (SSM) + logs-backend PR | [jit, gate, release, 5a826bd]
- ev-rd-hybrid-landed | 5e9abbf: RD give-back originals hybridize (PikeVM capture half) — matched 182.4us, no-match 641.9 (−7.7%, RD backtracking gone); 102 hybrids; fresh fuzz seeds: zero new findings, pre-existing out-of-corpus divergences recorded | [rd, give-back, 5e9abbf]
- ev-alternation-retry-landed | ae3a2c4: alternation-priority retry (mode-gated block bypass + needsFallback guard + END-anchor-in-alternation decline) — matched 225.3->187.6us (−17%), 1.21x ahead of rust; hybrid substring re-match = general anchor-context hazard (fuzz 48879); maven semver honestly declined (atomic charset overlap) | [alternation, retry, ae3a2c4]
- ev-leftmost-pruning-landed | 502181d: RE2 leftmost-first thread pruning (anchor-free lazy retries only; HybridMatcher.lazyFind routes matches() to the NFA half) — matched 282.9->225.3us (−20%), reggie FIRST TIME faster than rust matched (225 vs 232); two lazy tranches net blended −5.7%/pair | [pruning, re2, 502181d]
- ev-lazy-hybrid-landed | 7a73126: lazy-aware captureless DFA retry + central leftmost-first certification — 9 more hybrids (66), matched 313.7->282.9us (−9.8%) but no-match 665->693.7 (+4.3%), blended per-pair FLAT; stack-frame chain genuinely uncertifiable (13 real priority conflicts, 530/266k divs caught pre-gate) — needs priority-pruning DFA; probe artifact: 513-pattern JVM overstates per-pattern no-match 20-200x vs isolated | [lazy, certification, 7a73126]
- ev-lane1-jit-hybrid-landed | 6b583a8: JIT-sized DFA_SWITCH codegen + start-anchored hybrid re-admission + BitState nfa-half — box matched 313.7us WIN and no-match 665us (29% faster, first simultaneous win; 10b1a43 regression inverted) | [jit, hybrid, 6b583a8]
- ev-word-boundary-find-fix | 10b1a43: NEW find-span parity battery (513x527 real pairs, ~2s, committed RealFindParityTest) found 2 PRE-EXISTING  find() divergences — MULTI_GROUP_GREEDY accepted across word boundaries (name:(\S+) on "@peer.hostname..." matched), RECURSIVE_DESCENT (.*)end no-match on "appendend"; fixed via targeted PatternAnalyzer declines (blanket ->PikeVm reverted: broke PINNED_BACKREFERENCE backref routing); 266k find pairs 0 divergences after;  hybrid exclusion kept | [word-boundary, find, parity-gate, 10b1a43]
- ev-rust-bench-lane-standardized | 60db93c: Rust regex = 4th benchmark lane — JNI shim (Java-21-safe, marshal in timed path) + RustRegexEngine (scan + full-match semantics, graceful unavailability) + buildRustEngine task + MatchOperationBenchmark lanes + RealCorpusScanBenchmark (513 real patterns committed, MATCH/NOMATCH split; reproduces smoke exactly 491/10/12, rust no-match ~303ns/pair) — the R1/R2 acceptance gate | [benchmark, jni, 4th-lane, real-corpus]

- ev-r1-prefilter-landed | 38eeb62: R1 SHIPPED — sound AST literal extraction + universal rejection wrapper; workspace-jb measured no-match sweep 33.9ms -> 3.7ms (9.1x), reggie/rust 10.6x -> 1.17x, matched unchanged, jdk/rust controls flat; audit 287/513 literals, 63% instant-reject, 0 divergences; R2 (single-pass .* scan) remains | [r1, prefilter, workspace-jb, 9x, 38eeb62]

- ev-r2-rd-landed | b5cf63e: R2 first half — RD .*-prefix find() linear (anchor-start + fast give-back; slot-collision bug caught by span battery); box: no-match 2.66ms (R1+R2 total 12.7x, reggie 1.19x AHEAD of rust), matched 508us, blended corpus fastest (~16x jdk); R2b = HybridMatcher lane (~40% of residual); detour: BytecodeDebugger fixed to report ACTUAL routing (6e9b8eb) | [r2, give-back, anchor-start, b5cf63e]

- ev-r2b-landed | 97ec426: R2b = ci-facts (global (?i) facts from stripped pattern, ASCII-ci exact scan) + R1b 1-char required facts (requiredChar soundness-by-construction walk); same-day box series no-match 15,571 -> 2,475 (R1) -> 2,070 (R2) -> 983us (15.8x, reggie 1.79x AHEAD of rust); matched flat 338us (1.48x behind rust, 2x ahead jdk); hybrid no-match collapsed 582->58us; METHODOLOGY: generated methods need 10k+ invocations for C2, short-rep probes overstate reggie ~6x; box drift ~1.85x between days — same-run controls mandatory | [r2b, r1b, ci-facts, prefilter, 97ec426]

- ev-lazydfa-nfa-delegate-limit | Analyzer recommends LAZY_DFA but generation reuses NFABytecodeGenerator for span methods -> 64KB method limit at ~6800 NFA states -> JAVA_FALLBACK; effectively unreachable for the large-NFA patterns it targets; 0 corpus impact today | [lazydfa, fallback, method-size]

- ev-real-input-parity | 32a9e26: real-input parity battery (527 lines harvested from logs-backend grok tests x 513 corpus, drop-in contract) found 3 divergences invisible to the synthetic corpus — nullable-tail group span (tagged DFA), VARIABLE_CAPTURE_BACKREF suffix/end-anchor bugs, parse-refusal fallback bypass — ALL FIXED; now 270,351 pairs / 8,179 matches / ZERO divergences; battery committed (RealInputParityTest, ~1.2s); supplier drafted for the shadow seam; real-traffic shadow rollout remains (logs-backend PR + reggie 0.4.0) | [real-inputs, parity, drop-in, 32a9e26]

## Questions
- q-generated-nfa-findfrom-broken | FIXED (1283fec): findFrom's indexOf scan-start jump now gated on verified match prefix (everyMatchStartsWith walk); sound indexOf==-1 rejection kept; corpus 513->528 with 15 synthetic guards (+9 inputs, 4 new hybrids -> 106); box matched 180.7/no-match 636.4 flat; previously ROOT-CAUSED — findFrom's indexOf scan-start jump assumes the required literal sits at offset 0 of the match; requiredLiterals only guarantees somewhere-in-match ((.c)+ on "-cc" → start 1; (.0){3,} → -1). Reachability census: 0/513 corpus standalone OPTIMIZED_NFA, hybrid halves gated off since ff34f90, 0 fuzz findings, 0 constructed public-API repros. Fix = first-set prefix gating when a lane routes captureless patterns there | [optimized-nfa, findfrom, required-literal, latent-bug]
- q-fuzz-preexisting-divergences | OPEN: 9 divergences from fresh seeds 90210/424242 all reproduce pre-change (backref {0} boolean, c{1,1} group span, $|\z alternation span + others); out-of-corpus, canaries for future tranches | [fuzz, pre-existing]
- q-hybrid-anchored-admission | OPEN: admitting anchored PIKEVM/BITSTATE into hybrid regressed matched 340->687us (10b1a43, reverted 2db164b) — whole-line anchored .*-$ shapes gain nothing from narrowing (DFA span = full line; 68us LazyDFA/RD scan + 45us PikeVM rescan on a 162-char line) and the LazyDFA/RD lane runs ~420ns/char on alternation shapes (WHY = uninvestigated, entry points in node); re-admission needs a DFA-half-cheap + span-narrows rule; `.+)` vs `.*)` in the last alternation branch decides the alternation-priority flag (probe lied with `.*`) | [hybrid, anchors, matched, lazydfa]
- q-lazydfa-findfrom-leftmost | FIXED 222c8e6: plain-closure findFrom restarts at matchStart+1 (was death+1, skipping viable starts inside the dead span); self-anchoring closures (PikeVM findStep/rejectStep, BitState rejectStep) moved to findFromUnion (union covers dead-span starts, pos+1 sound); trap x(?:a+b+|b+a+){75} on "xax"+"ba"*75 -> 2 not 3 | [lazydfa, leftmost, fixed]
- q-caret-midpattern-anchor | OPEN, PRE-EXISTING (reproduces pre-222c8e6): (?:[^a-caa]|c)^|.\\z\\z on "1\\n0\\n_cbcc_" — jdk [9,10) via .\\z\\z branch vs reggie [0,1) via the ^-branch; findAll 1 vs 7; suspect mid-pattern non-multiline ^ in alternation; fuzz seed 131071 reproduces (FuzzProbe argv[0]) | [anchor, caret, alternation, fuzz]
- q-lazydfa-findfrom-leftmost | OPEN, LATENT (no corpus pattern routes LAZY_DFA): LazyDFACache.findFrom restarts at death+1 without re-walking — a later viable start can begin inside a dead attempt's span (ab|b on xaab returns -1 vs true leftmost 2) and ran-out-of-input attempts also skip viable later starts; fix before routing hybrid/other lanes through it; test shape: (?:aXb|Xb)-large on aaXb | [lazydfa, leftmost, soundness]
- q-z-anchor-span-bug | FIXED 222c8e6 (DFA_UNROLLED greedy-walk \\Z early-return deleted; accepting-state recording with full anchor conditions already handles before-terminator acceptance and falls through to consume; [^a]*$\\Z on x\\n now [0,2)) | was OPEN, PRE-EXISTING (on 677417b): [^_-ab-c]*$\Z on "ccc0_1\n00a\n" — jdk span [10,11) vs reggie [10,10); greedy newline consumption under $ + \Z; fix separately | [anchor, span, greedy, fuzz]
- q-real-input-validation | PARTIAL (local half done, 32a9e26): remaining = prod shadow rollout (supplier drafted; needs reggie 0.4.0 published + logs-backend PR; Bastien Lemale's branch is the wiring precedent) | was OPEN: all corpus numbers use synthetic lines — validate fleet f against REAL grok traffic via the shadow seam (logs.processing.grok.shadow.*, ReggieRegexPatternSupplier) before quoting beyond the ~2x floor | [inputs, grok, shadow-seam, validation]

## STATE

# Current State

## Active investigation
"Make the backend regex engine replacement ready"

## Current hypothesis
(none active — engineering hypothesis hyp-unanchored-find-prefilter (R1/R2 prefilter roadmap)
is OPEN but blocked on a sound literal extractor; see node for the honesty flag.)

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

## Nodes

---
id: dead-bitstate-lazy-fastconsume
type: deadend
status: refuted
depends_on: [q-hybrid-anchored-admission]
supersedes: []
related: [ev-r2b-landed, q-lazydfa-findfrom-leftmost]
tags: [bitstate, lazy-loop, perf, measured-negative, rollback]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# BitState lazy char-class loop fast-consume — measured flat, ROLLED BACK

## What was built
The lazy mirror of the existing greedyLoopMid fast path in BitStateMatcher:
a lazy single-char-class loop (`c*?`) whose continuation closure contains no
anchor, no accepting state, and at least one consuming transition gets a
tight first-pass: consume the whole `c`-run in a scan, mark the same
(mid,p)/(leaf,p) visited cells, push exit jobs ONLY at positions where the
continuation's first-char set can match input[p] (descending push = ascending
pop = Perl lazy priority). All gates green: 3 fuzz seeds (777/48879/131071),
RealFindParityProbe (266,662 pairs / 0 div), RealInputParityProbe
(270,351 pairs / 0 div), full test suite.

## Why it was rolled back (box evidence)
- Box JMH matched sweep: 334.6±4.7us vs 320-327us for 6ad33a0 same-day
  (controls flat) — flat to slightly negative.
- Box per-pattern (C2-converged) showed the path NEVER ENGAGES on the corpus
  lazy families:
  - stack-frame `\s*((?<function>[^@]*)@)?(?<file>.*?)(:?\d+)?(:\d+)?`:
    49.9us/6 pairs, IDENTICAL to pre-change. Its `.*?` barely consumes —
    the all-optional tail lets the lazy loop exit EMPTY almost immediately
    (zero-width guard correctly disables the fast path). Its 8us/pair cost is
    unanchored-seed DFS overhead + greedy give-back, NOT loop consumption.
  - kind-message `^(?<kind>.+?): (?<message>.+?)( --->.+)?$`: 19.5us vs
    21.8us — the fast path engages (continuation first-set {':'} narrow) but
    kind-consumption is a small share of the pattern's total cost.
- Local probe "wins" (stack-frame 20->16us, kind-msg 10->7us, at-line 5->3us
  single JVM) were NOISE. RULE REINFORCED: local single-JVM probe deltas are
  not perf evidence — box JMH + box per-pattern only.

## What this rules out / redirects
- Lazy-loop stack round-trips are NOT the lazy families' cost center.
- The real lever (shared with the A-remainder start-anchored hybrid
  re-admission): a priority-correct (lazy leftmost-first) AND fast
  (~140ns/char -> ~30 target) captureless DFA find in the LazyDFA/RD lane.
  The chain lane already proves leftmost-first lazy is achievable for simple
  shapes (`a.*?b` -> [0,4) on JDK-parity probes).

## Re-entry points if revisited
- BitStateMatcher greedy fast-consume block is the template (greedyLoopMid
  fields, shape detection in the compiled matcher ctor); the lazy variant was
  fully written and gate-verified — see git stash / this node's design above.
- MatchSweepProbe3 (per-pattern C2-converged: warm 12k / measure 4k) is the
  per-family meter; RealCorpusScanBenchmark is the acceptance gate.

---

---
id: dead-group-boundary-fusion
type: deadend
status: refuted
depends_on: []
supersedes: []
related: []
tags: [fusion, group-boundary, dfs, perf, measured-negative, reverted, 677417b]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Group-boundary fusion pass (user directive) — REFUTED, REVERTED

## Reasoning chain
Fusing group enter/exit-only states into incoming edges (writes ride on edges). Two variants
built + fully tested: EAGER (ops applied at push — pays ~150 wasted caps clones for
never-popped marker-stop frames on greedy paths) and DEFERRED (ops row on the frame, applied
at pop; sound: push pos == pop pos of the bypassed state; post-write dedup strictly better).

Measured (SplitPerf/Re2jProbe, vs shipped 677417b): eager 5ch 0.585us win but 313ch +2.4us,
reject +7.8us; deferred 5ch 0.516 win, 313ch +1.4us, reject +6.2us. Instrumented: fusion
removes only ~10 pops on the 313-char accept (prerelease loop body has NO boundary states —
group writes sit on loop EDGES, off the winning path) while a 5th opsStack array taxes
every push/pop (~0.9ns x ~1700 frames; reject path pays on 5-10x more frames).

Verdict: trades a 0.1-0.14us win on degenerate 5-char for 1.4-6us losses on realistic shapes
-> REVERTED (no code delta vs 677417b). State-id bit-packing of ops (~0.3-0.5ns/pop) also
estimated net-negative for 313ch. Short-input closure needs a hybrid engine, not fusion.
Details: multi-64k ev-re2j-parity-push follow-up section (commit de871b5).

---

---
id: find-backend-prod-regex-cost
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [find-rust-engine-crossover, ev-rust-bench-lane-standardized]
tags: [prod-measurement, continuous-profiler, cloud-cost, savings-estimate, logs-processing, prof-analyzer, apm-processing, grok]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Measured prod regex CPU & $ savings for the backend services — CONFIRMED

## Reasoning chain
User asked for a $ savings estimate from cloud deployments + continuous profiles. Service
naming (user-corrected): profiling-backend = prof-analyzer; logs-backend = logs-processing
(+maybe apm-processing). Continuous profiles are NOT reachable via the Datadog MCP toolset —
pulled via the dodo-cli flamegraph endpoint (POST app.datadoghq.com/api/ui/ide/profiles/
flamegraph, OAuth from dodo-cli auth, 2h window, env:prod, family java, cpu-time; dodo-cli
fetcher cmd/profshare used + REMOVED per user directive — never commit one-off fetchers).

Measured (2026-09-17):
- logs-processing: $20,227/day ($7.38M/yr, 29d CCM all.cost service-allocated); 32,790 cores
  used / 64,963 requested. java.util.regex self = 5.60% of CPU (~1,836 cores); compile 0.003%
  (all match-time); com.google.re2j = 0.00% -> grok RE2J migration NOT landed in prod; driver
  ~100% grok (11.47 of 11.95% cumulative under Matcher entries; fsmatic GrokModule);
  +0.55% InterruptibleCharSequence.charAt regex-input plumbing.
- prof-analyzer: $2,977/day ($1.09M/yr); 5,783 cores. java.util.regex = 1.00% (JFR/pprof
  parsing).
- apm-processing: $14,902/day; java.util.regex = 0.38% only — heavy regex there is RUST
  dd_sds/regex-automata (~11%), NOT reggie-replaceable.
- Effective cost $0.021-0.026/used-core-hr across services (CCM consistent with CPU).

Savings model: cost x share x (1-1/f). Initial f=3-6x (repo 3-engine bench corpus) gave
$296-370K/yr (~89% logs-processing). REVISED after real-corpus smoke (see
find-rust-engine-crossover): honest central f ~= 2x -> logs-processing ~$210K/yr central,
range ~$207-280K/yr. Rule of thumb: every 1% of logs-processing CPU in JDK regex =
$49-62K/yr. Savings materialize only if HPA scales replicas down (fleet ~50% of requests,
diurnal autoscaling visible).

Full write-up: Datadog notebook 15576172 (updated with smoke + revision cells). Repo cairn:
doc/investigations/multi-64k/evidence/ev-reggie-cpu-savings-estimate.md (commits 9df5f78,
af1278e).

---

---
id: find-jit-hugemethodlimit
type: finding
status: confirmed
depends_on: [q-hybrid-anchored-admission]
supersedes: []
related: [ev-lazydfa-nfa-delegate-limit]
tags: [jit, hugemethodlimit, dfa-switch, codegen, interpreted, mechanism]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# The "slow LazyDFA/RD lane" is a JIT-blindspot, not an algorithm

## Mechanism (PROVEN, r5 box-local experiment 2026-09-17)
The generated DFA_SWITCH matcher class for the suppressed-kind pattern has
matchesAtStart=21,477 bytecodes, matchInto=17.9k, match=17.5k, matchesBounded=15.9k,
matches=13.8k, findMatchEnd=9k — ALL over HotSpot's HugeMethodLimit (default 8000
bytecodes: methods above it are NEVER JIT-compiled, C1 or C2 — they run interpreted).
Proof (DumpGen2 on /tmp/r5, same pattern, same generated code, only the JVM flag):
- dfa.find:        19,538ns ->    941ns with -XX:-DontCompileHugeMethods (20.8x, 120.6 -> 5.8 ns/char)
- dfa.findMatchFrom: 31,762ns -> 1,787ns (17.8x, 196 -> 11.0 ns/char)
So the 140-420ns/char "DFA_SWITCH matchesAtStart cost" in q-hybrid-anchored-admission
is INTERPRETED-execution cost. The 20x headroom means the compiled code is FAST as-is.

## Root cause in DFASwitchBytecodeGenerator
- STATE_SPLIT_THRESHOLD=100 buckets per-state case logic into $ng_step_N helpers sized
  against the 64KB JVM hard limit (~30KB helpers) — 3.75x OVER the 8KB JIT limit.
- The accept-state check block (per accept state: sequential state==id compare + FULL
  anchor-condition emission, ~400B/accept for $-anchor families) is emitted INLINE in the
  MAIN method — for alternation-heavy patterns (dozens of accept states) this alone blows
  past 8KB even when transitions are bucketed.
- matchesAtStart/findMatchEnd for anchored shapes ALSO inline the anchor prologue.

## Corpus census today (513 patterns, -Dreggie.debug.bytecode dump + javap size walk)
Only 5 classes exceed 8000: OPTIMIZED_NFA_WITH_BACKREFS react-decoder 40,059;
OPTIMIZED_NFA_WITH_LOOKAROUND python-pkgs 19,854; DFA_SWITCH elasticsearch 9,123,
kafka-consume 9,086, kafka-produce 9,114. All under 8KB: DFA_UNROLLED (86), CHAIN (44),
everything else. The 5 contribute ~0 to both JMH sweeps (6 canonical INPUTS match none of
them; no-match is R1-prefiltered) — their cost is REAL-TRAFFIC tail (a React-error line in
prod paying interpreted rates) — a shadow-rollout robustness item, not a sweep item.

## Consequences
- Hybrid re-admission of start-anchored patterns: blocked ONLY by this (JIT-able dfa-half
  fixes the find() regression; reggieSweepMatched is a find()-boolean sweep).
- compileHybrid picks PikeVMMatcher as nfa-half even when the original routed
  BITSTATE_CAPTURE (skips the routeBitState upgrade) — measured 45.5us vs 13.5us BitState
  on the suppressed-kind capture path. Fix candidate: nfa-half = BitState for those.
- The suppressed-kind 21.5KB dfa-half exists only when anchored patterns enter hybrid
  (they don't today); the 3 marginal DFA_SWITCH classes exist today.

## Probes
DumpGen2 (/tmp/prefilter, timing with/without -XX:-DontCompileHugeMethods),
SizeCensus + -Dreggie.debug.bytecode=/tmp/prefilter/gen-classes dump,
/tmp/methodsize.py + /tmp/allsize.py (javap max-offset walker).

---

---
id: find-refusal-set-parity
type: finding
status: confirmed
depends_on: [find-rust-engine-crossover, ev-rust-bench-lane-standardized]
supersedes: []
related: [find-backend-prod-regex-cost]
tags: [refusals, coverage, migration, jdk-fallback, alternation-priority, 8-of-513]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Refusal-set parity: reggie vs rust on the 513-pattern corpus — CONFIRMED

## Reasoning chain
USER QUESTION: "is the 10 refused by reggie from the rust's 12 set? if we replace rust with
reggie, we are not going to get new refusals, right?" Answer: NO — the sets are DISJOINT.

Measured (RefusalProbe over the committed corpus; plain compile, no fallback options):
- reggie refuses 10, rust refuses 14 (12 rust-only + 2 shared; the benchmark's "rust=12"
  counts only reggie-accepted patterns).
- reggie-only refusals (NEW if replacing rust): 8/513 = 1.6%:
  4x "alternation priority conflict: DFA longest-match vs NFA first-alternative"
  (tableName rule, requirements.txt parser, semver pre/post/dev, kind:message parser),
  2x "anchor condition diluted in DFA construction" ((^|\S)@[]/], ^/rustc|/rustlib/),
  1x nullable-capture divergence (.*?\{\{...>.*), 1x anchor-inside-quantifier
  (multiline log block). All are CORRECTNESS guards, not resource limits.
- shared refusals (both engines): camelCase splitter (lookbehind/lookahead alternation),
  React minified-error backref+case-insensitive — no delta from replacement.
- rust-only refusals reggie SERVES natively: 12 (incl. the counted-quantifier {0,256}
  semver family via counted-loop lowering).

DEPLOYMENT NUANCE: with allowJdkFallback (the natural seam mode), ALL 8 route to
java.util.regex — zero functional refusals, and the R1 PrefilteringMatcher wraps the
fallback matcher too, so no-match inputs still get the literal rejection prefilter.
Hard-refuse deployment would strand 1.6% of rules.

LIFT LANDED (6caf5db): the 3 alternation-priority rules now re-route to PikeVM
(RuntimeCompiler alternationPriorityConflict branch; needsFallback stays the safety net).
Corpus refusals 10 -> 7 (native 506/513 = 98.6%). kind:message stays refused (own guard),
^/rustc|/rustlib/ deliberately NOT lifted (anchor-dilution class contains a measured
diverger: (^|\S)@[]/]). Gates: full suite + fuzz + corpus audit + route test with span
parity. NOTE (user correction, accepted): logs-backend regex is grok-on-JDK; rust serves
apm-processing, not these patterns — the rust lane is a reference, so refusal parity
matters only for a hypothetical rust-surface replacement; for the actual grok seam the
relevant parity is vs JDK (8 refusals -> 7, zero divergences).

PIKEVM JUDGMENT (measured 2026-09-17, user challenge "rust accepts them, why not reggie"):
hand-built PikeVM matchers (RuntimeCompiler.compilePikeVm bypasses the guard) vs the JDK
oracle, 325 inputs/pattern:
- LIFTS (0 findings): tableName rule, requirements.txt parser, semver pre/post/dev,
  ^/rustc|/rustlib/ — the alternation-priority-conflict guard is OVER-CONSERVATIVE for
  these: it fires when the DFA strategy can't express Java first-alternative preference,
  but the selector refuses instead of re-routing to PikeVM (which does first-alternative
  priority AND is linear-time, so ReDoS resistance is preserved). Native coverage with
  re-route: 507/513 (98.8%).
- HONEST (PikeVM itself refuses or diverges): kind:message (anchor-in-quantifier guard),
  (^|\S)@[]/] (14 real findings — divergence), .*?\{\{... (nullable-capture B16 family),
  multiline \n|$ block (anchor-in-quantifier). These need backtracking semantics to be
  byte-identical to Java — reggie refuses rather than return subtly-wrong spans.

ROOT PRINCIPLE: rust accepts all 8 because it promises rust semantics; reggie's contract
is JDK-identical spans (grok field extraction). ReDoS resistance was never the gate —
reggie's native engines are all linear-time; the only honest blocker is semantic fidelity.

---

---
id: find-rust-engine-crossover
type: finding
status: confirmed
depends_on: [find-backend-prod-regex-cost]
supersedes: []
related: [hyp-unanchored-find-prefilter, ev-rust-bench-lane-standardized]
tags: [rust-regex, regex-automata, dd_sds, real-corpus, smoke-test, ffm-jni, crossover, f-revision]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# reggie vs JDK vs Rust regex on the REAL logs-backend mix — CONFIRMED

## Reasoning chain
User: "would reggie be competitive against that rust impl?" and "use patterns that are in
logs-backend". Smoke: 513 real logs-backend pattern literals (readiness census,
backend-pats.json -> logs-pats.tsv) x 6 realistic log lines, engines jdk/reggie/rust
(regex 1.13.1 = regex-automata meta engine = dd_sds family; FFM incl. per-call UTF-8
marshalling; mode-split by JDK oracle boolean).

Coverage: jdk 0 / reggie 10 (2.0%) / rust 12 (2.3%) refusals; 491 common; ZERO divergences
across 2,946 pairs. Rust counted-quantifier WALL real: semver {0,256} exceeds default 10MB
NFA limit AND 256MB raised; {0,128} builds in 394ms; {0,192} fails at 256MB.

MATCH mode (327 pairs, grok-parsing shape where prod 5.6% lives): jdk 198us, reggie 180us,
rust 16us; reggie/jdk median 0.48x (2.1x faster); reggie/rust median 2.77x.
NOMATCH mode (2,619 pairs, rule-filter scans): jdk 11.2ms, reggie 5.2ms, rust 97us;
reggie/jdk median 0.68x; reggie/rust median 4.9x, totals 54x.
Worst reggie shapes: .*-prefixed unanchored patterns, e.g. '(.*) \((.*)\)': jdk 213us,
reggie 1.7ms(!), rust 316ns — no literal prefilter + per-start-position scanning.

Consequences: (1) rust is 3-5x faster than reggie at median on the real mix (11-54x totals),
surviving FFI marshal — dd_sds replacement by reggie would RAISE CPU; architectural case
only (dd.sds.Encoder ~1% apm = the cross-runtime tax the user hates). (2) f for the savings
model revised 3-6x -> ~2x central (matched-pairs median) -> see find-backend-prod-regex-cost.
(3) reggie engineering gaps surfaced: R1/R2 (see hyp-unanchored-find-prefilter).
Multi-64k: ev-reggie-cpu-savings-estimate follow-up + ev-rust-engine-bench-standardized.

---

---
id: hyp-unanchored-find-prefilter
type: hypothesis
status: open
depends_on: [find-rust-engine-crossover, ev-rust-bench-lane-standardized]
supersedes: []
related: []
tags: [prefilter, literal-extraction, memchr, single-pass, unanchored-find, roadmap, R1, R2, projection-invalid]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# R1 LANDED (38eeb62); R2 (single-pass .* scan) — OPEN

## Reasoning chain
Real-corpus smoke shows reggie's fleet-mix f is capped at ~2x vs JDK by the no-match scan:
R1 = memchr-style required-literal prefilter for unanchored find() (as rust/JDK BnM do)
would collapse NOMATCH totals where the required literal is absent (Java String.indexOf is
SIMD-intrinsic'd); R2 = single-pass unanchored scan (start-state self-loop closure, RE2
style) instead of per-start-position restarts for .*-prefix shapes (kills the 1.7ms-class
patterns). Expected to lift blended f above 2x again (user's framing); at logs-processing
scale each 1x of f ~ $100K/yr.

UPDATE 2026-09-17 (38eeb62): R1 SHIPPED — sound extractor built (language-level facts),
audited against the JDK oracle (0 violations, 287/513 patterns, 63% of no-match pairs
instant-reject), and MEASURED on workspace-jb: no-match sweep 33.9ms -> 3.7ms (9.1x),
reggie 1.17x behind rust (was 10.6x), 12x faster than JDK; matched unchanged. The old
projections are now measurements. R2 (single-pass .* scan for no-literal .*-prefix
patterns) is the remaining half — it now dominates the 3.7ms residual.

---

---
id: q-caret-midpattern-anchor
type: question
status: resolved-fixed
depends_on: []
supersedes: []
related: [find-refusal-set-parity]
tags: [anchor, caret, alternation, pre-existing, fuzz]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Mid-pattern ^ in alternation diverges from JDK — OPEN, PRE-EXISTING

## Reasoning chain
Fresh fuzz seed 131071 (first time exploring this region): pattern
`(?:[^a-caa]|c)^|.\\z\\z` on "1\\n0\\n_cbcc_" — jdk first-match [9,10) (the .\\z\\z
branch at the last char) vs reggie [0,1) (the (?:..)^ branch at 0); findAll() counts
differ (1 vs 7). Reproduces IDENTICALLY on the pre-fix tree (222c8e6 minus both fixes —
verified via git stash) — pre-existing, NOT introduced by the leftmost/greedy fixes.
Suspect: mid-pattern `^` (default mode, non-multiline) inside an alternation branch —
reggie appears to accept the (?:X)^ branch at non-zero positions or resolve branch
priority differently. Anchor-dilution-adjacent family (see find-refusal-set-parity for
the honest-refusal relatives). Fix separately; fuzz-seed 131071 is the reproducer
(FuzzProbe in /tmp/prefilter now takes the seed as argv[0]).

## RESOLVED (2026-09-17, 5c3587b + follow-up)
Root cause found by minimization: NOT the caret alone — two independent bugs in the same
shape family:
1. scanForMisplacedStartAnchor had NO AlternationNode case, so a consuming alternation
   (?:c|a) before ^ scanned as anchor-at-start and the misplaced-anchor guard never fired.
   The subset DFA for (?:c|a)^|.\z\z then ERASED the [START] acceptance condition on the
   (?:c|a)-branch accept states (merge with the parallel branch's [STRING_END_ABSOLUTE]
   conditions collapsed to unconditional, no dilution flag) → find() fired ^ at position 1.
   FIX: alternation in the spine counts as consumed when any branch consumes (over-approx,
   safe direction). Fuzz 131071: findings 3 → 0.
2. (Follow-up, found via NEW fuzz seed 777 — always run multiple seeds) the guard's decline
   target OPTIMIZED_NFA miscompiles mid-alternation consumer-then-^ into a GLOBAL start
   anchor: -^.|(?:1-) rejects EVERYTHING (even plain '1-' matching the other branch).
   FIX: decline target → PIKEVM_CAPTURE (routes BitState). Fuzz 777: findings 2 → 0.
Minimal repros: CaretProbe/NfaCaret probes in /tmp/prefilter.

---

---
id: q-fuzz-preexisting-divergences
type: question
status: open
depends_on: [ev-rd-hybrid-landed]
supersedes: []
related: []
tags: [fuzz, pre-existing, backref, span]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Pre-existing out-of-corpus divergences found by fresh fuzz seeds (90210/424242)

All reproduce on 9a4d842 (pre-RD-hybrid); none in the 513-corpus or real-input batteries.
- (1[^c]?)[^-1]*. on "c_1cb"/"b01-a": find boolean false + span [2,5) vs [2,4) — give-back shape
  (FIXED by the RD hybrid tranche, kept as regression canary)
- (.[^b-b]+.+)(\1_[10]){0}0 on "_1a-cc0b1": find boolean false — quantified-backref {0} shape
- .{3,}([^_-a0]c{1,1})[^-0] on "\n00c_cb1_1c1": group 1 span [9,11) vs [10,11)
- [10-_--0]$|[_-c]\z on "a1_-_c\nba\n-": first-match span [10,11) vs [2,3) — $/\z alternation
  (suspect: END-anchor alternation handling, same family as the hybrid substring-rematch hazard)
- 424242 has 5 total (2 shown in first runs; see probe runs for the full list)
Standing seeds: 777/48879/131071/31337/90210/424242 — zero NEW findings on 5e9abbf.

---

---
id: q-generated-nfa-findfrom-broken
type: question
status: resolved-fixed
depends_on: [ev-hybrid-context-rematch]
supersedes: []
related: []
tags: [optimized-nfa, findfrom, latent-bug, required-literal, indexOf]
generator: cairn
created: 2026-09-18
updated: 2026-09-18
---

# FIXED (1283fec): generated OPTIMIZED_NFA findFrom indexOf scan-start jump gated on verified match prefix

IMPLEMENTED (committed 1283fec, pushed; all gates green, box flat): NFABytecodeGenerator.everyMatchStartsWith() +
collectContextAwareEpsilonClosure() verify the literal is a deterministic NFA prefix (bail on
assertion/backref/conditional/counted-loop states and on accepts before the literal completes);
the scan-start jump AND the retry jump emit only for verified prefixes; the sound indexOf==-1
rejection stays for all required literals (no-match fast path unchanged); extractLongestRequiredLiteral's
startState-eps>1 guard subsumed. findMatchFrom/findBoundsFrom delegate to findFrom — one fix covers
the family; processor delegates to the same generator (dual-path covered). NfaFindFromRegressionTest
(half via EngineRouting, HybridMatcher.nfaMatcher reflection, standalone fallback) asserts findMatchFrom
parity incl. group spans on the give-back family. Corpus extended 513->528 (+15 logs,synthetic guards:
IPv4 quantified-group, @domain$ anchor-in-branch, lookbehind kv, MAC OnePass, dotted-suffix give-back,
4 new hybrids -> 106) + 9 inputs; batteries 274,567/283,008 pairs 0-div, refused=7; fuzz 15/0-reg;
box matched 180.7±5.4 / no-match 636.4±3.2 (controls flat) = new baseline.

## Historical analysis (pre-fix, kept for context)


## ROOT CAUSE (proven 2026-09-18)
NFABytecodeGenerator.generateFindFromMethod (~line 4560) inits the candidate-scan loop with
`tryPos = input.indexOf(requiredLiteral, start)` (multi-char run >= 3 via
extractLongestRequiredLiteral, or single char from requiredLiterals), and the retry jump
(~line 4848) advances `tryPos = indexOf(requiredChar, tryPos + 1)`. Both assume the required
literal appears AT THE MATCH START (position 0 of the match). The actual requiredLiterals
semantic (PatternAnalyzer.RequiredLiteralsExtractor, ~11763) is only "must appear SOMEWHERE in
every match". For (.c)+ the required char 'c' sits at offset 1 of the match (any-char first):
on "-cc" findFrom returns start 1 instead of the leftmost 0; (.0){3,} on "1b0c010bc-a" returns
-1 while matchBounded(1,7)=[1,7) exists. The multi-char branch has the same flaw for suffix runs
(a(x|y)cdefg-style) and branch-local runs (extractLiteralFromState takes the longest run
anywhere in the machine, guarded only by startState-epsilon>1). match()/matchBounded() are
correct — only the findFrom scan machinery is wrong. Comments in the generator and at
PatternAnalyzer:1163 show the authors knew the position-0 assumption; the guard list
(anchored/backref-to-lookahead/skipLiteralOptimization/hybridInfo) is incomplete for
variable-length prefixes (quantified groups, alternation mid-pattern).

## REACHABILITY CENSUS (2026-09-18, build ff34f90)
- Corpus: 0 of 513 logs-backend patterns route standalone OPTIMIZED_NFA (full routing dist
  captured: DETERMINISTIC_CHAIN 98, DFA_UNROLLED 137, HYBRID_DFA 102, ...).
- Hybrid nfa-halves (generated OPTIMIZED_NFA): findFrom no longer called by HybridMatcher since
  the ff34f90 type-gate (PikeVM/BitState halves search; generated halves substring-re-match).
- Fuzz: all 15 remaining findings' patterns route SPECIALIZED_FIXED_SEQUENCE — not this bug.
- Constructed public-API batteries (captureless give-back, alternation, lookahead/lookbehind,
  backref): 0 divergences — captureless give-back routes DFA_UNROLLED, lookarounds take
  separated-execution or sound cases.
=> LIVE-UNREACHABLE today; becomes reachable if a future lane routes captureless patterns
standalone OPTIMIZED_NFA (e.g. priority-correct lazy-DFA find, B3b-style routing guard changes).

## FIX SHAPE (when a lane needs it)
Sound only if the literal is at offset 0 of every match: compute the NFA first-set (chars that
can begin a match); emit the scan-start jump only when first-set == {literal[0]} and, for
multi-char, the literal is a deterministic prefix run; keep the indexOf==-1 REJECTION
unconditionally (sound for any-position required literals — preserves the no-match fast path);
gate the retry jump on the same condition. Dual-path rule applies (processor twin passes
requiredLiterals into the same generator). Probes: /tmp/prefilter NfaHalfProbe (direct half
findFrom), LiteralJumpProbe/CapturelessProbe/LookaheadJumpProbe (public-API parity batteries),
CorpusOptNfa (routing census).

---

---
id: q-hybrid-anchored-admission
type: question
status: open
depends_on: [ev-real-input-parity, ev-r2b-landed]
supersedes: []
related: [hyp-unanchored-find-prefilter, ev-lazydfa-nfa-delegate-limit]
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

---

---
id: q-lazydfa-findfrom-leftmost
type: question
status: resolved-fixed
depends_on: []
supersedes: []
related: [ev-r2b-landed, find-refusal-set-parity]
tags: [lazydfa, leftmost, soundness, fixed]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# LazyDFACache.findFrom leftmost restart — FIXED (222c8e6)

## Original question (from the R2b arc)
findFrom restarts a dead attempt at death+1 without re-walking — a later viable start
can begin inside a dead attempt's span. Considered latent because no corpus pattern
routes LAZY_DFA.

## Resolution (2026-09-17, 222c8e6)
CONFIRMED and FIXED. Blast radius was larger than "latent": LazyDFACache.findFrom is
also used INTERNALLY by PikeVMMatcher (findStep/rejectStep) and BitStateMatcher
(rejectStep) — but with SELF-ANCHORING closures (findStepClosure/rejectStepClosure
re-inject the start state at every position), for which the pos+1 restart is SOUND (the
union covers every start in the dead span). The PLAIN step (generated LAZY_DFA matchers,
HybridMatcher's dfaMatcher delegate) needed matchStart+1. Fix:
- findFrom = plain: restart at matchStart+1, matching nfaFallbackFindFrom's existing
  semantics — the DFA path previously DISAGREED with its own frozen-cache fallback.
- findFromUnion = self-anchoring: restart at pos+1 (unchanged behavior); PikeVM (3 sites)
  and BitState (1 site) converted.
Corpus hybrid lane perf-unaffected (.*-prefixed DFA is death-immune, no restarts); box
re-run flat. LazyDfaLeftmostTest (trap: x(?:a+b+|b+a+){75} on "xax"+"ba"*75 -> leftmost 2
not 3), validated test-first (3 failures pre-fix incl. the xx-control).

## Lessons (don't re-derive)
1. A minimal leftmost trap does NOT need alternation — bounded runs alone do (x{2}y on
   "xxxy": start 1 lies inside the dead span [0,2)).
2. Reaching LAZY_DFA from the public API is a narrow window (see ev-lazydfa-nfa-delegate-limit):
   DFA_TABLE grabs everything until stateSlots x classCount x 4B > 1MB, and above that
   the NFA-delegate span methods blow the 64KB method limit at ~6800 NFA states. The
   known-good route is a subset-construction explosion (e.g. x(?:a+b+|b+a+){75}).

---

---
id: q-real-input-validation
type: question
status: partial
depends_on: [find-rust-engine-crossover]
supersedes: []
related: [hyp-unanchored-find-prefilter]
tags: [inputs, synthetic, grok, shadow-seam, validation]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Are synthetic log lines representative of real grok traffic? — OPEN

## Reasoning chain
All real-corpus measurements (smoke + RealCorpusScanBenchmark) run 6 synthetic-but-realistic
lines; ratios per mode are the signal, absolutes indicative. Before quoting a fleet f (or a
$ number beyond the ~2x floor), validate against REAL grok traffic via the existing shadow
infra (logs.processing.grok.shadow.*, insertion point ReggieRegexPatternSupplier seam).
Also open: exact grok pattern corpus (the 513 census literals approximate the fleet mix but
grok-generated rules are the measured prod driver).

## Partial resolution (2026-09-17, 32a9e26)
LOCAL half complete: ev-real-input-parity — 527 real lines harvested from logs-backend
grok test fixtures, 270k real-pattern x real-line pairs under the drop-in contract, THREE
real divergences found and fixed (nullable-tail group span, VARIABLE_CAPTURE_BACKREF
suffix/end-anchor, parse-refusal fallback bypass), now ZERO divergences + battery committed
as RealInputParityTest. REMAINING: real PROD traffic via the shadow rollout — supplier
drafted (doc/temp/ReggieRegexPatternSupplier.java), needs reggie 0.4.0 published + a
logs-backend PR (Bastien Lemale's branch is the wiring precedent) + shadow_ratio>0.

---

---
id: q-z-anchor-span-bug
type: question
status: resolved-fixed
depends_on: []
supersedes: []
related: [ev-r1-prefilter-landed]
tags: [anchor, Z-anchor, span, greedy, pre-existing, fuzz]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# PRE-EXISTING span divergence: $ + \Z with newline-consuming greedy star — FIXED (222c8e6)

## Reasoning chain
Surfaced while landing R1: pattern `[^_-ab-c]*$\Z` on input "ccc0_1\n00a\n" — JDK
first-match span [10,11) (greedy star consumes the final \n, then $/\Z at end) vs
reggie [10,10) (empty match at position 10). Reproduces on the PRE-CHANGE build
(verified via git stash) and standalone via RegexFuzzOracle.check — NOT an R1
regression. Hidden in the seeded fuzz sweep before because the oracle's
instanceof-JavaRegexFallbackMatcher skip normally masks it; R1's wrapper broke that
skip (fixed via isJdkFallback()), which shifted the input RNG stream onto the
diverging input. Engine-side fix (greedy consumption order across $\Z boundary) is a
separate work item.

## Resolution (2026-09-17, 222c8e6)
Diagnosis: NOT an anchor-semantics bug — reggie's $/\Z were correct everywhere (verified:
\Z and $ on "\n" -> [0,0) matching jdk). The bug: the DFA_UNROLLED greedy walk's
pattern-level hasStringEndAnchor special check recorded the before-final-terminator
acceptance and RETURNED, truncating any greedy run whose charset can consume the final
line terminator. Trigger = TWO consecutive end anchors ($\Z, \Z\z) or a terminator-
consuming run nested behind other elements; single trailing anchor patterns escape to
SPECIALIZED_SUFFIX_SEQUENCE (correct). Fix: the special block was redundant-or-wrong —
the accepting-state recording already evaluates the state's FULL anchor conditions at
every position (emitSingleAnchorCheck: pos==len, len-1 terminator, len-2 CRLF) and falls
through to consuming transitions — deleted it. Corollary fixed free: [^a]*\Z\z on "x\n"
(the check ignored \z in the state's conditions). GreedyEndAnchorSpanTest: 28-case JDK
span matrix + route assertions, validated test-first (4 failures pre-fix).
Perf: box re-run flat (no-match 952us vs 983 R2b, matched 339 vs 338, rust/jdk controls
flat). matches() unaffected (full-match requires the entire input consumed; verified
matrix). Latent observation kept open elsewhere: DFASwitchBytecodeGenerator.matches()
has the same-shaped pattern-level early-TRUE at before-terminator positions — no current
routing reaches it with a diverging shape (BITSTATE takes the probes); revisit if
routing changes.

---
