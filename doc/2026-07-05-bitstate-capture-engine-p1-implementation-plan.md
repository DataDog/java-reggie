# P1 implementation plan: BitState capture engine

**Date:** 2026-07-05
**Branch:** `perf/eliminate-slow-paths`
**Status:** plan — pending adversarial review before any code is written
**Precursor:** `doc/2026-07-03-bitstate-capture-engine-design.md` (design, revised)

## 0. Corrections to the design doc, verified against current code

Two of the design doc's wiring claims do not match the current tree (verified by direct code
inspection, not doc-derived assumption). This plan uses the verified facts below; the design doc's
algorithm content (§3, §7, §9) is unaffected and still authoritative.

1. **`RuntimeCompiler.compileFromPrecompiled` does not exist.** There is no dedicated "precompiled
   strategy resolver" method. The design doc's §2/§6.1 references to it are stale.
2. **Actual AP → PikeVM path**, verified in `reggie-processor` and `reggie-runtime`:
   - `ReggieMatcherBytecodeGenerator.resolveRealization()` (`reggie-processor/.../ReggieMatcherBytecodeGenerator.java:94-143`)
     runs `PatternAnalyzer.analyzeAndRecommend()` — the **same** call the dynamic path uses — and maps
     the result to `Realization.{NATIVE, DELEGATE_PIKEVM, DELEGATE_FALLBACK}`. `PIKEVM_CAPTURE` maps to
     `DELEGATE_PIKEVM` at line 121 (or `DELEGATE_FALLBACK` if `FallbackPatternDetector.needsFallback`).
   - For `DELEGATE_PIKEVM`, `RegexPatternProcessor.generateMatcherClass()` (`RegexPatternProcessor.java:248-256`)
     emits `MethodInfo.pikevm(methodName, pattern, encodedNames)` — the **raw pattern string** baked
     into the generated class, not a precompiled matcher object.
   - At runtime, `ImplClassBytecodeGenerator.generateLazyInitMethod` (`ImplClassBytecodeGenerator.java:149-162`)
     emits `INVOKESTATIC RuntimeCompiler.compilePikeVm(String pattern, String encodedNames)` — called
     **on every invocation**, no caching field on the generated class side (mutable per-call PikeVM
     buffers make a fresh instance necessary).
   - `RuntimeCompiler.compilePikeVm` (`RuntimeCompiler.java:296-319`) re-parses, skips strategy
     re-analysis, builds/caches the NFA in `PIKEVM_NFA_CACHE`, returns `PikeVMEntry.newMatcher(pattern)`.
