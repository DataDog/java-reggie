# multi-64k

_Work around max 64k per method_

- status: done
- repo: datadog--java-reggie / branch: feat-dd-backend-check
- last_commit: a5ef0fb13a574013d8b7c64c81fa9f825ad82c3f

## INDEX

# Investigation Index

> STATUS: DONE (2026-09-16). L2 splitter built → benchmarked (no runtime regression, +9% compile) → effectiveness-analyzed (fires on nothing real) → DROPPED per user decision. Patches preserved: reports/l2-splitter-v3-dropped.patch, reports/l2-splitter-v1-benchmark.patch. Open carry-over: find-deadline-coverage-gaps.

## Findings (confirmed)
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

## Dead ends
- dead-classwriter-subclass | SplittingClassWriter subclass | [asm, classwriter, final, refuted-approach] | REFUTED
- dead-sourceinterpreter-typing | SourceInterpreter as slot-typing basis | [asm, sourceinterpreter, refuted-approach, performance] | REFUTED
- dead-handler-extent-heuristic | Handler-extent-to-first-terminal noCut zone | [exception-handler, no-cut-zone, refuted-approach] | REFUTED
- dead-real-generator-differential-test | Real-generator overflow differential test in current corpus | [testing, differential, probe, ci-flake] | REFUTED

## Evidence
- ev-asm-semantics-probes | WhereProbe + SourceProbe outputs pinning ASM behavior | [asm, empirical, probe-output]
- ev-merge-hang-threaddump | Thread dump: Analyzer.merge hotspot on 78k-insn method | [performance, thread-dump, analyzer-merge]
- ev-split-class-javap | javap of split class: chain correct, test formula inverted | [javap, bytecode-decode, chunk-chain, test-bug]
- ev-validation-green | Final validation (6/6 unit, 3,152 runtime, spotless) + strategy probe outputs | [tests, regression, probe-results]
- ev-split-coverage-probe | No benchmark exercises the split path; 11 constructible overflow families, ALL declined (0 rescues); supersede-P recalibration | [benchmark-coverage, split-path, literal, nfa, code-size, empirical]
- ev-bench-ab-temurin | A/B on workspace-jb/Temurin: 83 JMH entries no reggie regression; compile-time +9% | [benchmark, jmh, temurin, compile-time, runtime]
- ev-deadline-fix-verification | Before/after: look-1000 OOM 4.2s -> 286ms graceful; calt-6000 22.3s -> 2.4s; calt-12000 3.6s (scale-independent) | [deadline, fix, benchmark, before-after, probe]

## Questions
- q-prod-readiness | NOT YET ready to replace JDK/RE2J in backend: 2 axes confirmed (compile DoS-safety, JDK-parity correctness), 3 unproven (perf-vs-JDK verdict absent, RE2J evidence base = zero, OpenJ9 + service-corpus census) | [readiness, production, adoption, jdk-regex, re2j, backend-services, verdict]
- q-l2-disposition | ANSWERED: DROPPED (2026-09-16); work preserved in reports/ | [disposition, decision, scope]
- q-passthrough-overhead | ANSWERED: passthrough adds ~+9% compile-time (range 0-15%), no runtime regression | [benchmark, performance, compile-time]
- q-commit-hygiene | ANSWERED: committed 715fb53 (chore: spotless, 24 files, content-only) | [commit, spotless, git-hygiene]

## STATE

# Current State

## Active investigation
"Work around max 64k per method" — **CONCLUDED 2026-09-16**. Final disposition: the L2 generic
method-size splitter was built, validated (no runtime regression; +9% compile cost), then
**DROPPED** after the effectiveness analysis showed it fires on nothing real (11 constructible
overflow families, all declined; wide-switch noCut flood). See q-l2-disposition.

## Current hypothesis
(none — investigation concluded. hyp-v2-driver-lowering recorded as not-planned; the
knowledge is preserved for any future revival.)

## What I'm doing now
ALL WORK CONCLUDED 2026-09-17. Branch feat/dd_backend_check = 2fc44cc -> 715fb53 (chore:
spotless) -> a5ef0fb (fix: deadline coverage gaps, A+B). Tree clean except untracked
doc/investigations/ cairn export. Deadline-gaps fix implemented on the same branch, verified
before/after (ev-deadline-fix-verification), all suites + integration + spotless green.
Remaining optional items:
1. workspace-jb cleanup if desired: ~/bench/l2-validation/ + ~/bench/java-reggie-l2-validation
   can be deleted; jars + results are summarized in cairn.
2. doc/investigations/ untracked cairn export: move out of the repo or gitignore.

## Open questions
- q-prod-readiness (2026-09-17): "Ready to replace JDK regex / RE2J in backend services?" —
  ANSWERED: NOT YET. Confirmed: compile DoS-boundedness (a5ef0fb) + JDK-parity correctness
  infra (RE2/PCRE suites, JDK-oracle fuzz) + graceful degradation. Missing: any recorded
  performance-vs-JDK verdict, the entire RE2J evidence base (zero RE2J benchmarks; linear-
  time guarantee unassessed — every fallback lands on backtracking JDK), fleet JVM-flavor
  census (OpenJ9 cliff), and a service-corpus compile census. Closure sequence (1-2 days)
  in the node.

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
- find-deadline-coverage-gaps — 10s deadline holes (memory: OOM; time: 73s BitState compile).

## Ruled out (don't re-investigate)
- dead-classwriter-subclass — final methods.
- dead-sourceinterpreter-typing — quadratic + masking.
- dead-handler-extent-heuristic — fall-through handlers break it.
- dead-real-generator-differential-test — no trigger pattern within deadline envelope.
- (whole L2 approach, 2026-09-16) — no real pattern is v1-splittable; dead code while wired;
  see find-l2-dead-code-verdict. Preserved in reports/ for revival.

