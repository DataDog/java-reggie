# Investigation Index

> STATUS: DONE+FIXED (2026-09-17). READINESS evidence now complete on all axes: bench 64/64+62/62 wins (ev-3engine-bench-current), real census 98% native, degradation policy answered (q-2pct-fallback-policy), OpenJ9 mooted, insertion points found — with ONE regression carve-out (find-bounded-quantifier-regression, semver family).

## Findings (confirmed)
- find-bounded-quantifier-regression | RESOLVED by 8e4750c (counted-loop lowering) + 750f848 (DFS alloc fix): 61ms compile, 3.6-8.5x-JDK match, exact semantics + budgeted fast-fail; family now migratable | [bounded-quantifier, semver, unrolling, nfa-size, compile-cost, match-performance, regression, routing-refuted, pikevm, bitstate]
- find-logs-backend-re2j-migration | logs-backend grok regex is mid-migration to RE2-J with a 13-class pluggable supplier layer + shadow infra + fleet inspection job — ReggieRegexPatternSupplier is the natural insertion point; RE2-incompatible syntax reggie handles natively+linearly | [logs-backend, re2j, migration, grok, insertion-point, shadow-testing, supplier, engine-abstraction]
- find-fallback-optin-refuse | JDK fallback is OPT-IN; default throws UnsupportedPatternException (single gate, no bypass) — README's O(n)/ReDoS-safe footnote grounded here | [fallback, jdk-fallback, unsupported-pattern-exception, refuse-by-default, safety-posture, redos, linear-time]
- find-matchtime-heap-scaling | Native match-time memory scales with group count — 12k groups OOMs a 512m JVM at matches(); tests must be heap-safe; pre-existing, separate from compile bounds | [match-time, memory, pikevm, groups, test-infrastructure, gradle, scaling]
- find-repo-method-size-landscape | JVM 64k method limit: repo state (L1/L3 pre-existing) and agreed layered design | [jvm, method-limit, codegen, architecture, layered-design]
- find-asm-classwriter-final | ASM 9.10 ClassWriter.visit/visitMethod are final — intercept via wrapping ClassVisitor | [asm, classwriter, classvisitor, interception]
- find-asm-toobytearray-sizecheck | MethodTooLargeException fires at toByteArray(), not visitMaxs — probes must serialize | [asm, methodtoolargeexception, tobytearray, probe]
- find-sourceinterpreter-pathologies | SourceInterpreter: copyOperation masks producers + quadratic set growth on loop-carried locals | [asm, sourceinterpreter, quadratic, astore-masking, analysis]
- find-descriptor-interpreter-design | DescriptorInterpreter: one descriptor per value, O(1) merges, UNKNOWN = reject cut; ASM 9.10 API gotchas | [asm, interpreter, type-descriptor, soundness, api-gotchas]
- find-try-handler-chunk-constraint | Region + handler ENTRY share a chunk; handler bodies may tail-chain across cuts | [jvm, exception-table, try-catch, cut-points, verifier]
- find-l2-splitter-shipped | L2 implemented + validated, then DROPPED — preserved as patches in reports/ | [implementation, splitter, pipeline, wiring, tests, dropped]
- find-visitmaxs-zero-blindspot | visitMaxs(0,0) made L2 blind to all real generator methods (~230 sites) — fixed via maxima repair in analyze() | [visitmaxs, computemaxes, asm-analyzer, bug, post-ship-fix, maxlocals]
- find-computeextras-early-return-bug | Extras dropped when params live alongside non-param locals — latent chunk-signature corruption; fixed | [bug, liveness, extras, chunk-signature, corruption, post-ship-fix]
- find-l2-dead-code-verdict | L2 fires on nothing real (11 families, all declined); +9% cost; DROPPED 2026-09-16 | [effectiveness, dead-code, battery, nfa-cascade, wide-switch, disposition]
- find-deadline-coverage-gaps | RESOLVED by a5ef0fb (A+B): emission budget (65535 insns/method) + charged bypass BFS + compile-scope OOM catch; look-1000 6g-OOM/4.2s -> 286ms graceful, calt-6000 22.3s -> 2.4s (x12000 3.6s) | [deadline, dos, oom, bitstate, compile-time, coverage-gap, verified-on-baseline, resolved-by-fix]
- find-no-overflow-trigger-today | No current pattern family overflows a method (BitState/tables absorbed them; OOM/deadline first) — L2 is a net | [bitstate, huge-charset, alternation, compile-deadline, oom]
- find-openj9-deadline-rejection | OpenJ9 10s-deadline rejects HotSpot-compilable patterns (37/83 forks) — JVM-flavor cliff + bench JVM must be Temurin | [openj9, deadline, compile-deadline, j9, deployment]