3. **Full switch/comparison-site inventory over `PatternAnalyzer.MatchingStrategy`** (supersedes design
   doc §6.1's list, which missed several):
   - `reggie-processor/.../ReggieMatcherBytecodeGenerator.java:282` (codegen dispatch `switch`) and
     equality checks at lines 103, 213, 255, 268-274, 275.
   - `reggie-runtime/.../RuntimeCompiler.java:826` and `:882` (two `switch` blocks), plus equality
     checks at 470, 498, 694, 724, 738, 756, 854, 870-876, 878.
   - `reggie-codegen/.../analysis/StrategyJdkClassifier.java:78` (`switch`, JDK-dependency classification).
   - `reggie-codegen/.../analysis/PatternDebugger.java:59` (`switch`, diagnostics).
   - `reggie-codegen/.../codegen/NFABytecodeGenerator.java:10970` (`switch`).
   - `reggie-codegen/.../analysis/FallbackPatternDetector.java` — takes `strategy` as a parameter,
     branches on it internally (needs review for whether BITSTATE_CAPTURE should be treated like
     PIKEVM_CAPTURE here).
   - `RuntimeCompiler.isNfaBacked(strategy)` (`RuntimeCompiler.java:825`, called from line 589).

   **Every one of these needs a `BITSTATE_CAPTURE` arm before the enum value is ever emitted by
   `PatternAnalyzer.analyzeAndRecommend()`,** or an existing pattern that hits one of these switches
   throws/misbehaves at build or run time. This is a strictly larger touch-point set than the design
   doc anticipated (6+ files instead of 3).

## 1. Consequence for the "AP never emits BITSTATE_CAPTURE" decision (design §6.1)

The design doc's stated v1 choice — dynamic path may pick BitState, AP path never does — is still the
right call, and it is now cheaper to implement precisely than the design doc assumed, since AP routing
funnels through the single `resolveRealization()` method rather than a separate precompiled-strategy
resolver:

- In `resolveRealization()` (`ReggieMatcherBytecodeGenerator.java:94-143`), add `BITSTATE_CAPTURE` to
  the same branch that currently handles `PIKEVM_CAPTURE` (line 103) — both map to `DELEGATE_PIKEVM`
  (or `DELEGATE_FALLBACK` under the same `needsFallback` condition). No new `Realization` variant, no
  new codegen path.
- This means a `BITSTATE_CAPTURE`-eligible pattern compiled via `@RegexPattern` still runs on PikeVM at
  runtime (via `compilePikeVm`), while the same pattern compiled dynamically via `RuntimeCompiler.compile()`
  gets the faster BitState engine. That asymmetry is intentional for v1 and should be documented as a
  one-line code comment at the branch, not left implicit.
- No changes needed to `RegexPatternProcessor`, `ImplClassBytecodeGenerator`, or `MethodInfo.pikevm(...)`.

## 2. Task list (P1 scope only — core interpreter + eligibility + routing, per design §10)

Ordered so each step is independently compilable/testable.

### 2.1 Extract shared `checkAnchor`
- Move `PikeVMMatcher.checkAnchor` (`PikeVMMatcher.java:1287-1326`, `private static`) to a
  package-visible static home both engines can call — either a new small utility class in
  `reggie-runtime/.../runtime/` or widen its visibility in place if `BitStateMatcher` lives in the same
  package. Prefer the narrowest change: widen visibility in place first; only extract to a separate
  utility if BitState cannot be same-package for some reason.
- No behavior change. Verify with existing PikeVM anchor tests (must stay green — this is a pure move/visibility change).

### 2.2 Extract shared result-construction path
- Per design §12: extract `PikeVMMatcher.buildResult` (`PikeVMMatcher.java:1535`) and the
  `embedsNameMap` contract (`PikeVMMatcher.java:1531`) into a shared static helper or small base
  method usable from `BitStateMatcher`. `BitStateMatcher` must override `embedsNameMap()→true` so
  `RuntimeCompiler`'s `NameEnrichingMatcher` wrapping is not double-applied.

### 2.3 `BitStateMatcher` core interpreter
- New class, `ReggieMatcher` subclass (mirrors `PikeVMMatcher extends ReggieMatcher`,
  `PikeVMMatcher.java:39`). Must override the full set verified present on `PikeVMMatcher`:
  `matches` (602), `find` (610), `findFrom` (643), `match` (650), `findMatch` (655),
  `findMatchFrom` (660), `matchesBounded` (667), `matchBounded` (672). `findAll`/`findMatchInto` can be
  inherited from `ReggieMatcher` defaults (confirmed `PikeVMMatcher` does not override them either).
- Implement per design §3: EXPAND/RESTORE job stack (§3.1), visited bitmap (§3.2), mutable `caps` +
  undo log (§3.3), the `search()` step function (§3.4) using the extracted `checkAnchor`.
- Implement the single unanchored pass (§4.1) via the lowest-priority self-loop technique — do **not**
  implement per-start restart search.
- Data structures per design §5: constructor-preallocate `caps`/`winCaptures`/undo arrays sized off
  `groupCount`/`stateCount`; grow the visited structure lazily per search, capped at `BUDGET_CELLS`.
- Fallback per design §8: lazily-constructed `PikeVMMatcher` field, delegate whole-call when
  `stateCount × (spanLen+1) > BUDGET_CELLS`, increment a fallback counter (no silent truncation, per
  design §5).

### 2.4 `isBitStateEligible` (AST-level predicate)
- In `PatternAnalyzer` (`reggie-codegen/.../analysis/PatternAnalyzer.java`), add the predicate per
  design §6, using the verified AST predicates: `hasBackreferences` (1590), `hasLookaround` (1995),
  `hasAtomicGroups` (2158), `hasPossessiveQuantifiers` (2168), plus a new predicate for "anchored
  quantifier wrapping a nullable `^`/`\A`/`(?m)^` body" (design §6 item 5 / §7.5) — this new predicate
  does not exist yet and is new code, not an extraction.
- Add `BITSTATE_CAPTURE` to the `MatchingStrategy` enum (`PatternAnalyzer.java:3043`) immediately
  before `PIKEVM_CAPTURE` — i.e. lower priority than `ONEPASS_NFA` and every other strategy that
  already precedes `PIKEVM_CAPTURE` (all `DFA_*`, `BITPARALLEL_GLUSHKOV`, `COUNTING_GLUSHKOV`,
  `OPTIMIZED_NFA*`, `LAZY_DFA`, `HYBRID_DFA_LOOKAHEAD`, `RECURSIVE_DESCENT`), but higher priority
  than `PIKEVM_CAPTURE`.
- **Routing mechanism, not a per-branch edit.** `MatchingStrategy.PIKEVM_CAPTURE` is returned from
  roughly 30 separate call sites scattered across `analyzeAndRecommend()` (e.g. lines 377, 407, 443,
  836, 892, 941, 962, 1006, 1027, 1070-1303, 1348, 1384, 1403, 1413, 1421), each guarded by different
  structural conditions. `isBitStateEligible` is a pattern-level (AST) predicate independent of *which*
  of those branches picked `PIKEVM_CAPTURE` — so do **not** add an eligibility check at each of the ~30
  sites. Instead, wire this as a single post-processing step at the two public entry points,
  `analyzeAndRecommend()` (line 282) and `analyzeAndRecommend(boolean)` (line 292): after the existing
  branch logic produces its `MatchingStrategyResult`, if `result.getStrategy() == PIKEVM_CAPTURE` and
  `isBitStateEligible(...)` holds, substitute `BITSTATE_CAPTURE` for the strategy in the returned
  result before returning. This is the one funnel point through which every `PIKEVM_CAPTURE` result
  already passes.

### 2.5 Routing wiring (all sites from §0.3 above)
Add a `BITSTATE_CAPTURE` arm at every site, using the corrected inventory:
- `RuntimeCompiler.java`: the two `switch` blocks (826, 882), the `isNfaBacked` check (825), the
  `PIKEVM_NFA_CACHE`/`PikeVMEntry` construction (151-152, 166) — generalize `PikeVMEntry.newMatcher`
  into an engine-factory entry, or add a sibling `BITSTATE_NFA_CACHE` + `BitStateEntry` (pick one; see
  open question below), the L1 fast-path (213-244), the `compileInternal` early-return (498-506), the
  anchor-dilution guard (469-470) — decide whether BitState shares it (default: yes, same guard,
  since both are NFA-interpreter strategies with identical anchor semantics).
- `ReggieMatcherBytecodeGenerator.java`: per §1 above, fold into the existing `PIKEVM_CAPTURE` branch
  at `resolveRealization()` line 103 (and the corresponding `generate()` throw-guard at 213-222) —
  **not** a new codegen arm.
- `StrategyJdkClassifier.java:78`: add `BITSTATE_CAPTURE` to the same classification bucket as
  `PIKEVM_CAPTURE` (both are NFA-interpreter, non-JDK-delegating).
- `PatternDebugger.java:59`: add a diagnostic-label arm (cosmetic, low risk).
- `NFABytecodeGenerator.java:10970`: inspect this switch's purpose before adding an arm — flagged
  because it wasn't in the design doc's list and its role in the codegen path is unverified; do not
  assume it needs the same treatment as the AP dispatch switch without checking what it's used for.
- `FallbackPatternDetector.java`: review whether `needsFallback` logic should apply identically to
  `BITSTATE_CAPTURE` as it does to `PIKEVM_CAPTURE` (likely yes, since it's about pattern-level JDK
  incompatibility, not the executing engine) — confirm before wiring.

### 2.6 Tests (design §9, P1 subset)
Per design §10, the full differential gate (§9's group-span comparison against `PikeVMMatcher` and
`java.util.regex` as a merge condition) is **P2 scope**, not P1. This section is limited to unit-level
tests that exercise the P1 core interpreter and routing directly:
- New `BitStateMatcherTest`: production patterns (COMMAND, URL, SQL) × representative inputs, unit-level
  assertions on `BitStateMatcher` output (not a full differential comparison harness).
- Semantics corner cases (§9.2) and the §9.3 anchored-quantifier assertions
  (`(^a?){3}`, `(?m)(^x?)+`, `\A{3}a`) — these must show BitState is **excluded** from eligibility for
  these shapes (§2.4's new predicate), not that BitState gets them right.
- Budget/fallback test (§9.5): oversized pattern+input returns identical result to pure PikeVM, counter increments.
- The full §9 differential-gate harness (production patterns run through both `PikeVMMatcher` and
  `java.util.regex` as a merge condition) is tracked separately as a P2 task, per design §10.

## 3. Open questions requiring a decision before coding starts

1. **Cache/entry generalization** (§2.5): generalize `PikeVMEntry` into an engine-factory abstraction
   shared by both engines, or add a parallel `BitStateEntry`/`BITSTATE_NFA_CACHE`? Generalizing touches
   more existing code (risk to a stable, working cache) but avoids duplicate cache-management logic.
   Recommendation: parallel `BitStateEntry` for P1 (smaller diff, zero risk to existing PikeVM caching),
   revisit generalization later if a third engine ever appears.
2. **`NFABytecodeGenerator.java:10970`** — needs its role confirmed before deciding whether it needs a
   `BITSTATE_CAPTURE` arm at all (see §2.5).
3. Job-stack encoding and visited-set representation (design §12) — left as micro-benchmark decisions
   during implementation, not blocking plan approval.

## 4. Explicit non-goals (unchanged from design doc)

Atomic groups, possessive quantifiers, backreferences, lookaround, and the anchored-nullable-body
shape (§7.5) all stay excluded from `isBitStateEligible` in P1. Anchor-derived pruning port (§7.5) is
P4. DFA span-tightening (§4.3) is P3.

Related: `doc/2026-07-03-bitstate-capture-engine-design.md`, memory `project_capture_bottleneck_cpu_bound`,
`project_reggie_safe_backtracking_investigation`, `feedback_stress_test_plans`.
