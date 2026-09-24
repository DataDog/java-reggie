# Current State

## Active investigation
"Work around max 64k per method" — **CONCLUDED 2026-09-16**. Final disposition: the L2 generic
method-size splitter was built, validated (no runtime regression; +9% compile cost), then
**DROPPED** after the effectiveness analysis showed it fires on nothing real (11 constructible
overflow families, all declined; wide-switch noCut flood). See q-l2-disposition.

## Current hypothesis
CONCLUDED 2026-09-17: hyp-counted-loop-lowering IMPLEMENTED (8e4750c) + DFS allocation TODO FIXED (750f848, user directive)
(counted-loop lowering + counter-aware DFS; semver 2,257ms->61ms compile, 25-400x->10-19x
JDK match, parity-exact, budgeted fast-fail; ev-counted-loop-fix-verification). Branch =
2fc44cc -> 715fb53 -> a5ef0fb -> 8e4750c; all suites green.

## What I'm doing now
ALL WORK CONCLUDED 2026-09-17. Branch feat/dd_backend_check = 2fc44cc -> 715fb53 (chore:
spotless) -> a5ef0fb (fix: deadline coverage gaps, A+B). Tree clean except untracked
doc/investigations/ cairn export. Deadline-gaps fix implemented on the same branch, verified
before/after (ev-deadline-fix-verification), all suites + integration + spotless green.
Remaining optional items:
1. workspace-jb cleanup if desired: ~/bench/l2-validation/ + ~/bench/java-reggie-l2-validation
   can be deleted; jars + results are summarized in cairn.
2. doc/investigations/ — CAIRN STORE NOTE: the consolidation pass of 2026-09-17 relocated
   the live cairn store here (doc/investigations/multi-64k; the ~/.cairn copy is empty).
   This is NO LONGER a disposable export — do not delete; decide commit-vs-relocate with
   the user.