## Hypotheses
- hyp-v2-driver-lowering | Driver/continuation lowering for wide-switch cascades | [v2, driver, continuation, state-machine, back-edges] | OPEN (not planned; L2 dropped)
- hyp-counted-loop-lowering | IMPLEMENTED (8e4750c + 750f848 + 677417b re2j-parity): x{n,m} monsters lower to counter markers + counter-aware DFS; exact JDK semantics, budgeted; fixes the semver family | [bounded-quantifier, counted-loop, lowering, dfs-backtracker, design, semver, fix-implementation] | IMPLEMENTED

## Dead ends
- dead-classwriter-subclass | SplittingClassWriter subclass | [asm, classwriter, final, refuted-approach] | REFUTED
- dead-sourceinterpreter-typing | SourceInterpreter as slot-typing basis | [asm, sourceinterpreter, refuted-approach, performance] | REFUTED
- dead-handler-extent-heuristic | Handler-extent-to-first-terminal noCut zone | [exception-handler, no-cut-zone, refuted-approach] | REFUTED
- dead-real-generator-differential-test | Real-generator overflow differential test in current corpus | [testing, differential, probe, ci-flake] | REFUTED

## Evidence
- ev-word-boundary-find-fix | 10b1a43: find-span parity battery (committed RealFindParityTest, ~2s) caught 2 pre-existing  find() divergences (MULTI_GROUP_GREEDY cross-boundary accept, RECURSIVE_DESCENT  no-match); fixed via targeted declines; 266k find pairs 0 divergences | [word-boundary, find, 10b1a43]

