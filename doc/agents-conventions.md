# Conventions & Critical Rules

Detail doc for `AGENTS.md`. Covers coding rules that are easy to violate silently and
whose violation produces hard-to-debug wrong results.

## Bytecode Generation Dual-Path Rule

**CRITICAL**: Changes to bytecode generation require updates to BOTH (this includes strategy-realization and fallback-guard decisions: every path that returns a realization must call `FallbackPatternDetector.needsFallback(ast, strategy)` — check `RuntimeCompiler` and `ReggieMatcherBytecodeGenerator.resolveRealization` together):
1. `RuntimeCompiler.java` (runtime path: `Reggie.compile()`)
2. `ReggieMatcherBytecodeGenerator.java` (compile-time path: `@RegexPattern`)

The rule covers every per-strategy `case` body: constructor arguments, flags, and the set of
generated methods (e.g. `findBoundsFrom`, `matchesBounded`) must be identical for the same
`MatchingStrategy` in both files. Before committing, diff the touched strategy's `case` body in
`RuntimeCompiler.java` against the one in `ReggieMatcherBytecodeGenerator.java`; any asymmetry
must be fixed in the same commit.

Verify both:
```bash
./gradlew :reggie-runtime:test          # Test runtime
./gradlew :reggie-processor:test        # Test compile-time generator
./gradlew :reggie-benchmark:build       # Test compile-time
```

## Routing/Generator Alignment Rule

A `PatternAnalyzer` routing or eligibility predicate accepts exactly the constructs the target generator implements, no broader category: test the handled subset (e.g. `max == 1` when only `?` is supported; any `NFA.AnchorType`, not a hand-picked list, when no anchors are supported), and sibling detectors for the same transformation share one predicate. Each such predicate has a test with a construct just outside the subset asserting it is NOT routed to that strategy.

## Cross-Engine Semantic Parity Rule

When you change how one engine implements a shared construct (end-anchor `$`/`\Z` line terminators, start-position clamping in `findFrom`, anchor checks), apply the same change to every engine that implements it: `PikeVMMatcher`, and every `*BytecodeGenerator` that emits the construct (grep for the helper, e.g. `emitEndAnchorCheck`, `emitAnchorCheck`, `generateFindFromMethod`).
- End-anchor terminator set (non-`UNIX_LINES`): `\n` unless preceded by `\r`, lone `\r`, the `\r\n` pair, `\u0085`, `\u2028`, `\u2029`.
- `findFrom` with a negative start clamps to 0; it never returns -1 for that reason.
- Add a JDK-differential test covering the construct for each strategy you touched.

## Structural Hash Rule

**HARD RULE**: Any time you add or change a field on `DFA.DFAState`, `DFA.DFATransition`, or any
`PatternInfo` subclass that affects bytecode generation, you MUST also update
`StructuralHash.java` to include that field in the hash. Failure to do so causes the level-2
structural cache to return a compiled class built for a different pattern, producing wrong runtime
results that are extremely hard to debug.

Checklist when touching `DFA.DFAState`, `DFA.DFATransition`, `NFA.NFAState`, or any `PatternInfo`:
- `DFAState` field added → add it to `computeDFATopologyHash()` state-loop body
- `DFATransition` field added → add it to `computeDFATopologyHash()` transition-loop body
- `NFAState` field added → add it to `NFA.contentHashCode()` state-loop body
- New NFA anchor predicate (`NFA.hasXxx()`) added → add the corresponding flag to `StructuralHash.compute()`
- `PatternInfo` subclass field added → add it to that class's `structuralHashCode()`