## Open questions
- q-prod-readiness (2026-09-17, REVISED): "Ready to replace JDK regex / RE2J in backend
  services?" — Closer than first assessed. NOW CONFIRMED: compile DoS-boundedness (a5ef0fb);
  JDK-parity correctness (RE2/PCRE suites, JDK-oracle fuzz); refuse-by-default degradation
  (find-fallback-optin-refuse, code-verified — my original "silent backtracking fallback"
  and "no RE2J benchmark in repo" claims were wrong: grep head-truncation + assumption;
  user corrected 2026-09-17); full 3-engine bench harness in-repo (9 classes, reggie vs JDK
  vs re2j:1.8) + user-attested full-run win (large margin; README concurs). REMAINING:
  fleet JVM-flavor census (OpenJ9 cliff), service-corpus refuse-rate/routing census, and —
  for the cairn record — a current benchmarkAndReport run on Temurin (README: on-file run
  predates perf work 6841723/5db1866, uncommitted). Closure sequence in the node.
  PROGRESS 2026-09-17 (later): backend survey DONE (ev-backend-usage-survey + find-logs-backend-
  re2j-migration): profiling-backend = 1 RE2J user-regex site + 51 JDK compiles; logs-backend =
  4 RE2J deps, grok mid-migration-to-RE2J with pluggable supplier seam, 666 JDK compiles (84
  dynamic); REAL census 563 literals = 98.0% native, 11 refusals, worst compile 2.1s semver.
  OpenJ9 mooted for backend targets (user). BENCH DONE (ev-3engine-bench-current): 64/64 vs
  JDK, 62/62 vs RE2J, medians 5.9x/46.5x, max 1000x — with carve-out: semver {0,256} family
  regresses BOTH axes vs JDK (find-bounded-quantifier-regression, PikeVM routing refuted
  empirically; fix = counter-based codegen, large scope, only if fleet needs it). Degradation
  policy answered (q-2pct-fallback-policy: current services refuse/drop everywhere; reggie
  default maps 1:1; no silent ReDoS-able fallback — matches user constraint). JDK replacement
  in scope per user. Triage DONE (ev-refused-triage): 4 verified
  rewrites + 1 candidate + 6 JDK-retained (marked in code at migration time); native
  coverage ~99%. USER DIRECTIVE 2026-09-17 (semver must be fixed before proceeding): DONE — 8e4750c.
  Remaining: grok production-engine confirmation, SortTags rewrite verification; NEW DATA: ev-semver-vs-re2j (reggie/re2j = 2-3x on the counted-loop family, re2j supports it fully),
  DFS perf optimization DONE (750f848: 3.6-8.5x; ~1.5-2x structural headroom left, not needed).
  PROGRESS 2026-09-17: census DONE (ev-corpus-refuse-census — IAST 13/16 native, refusals =
  (?P< syntax flavor only; industry 310/361; max compile 78ms). 3-engine JMH bench (202
  entries) RUNNING in background /tmp/bench3e (smoke: reggie ~43x JDK, ~166x RE2J on
  phone-match) — file evidence node on completion. Worktree path changed mid-session: derive
  via `git rev-parse --show-toplevel`.

- ev-reggie-cpu-savings-estimate (2026-09-17): prod regex CPU MEASURED from continuous
  profiles: logs-processing 5.60% JDK regex (~100% grok, re2j 0.00% in prod),
  prof-analyzer 1.00%, apm-processing 0.38% (Rust dd_sds regex ~11% there is NOT
  reggie-replaceable). Savings $296-370K/yr at f=3-6x, ~89% in logs-processing.
  Full write-up: Datadog notebook 15576172.

- ev-rust-engine-bench-standardized (60db93c): Rust regex standardized as 4th
  benchmark lane (JNI, Java-21-safe; RealCorpusScanBenchmark = permanent real-mix
  gate, reproduces smoke exactly). NOTE: R1/R2 prefilter projections are INVALID
  until a sound literal extractor replaces the broken quick one (0/513 bug).

## Confirmed findings (don't re-derive)
- find-repo-method-size-landscape — layered L1/L2/L3 design and pre-existing repo state.
- find-asm-classwriter-final — ClassWriter final methods; wrapping ClassVisitor is the only interception.
- find-asm-toobytearray-sizecheck — size check fires at toByteArray; probes must serialize.
- find-sourceinterpreter-pathologies — ASTORE masking + quadratic merge growth.
- find-descriptor-interpreter-design — DV interpreter; sound, O(1) merges; ASM 9.10 API gotchas.
- find-try-handler-chunk-constraint — region + handler entry in one chunk; bodies tail-chain.
- find-l2-splitter-shipped — full implementation map; DROPPED from tree, preserved as patches.
- find-no-overflow-trigger-today — corpus patterns never overflow; constructible families all funnel to NFA.
- find-visitmaxs-zero-blindspot — visitMaxs(0,0) blinded L2 to all real generators (was fixed in v3).
- find-computeextras-early-return-bug — extras dropped when params live alongside locals (was fixed in v3).
- find-l2-dead-code-verdict — L2 fires on nothing real; NFA-L1 supersede P≈0.4-0.5; disposition: dropped.
- find-openj9-deadline-rejection — OpenJ9 rejects HotSpot-compilable patterns at the 10s deadline.
- find-deadline-coverage-gaps — 10s deadline holes RESOLVED by a5ef0fb (A+B; numbers in node).
- find-matchtime-heap-scaling — match-time memory scales with groups; 12k groups OOMs 512m at matches().

## Ruled out (don't re-investigate)
- dead-classwriter-subclass — final methods.
- dead-sourceinterpreter-typing — quadratic + masking.
- dead-handler-extent-heuristic — fall-through handlers break it.
- dead-real-generator-differential-test — no trigger pattern within deadline envelope.
- (whole L2 approach, 2026-09-16) — no real pattern is v1-splittable; dead code while wired;
  see find-l2-dead-code-verdict. Preserved in reports/ for revival.
