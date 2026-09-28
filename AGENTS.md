# Reggie - Agent Development Guide

<!-- cairn:knowledgebase begin (managed by cairn; do not edit manually) -->
## Cairn knowledgebase — consult before designing, implementing, or reviewing

If `.knowledgebase/memories/` exists in this repo (sentinel: `.knowledgebase/.cairn`),
it holds committed institutional memory: generally valid knowledge distilled from
past cairn investigations — confirmed findings and refuted dead ends, one flat
memory per file. The investigation trails themselves are personal (in ~/.cairn);
the repo carries only the distilled knowledge. Use it instead of re-deriving:

- **Before feature/design work on an area**: query it — run the cairn skill's
  script: `python3 <cairn-skill-dir>/investigation.py brief <topic> [--paths <files>] [--diff]` (the cairn-memory skill — its directory is listed in your available skills; the `/cairn` plugin command wraps the same script on
  Claude Code). Matched memories are design
  constraints; dead-end memories are approaches already refuted here.
- **Before/while implementing or reviewing**: check the diff against matched
  memories; never re-introduce a refuted dead end.
- **Cite** consumed memory ids (`kb/<memory-id>`) or their source nodes
  (`<investigation>/<node-id>`) in designs/PRs (`cairn cite <doc>`) so the
  memory's payoff stays measurable.
- **Resuming interrupted work** (fresh session or after context compaction):
  read the active investigation's wrap-up note (`notes/<name>-wrapup.md`, kept
  fresh at every checkpoint) first — settled findings, refuted dead-ends,
  open questions, next step — then query for specifics. The wrap-up is the
  point-in-time refill artifact; the memories are canonical.
- Memories live at `.knowledgebase/memories/<id>.md` (frontmatter: id, title,
  kind, tags, applies_to, source — provenance back into the ~/.cairn trail).
<!-- cairn:knowledgebase end -->

































































> Single source of truth for AI agents working in this repo. `CLAUDE.md` is a redirect stub —
> all edits go here, never there.

## Project Overview

Reggie is a Java 21+ regex library with dual compilation modes (compile-time via `@RegexPattern`,
runtime via `Reggie.compile()`) that generates specialized bytecode per pattern:
Pattern → AST → Thompson NFA → DFA → strategy selection → specialized bytecode (36+ strategies).

See `doc/ARCHITECTURE.md` for the full pipeline, module structure, and design patterns.

## Build & Test

```bash
./gradlew build                                             # full build incl. tests
./gradlew :reggie-runtime:test --tests ClassName.methodName # single test
./gradlew test -Dreggie.test.knownFailures=true              # include known failures
./gradlew :reggie-runtime:debugPattern -Ppattern="\\d{3}-\\d{3}-\\d{4}"  # inspect strategy/bytecode
```

Coverage: `./gradlew jacocoVerify` (thresholds in `doc/coverage-baseline.md`).
Benchmarks: `./gradlew :reggie-benchmark:benchmarkAndReport` (see AGENTS conventions doc for baselines/profiling).

## Critical Rules — read before editing bytecode generation

1. **Run `./gradlew spotlessApply` before every commit and push.** Unformatted pushes fail CI.
2. **Dual-path rule**: bytecode-generation changes must update both `RuntimeCompiler.java` and
   `ReggieMatcherBytecodeGenerator.java`.
3. **Structural hash rule**: new fields on `DFA.DFAState`/`DFA.DFATransition`/`PatternInfo`
   subclasses MUST be added to `StructuralHash.java`, or the structural cache silently returns
   wrong compiled classes.
4. **Never hardcode bytecode local-variable slots**; never `visitLdcInsn` a primitive int
   (use `BytecodeUtil.pushInt`).

Full detail, examples, and the concurrency contract: **`doc/agents-conventions.md`**.

## Fallback Behavior & Known Limitations

`Reggie.compile()` throws `UnsupportedPatternException` for unsupported constructs instead of
silently falling back — use `Reggie.compileAllowingFallback()` / `ReggieOption.ALLOW_JDK_FALLBACK`
to opt into `java.util.regex` delegation. Full fallback-condition tables (`FallbackPatternDetector`,
`RuntimeCompiler`), current known limitations (recursive palindromes, named-capture branch reset
groups), and performance-only gaps:
**`doc/agents-fallback-and-limitations.md`**.

## Common Task Workflows

- **Adding a regex feature**: AST node → `RegexParser` → `ThompsonBuilder` → tests →
  `SubsetConstructor` (if needed) → bytecode generator → `PatternAnalyzer` → integration test →
  benchmark → `spotlessApply` → `./gradlew build`.
- **Fixing a bug**: failing test first → `debugPattern` to locate the component → fix →
  `spotlessApply` → `./gradlew build` → `./gradlew :reggie-integration-tests:test`.
- **Performance work**: baseline via `saveBaseline` → change → `spotlessApply` → correctness
  tests → `benchmarkAndReport` → profile with async-profiler if needed.
- **PCRE conformance**: run `CorrectnessTest` → fix → update `doc/plans/pcre-conformance-roadmap.md`.

## Key Files

- `reggie-codegen/.../parsing/RegexParser.java` — pattern → AST
- `reggie-codegen/.../automaton/ThompsonBuilder.java` / `SubsetConstructor.java` — AST → NFA → DFA
- `reggie-codegen/.../analysis/PatternAnalyzer.java` — strategy selection
- `reggie-runtime/.../Reggie.java` — public API entry point
- `reggie-runtime/.../runtime/RuntimeCompiler.java` — runtime bytecode generation
- Bytecode generators: `reggie-codegen/.../codegen/*BytecodeGenerator.java`

Temporary/task-scoped documents go in `doc/temp/` (gitignored) — never in `.github/` or repo root.

## Publishing & Releasing

`./scripts/release.sh {major|minor|patch}`. Full process, SSM credential setup, and branch
strategy: **`doc/RELEASING.md`**.

## Further Reading

- `doc/ARCHITECTURE.md` — system design, pipeline, module structure
- `doc/agents-conventions.md` — code style, testing requirements, bytecode dual-path/structural-hash
  rules, concurrency contract
- `doc/agents-fallback-and-limitations.md` — correctness guarantee, JDK fallback conditions, known
  limitations
- `doc/RELEASING.md` — release process
- `doc/coverage-baseline.md` — coverage thresholds and analysis
- `doc/plans/pcre-conformance-roadmap.md` — PCRE conformance tracking
- `doc/TUTORIAL-RUNTIME.md` / `doc/TUTORIAL-COMPILE-TIME.md` — API usage guides
