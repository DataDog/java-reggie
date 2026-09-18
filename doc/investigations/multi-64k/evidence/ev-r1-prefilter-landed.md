# ev-r1-prefilter-landed: R1 required-literal rejection prefilter SHIPPED (2026-09-17, 38eeb62)

USER DIRECTIVES: "let's focus on the identified improvements now" (R1/R2), then
"stop wasting time running benchmarks here. run them on workspace-jb ssh box".

WHAT SHIPPED (38eeb62, feat/dd_backend_check, full suite green: 3157 runtime +
integration + differential fuzz oracle):
1. RequiredLiteralAnalyzer (codegen/analysis): sound language-level literal extraction.
   Facts = strings contained in EVERY match of the node's language; merge rules: concat
   exact-chains (extend through zero-width anchors/epsilon), boundary merges
   (suffixRun+chain / chain+prefixRun), alternation fact-intersection + LCP/LCS runs,
   quantifier min>=1 pass-through / min=0 drop, atomic-group subset pass, conservative
   breaks at backrefs/assertions/conditionals. Case-insensitive -> fold-stable facts only.
2. PrefilteringMatcher (runtime): universal input-rejection wrapper (find/findFrom/matches/
   findMatch/findMatchFrom reject when the required literal is absent). Applied at EVERY
   RuntimeCompiler compile() return via LITERAL_CACHE (per pattern string, computed once);
   shared L1 + cached() instances wrap AT INSERT to preserve same-instance contracts
   (assertSame tests caught the per-call-wrap bug); NFA-backed fresh-per-call paths wrap
   per call. Covers ALL strategies (DFA_SWITCH/UNROLLED/TABLE, OPTIMIZED_NFA, PikeVM,
   BitState, Hybrid, LTS, recursive descent) — the DFA lanes dominate the corpus, which is
   why per-generator bytecode emission (tried first, since removed) missed the win.
3. isJdkFallback() public API: wrappers transparent to fallback routing. Root cause of a
   fuzz-oracle regression: the oracle's "JDK fallback agrees by construction" skip used
   instanceof, the wrapper defeated it, the un-skipped pattern shifted the input RNG
   stream and surfaced a PRE-EXISTING divergence (see q-z-anchor-span-bug).

TWO EXTRACTOR BUGS FOUND BY THE AUDIT (both fixed, both would have gutted coverage):
- chains ending at a non-exact child with empty prefixRun never closed -> ".amazonaws.com"
  style facts lost for .*-prefix patterns (the Hybrid/.* bucket).
- 1-char facts flooded the 16-entry set and evicted long chain facts (MIN_USABLE_LEN=2
  inside addFact + evict-shortest-on-full; cap 32). " successfully logged in with "
  (33 chars) was being dropped while single chars filled the set.

MEASURED (workspace-jb, Linux x86_64, 16 cores, idle; RealCorpusScanBenchmark,
-wi 3 -i 5 -f 2; jdk+rust control lanes stable across builds):
- reggieSweepNoMatch: 33,896us -> 3,727us (9.1x). reggie/rust: 10.6x behind -> 1.17x.
  reggie vs JDK no-match: 0.78x -> 12x faster.
- reggieSweepMatched: 564 -> 586us (unchanged, prefilter passes there by design).
- jdk 43.7ms/44.9ms, rust 3.20/3.18ms no-match — controls flat, methodology sound.
Audit (RequiredLiteralAuditTest, committed, JDK-oracle-gated):
- 287/513 patterns with literal (avg 9.0 chars); 1690/2696 no-match pairs = 63% instant
  reject; zero soundness violations; zero reggie/JDK divergences end-to-end.

REMAINING (R2, next): the no-match sweep is now dominated by no-literal .*-prefix
patterns (^(.*)-([0-9]{1,4})$ family, (.*) \((.*)\), (.*[a-z0-9/-]*)-([0-9]*)). R2 =
single-pass unanchored scan (carry NFA state set across positions instead of restart
per start). Expected to close most of the remaining 3.7ms.

NOTE (measurement honesty): local mac benchmarks were ±2.5x noisy from concurrent
gradle daemons — the workspace-jb numbers are the citable ones.