## Nodes

---
id: dead-classwriter-subclass
type: deadend
status: refuted
depends_on: [find-asm-classwriter-final]
supersedes: []
related: []
tags: [asm, classwriter, final, refuted-approach]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# SplittingClassWriter (ClassWriter subclass) — REFUTED

## Reasoning chain
First design: `SplittingClassWriter extends ClassWriter`, override `visitMethod` to return a
buffering MethodVisitor. Died on compilation: ASM 9.10 declares `ClassWriter.visit` and
`visitMethod` `public final`. No interception is possible via subclassing.

Secondary cleanup once the visitor pivot happened: the `rawVisitMethod`/`MethodVisitorFactory`
dance (needed to bypass the override while emitting chunks) became unnecessary — chunks are
emitted directly on the real writer; the splitter takes `(node, realWriter, chunk0Visitor)`.

## What replaced it
- find-asm-classwriter-final — wrapping `SplittingClassVisitor extends ClassVisitor`

## Why it matters
Any future ASM-pipeline work in this repo should start from a ClassVisitor wrapper, not a
ClassWriter subclass.

---

---
id: dead-handler-extent-heuristic
type: deadend
status: refuted
depends_on: []
supersedes: []
related: [find-try-handler-chunk-constraint]
tags: [exception-handler, no-cut-zone, refuted-approach]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Handler-extent-to-first-terminal noCut zone — REFUTED

## Reasoning chain
First attempt at protecting try/handler regions: noCut zone = protected region ∪ handler
*extent*, where the extent was computed by walking forward from the handler label to its first
`athrow`/`return`/`goto`/switch. Two flaws:

1. **Over-conservative**: a fall-through handler (catch sets a flag, then continues into the
   method tail) has no terminal → the walk ran to the end of the method → the entire method
   became no-cut → `chooseCuts` declined (observed: tryRegion test declined then verbatim
   threw).
2. **Wrong model**: the handler body extending across a cut is *fine* — control flows through
   the chunk tail chain; only the handler *entry label* must live in the region's chunk.

## What replaced it
- find-try-handler-chunk-constraint — region + handler entry must share a chunk; bodies may
  tail-chain. noCut = positions separating {start, end−1, handler}.

---

---
id: dead-real-generator-differential-test
type: deadend
status: refuted
depends_on: [find-no-overflow-trigger-today]
supersedes: []
related: []
tags: [testing, differential, probe, ci-flake]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Real-generator overflow differential test — DEAD END

## Reasoning chain
Design-doc test plan item 7: "a pattern whose NFA step method overflows 64KB today compiles,
loads, and matches java.util.regex differentially." No such pattern could be constructed:

- Huge literal capture alternations route to BitStateMatcher (compact) or LiteralAlternation
  trie strategies — method size never overflows.
- Pushing counts up (4000 alternatives) hits OOM in the analysis phase, before codegen.
- Compiles that do succeed take 8.5–13.5s against the 10s total-compile deadline — too close
  for a stable CI test; the probe test was deleted rather than kept flaky.

Splitter semantics are instead covered at unit level (`SplittingClassVisitorTest`), which
loads real classes (JVM verifier validates recomputed frames) and checks exact values, plus
the 3,152-test runtime suite through the wired-in pipeline.

## What this rules out
- Spending more time hunting a triggering pattern within the current strategy/limit envelope.

---

---
id: dead-sourceinterpreter-typing
type: deadend
status: refuted
depends_on: [find-sourceinterpreter-pathologies]
supersedes: []
related: []
tags: [asm, sourceinterpreter, refuted-approach, performance]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# SourceInterpreter as slot-typing basis — REFUTED

## Reasoning chain
The original typing plan derived live-slot types from `SourceInterpreter` frames (per-source
descriptors + unmask recursion for ASTORE/xLOAD). Two fatal flaws emerged:

1. Quadratic set growth on loop-carried locals at merge points — a 78k-insn realistic test
   method hung the test worker (>120s, thread dump in ev-merge-hang-threaddump). Generated
   NFA-style methods are exactly this shape.
2. ASTORE source masking forced an unmask-recursion layer (operand sources at the store, local
   sources at loads) — built, working, then deleted with the interpreter swap.

## What replaced it
- find-descriptor-interpreter-design — DescriptorInterpreter (descriptors flow through copies;
  O(1) merges; UNKNOWN on divergence/null)

## Caveat
SourceInterpreter remains fine for small methods or one-shot analyses; the pathology needs
loop-carried locals surviving merge points.

---

---
id: find-asm-classwriter-final
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [dead-classwriter-subclass, find-l2-splitter-shipped]
tags: [asm, classwriter, classvisitor, interception]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# ASM 9.10 ClassWriter methods are final — intercept with a wrapping ClassVisitor

## Reasoning chain
`javap` on asm-9.10.1.jar shows:
`public final void visit(int,int,String,String,String,String[])` and
`public final MethodVisitor visitMethod(int,String,String,String,String[])` in
`org.objectweb.asm.ClassWriter`. A `ClassWriter` subclass therefore cannot intercept method
emission ("overridden method is final" compile errors).

Consequence: the interception point must be a **wrapping `ClassVisitor`** (the standard ASM
instrumentation shape) delegating to the real ClassWriter, which stays the terminal pipeline
stage and `toByteArray()` producer. Reggie's generators had parameters typed `ClassWriter`, so
they were mechanically widened to `ClassVisitor` (~32 files; compiler-verified; the only
non-visitor use, `RecursiveDescentBytecodeGenerator.generate()`'s internal `cw.toByteArray()`,
was restructured to real-writer + front-visitor). Both pipeline entry points
(`RuntimeCompiler.generateBytecode`, `ReggieMatcherBytecodeGenerator`) construct
`new ClassWriter(COMPUTE_FRAMES|COMPUTE_MAXS)` wrapped by `new SplittingClassVisitor(real)`.

