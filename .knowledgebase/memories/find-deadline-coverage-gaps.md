---
id: find-deadline-coverage-gaps
title: The 10s total-compile deadline originally bound only SubsetConstructor — two verified holes (codegen-emission OOM in ~4.2s that a time deadline cannot close; BitState-route compile 22.3s past the deadline) closed in a5ef0fb via work-charged bypass BFS + 65,535-insn per-method emission budget + compile-scope OOM catch
kind: finding
tags: [deadline, dos, oom, bitstate, compile-time, coverage-gap, verified-on-baseline, resolved-by-fix]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/NFABytecodeGenerator.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java, reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/automaton/SubsetConstructor.java]
source: multi-64k/find-deadline-coverage-gaps
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Deadline coverage holes (closed in a5ef0fb)

The total deadline binds ONLY via SubsetConstructor: RuntimeCompiler.compileWithDeadline sets
SubsetConstructor.TOTAL_COMPILE_DEADLINE_NANOS (ThreadLocal, clamp-only-tightens) and periodic
checks inside buildDFA loops. Two bypass classes existed:
- Routes that never determinize (BitState/NFA-cascade: parse -> NFA -> bit-parallel codegen)
  consulted the deadline ZERO times → hole 2 (concatAlt x6000 `(wa0|wb0)(wa1|wb1)...`, len
  87,780, 12,000 capture groups → COMPILED in 22.3s as BitStateMatcher, 2.2x past the deadline).
- Phases outside determinization (parse, NFA build, analysis passes, codegen emission) had no
  checks → hole 1 (lookahead x1000 `(?=lk0)lk0...` len 13,780 → OOM in ~4.2s; jstack pinned
  NFABytecodeGenerator.generateMatchIntoMethod:8379 — codegen EMISSION, outside
  SubsetConstructor's OOM-catch). Key property: a TIME deadline cannot close hole 1 — the
  pattern dies inside the envelope; only a memory/size cap can.

RESOLVED (a5ef0fb):
- Time: bypass BFS charged via chargeWork(8)/dequeued state — work budget (200M) and deadline
  bind it. calt-6000: 22.3s -> 2.4s BitStateMatcher (scale-independent: x12000 -> 3.6s).
- Memory: per-method emission budget in NFABytecodeGenerator
  (MAX_EMITTED_INSNS_PER_METHOD = 65_535, BoundedMethodVisitor on all 12 method-emission
  sites) — oversized methods abort DURING emission (before ASM's maxs/frames pass) and
  surface as the standard MethodTooLargeException graceful path. lookahead-1000: 6g OOM
  in 4.2s -> graceful UnsupportedPatternException in 286ms (JDK matcher with
  ALLOW_JDK_FALLBACK). Plus compile-scope OutOfMemoryError catch in compileInternal
  as the general backstop.
- Deliberately NOT added: deadline checks at the PikeVM/BitState early returns — the
  pass-through policy is designed semantics (TotalCompileDeadlineTest asserts PikeVM
  exactly), and post-fix the finish work is genuinely cheap.
- Design note: the 65,535-insn cap can never false-trip vs the 64KB method limit
  (would require <1.01 bytes/insn; cascade emission averages ~3B); it fires exactly
  where MethodTooLarge would at toByteArray, ~100x cheaper.
- Tests: GroupBypassWorkBudgetTest (codegen, incl. legit-shape guard),
  DeadlineCoverageHolesTest (runtime, both holes, heap-safe — see the match-time heap
  memory).
