---
id: ev-pr129-review-fixes
type: evidence
status: confirmed
depends_on: [ev-4lane-bench-results, ev-rd-hybrid-landed]
supersedes: []
related: [q-fuzz-preexisting-divergences]
tags: [review, pr129, counted-loop, hybrid, unicode-case, jit-gate, b185c90]
generator: cairn
created: 2026-09-21
updated: 2026-09-21
---

# b185c90: all 12 PR #129 review findings verified-then-fixed (Codex x3 + Datadog Autotest x9)

Every claim was reproduced before any fix (repros in /tmp/prefilter/rr, wiped daily). 10 were real
bugs; 2 were the same finding counted twice per bot. Each fix carries a regression test:
CountedLoopSemanticsTest (+2), HybridFreshInstanceTest, UnicodeCaseFoldRefusalTest,
CompileCacheHygieneTest (4), CountedLoopCompileTimeRefusalTest (processor, 3).

## Verified findings and fixes
1. P1 counted-loop route bypassed FallbackPatternDetector: (?=b)a{0,6000} MATCHED "a" as [0,1)
   (JDK: no-match) — the backtracker treats assertion states as plain epsilon. Fix: NFA scan
   (countedLoopUnsupportedFeature) declines on lookaround/conditional/atomic; strict refuses,
   fallback delegates. NOTE: this strict refusal also covers shapes main served via the 6000-state
   unrolled path — 0 corpus patterns affected, honest refusal chosen over teaching the backtracker
   assertions.
2. P1 compile-time (processor) path never checked hasCountedLoops: a{0,6000} CRASHED the
   generator (IllegalStateException "Unknown strategy: COUNTING_GLUSHKOV" — COUNTING_GLUSHKOV
   predates the branch, but the marker-NFA made the processor reach it); (?=b)a{0,6000} generated
   a silent false-negative matcher (DFA_UNROLLED_WITH_ASSERTIONS treats markers as dead). Fix:
   resolveRealization + generate refuse; strict throws with a Reggie.compile() pointer.
3. P1 nested counted-loop counters carried across outer iterations: (?:x(?:yy){0,6000}){0,500}
   on two saturated sections -> [0,12002) vs JDK [0,12004). Fix: nestedLoopSlots precompute
   (reachability from body entry bounded by the loop's stop) + zero-on-body-entry.
4. P1 hybrid matchers SHARED through L1 (compile() twice -> same instance, mutable PikeVM/BitState
   half) while PikeVM/BitState patterns are explicitly removed — introduced by the hybrid
   admission tranches. Fix: HybridEntry (shared stateless DFA half + fresh nfa-half per call)
   + fast path + L1 fixup, mirroring PikeVMEntry.
5. P2 (?iu)/(?iU) case fold divergence: (?iu)k no-match on Kelvin U+212A where JDK matches.
   DISCOVERED: (?iU) diverges TOO — the JDK's UNICODE_CHARACTER_CLASS implies unicode-aware
   case folding (undocumented; measured on JDK 21). Fix: parser rejects ci+u and ci+U at the
   fold site (same convention as \b-under-(?U)); 0 corpus patterns use u/U flags.
6. P2 JIT-gate classfile scanner read 4 of the 8-byte long/double payload -> pool derail -> -1
   -> NO GATE for any generated class with long constants. Fix: getLong().
7. P2 JIT size gate only on structural-cache miss; strict-cached oversized class was returned to
   later fallback compiles. Fix: largestMethodBytecodes recorded in CachedStructure, gate on
   verified hits (end-to-end verified via cached() with a fresh key).
8. P2 missing prefilter facts re-analyzed per compile (computeIfAbsent drops null). Fix: NO_FACT
   sentinel ("").
9. P2 clearCache missed COUNTED_LOOP_NFA_CACHE + LITERAL_CACHE (leak). Fix: both cleared (+HYBRID_CACHE).
10. P2 RustRegexEngine.close() double-free (Box::from_raw on freed pointer). Fix: zero handle
    first; box-verified with the native engine (double close survives).

## Gates after
Full build + jacocoVerify green. Fuzz: 0 new findings; 10 of 21 standing canaries DISAPPEARED
(\Z/\z-span, hybrid-context, nullable-tail families). Corpus batteries 0 divergences. Box corpus
sweep FLAT: matched 179.5us / no-match 634.9us (controls flat) — the hybrid restructure costs
nothing at the corpus.

## Methodology (new)
- Routing census on the RAW corpus TSV LIES (patterns are escaped): the snowflake-JDBC hybrid
  from the census probe is NOT hybrid unescaped. Always unescape (RealCorpusScanBenchmark's
  unescape) or verify with describeRouting before hardcoding a hybrid pattern in a test —
  .*-shadow(-.*)?-sep is the verified standalone hybrid for tests.
- Structural-cache hit-gate scenario: Reggie.compile(p) strict (caches oversized class) then
  RuntimeCompiler.cached("fresh-key", p, fallback) — different L1 key, same structural hash.
- git stash on this worktree can pop ANCIENT stashes from other branches (untracked-file
  conflicts); avoid stash here, use reset --soft grouping for fixup regrouping.

## Branch hygiene (same day)
History squashed 62 -> 10 logical chunks (read-tree method, tree byte-identity verified),
comments humanized (why-only per user directive; cairn node ids stripped from source), PR title/
body scrubbed of internal system names (logs-backend already public in repo files on main, so
source mentions left; PR text only). PR #129 is no longer draft (human marked it ready — not me).