This pivot was the user's suggestion ("Can you just insert a ClassVisitor?") after the
subclass approach died on finality.

## Evidence
- find-asm-toobytearray-sizecheck for the other half of the ASM API reality

## Open questions
- none

---

---
id: find-asm-toobytearray-sizecheck
type: finding
status: confirmed
depends_on: [ev-asm-semantics-probes]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [asm, methodtoolargeexception, tobytearray, probe]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# MethodTooLargeException fires at toByteArray(), NOT at visitMaxs()

## Reasoning chain
Empirically verified with a scratch probe (25,000 × {NOP; ICONST_1; POP} ≈ 75,001 code bytes
into a `ClassWriter(0)`): `visitMaxs` returns normally, `visitEnd` returns normally,
`toByteArray()` throws `MethodTooLargeException` with `getCodeSize()=75001`. The COMPUTE_FRAMES
| COMPUTE_MAXS writers used by Reggie behave the same (test failures surfaced at
`realWriter.toByteArray()`).

Consequences:
1. **A size probe must call `toByteArray()`** on its scratch writer. The first probe version
   only emitted + visitMaxs → it silently reported 72–144KB methods as "fits" → flush took the
   verbatim path → the entry point's toByteArray threw later. Found via flush-debug prints
   (`conservative=71994 probeFits=true`).
2. The real writer's guard surfaces at the entry point's `toByteArray()` → still inside
   `RuntimeCompiler`'s existing try/catch → L3 preserved (a chunk-margin bug cannot escape
   to a corrupt class, only to the fallback).

This corrects the earlier in-conversation claim that MethodWriter throws in visitMaxs —
the user's doubt ("I am not sure whether ASM does not check the method size while writing")
was well-founded.

## Evidence
- ev-asm-semantics-probes — full probe output

## Open questions
- none

---

---
id: find-computeextras-early-return-bug
type: finding
status: confirmed
depends_on: [find-l2-splitter-shipped]
supersedes: []
related: [find-visitmaxs-zero-blindspot]
tags: [bug, liveness, extras, chunk-signature, corruption, post-ship-fix]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# computeExtras early-return could drop live non-param extras — fixed

## Reasoning chain
Found by code reading during the battery analysis. `computeExtras` had:
`if (live.nextSetBit(0) >= 0 && live.nextSetBit(0) < paramSlots) return List.of();` — an
optimization meaning "all live slots are params" that actually only checked the FIRST live
slot. A method with live params AND live non-param locals at a cut (the common real shape)
would return empty extras → chunk signatures missing live state → VerifyError at load or
silent miscompilation. Never fired in the shipped tests: the ()I tests have paramSlots=0
(early return dead) and the (II)I crossing test carries everything in params (no extras
needed). The existing `for (int s = live.nextSetBit(paramSlots); …)` loop already handles the
all-params case correctly — the early return was pure liability. Deleted; regression test
`splitsWithLiveParamsAndLocalAcrossCut` ((II)J, params + long accumulator live across cuts,
exact closed-form result) added — suite 8/8.

---

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
- Design note: the cap at 65,535 insns can never false-trip vs the 64KB method limit
  (would require <1.01 bytes/insn; cascade emission averages ~3B); it fires exactly
  where MethodTooLarge would at toByteArray, ~100x cheaper.

---

---
id: find-descriptor-interpreter-design
type: finding
status: confirmed
depends_on: [find-sourceinterpreter-pathologies]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [asm, interpreter, type-descriptor, soundness, api-gotchas]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# DescriptorInterpreter: one descriptor per value, O(1) merges, conservative UNKNOWN

## Reasoning chain
Replacement interpreter (`reggie-codegen/codegen/DescriptorInterpreter.java`): every abstract
value is a `DV` wrapping one type-descriptor string (or `UNKNOWN`).

- **copy ops propagate the descriptor unchanged** — the ASTORE-masking problem disappears by
  construction; a local's type at any point is directly the frame value.
- **merge = equal descriptors or UNKNOWN** — no class loading, no set growth; O(n) total.
- **UNKNOWN sources**: divergent merges (the original verifier would compute a LUB —
  reconstructible only with a class hierarchy), `ACONST_NULL` (null-typed local used as a chunk
  param typed Object breaks verification of later uses), uninitialized locals (`newEmptyValue`),
  unresolvable ops.
- **Soundness**: chunk parameter types must be exactly what the verifier would accept at the
  cut. Strict equality is conservative: a divergent slot that is *live* at a cut → the cut is
  rejected, never guessed. `SimpleVerifier` LUB merging was ruled out: it needs classloading,
  and generated class names (ReggieMatcher$xxx) are not loadable in the codegen module.

ASM 9.10 Interpreter API gotchas hit along the way (all fixed in the file):
- interpreter methods are `public`, not `protected`;
- `newExceptionValue(TryCatchBlockNode, Frame<DV>, Type)` — takes a `Type`, not a value;
- values must implement `org.objectweb.asm.tree.analysis.Value` (Frame<V extends Value>);
- `MethodNode.exceptions` is `List<String>` (not String[]), `annotationDefault` is `Object`
  (methods carrying an annotation default are refused by the splitter; verbatim handles them).

## Evidence
- ev-validation-green — all tests pass with this interpreter

## Open questions
- none

---