Example — `acceptanceAnchorConditions` and `entryGuard` added post-anchor fix:
```java
// DFAState: per-state acceptance anchor conditions. Use ordinal-derived bitmasks for
// anchor EnumSets, not EnumSet.hashCode(), because Enum.hashCode() is identity-based.
hash = 31 * hash + anchorBitmask(state.acceptanceAnchorConditions);

// DFATransition: per-transition entry guard
hash = 31 * hash + anchorBitmask(entry.getValue().entryGuard);

private static int anchorBitmask(EnumSet<NFA.AnchorType> anchors) {
    int mask = 0;
    for (NFA.AnchorType anchor : anchors) {
        mask |= (1 << anchor.ordinal());
    }
    return mask;
}
```

When creating `PatternInfo` subclasses, `structuralHashCode()` MUST include ALL fields affecting bytecode:
```java
public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + minCount;
    hash = 31 * hash + (charset != null ? charset.hashCode() : 0);  // Include all fields!
    hash = 31 * hash + groupNumber;
    return hash;
}
```
Missing fields → cache collisions → wrong results.

## Bytecode Generation Best Practices

1. **Never hardcode local variable slots** — this includes method parameters: bind every parameter and temp to a named slot variable (e.g. `depthSlot`) allocated from `highestSlot`, and never emit `ILOAD`/`ISTORE` with a numeric literal slot, because a literal can silently alias a slot another path repurposes:
   ```java
   // WRONG: int var = 50;
   // RIGHT: int var = highestSlot + 1;
   ```

2. **Never use visitLdcInsn with primitive int**:
   ```java
   // WRONG: mv.visitLdcInsn(value);  // autoboxes!
   // RIGHT: BytecodeUtil.pushInt(mv, value);
   ```

3. **Document slot allocation** in method comments

4. **`MatchResultImpl` contract**: generated code passes the original `input` and `starts[g]`/`ends[g]` arrays indexed by group (group 0 = whole match at index 0, length `groupCount + 1`) holding absolute offsets into that input — never offsets relative to a substring copy. Every new strategy has tests asserting `start(0)`/`end(0)`/`group()` for `match()` and `matchBounded(input, s, e)` with `s > 0`.

## Concurrency Contract

- **DFA-backed matchers** (strategies `DFA_UNROLLED`, `DFA_SWITCH`, `DFA_UNROLLED_WITH_ASSERTIONS`,
  `DFA_SWITCH_WITH_ASSERTIONS`, `DFA_UNROLLED_WITH_GROUPS`, `DFA_SWITCH_WITH_GROUPS`, and
  similar): `Reggie.compile()` returns a **single shared instance** from `PATTERN_CACHE`. A matcher must be fully initialized (including `reportPattern(...)` and any other setter) inside the `computeIfAbsent` mapping function; never mutate an instance after it is published to a compile cache. The
  generated bytecode uses only method-local variables — no instance fields are modified during
  matching. These matchers are **safe for concurrent use from multiple threads** without external
  synchronization.

- **Matchers with mutable match state** (strategies for which
  `MatchingStrategy.hasMutableMatchState()` is true: the NFA-backed strategies such as
  `OPTIMIZED_NFA`, `OPTIMIZED_NFA_WITH_BACKREFS`, `OPTIMIZED_NFA_WITH_LOOKAROUND`, `LAZY_DFA`, plus
  `PIKEVM_CAPTURE`, `BITSTATE_CAPTURE` and `RECURSIVE_DESCENT`): the matcher writes instance fields
  during matching (state sets, scratch capture arrays, `lazyFindMode`, per-call buffers).
  `Reggie.compile()` returns a **fresh instance** per call. Each instance is **not thread-safe** — do
  not share a single instance across threads. Call `Reggie.compile()` once per thread or per match
  operation.

- **Shared matchers built from stateful parts** wrap them in `PooledMatcher`, which hands each call
  an exclusive instance (one pooled instance, a new one when it is in use). The annotation
  processor's `$Impl` caches one matcher per method, so it caches a `PooledMatcher` for
  mutable-state strategies and for `ALLOW_JDK_FALLBACK` delegates, and builds the HYBRID
  realization as a `HybridMatcher` whose NFA half is a `PooledMatcher`. The
  `DETERMINISTIC_CHAIN_BYTECODE` PikeVM fallback (`RuntimeCompiler.createChainFallback`) is a
  `PooledMatcher` too.

