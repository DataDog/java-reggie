---
id: ev-deadline-fix-verification
type: evidence
status: final
depends_on: [find-deadline-coverage-gaps]
supersedes: []
related: []
tags: [deadline, fix, benchmark, before-after, probe]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Before/after probe: the two deadline holes, on 715fb53 vs a5ef0fb (Mac OpenJDK 26, -Xmx6g)

DlProbe2 (same shapes as the original battery), default 10s deadline:

| shape | before (715fb53) | after (a5ef0fb) |
|---|---|---|
| look x1000 | OOM 6g, 4.2s (ASM maxs/frames pass in NFABytecodeGenerator.generateMatchIntoMethod:8379) | UnsupportedPatternException in 286ms (emission budget -> MethodTooLargeException -> graceful path); with ALLOW_JDK_FALLBACK: JDK matcher |
| calt x6000 | COMPILED 22.3s BitStateMatcher (unchecked group-bypass BFS: SubsetConstructor.canReachAcceptWithoutEnteringGroup, 14+ s in-loop) | COMPILED 2.4s BitStateMatcher (BFS work-charged, aborts onto NFA-backed routes) |
| calt x12000 | (unbounded extrapolation) | COMPILED 3.6s BitStateMatcher — scale-independent: the 200M work budget bounds analysis volume, not wall time |
| look x300/600 | (MethodTooLarge territory at toByteArray, after the expensive pass) | same graceful rejection in 385-484ms |

Matching semantics preserved: BitState/PikeVM pass-through tests green (TotalCompileDeadlineTest),
all suites + integration green, spotless clean. Match-time heap for 12k-group native matchers
remains a separate, pre-existing property (not a compile-deadline issue).