---
id: find-l2-dead-code-verdict
type: finding
status: confirmed
depends_on: [ev-split-coverage-probe, find-l2-splitter-shipped]
supersedes: []
related: [hyp-v2-driver-lowering, find-no-overflow-trigger-today, q-l2-disposition]
tags: [effectiveness, dead-code, battery, nfa-cascade, wide-switch, disposition]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# L2 splitter verdict: fires on nothing real — effectively dead code as shipped

## Reasoning chain
Battery (ev-split-coverage-probe): every CONSTRUCTIBLE overflow family routes to
NFABytecodeGenerator's per-config cascade or equally switch-dominated shapes — 12 oversized
methods across 6 families, **12/12 declined, 0 rescued** (v1 + both post-ship fixes). The only
linear-code family, `(abcdefgh)\1{N}`, is loop-compact and never overflows. Combined with the
benchmark evidence (0 splits across the entire corpus, APT + runtime paths): **no real
generator pattern can be split by v1** — the split path fires only on synthetic shapes (unit
tests).

Costs of keeping it wired in:
- +9% (point estimate) compile-time on EVERY pattern (MethodNode buffer + replay passthrough).
- 2–12× slower failure on oversized patterns (analysis runs, then declines; bounded by the
  total-compile deadline → same L3 outcome as baseline, just slower; capalt2k went 1.1s → 9.5s,
  nearly the 10s deadline).

What would make it live: (a) v2 driver lowering handling wide-switch cascades (hyp-v2 —
every overflow family needs exactly this), or (b) NFABytecodeGenerator L1 bucketing (shrinks
those methods instead — REMOVES the need for L2 there). Recalibrated: P(b) supersedes L2 for
the known families ≈ 0.4-0.5 (see ev-split-coverage-probe) — "probably supersede" was
overstated; it is the plausible default, roughly even odds. Note the disposition question is
invariant to this: v1 fires on nothing either way; the supersede probability only informs
WHETHER/WHEN to build v2 or NFA-L1, not whether to unwire v1.

**DISPOSITION (2026-09-16): option 3 — DROPPED** (user decision; see q-l2-disposition).
Work preserved as patches in this investigation's reports/.

## Options (were)
1. Keep as-is: net for unforeseen shapes; insurance costs +9% + slower failures.
2. Unwire (keep code+tests, remove the SplittingClassVisitor wrap from the two pipeline sites
   or gate behind an option): restores baseline compile-time; machinery stays reviewed+tested.
3. Drop entirely: simplest; loses machinery.
4. Build v2: only path to liveness on real patterns; large scope.

---

---
id: find-l2-splitter-shipped
type: finding
status: confirmed
depends_on: [find-asm-classwriter-final, find-asm-toobytearray-sizecheck, find-descriptor-interpreter-design, find-try-handler-chunk-constraint]
supersedes: []
related: [find-repo-method-size-landscape, find-no-overflow-trigger-today]
tags: [implementation, splitter, pipeline, wiring, tests]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# L2 generic splitter: what shipped, where, and the implementation subtleties

## Reasoning chain
Shipped on branch feat/dd-backend-check (on top of 2fc44cc), uncommitted:

- **`SplittingClassVisitor.java`** (reggie-codegen/codegen): wraps the real ClassWriter;
  returns a `MethodNode`-backed `BufferedMethod` for every splittable method header
  (`isSplittableHeader`: never `<init>`/`<clinit>`/abstract/native). Flush at visitMaxs:
  conservative upper-bound sizer (jumps as wide forms, ldc_w, padded switches — a fitting
  conservative bound is *guaranteed* to fit) → verbatim replay via `MethodNode.accept`;
  else exact probe (scratch `ClassWriter(0)`, fresh labels, **toByteArray**) → verbatim;
  else `MethodSplitter.trySplit`. All refusals → verbatim → `MethodTooLargeException` → L3.
- **`MethodSplitter.java`**: instruction indexing, CFG/successors (incl. exception edges),
  slot-level backward liveness (ring-buffer worklist), DV frames (Analyzer), noCut zones,
  greedy cut scan at CHUNK_BUDGET=60,000 conservative bytes, `validateChunks` (per-chunk
  conservative size incl. callSeq estimates; try-triples whole; param slot count ≤255),
  emission (chunk0 keeps original header; `$sK` chunks private synthetic, same static-ness;
  per-chunk slot remap — params identity, extras become trailing params, rest shifted;
  tail = push params + invokestatic/invokespecial + matching xreturn; LDC-Integer replays
  via `BytecodeUtil.pushInt` per repo convention).
- **`DescriptorInterpreter.java`** — see find-descriptor-interpreter-design.
- Wiring: `RuntimeCompiler` + `ReggieMatcherBytecodeGenerator` both wrap their writer
  (dual-path rule satisfied at a single shared implementation). `asm-tree` +
  `asm-analysis` 9.10.1 deps added to reggie-codegen.
- Design doc `doc/plans/method-size-splitting.md` updated with all corrections.

Subtleties found only by tests:
- `validateChunks` must not add a tail-call estimate for the final boundary — `insnCount`
  is the end boundary, not a chunk entry (first crash: IndexOutOfBounds in `extrasAt`); the
  final chunk has no tail call.
- Crossing GOTO → inline call+return; crossing conditional → jump to a local end-of-chunk
  label block; crossing switch case → per-case local label blocks. Forward jumps into a
  *later chunk's interior* are forbidden by noCut by construction.

## Evidence
- ev-validation-green — full validation results

## Open questions
- q-passthrough-overhead (benchmark), q-commit-hygiene (commit split)

## Post-ship updates (still uncommitted)
- **Fixed**: `visitMaxs(0,0)` blind spot (find-visitmaxs-zero-blindspot) — every real generator
  method had silently declined; analyze() now repairs node.maxLocals/maxStack. Regression test
  added: suite 7/7.