- **Static state shared across matcher instances and mutated during matching** (e.g. `LazyDFACache`): every publication of a lazily built object or slot uses a release write paired with an acquire read (`VarHandle.setRelease`/`getAcquire`, or `Atomic*Array`), in both the runtime class and generated bytecode. Plain stores plus writer-side fences (`storeStoreFence`, `fullFence`) are not sufficient on weakly ordered CPUs (ARM), and this is never deferred as an x86-only non-issue.

This contract is verified by `DfaMatcherConcurrencyTest`, `NfaMatcherConcurrencyTest`,
`ChainFallbackPoolingTest` and `HybridRealizationProcessorTest`.

## Compile-Cache Hazards (flaky-test checklist)

The compile caches in `RuntimeCompiler` (`PATTERN_CACHE`, `NFA_CLASS_CACHE`, `PIKEVM_NFA_CACHE`,
`STUB_PIKEVM_CACHE`, `BACKTRACK_NFA_CACHE`, `USER_CACHE`) are global static per-JVM state. Two
consequences:

- **Compiling on one thread and matching on another, or `clearCache()` racing a concurrent
  `compile()`, flakes nondeterministically** — a matcher can lose its compiled class or pick up one
  compiled for different options. `describeRouting()` clears ALL caches unconditionally, so it must
  never be called concurrently with `compile()` (or another `describeRouting()`) in the same JVM.
- When a test flakes and the failure is not a pure timing/load artifact, check this list first:
  does the test compile and match across thread boundaries, call `clearCache()`/`describeRouting()`
  while other tests run, or share one matcher instance of a mutable-state strategy across threads?
  (See the Concurrency Contract above for which strategies share instances.)

Cross-JVM cache conflicts are impossible: Gradle test tasks run in separate JVMs and there is no
on-disk pattern cache. Wall-clock timing assertions (`assertTimeoutPreemptively`) are still
load-sensitive in parallel full builds — the first run of freshly generated bytecode executes
interpreted while C1/C2 compile it, so give timing tests generous budgets or measure work instead
of wall-clock.

## Routing & Admission Decisions

Derive strategy/route admission from the parsed AST or plan (e.g. `LinearTokenSequencePlan` op categories, parsed flags), never from substring scans of the pattern source. If a source-level scan is unavoidable, it must honor Java regex syntax: escapes, `\Q...\E`, and a leading literal `]` in `[]]`/`[^]]`. Tests must pin each of those cases.

## Admission-Predicate Parity

A predicate that admits a pattern onto a specialized path (e.g. `CaptureProjection.isDirectCaptureSource`, the over-JIT split admission in `RuntimeCompiler`) MUST reject every construct and option that the paired builder or matcher rejects or projects differently (e.g. the node types `collectFullCaptureLayout` rejects; `CAPTURE_NAMED_ONLY`). Each rejected construct/option is pinned by a test asserting the specialized path is not selected for it.

## Code Style

**Enforced via Spotless** (Google Java Format 1.22.0): 2-space indent, 100-char line limit, K&R braces.

```bash
./gradlew spotlessCheck   # check formatting compliance
./gradlew spotlessApply   # auto-fix (also checked during `./gradlew build`)
```

