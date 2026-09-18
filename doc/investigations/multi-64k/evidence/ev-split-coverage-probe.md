---
id: ev-split-coverage-probe
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-l2-splitter-shipped, find-no-overflow-trigger-today, find-visitmaxs-zero-blindspot, hyp-v2-driver-lowering]
tags: [benchmark-coverage, split-path, literal, nfa, code-size, empirical]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Does anything produce a too-large method and get split? — No; plus the deliberate-trigger probe

## The question
"Do we have something in the benchmarks that would have produced the too large method and now
is split?"

## Empirical answer: NO — the split path never fires for anything in the benchmark suite
- **Build-time (APT)**: scanned all 1,204 com/datadogh classes in the candidate fat jar (the
  patched processor generated the benchmark matchers during the candidate build) for `$s` chunk
  methods → 0. Baseline control: 0 (no splitter there).
- **Runtime**: compiled all 28 distinct static string constants from the 6 selected benchmark
  classes through `RuntimeCompiler.compile` on the candidate jar → 28 compiled, 0 fallbacks,
  **0 split matchers**.
- Controls `(?:abcdefghij){2000}`, `(?:ab|cd){3000}`: compile to small code, no split.
The benchmarks therefore validated the verbatim path (identical bytecode → no runtime
regression) and the passthrough cost — NOT the splitting path. The splitting path's coverage
remains the synthetic unit tests (now 7/7).

## Deliberate trigger: "a" × 40,000 → DOES produce a too-large method; v1 still declines
- Baseline (2fc44cc): `UnsupportedPatternException: generated method too large:
  ...findLongestMatchEnd(Ljava/lang/String;I)I codeSize=4,719,342` (NFABytecodeGenerator
  per-config cascade, ~1.8M instructions, wide dispatch switches).
- Candidate (with L2 patch v1): **same throw** — split declined. Compile took 4.3–4.4s vs
  baseline 2.3–2.4s (analysis ran, then declined).
- Blockers identified in order:
  1. `visitMaxs(0,0)` maxLocals bug — **fixed** (find-visitmaxs-zero-blindspot; with the fix,
     analysis proceeds past instruction 0).
  2. Heap: analyzing 1.8M instructions needs >512 MB (gradle default test JVM OOMs; 6g works).
  3. Wide-switch noCut flood: `forbidCrossing` over every switch edge marks the entire span
     between a giant dispatch switch and its (far) targets as no-cut → chooseCuts starves at the
     first window (i=83, 1.8M insns). Also: single tableswitch with ~40k labels ≈ 160 KB alone
     exceeds any 64 KB method — inherently un-emittable.
- Net behavior: L2 preserved status quo (declined → verbatim → L3 throw), but the pattern is the
  **first constructible trigger family for v2 driver lowering / NFA L1 bucketing**.

## Patch bookkeeping
- workspace-jb:/tmp/l2-splitter.patch = v1 (pre-fix, what the benchmark A/B ran).
- Mac:/tmp/l2-splitter.patch = v2 (v1 + fix + regression test + doc updates; 3 files differ, 56
  byte-identical). Benchmark conclusions unaffected: the verbatim path (what the suite measured)
  is identical in v1/v2; no split fired in either benchmark run.

## Battery (post-fix, v2 candidate vs baseline jar; both Temurin, 6g)
Constructible overflow families, both sides:
- literal8k: OK both (no overflow). literal12k / literal20k / periodic6k ("abc"x6000!) /
  groups2k / groups4k / capalt2k ("(x0|…|x1999)") / literal40k-a: TOO-LARGE on BOTH sides —
  candidate spends 2–12x longer then declines (capalt2k 1.1s -> 9.5s, near the 10s deadline).
- (abcdefgh)\1{1000..3500}: compact (loop, ~10ms) — never overflows. (Battery v1's
  "backrep2500 TOO-LARGE" was a double-escape artifact — the pattern actually tested a
  literal "\1{2500}" suffix.)
- Instrumented Mac run, 6 correct shapes: 12 oversized methods TRY split, 12 DECLINE —
  all at chooseCuts window starvation: NFA-cascade findLongestMatchEnd at i=83 (noCut from
  52 — the giant switch table at the method head), capalt2k's ~923k-insn match family at
  i~1979xx (noCut from ~19290x). Zero successes.
- Also fixed during this round: computeExtras early-return bug (find-computeextras-early-
  return-bug) — latent chunk-signature corruption, suite now 8/8.

## Extended battery (post-fix; targets non-NFA generators — 10 more shapes)
- greedy `(.*?)a` x8000/24000: BitState, compact, no overflow.
- lookahead chain x1000: OOM (6g) at 26s on candidate (later jstack pin on 715fb53: dies in NFA
  codegen EMISSION, generateMatchIntoMethod:8379 — not analysis as first believed; ~4.2s on the
  L2-free tree); x3000: rejected in 1.2s (size cap catches it) — see find-deadline-coverage-gaps.
- concatAlt `(wa0|wb0)(wa1|wb1)...` x2000/x6000: OK BitState but 19s/73s compiles on candidate
  (22.3s@x6000 on 715fb53) — deadline hole, verified pre-existing (find-deadline-coverage-gaps).
- classGrp `([a-y])([b-z])` x4000/12000: TOO-LARGE (matches() 1.5MB/4.5MB) — declined.
- concatGrp `(g0x)(g1x)...` x4000/12000: TOO-LARGE (matches() 2.2MB/6.9MB) — declined.
=> 4 more overflow families, all still declined (running total: 11 families, 0 rescues).
MultiGroupGreedy rejects fixed-length groups (needs variable segments) -> these funnel to NFA
like everything else. Specialized generators self-limit by design (FixedSequence
MAX_PURE_LITERAL_LENGTH=4096, LazyDFA MAX_HELPER_BYTES=40k, DFAUnrolled LOOKUP_TABLE_RANGE_
THRESHOLD=100, RecursiveDescent MAX_RECURSION_DEPTH=100, DFASwitch STATE_SPLIT_THRESHOLD=100)
— the overflow burden concentrates in NFABytecodeGenerator, the general engine, by design.

## Supersede-probability recalibration (answers "how probably?")
P(NFA is the only overflow frontier) ~0.85 (11/11 families funnel there; self-limit design;
residual: no direct strategy trace for classGrp/concatGrp, lookaround shapes OOM'd unobserved)
x P(NFA cascade bucketable AND lands at reasonable effort) ~0.65 (DFASwitch+LazyDFA precedents
make in-generator helper-splitting the house idiom; but 11.5k-line generator, active journaling
work on a sibling branch, capture/backref state in the cascade)
x P(no new frontier after NFA-L1) ~0.78
=> P(NFA-L1 supersedes L2 for these families) ~ 0.4-0.5 — NOT "probably". It is the plausible
default path, roughly even odds.