- **Documented**: wide-switch methods (per-config NFA cascade) stay unsplittable in v1 —
  doc/plans/method-size-splitting.md "Wide-switch methods stay unsplittable in v1".
- v2 patch = v1 + MethodSplitter fix + test + doc (56 other files byte-identical).
- **Battery verdict (post-benchmark)**: every constructible overflow family declines (12/12)
  — see find-l2-dead-code-verdict + ev-split-coverage-probe. Suite now 8/8.
- **Fixed**: computeExtras early-return could drop live non-param extras → chunk-signature
  corruption (find-computeextras-early-return-bug).
- Patches: v1 (benchmark A/B) -> v2 (visitMaxs fix + test 7/7 + doc) -> v3 (computeExtras fix +
  test 8/8 + doc). v3 = v2 + exactly 2 files; v2 = v1 + 3 files.

## DISPOSITION (2026-09-16): DROPPED from the tree
User decision after the effectiveness analysis (find-l2-dead-code-verdict). The implementation
never landed as a commit; the working tree was reverted to 2fc44cc (+ formatting). Preserved:
reports/l2-splitter-v3-dropped.patch (v3 = final with both post-ship fixes),
reports/l2-splitter-v1-benchmark.patch (A/B-benchmarked tree). Resurrect via git apply from
2fc44cc. All subtleties documented here remain accurate for any future revival.

---

---
id: find-no-overflow-trigger-today
type: finding
status: confirmed
depends_on: [ev-validation-green]
supersedes: []
related: [find-repo-method-size-landscape, hyp-v2-driver-lowering]
tags: [bitstate, huge-charset, alternation, compile-deadline, oom]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# No current pattern family overflows a method — L2 is a net, not a currently-firing fix

## Reasoning chain
Probing with capture-alternations of distinct literals (n=2000/4000, len 3/5) through strict
`Reggie.compile`:
- n=2000 → **BitStateMatcher**, compiled in 8.5–13.5s, **no `$s` chunk methods** — bit-parallel
  code is compact; method size never approaches 64KB.
- n=4000 → **OutOfMemoryError during compilation** (analysis phase, before codegen).
- Nothing hits a method overflow within the 10s total-compile deadline.

Interpretation: the recent L1 work (huge-charset boolean[] tables, commit 2fc44cc; BitState)
already absorbed the known overflow families that `RuntimeCompiler`'s catch comment named.
L2 therefore stands as a safety net for unforeseen pattern shapes; no pattern in the current
test corpus is known to overflow through the wired-in pipeline. Observable trigger signal
when it does fire: `$s`-suffixed methods on the generated matcher class
(`matcher.getClass().getDeclaredMethods()`).

The exploratory SplitterProbeTest was **deleted** (8.5s compile near the 10s deadline = CI
flake risk; OOM shape unusable in CI).

## Evidence
- ev-validation-green — probe outputs

## Open questions
- If an overflow family re-emerges, is per-generator L1 (NFABytecodeGenerator) preferable to
  relying on L2? → hyp-v2-driver-lowering is dormant for the same reason.

## Post-benchmark nuance (ev-split-coverage-probe)
A constructible overflow DOES exist outside the corpus: "a"×40000 → 4.7 MB NFA
findLongestMatchEnd (baseline throws too-large). v1 L2 declines it (wide switches) → L3 —
status quo preserved, not rescued. Corpus-scoped conclusion unchanged.

---

---
id: find-openj9-deadline-rejection
type: finding
status: confirmed
depends_on: [ev-bench-ab-temurin]
supersedes: []
related: [find-no-overflow-trigger-today]
tags: [openj9, deadline, compile-deadline, j9, deployment]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# OpenJ9 rejects HotSpot-compilable patterns via the 10s total-compile deadline

## Reasoning chain
First A/B attempt ran the same benchmark selection on IBM Semeru OpenJ9 21.0.12 (sdkman
"current" on workspace-jb). 37/83 benchmark forks failed identically in BASELINE and
CANDIDATE at warmup iteration 1: RuntimeException "Failed to compile pattern:
(?:a+b+|b+a+){75}" from RuntimeCompiler.compileWithDeadline — i.e., the default 10s total
-compile deadline (commit b0c5615) expires on OpenJ9 for a bounded-quantifier pattern that
compiles well within the deadline on Temurin/HotSpot 21.0.12 (all 83 entries ran green
after the JVM switch). JMH additionally warns "Not a HotSpot compiler command compatible VM
— compiler hints are disabled" on OpenJ9, so JMH microbenchmarks on it are compromised anyway.

Implications:
- Deployment relevance: dd-trace/Reggie on IBM J9-derived runtimes would fall back to
  java.util.regex for patterns that compile fine on HotSpot — a silent performance cliff
  gated by JVM flavor, not pattern class.
- Benchmarking on workspace-jb must explicitly set JAVA_HOME to Temurin
  (/usr/local/sdkman/candidates/java/21.0.12-tem); sdkman "current" points at Semeru OpenJ9.

## Open questions
- Quantify the HotSpot↔OpenJ9 compile-speed ratio for deadline-prone pattern families?
  (not blocking this investigation)

---

---
id: find-repo-method-size-landscape
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [find-l2-splitter-shipped, find-no-overflow-trigger-today]
tags: [jvm, method-limit, codegen, architecture, layered-design]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Reggie's method-size landscape and the agreed layered design

## Reasoning chain
JVM caps one method's bytecode at 65,535 bytes (code_length is a `u2` — an over-limit method
cannot even be encoded; post-hoc repair of a serialized class is impossible). Reggie generates
specialized matcher classes per pattern (36+ strategies in `reggie-codegen/codegen/`), so some
patterns overflow one method.