## Evidence
- ev-asm-semantics-probes | WhereProbe + SourceProbe outputs pinning ASM behavior | [asm, empirical, probe-output]
- ev-merge-hang-threaddump | Thread dump: Analyzer.merge hotspot on 78k-insn method | [performance, thread-dump, analyzer-merge]
- ev-split-class-javap | javap of split class: chain correct, test formula inverted | [javap, bytecode-decode, chunk-chain, test-bug]
- ev-validation-green | Final validation (6/6 unit, 3,152 runtime, spotless) + strategy probe outputs | [tests, regression, probe-results]
- ev-split-coverage-probe | No benchmark exercises the split path; 11 constructible overflow families, ALL declined (0 rescues); supersede-P recalibration | [benchmark-coverage, split-path, literal, nfa, code-size, empirical]
- ev-bench-ab-temurin | A/B on workspace-jb/Temurin: 83 JMH entries no reggie regression; compile-time +9% | [benchmark, jmh, temurin, compile-time, runtime]
- ev-deadline-fix-verification | Before/after: look-1000 OOM 4.2s -> 286ms graceful; calt-6000 22.3s -> 2.4s; calt-12000 3.6s (scale-independent) | [deadline, fix, benchmark, before-after, probe]
- ev-counted-loop-fix-verification | Semver fix (8e4750c) + DFS alloc TODO fix (750f848): 2,257ms -> 61ms compile, 25-400x -> 3.6-8.5x-JDK match, parity-exact incl. >256 rejections, adversarial shapes fail fast (~230ms) where JDK hangs | [counted-loop, fix, before-after, semver, perf, parity, budget, commit-8e4750c]
- ev-semver-vs-re2j | re2j 1.8 fully supports the semver {0,256} family (37ms compile, parity incl. named groups + >256 rejections); reggie/re2j = 2-3x realistic (6x trivial), re2j itself 1.5x slower than JDK here (no lazy DFA) | [re2j, comparison, semver, perf, parity, baseline, migration]
- ev-re2j-parity-push | DFS optimized to re2j PARITY on realistic counted-loop inputs (0.98-1.2x; 2.5-3x on degenerate 5-char); +fusion follow-up TRIED/MEASURED/REVERTED (net-negative: helps only 5-char): join-point-only memo + live-key masking + pass-through compression + two-level (state,pos)-keyed MemoSet; research: re2j expands to 419k insts, Hyperscan uses counter machinery | [re2j, parity, perf, dfs, memo, join-point, liveness, compression, research, commit-677417b]
- ev-refused-triage | Per-pattern triage of the 11: 4 verified rewrites (unifiedkv native w/ captures; rustc → string ops), 1 candidate pending verification, 6 JDK-retained w/ markers; native coverage → ~99%; version-parsing = recurring family | [triage, refused-patterns, rewrite, jdk-retained, migration, per-pattern, rewrites-verified]
- ev-3engine-bench-current | Current recorded 3-engine JMH: reggie faster in 64/64 vs JDK (median 5.9x, max 1000x) and 62/62 vs RE2J (median 46.5x); JDK drain 137ms/op backtracking; recovered from log after harness path bug | [benchmark, jmh, 3-engine, jdk, re2j, verdict, current-recorded-run]
- ev-backend-usage-survey | Real-service survey: prof-backend 1 RE2J site (UserDefinedRegex, ReDoS immunity) + 51 JDK compiles; logs-backend 4 RE2J deps + grok supplier layer + 355 files/666 JDK compiles (84 dynamic); census on 563 real literals: 98.0% native, 11 refusals, worst 2.1s | [backend-services, jdk-regex, re2j, usage-survey, census, profiling-backend, logs-backend, migration]
- ev-corpus-refuse-census | Default-options census: industry 310/361 native (refusals = RE2J-inexpressible + honest divergence guards); IAST 13/16 (3 = (?P< syntax flavor, mechanical cure); max compile 78ms | [census, refuse-rate, routing, corpus, re2, pcre, iast, service-patterns, compile-time]

- ev-reggie-cpu-savings-estimate | MEASURED prod impact: logs-processing JDK regex = 5.60% CPU ($413K/yr, ~100% grok-driven, re2j 0.00% not landed); prof-analyzer 1.00%; apm-processing 0.38% (heavy regex is Rust dd_sds, not replaceable) -> $296-370K/yr savings at f=3-6; notebook 15576172 | [backend-services, cost, continuous-profiler, grok, savings, cloud-cost, rust-dds]

- ev-reggie-cpu-savings-estimate | +FOLLOW-UP real-corpus 3-engine smoke: rust ~3-5x faster than reggie at median on real logs-backend mix (11-54x totals, survives FFI marshal); reggie/jdk median 2.1x MATCH / 1.5x NOMATCH -> f revised to ~2x central ($210K/yr); reggie gaps: unanchored find() literal prefilter + single-pass .* scan; rust counted-quantifier wall real | [rust-regex, smoke-test, ffm, real-corpus, prefilter-gap, revision]

- ev-rust-engine-bench-standardized | Rust regex = 4th benchmark lane (60db93c): JNI shim (regex-automata, marshal in timed path) + RustRegexEngine adapter + buildRustEngine task + MatchOperationBenchmark rust lanes + RealCorpusScanBenchmark (513 real logs-backend patterns, MATCH/NOMATCH split; reproduces smoke exactly 491/10/12) — the acceptance gate for prefilter roadmap R1/R2 | [benchmark, rust-regex, jni, real-corpus, 4th-lane, 60db93c]

- ev-r1-prefilter-landed | 38eeb62: R1 SHIPPED — sound AST literal extraction (2 audit-caught extractor bugs fixed) + universal PrefilteringMatcher rejection at every compile() return; workspace-jb measured: no-match sweep 33.9ms -> 3.7ms (9.1x), reggie/rust 10.6x -> 1.17x, matched unchanged, jdk/rust controls flat; audit 287/513 literals, 63% of no-match pairs instant-reject, 0 divergences | [r1, prefilter, required-literal, workspace-jb, 9x, 38eeb62]

- ev-r2-rd-landed | b5cf63e: R2 first half — recursive-descent .*-prefix find() made linear (anchor-start for fully-^-anchored spines + fast give-back via capture-free iteration-position arrays, slot-collision bug caught by span battery); box: no-match 3.73->2.66ms (R1+R2 total 12.7x), reggie 1.19x AHEAD of rust on no-match, blended corpus fastest overall (~16x jdk); R2b = HybridMatcher lane (~40% of residual) | [r2, recursive-descent, give-back, anchor-start, workspace-jb, b5cf63e]

- ev-r2b-landed | 97ec426: R2b = ci-facts + R1b 1-char required facts; same-day box series no-match 15,571 -> 2,475 -> 2,070 -> 983us (15.8x; reggie 1.79x ahead of rust no-match), matched flat 338us; hybrid no-match collapsed 582->58us; methodology: 10k+ invocations needed for C2, box drift ~1.85x between days — same-run controls mandatory | [r2b, r1b, ci-facts, 97ec426]

- ev-correctness-cleanup-a | 222c8e6: greedy \\Z-span truncation (DFA_UNROLLED early-return deleted; 28-case matrix test-first) + LazyDFA findFrom leftmost skip (plain restart matchStart+1, self-anchoring -> findFromUnion; consistent with its own NFA fallback); box flat; new pre-existing q-caret-midpattern-anchor (seed 131071) + ev-lazydfa-nfa-delegate-limit recorded | [correctness, greedy, leftmost, 222c8e6]

- ev-real-input-parity | 32a9e26: real-input parity battery (logs-backend-grok-test-harvested lines x 513 corpus, drop-in contract) — 3 divergences found+fixed (nullable-tail spans, backref suffix/end-anchor, parse-refusal fallback bypass), 270,351 pairs / ZERO divergences after; battery committed; shadow supplier drafted | [real-inputs, parity, 32a9e26]

## Questions
- q-hybrid-anchored-admission | OPEN: hybrid admission for anchored PIKEVM/BITSTATE measured NEGATIVE (matched 340->687us, 10b1a43 reverted 2db164b); re-entry points in node (LazyDFA/RD dfa-half 420ns/char on alternation shapes; whole-line anchored .*$ shapes gain nothing from narrowing; `.+)` vs `.*)` decides alternation-priority flag) | [hybrid, anchors, matched, lazydfa]

## Questions
- q-lazydfa-findfrom-leftmost | FIXED 222c8e6: plain-closure findFrom restarts matchStart+1; self-anchoring closures (PikeVM, BitState) use findFromUnion | [lazydfa, leftmost, fixed]
- q-caret-midpattern-anchor | OPEN, PRE-EXISTING: (?:[^a-caa]|c)^|.\\z\\z diverges (jdk [9,10) vs reggie [0,1)); mid-pattern ^ in alternation; seed 131071 | [anchor, caret, fuzz]
- q-lazydfa-findfrom-leftmost | FIXED 222c8e6 | was OPEN, LATENT (no corpus pattern routes LAZY_DFA): LazyDFACache.findFrom restarts at death+1 without re-walking — a later viable start can begin inside a dead attempt's span (ab|b on xaab) — fix before routing anything through it | [lazydfa, leftmost, soundness]
- q-2pct-fallback-policy | ANSWERED: no silent JDK fallback needed — current services refuse/drop at every site (400/skip/disable/drop); reggie default maps 1:1; statics = per-pattern explicit decisions; dynamics never fall back | [fallback-policy, jdk-fallback, redos, refuse-by-default, degradation, migration-policy, dynamic-patterns]
- q-prod-readiness | REVISED after user corrections: refuse-by-default code-verified + full 3-engine bench harness in-repo (9 classes) + attested reggie>both; remaining = fleet JVM census + service-corpus refuse-rate + optional current recorded bench run | [readiness, production, adoption, jdk-regex, re2j, backend-services, verdict]
- q-l2-disposition | ANSWERED: DROPPED (2026-09-16); work preserved in reports/ | [disposition, decision, scope]
- q-passthrough-overhead | ANSWERED: passthrough adds ~+9% compile-time (range 0-15%), no runtime regression | [benchmark, performance, compile-time]
- q-commit-hygiene | ANSWERED: committed 715fb53 (chore: spotless, 24 files, content-only) | [commit, spotless, git-hygiene]
