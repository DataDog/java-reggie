---
id: find-deadline-coverage-gaps
type: finding
status: confirmed
depends_on: [ev-split-coverage-probe]
supersedes: []
related: [find-openj9-deadline-rejection]
tags: [deadline, dos, oom, bitstate, compile-time, coverage-gap, verified-on-baseline, resolved-by-fix]
generator: cairn
created: 2026-09-16
updated: 2026-09-17
---

# The 10s total-compile deadline has two verified holes (pre-existing, confirmed on 715fb53)

## VERIFIED 2026-09-17 on current tree (715fb53 — post-L2-drop = baseline-equivalent; jars +
DlProbe at /tmp/dlverify; Mac OpenJDK 26.0.1, -Xmx6g):
1. Memory hole: lookahead x1000 `(?=lk0)lk0(?=lk1)lk1...` (len 13,780) → **OOM in ~4.2s**
   (earlier candidate-v2 run: 26s — variance/added L2 pre-pass cost; either way well under the
   10s deadline). jstack pin: allocation dies in
   **NFABytecodeGenerator.generateMatchIntoMethod:8379** (NFA-cascade codegen EMISSION —
   CORRECTION of the earlier "dies in analysis" note; it is codegen, and outside
   SubsetConstructor's buildDFA, whose OOM-catch would have converted it gracefully).
   x3000 is rejected in ~1.2s (a size cap somewhere in parse/analysis catches that scale).
   Key property: a TIME deadline cannot close this hole — the pattern dies at 4s, inside the
   envelope. Only a memory/size cap can.
2. Time hole: concatAlt x6000 `(wa0|wb0)(wa1|wb1)...` (len 87,780, 12,000 capture groups) →
   **COMPILED in 22.3s** as BitStateMatcher (earlier: 19s@x2000, 73s@x6000 on candidate-v2).
   2.2x past the 10s total deadline, successful compile, no fallback.

## Mechanism
The total deadline binds ONLY via SubsetConstructor: RuntimeCompiler.compileWithDeadline sets
SubsetConstructor.TOTAL_COMPILE_DEADLINE_NANOS (ThreadLocal, clamp-only-tightens) and periodic
checks inside buildDFA loops. Two bypass classes:
- Routes that never determinize (BitState/NFA-cascade: parse -> NFA -> bit-parallel codegen)
  consult the deadline ZERO times -> hole 2.
- Phases outside determinization (parse, NFA build, analysis passes, codegen emission) have no
  checks -> hole 1 (where a check wouldn't even help: allocation-rate OOM at 4s).

## Options (decision pending — user call; separate branch/investigation)
A. Phase-boundary deadline checks in compileWithDeadline (after parse / NFA build / analysis /
   per-generator codegen) — closes hole 2 on all routes; small local change.
B. Memory/size caps for the unchecked phases — e.g. NFA state-count or emission-budget cap in
   NFABytecodeGenerator mirroring the existing DFA state/work caps + SubsetConstructor's
   OOM->StateExplosionException graceful-fallback idiom — closes hole 1 (and partially 2).
C. Accept + document (deadline covers the original semver/DoS P0 shape only).
Recommendation: B is the one that closes the actual OOM; A+B together give bounded compile on
every route. C leaves a known 6g-killable input in the request path.

## RESOLVED 2026-09-17: option A+B implemented in commit a5ef0fb (user decision)
- A (time): bypass BFS now charged via chargeWork(8)/dequeued state — the work budget
  (200M) and wall-clock deadline bind it. calt-6000: 22.3s -> 2.4s BitStateMatcher
  (scale-independent: x12000 -> 3.6s).
- B (memory): per-method emission budget in NFABytecodeGenerator
  (MAX_EMITTED_INSNS_PER_METHOD = 65_535, BoundedMethodVisitor on all 12 method-emission
  sites) — oversized methods abort DURING emission (before ASM's maxs/frames pass) and
  surface as the standard MethodTooLargeException graceful path. lookahead-1000: 6g OOM
  in 4.2s -> graceful UnsupportedPatternException in 286ms (JDK matcher with
  ALLOW_JDK_FALLBACK). Plus compile-scope OutOfMemoryError catch in compileInternal
  (mirrors SubsetConstructor's idiom) as the general backstop.
- Deliberately NOT added: deadline checks at the PikeVM/BitState early returns — the
  pass-through policy is designed semantics (TotalCompileDeadlineTest asserts PikeVM
  exactly), and post-fix the finish work is genuinely cheap (work budget bounds the A6
  monster; probe shows 2.4s total native compile).
- Tests: GroupBypassWorkBudgetTest (codegen, incl. legit-shape guard),
  DeadlineCoverageHolesTest (runtime, both holes, heap-safe: native matchers for 12k
  groups need match-time allocations beyond the 512m gradle test heap — match asserts
  run on the fallback path only). Full suites + spotless green.
- Hygiene: the "uses or overrides a deprecated API" javac note in NFABytecodeGenerator is
  pre-existing — verified by stash-compile comparison (2 on unpatched 2fc44cc too); a5ef0fb
  added none.
- Design note: the cap at 65,535 insns can never false-trip vs the 64KB method limit
  (would require <1.01 bytes/insn; cascade emission averages ~3B); it fires exactly
  where MethodTooLarge would at toByteArray, ~100x cheaper.