Pre-existing repo state:
- **L3 (last resort)**: `RuntimeCompiler.compile()` catches `MethodTooLargeException` →
  `fallbackOrThrow` → JDK `java.util.regex` delegation with a warning (reggie-runtime,
  ~line 1042). Comment names NFABytecodeGenerator as a generator without splitting.
- **L1 (per-generator structural lowering)**: precedent `DFASwitchBytecodeGenerator`
  STATE_SPLIT_THRESHOLD=100 — bucket helpers `$ng_step_N`/`$gt_step_N`, ~30KB each; recent
  commits added huge-charset boolean[] lookup tables and BitState (compact) alternatives.

Agreed design (conversation): **L1** generators lower as far as their structure allows;
**L2** = generic in-pipeline splitter as safety net (this work); **L3** unchanged. Failure of
L2 must degrade to verbatim emission → today's exception → L3 (never worse than status quo).

## Evidence
- doc/plans/method-size-splitting.md (written + updated this session, uncommitted)

## Open questions
- none for this node

---

---
id: find-sourceinterpreter-pathologies
type: finding
status: confirmed
depends_on: [ev-asm-semantics-probes, ev-merge-hang-threaddump]
supersedes: []
related: [dead-sourceinterpreter-typing, find-descriptor-interpreter-design]
tags: [asm, sourceinterpreter, quadratic, astore-masking, analysis]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# SourceInterpreter has two pathologies for slot-typing at cut points

## Reasoning chain
Two independent problems, both empirically verified:

**1. copyOperation masks producers.** `SourceInterpreter.copyOperation` attributes a value to
the *copy instruction*: after `ldc "abc"; astore 1`, local 1's source set is `[VarInsnNode#58]`
(the ASTORE), not the LDC. A local's type at a cut therefore cannot be derived directly from
its source insns — stores must be unmasked (primitive stores from the opcode; ASTORE by
recursing into the stored operand's sources; xLOAD stack sources by recursing into the loaded
local's sources). That unmasking machinery was built, then deleted when the interpreter was
replaced.

**2. Quadratic set growth on loop-carried locals.** Source sets grow ~1 node per loop
iteration at merge points (each `Lend`-style merge unions the previous iteration's accumulated
set with new defs), so Frame merges perform O(k) set unions → O(n²) total. A 78k-insn test
method (6,001 if/else iterations) took >120s, hung the Gradle test worker; jstack pinned
`Analyzer.merge` (Analyzer.java:642). ASM's `MethodNode.accept`-time behavior is fine — it's
only the *analysis* that degrades.

## What this rules out
- dead-sourceinterpreter-typing — SourceInterpreter as the typing basis for the splitter

## Open questions
- none

---

---
id: find-try-handler-chunk-constraint
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [dead-handler-extent-heuristic, find-l2-splitter-shipped]
tags: [jvm, exception-table, try-catch, cut-points, verifier]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Try regions: entry+handler-entry must share a chunk; handler bodies may tail-chain

## Reasoning chain
The per-method exception table means each `tryCatchBlock(start, end, handler, type)` is
re-emitted into the chunk that contains it — so the protected region `[start, end)` and the
**handler entry label** must be in one chunk. The handler **body** is ordinary code and may
extend past chunk boundaries via normal tail chaining (handler-entry → … → cut → chunk tail
call); only the label binding matters.

Cut-safety rule derived: a cut is forbidden iff it separates the triple
{start, end−1, handler} (noCut zone `[min(start,handler)+1 .. max(end-1, handler)]`).

The initial heuristic — bounding the no-cut zone by the handler's *extent* (walk to first
athrow/return/goto) — was wrong: fall-through handlers (`catch { acc = -999 }` then continue
into the method tail) never hit a terminal, so the walk consumed the whole remainder of the
method and `chooseCuts` declined the split (seen: conservative=66018, probe failed, split
declined, verbatim threw).

## What this rules out
- dead-handler-extent-heuristic

## Evidence
- SplittingClassVisitorTest.tryRegionStaysWholeAndBothPathsBehave — both paths exact after fix

## Open questions
- none

---

---
id: find-visitmaxs-zero-blindspot
type: finding
status: confirmed
depends_on: [find-l2-splitter-shipped, ev-split-coverage-probe]
supersedes: []
related: [find-l2-splitter-shipped, hyp-v2-driver-lowering]
tags: [visitmaxs, computemaxes, asm-analyzer, bug, post-ship-fix, maxlocals]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# visitMaxs(0,0) generators made the shipped L2 splitter blind to all real methods — fixed

## Reasoning chain
Discovered after the benchmark, by deliberately compiling a 40k-char literal through the real
pipeline: the splitter declined with `AnalyzerException at instruction 0: "Trying to set an
inexistant local variable 0"`.

- Generators stream into COMPUTE_MAXS writers and legally declare `visitMaxs(0, 0)` — **~230
  call sites across 21 generator files** (NFABytecodeGenerator alone: 22).
- The buffered `MethodNode` therefore carries `maxLocals = 0`, and ASM's `Analyzer` sizes its
  initial Frame from `node.maxLocals` → refuses at instruction 0 → `analyze()` returned false →
  **every real generator method silently declined to verbatim**. The L2 net shipped effectively
  inert for the pipeline it was built for.
- The unit suite passed because `buildClass` declared real maxima (`visitMaxs(64, 64)`) — tests
  never exercised the 0/0 contract the generators actually use.