- **License headers**: all `.java` files require the Apache 2.0 header (`.github/license-header.txt`), auto-checked by Spotless.
- **Naming**: classes `PascalCase`, methods/variables `camelCase`, constants `UPPER_SNAKE_CASE`, packages lowercase no underscores.
- **Package structure**: AST nodes `*.ast.*Node`, bytecode generators `*.codegen.*BytecodeGenerator`, test classes `*Test` suffix mirroring package structure.
- **Documentation**: Javadoc for public APIs; no hallucinations — documentation must match actual code. Every system property, flag, class, or method named in a Javadoc/comment must exist (grep for it before committing), and a Javadoc must state any filtering or exclusion the method performs; update the Javadoc in the same commit that changes the behavior.
  When a change alters routing or fallback behavior (`PatternAnalyzer`, `FallbackPatternDetector`, `RuntimeCompiler`), grep `src/main` and `src/test` for comments naming the affected strategy, pattern shape, or fallback reason and update every comment that describes the old routing in the same commit. This covers Javadoc, design specs, plan docs and the PR description alike: every repo path a doc references must exist (`git ls-files <path>`), and a commit that changes routing, eligibility, or concurrency behavior updates the spec/PR-description statements about that behavior in the same push. Documentation includes code comments, test names/section labels, and the PR description: when a change removes a limitation or guard, delete the comment claiming it; never label code with a property it lacks (e.g. "allocation-free" around `match()`, which allocates); every API the PR description names must exist in the diff.
- **Imports**: specific imports (no wildcards except `java.*`/`javax.*`); prefer importing over fully-qualified names; order java/javax → external → internal.
- **No redundant work**: no dead stores or unread allocations (e.g. a saved `groups.clone()` that is never read); compute a compile-time analysis once per method and reuse the local; read authoritative existing state (e.g. `ReggieMatcher.nameToIndex`) instead of re-deriving it from the pattern string.

## Testing Requirements

- Unit test for every new feature — covering every new public method, including inherited interface contracts (e.g. `Iterator.hasNext()`/`next()`) and each documented interaction between methods; integration test for PCRE conformance changes; benchmark for performance-sensitive changes.
- An API documented as the equivalent of a `java.util.regex` method (e.g. `MatchCursor.appendReplacement`) matches JDK behavior for every replacement and error case (`$x`, trailing `$`, `$$`, trailing `\`, out-of-range group), each pinned by a test asserting the JDK outcome; intentional divergences are listed in `doc/agents-fallback-and-limitations.md`.
- All tests must pass: `./gradlew build`.
- Concurrency tests own their `ExecutorService` via try-with-resources (`ExecutorService#close()`), so the pool shuts down even when an assertion or latch await fails. Worker bodies catch `Throwable` into a shared `ConcurrentLinkedQueue`, and the test asserts it is empty after the pool closes. Never rely on assertions thrown inside `executor.execute(...)`. Reference: `LinearTokenSequenceMatcherConcurrencyTest`.
- A test's method name and comment must state what it actually asserts. When a change flips an asserted outcome (e.g. fallback → native) or the pattern under test, rename the test and update its comment in the same commit; a name that claims a construct ("double backref", "Fallback") must match the pattern and assertions.
- Coverage must meet both gates: `./gradlew jacocoVerify` (target 70-75% line coverage overall; see `doc/coverage-baseline.md`) AND the Codecov **patch** gate (`codecov.yml` coverage.status.patch, target 70%, threshold 5%) — overall coverage passing while the PR's changed lines sit below 70% fails CI; a PR that passed jacocoVerify yet failed the patch gate at 64.9% is the precedent. Adding tests only to untouched files does not satisfy the patch gate.
- Each test must fail if the behavior it names is broken: a test for one eligibility guard uses an input that passes every other guard; matcher tests include at least one deterministic accepting input next to random/rejecting ones; tests comparing two instances first `assertNotSame` them (bypass `PATTERN_CACHE`, e.g. `RuntimeCompiler.cached(key, pattern)`).

## Commit Guidelines

- Concise commit messages, one logical change per commit, all tests passing before commit.

## Replying to Review Findings

- Before rebutting an automated review finding, check it against the commit SHA the reviewer reviewed and against current HEAD. If it was valid at the reviewed SHA, reply "valid at <sha>, fixed in <sha>" — never "not valid".
- A rebuttal must cite the file:line at HEAD (or the test) that disproves the finding.