**Fix (in tree, uncommitted, v2 patch):** in `MethodSplitter.analyze()`, before analysis:
`node.maxLocals = max(varAccessed)+1 (≥ paramSlots)` and
`node.maxStack = max(node.maxStack, 64)`. The real writer recomputes both at serialization, so the
repair affects the analysis only. A still-too-small repaired stack floor (operand stack >64 in a
method that declared zeros) surfaces as AnalyzerException → decline → verbatim — never corrupt.
Regression test `splitsOversizedMethodDeclaredWithZeroMaxes` (suite now 7/7, reggie-runtime +
codegen green, spotless clean).

## Open questions
- Should the floor 64 be derived (e.g. paramSlots + heuristic) instead of constant? Not urgent —
  failure mode is decline, not corruption.

---

---
id: hyp-v2-driver-lowering
type: hypothesis
status: open
depends_on: [find-no-overflow-trigger-today]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [v2, driver, continuation, state-machine, back-edges]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# v2: driver/continuation lowering for back-edge-crossing cuts

## Reasoning chain
v1 forbids cuts crossed by loop back-edges (a jump can't cross methods). A method whose whole
>64KB body is one straight-line loop body with a single back edge therefore stays unsplit →
verbatim → L3 fallback. The general fix is driver lowering: chunks return a continuation id;
the original method becomes a `tableswitch` driver loop; loop-carried live slots are passed
per-entry (types from DescriptorInterpreter as today).

**Dormant but no longer triggerless**: no *corpus* pattern needs it, but a constructible
trigger family now exists (ev-split-coverage-probe): "a"×40000 → NFA per-config cascade →
4.7 MB findLongestMatchEnd with wide dispatch switches — v1 declines (noCut flood from
forbidCrossing over switch edges). Now the ONLY path to L2 liveness on real patterns (find-l2-dead-code-verdict: v1 rescues
nothing real; every overflow family is switch-dominated). Do not build speculatively — but if
L2 is kept rather than unwired, v2 (or NFA L1 bucketing, which obviates L2 there) is the
decision that matters.

**Recursion trap if built**: replacing back-edges with *calls* (not the driver) creates one JVM
frame per loop iteration → stack overflow on long inputs. Must be the driver loop, and result
carrying needs a completion channel (field or outcome object) because chunk return type
changes from the method's return type to the continuation id.

## Evidence
- find-l2-splitter-shipped — v1 invariants (declinesToSplitLoopBody test asserts current refusal)

## Open questions
- none blocking (dormant by design)

## DISPOSITION (2026-09-16): not planned; L2 dropped entirely
With L1 dropped, v2 is moot unless a REAL need for >64KB method rescue emerges (extreme
synthetic patterns only, all currently L3-fallback — today's accepted behavior). If it ever
returns: the trigger families (ev-split-coverage-probe) + the recursion trap + the driver
design notes above are the starting point; NFA-L1 bucketing (P~0.4-0.5 to supersede) or
accepting L3 remain the alternatives.

---

---
id: q-commit-hygiene
type: question
status: answered
depends_on: []
supersedes: []
related: []
tags: [commit, spotless, git-hygiene]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Split spotless normalization hunks from feature hunks at commit? — ANSWERED

**Answer: split — and the feature hunks no longer exist (L2 dropped).**
2026-09-16, commit 715fb53 on feat/dd_backend_check: "chore: spotless normalization — javadoc
rewrap + license-header formatting" (24 files, +470/-352, content-only formatting). The stray
untracked doc/investigations/dd_backend_fit cairn export was deliberately NOT committed and
remains untracked in the worktree — decide its fate separately (move out of the repo or
gitignore; it is a cairn artifact, not repo content).

---

---
id: q-l2-disposition
type: question
status: answered
depends_on: [find-l2-dead-code-verdict]
supersedes: []
related: [find-l2-splitter-shipped, hyp-v2-driver-lowering]
tags: [disposition, decision, scope]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Keep L2 wired / unwire / drop / build v2? — ANSWERED: DROPPED (user decision, 2026-09-16)

Rationale on record (find-l2-dead-code-verdict): fires on nothing real — 11 constructible
overflow families, all declined (wide-switch noCut flood); +9% compile cost on every pattern;
2–12x slower failures on oversized patterns; NFA-L1 supersede probability ~0.4-0.5.
Decision: drop entirely.

Executed: 5 new files deleted (SplittingClassVisitor, MethodSplitter, DescriptorInterpreter,
SplittingClassVisitorTest, doc/plans/method-size-splitting.md), 33 L2-marked files reverted
to 2fc44cc, spotless re-applied. Verified: codegen/runtime/processor/integration tests green,
spotless clean, zero L2 residue; remaining diff vs 2fc44cc = 24 files of pure formatting
drift (+ untracked doc/investigations/ cairn export).

The work is preserved as patches in this investigation: reports/l2-splitter-v3-dropped.patch
(final: + visitMaxs fix + computeExtras fix + 8/8 tests) and
reports/l2-splitter-v1-benchmark.patch (the exact tree the Temurin A/B benchmark ran).
Resurrection: from 2fc44cc, `git apply reports/l2-splitter-v3-dropped.patch`.
Benchmark artifacts remain on workspace-jb: ~/bench/l2-validation/ (jars, probes, results).

---

---
id: q-passthrough-overhead
type: question
status: answered
depends_on: [ev-bench-ab-temurin]
supersedes: []
related: [find-l2-splitter-shipped]
tags: [benchmark, performance, compile-time]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Does the verbatim passthrough path add measurable compile-time overhead? — ANSWERED

**Answer: yes, modestly — ~+9% point estimate on total cache-miss compile time
(range ≈ +0–15% given noise), zero runtime regression.**

Measured 2026-09-16 on workspace-jb (Temurin 21.0.12, idle 16-core): CompileTimeProbe over
8 strategy-family templates, 200 real PATTERN_CACHE-miss compiles each — mean 2,949 →
3,226 µs/compile (+9.4%); JMH A/B 83 entries: 79 stable, 3 improved, 1 flagged entry is a
JDK-side benchmark (no code path — noise). The passthrough (MethodNode buffer + accept
replay for every splittable method) roughly doubles the emission step, which is ~10% of
total pipeline cost for these patterns. Against the 10s compile deadline: immaterial.

Mitigation if ever needed: lazy buffering is NOT possible via direct-write + retry (writer
is dirtied by the time MethodTooLargeException fires at toByteArray) — the buffer-first
shape is required; alternatives (threshold by cheap instruction count before replay) exist
but are unwarranted at +9%. See ev-bench-ab-temurin for raw numbers.

---

---
id: q-prod-readiness
type: question
status: answered
depends_on: [find-deadline-coverage-gaps, find-openj9-deadline-rejection, find-no-overflow-trigger-today, ev-validation-green, ev-bench-ab-temurin, ev-deadline-fix-verification]
supersedes: []
related: []
tags: [readiness, production, adoption, jdk-regex, re2j, backend-services, verdict]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# "Are we ready to replace JDK regex and RE2J in backend services?"

## ANSWER (2026-09-17): NOT YET — confirmed on 2 of 5 axes; the replacement case has no
## recorded performance evidence for either target, and the RE2J safety story is unassessed.

## Axis-by-axis verdict from this investigation's records (all on feat/dd_backend_check @ a5ef0fb)

### CONFIRMED READY
1. **Compile-time DoS-boundedness** (the backend P0): every compile route is now bounded —
   10s total deadline with both verified holes closed (find-deadline-coverage-gaps RESOLVED
   by a5ef0fb: emission budget 65,535 insns/method + charged bypass BFS + compile-scope OOM
   catch). Numbers: lookahead×1000 6g-OOM/4.2s → 286ms graceful; calt×6000 22.3s → 2.4s
   native BitState (×12000 → 3.6s, scale-independent).
2. **Correctness/parity infrastructure**: 3,152 runtime tests + integration corpus built from
   the RE2 and PCRE test suites (capture positions byte-exact) + fuzz oracle with
   **java.util.regex as the semantic oracle** — i.e. parity target = JDK semantics, tested
   against industry suites (ev-validation-green). All green on the final tree.
3. **Degradation semantics**: no input kills the process; too-large/deadline/OOM surfaces as
   JDK fallback (with allowJdkFallback) or explicit UnsupportedPatternException (service can
   route). No real pattern family overflows methods today (find-no-overflow-trigger-today);
   compile cost on realistic templates ~1-14ms (ev-bench-ab-temurin probe), +0% vs baseline
   after the L2 drop.

### NOT CONFIRMED (evidence gaps — each is a concrete, small closure item)
4. **Performance vs JDK on service workloads**: cairn holds NO reggie-vs-JDK verdict. The A/B
   recorded here was reggie-vs-reggie (splitter passthrough; splitter now dropped anyway).
   The harness exists (AllStrategyVsJdk, CommonPatterns, LogsBackendGrok, CorpusScan,
   DFATable/LazyDFA/BitParallel JMH suites + HTML report) — run it on the service pattern
   set and record the verdict. Without a recorded win (or parity) on service shapes, "replace
   JDK" is unproven.
5. **RE2J replacement specifically — zero evidence base**: no RE2J benchmark exists anywhere
   in the repo (only an HTMLReporter hook anticipating a "re2j" series). Worse, the RE2J
   value proposition — guaranteed linear-time matching — is UNASSESSED on reggie:
   - Native routes (DFA/table/lazyDFA/BitParallel/BitState/PikeVM) are automata: linear in
     input × states. Fine.
   - But EVERY degradation path lands on JavaRegexFallbackMatcher = java.util.regex =
     backtracking (RuntimeCompiler.fallbackOrThrow:791). An RE2-compatible pattern (regular
     language) that falls back would be exponentially attackable where RE2J was linear —
     a silent safety regression gated on fallback rate, which nobody has measured.
   - Backref-bearing patterns route to reggie's own backtracking strategy — but those are
     un-RE2J-able today anyway (no parity loss vs RE2J, just JDK-class exposure).
   Closure options: (a) measure fallback rate ≈0 on the real service corpus; or (b) make the
   terminal fallback linear-time — PikeVMMatcher(NFA, String) is a runtime NFA interpreter
   constructible from the already-built NFA at the MethodTooLarge catch point (when emission
   overflowed, the NFA exists by construction), turning the worst case into linear-native
   instead of backtracking-JDK. (b) is a small, self-contained RuntimeCompiler change.
6. **Deployment constraint (cairn-confirmed)**: find-openj9-deadline-rejection — on
   J9-derived JVMs, patterns that compile fine on HotSpot blow the 10s deadline and silently
   fall back. Fleet JVM-flavor census required before rollout; if J9-derivatives are in the
   fleet, either raise their deadline or exclude them from phase 1.
7. **Service-corpus validation**: all DoS/safety verification is synthetic-shape +
   8-template-families. No recorded run of the actual backend-service pattern set (compile
   success rate, strategy routing census, fallback rate, metaspace churn under PATTERN_CACHE
   turnover).

## Recommended sequence (small: est. 1-2 days total)
1. Service-corpus compile census (patterns → route/fallback/compile-ms) — answers 4+7 half.
2. JMH AllStrategyVsJdk + LogsBackendGrok on Temurin, record verdict node — answers 4.
3. Decide RE2J-fallback policy: measure fallback rate; if >0 in corpus, PikeVM-as-terminal-
   fallback change — answers 5.
4. Fleet JVM flavor check — answers 6.
Then this question flips to YES (with the J9 caveat documented).

---
