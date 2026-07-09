# Design: BitState bytecode generator (revisiting "interpreter, not codegen")

Date: 2026-07-08
Status: implemented and merged (PR #99, commit b9cf582). See §4a for the scope-narrowing
decision made during implementation (general NFA-splitting deferred; first cut recognizes a
specific structural family directly), and §2a for post-implementation measurements
confirming the regression is fixed.

## 1. Why this doc exists

`doc/2026-07-03-bitstate-capture-engine-feasibility.md` §3 deliberately chose an interpreter
over a bytecode generator for BitState, to minimize risk for the P1 rollout:

> "Interpreter, not codegen... This is the lowest-risk integration and avoids touching
> `RuntimeCompiler`'s bytecode paths."

That shipped as `BitStateMatcher.java` (job-stack DFS + generation-stamped visited bitmap +
undo-log capture restoration, interpreting the shared `NFA` object). Separately,
`doc/2026-07-06-bitstate-span-tightening-design.md` is an in-flight, not-yet-implemented
follow-up that narrows the *span* the interpreter searches over — a real but bounded win
(less redundant work over trailing input), not a change to per-character constant-factor
overhead.

This doc presents new evidence that the interpreter's per-character constant-factor
overhead — not just span length — is a large, separate cost, and proposes a narrow,
opt-in bytecode path for BitState-eligible patterns whose backtracking is shallow enough to
make generation tractable, rather than reopening the general case.

## 2. Evidence: hand-written prototype ceiling

A throwaway, hand-written (non-generated) specialized matcher for the IAST `COMMAND` pattern
(`(?s)(?m)^(?:\s*(?:sudo|doas)\s+)?\b\S+\b\s*(.*)`, currently routed to `BITSTATE_CAPTURE`)
was built to measure the ceiling of eliminating interpreter overhead: direct `char`
comparisons and plain loops instead of `CharSet.contains()` dispatch, job-stack array
indirection, and generic NFA-state lookup. Verified byte-for-byte correct against
`java.util.Pattern` across 11 edge cases (anchors, MULTILINE re-anchoring, empty input,
false-keyword-prefix rejection). JMH (`:reggie-benchmark:jmh`, JDK 21):

| engine | find (ops/ms) | capture (ops/ms) |
|---|---:|---:|
| hand-written prototype | 92,234 | 92,948 |
| JDK `Pattern` | 20,995 | 20,843 |
| Reggie `BITSTATE_CAPTURE` (interpreter) | 1,809 | 1,668 |
| RE2J | 711 | 345 |

The prototype is ~51x faster than the current interpreter and ~4.4x faster than JDK on this
pattern. Since the prototype uses the *same* algorithmic shape as `BitStateMatcher` (locate
the mandatory prefix, one optional-group backtrack point, then a dotall tail) with no
asymptotic advantage, the gap is attributable to interpreter overhead: `CharSet` object
dispatch per character, `int[]` job-stack push/pop per state transition, and NFA-state
array indirection per step.

## 2a. Result: regression fixed and measured post-implementation

`debugPattern` on the `COMMAND` pattern confirms it now routes to `BITSTATE_BYTECODE`
(`PrefixGuardedScanInfo`) instead of `BITSTATE_CAPTURE`. Re-running the same
`IastRegexpBenchmark` COMMAND methods (`:reggie-benchmark:jmh`, JDK 21.0.10) across two
independent runs (varying fork/iteration counts) gave:

| engine | find (ops/ms) | capture (ops/ms) |
|---|---:|---:|
| Reggie `BITSTATE_BYTECODE` (this doc's fix) | ~59,000–69,000 | ~26,000–33,000 |
| JDK `Pattern` | ~11,000–22,000 | ~14,000–21,000 |
| RE2J | ~460–700 | ~150–340 |
| *(pre-fix)* Reggie `BITSTATE_CAPTURE` (§2 baseline) | 1,809 | 1,668 |

The JDK/RE2J columns show run-to-run spread (single-machine JMH runs, no isolation
controls) wide enough that the exact figures shouldn't be treated as stable benchmark
baselines — but the headline result is unambiguous and consistent across both runs: the
~11-12x regression against JDK documented in §2 is gone. Reggie now beats JDK by roughly
3-5x on find and 1.3-1.9x on capture, and is two orders of magnitude ahead of the
interpreter it replaced.

**Caveat (load-bearing for the scope decision in §4):** this pattern's backtracking is
shallow — exactly one optional-group fallback, no nested or combinatorial choice points.
Patterns with deeper alternation/backtracking structure were not measured and are not
assumed to see the same multiplier; a general bytecode generator would need to reproduce
the interpreter's explicit backtrack stack and undo-log in generated form, which is a
materially harder codegen problem than `OnePassBytecodeGenerator`'s straight-line
switch-per-state (OnePass has no choice points to encode at all).

## 3. What makes this harder than `OnePassBytecodeGenerator`

`OnePassBytecodeGenerator` works because OnePass-eligible patterns are unambiguous: at most
one valid transition per state, so codegen is a straight-line `switch`-per-state loop with
no backtracking. BitState exists precisely for ambiguous patterns, so a bytecode version
must additionally encode, per generated method:

- **Choice points.** Where the interpreter pushes competing jobs onto an explicit stack in
  priority order, generated code needs an equivalent — either recursive method calls (using
  the JVM call stack as the backtrack stack) or an explicit local/instance array mirroring
  `BitStateMatcher.stackA/B/C`.
- **Capture undo-log.** `BitStateMatcher` pushes a `RESTORE` job before overwriting a
  capture slot, so a failed branch's writes are undone on backtrack. Recursive-call codegen
  gets this for free (save the old value in a local before recursing, restore after the
  call returns false) — this is the strongest argument for choosing recursive-descent
  codegen over explicit-stack codegen for this generator specifically.
- **Visited-bitmap / ReDoS bound — NOT required at the ≤1-choice-point tier (revised).**
  The interpreter's visited bitmap exists to bound work when the *same* `(stateId, pos)`
  pair is reachable via multiple different combinations of backtracking decisions — which
  only happens when choice points nest or repeat (inside a `*`/`+` loop, or two independent
  optionals compounding). Eligibility for this generator (§4) is capped at exactly one
  choice point, not nested inside any repeating quantifier, so no `(stateId, pos)` pair can
  ever be revisited: at most two straight-line passes are attempted (with the optional
  element, then without), bounding total work to `O(2 × pattern length)` at *compile* time,
  not run time. Adding a bitmap here would reintroduce the exact dynamic dispatch overhead
  this generator exists to eliminate, for no safety benefit — there is nothing unsafe to
  bound.
- **Budget fallback — NOT required, for the same reason.** The interpreter's
  `exceedsBudget(spanLen)`/PikeVM-delegation exists to catch visited-bitmap blowup. Since
  the ≤1-choice-point tier has no such blowup by construction (bounded compile-time work,
  independent of input length), there is no budget to exceed and no fallback path needed.
  Generated classes for this tier are pure "always-native" classes, the same shape as
  `ONEPASS_NFA` classes — this is *only* true because eligibility is capped this tightly;
  it would need to be revisited if the choice-point bound is ever widened (§4, "Open
  questions").
- **Mandatory (non-optional) quantifiers still need a narrow backtrack-free proof.** A
  quantifier with `min ≥ 1` (e.g. `\S+`) is not a "choice point" under §4's semantic
  definition (it has no distinct branches), but it can still in principle need to give back
  consumed characters if what follows it would otherwise fail to match. The prototype's
  `\S+` never needs to because everything after it (`\s*` then an unconstrained dotall
  `.*` capture) can absorb any remaining input regardless of how much `\S+` consumed. This
  "the suffix always succeeds" property is not decidable from local AST shape alone in
  general; rather than general dataflow analysis, eligibility treats it as a narrow
  syntactic special case: a mandatory quantifier is accepted as backtrack-free only when
  everything following it is optional whitespace/anchors terminating in an unconstrained
  trailing `.*`-style capture (the exact COMMAND shape). Anything else declines eligibility
  and falls back to the interpreter.

None of this makes a bytecode generator infeasible — it makes it a different, larger shape
than `OnePassBytecodeGenerator`, closer in spirit to generating the interpreter's own
control flow than to eliminating it.

## 4. Proposed scope: narrow, opt-in, falls back to the interpreter

Given §3, this proposal does **not** replace `BitStateMatcher` or attempt to
bytecode-generate every `BITSTATE_CAPTURE`-eligible pattern. It adds a second, stricter
eligibility tier:

```
OnePass (unambiguous)                                  — existing, unchanged
  ↓ not one-pass
BitState-bytecode (ambiguous, SHALLOW backtracking)    — NEW, this proposal
  ↓ not shallow-eligible
BitState-interpreter (ambiguous, bounded budget)       — existing, unchanged
  ↓ budget exceeded
PikeVM (ambiguous, any size)                           — existing, unchanged
```

"Shallow backtracking" eligibility (a new, stricter predicate layered on top of the
existing `isBitStateEligible`) is deliberately conservative for a first cut: **at most one
semantic choice point** on any path, where a choice point is counted only when the
take-or-skip (or which-alternative) decision is *continuation-dependent* — its correctness
can only be confirmed by whether the rest of the pattern subsequently matches, so it can only
be resolved by attempting a full downstream match and retrying on failure. An alternation
whose branches are distinguishable by local lookahead and lead to a shared continuation
(e.g. `sudo|doas`, disjoint first characters, identical code after either matches) is **not**
a choice point under this definition — it compiles to a plain sequential
try-this-then-that with no retry-the-continuation step, exactly as the hand-written
prototype's `matchKeyword` does. This is intentionally narrower than "everything BitState
currently handles" — widening it is future work, done incrementally as each additional
shape is measured, not assumed.

Because eligibility is capped at exactly one continuation-dependent choice point, and that
choice point may not sit inside any repeating quantifier, codegen does not need general
recursive backtracking, an undo-log, a visited bitmap, or a dynamic PikeVM-budget fallback
(§3, revised) — the generated shape is two independent straight-line methods (one per
branch of the single choice) selected by a single `if`, matching the hand-written
prototype's structure exactly. This is a stricter and simpler codegen target than the
original recursive-descent sketch; if the choice-point bound is ever widened beyond one
(future work, not this proposal), the recursive-descent-with-undo-log approach and the
visited-bitmap/budget-fallback requirements from §3 would need to be reinstated.

## 4a. Scope-narrowing decision made during implementation

While implementing §5 step 2, composing two independently bytecode-generated sub-matchers
generically (split an arbitrary eligible NFA at its one choice point, generate each branch,
glue them with correct Perl-priority semantics for unanchored `find`) turned out to need a
new sub-NFA-splitting/composition layer, not just "two straight-line methods and an `if`"
as §4 assumed — `ReggieMatcher`'s public API has no "attempt an anchored match starting at
exactly this position" hook that two separately-generated `OnePassBytecodeGenerator`
instances could share to interleave branch priority per candidate start position during an
unanchored scan.

**Decision: defer the general NFA-splitting/composition layer. The first implementation
recognizes one specific structural family directly from the AST** — the exact shape
validated by the prototype and this doc's motivating evidence (§2): an optional,
lookahead-disjoint literal-or-charclass prefix, a mandatory backtrack-free affix (§3's
narrow syntactic special case), and an unconstrained trailing `.*`-style capture. This is
implemented as a dedicated `PatternAnalyzer` detector (`detectXxx(ast)` returning a
descriptor `PatternInfo`, e.g. `PrefixGuardedScanInfo`) in the same style as the existing
`detectStatelessPattern`/`StatelessPatternInfo` and `detectCountingGlushkov`/
`CountingGlushkovInfo` pairs already in `PatternAnalyzer` — not a generic NFA-consuming
generator like `OnePassBytecodeGenerator`. The detector extracts literals/char-sets from
the AST (data-driven), so it covers any pattern matching this *shape*, not just the literal
COMMAND pattern string — but it declines (falls back to the `BITSTATE_CAPTURE` interpreter)
for any other ≤1-choice-point pattern shape, including ones that would satisfy §4's
semantic choice-point definition but aren't structured as
`prefix? affix (.* capture)`.

**What this means going forward:** "general ≤1-choice-point NFA bytecode generation" (the
NFA-splitting/composition layer described in §4/§5 step 2 originally) is explicitly
deferred future work, not implemented in this pass. Widening coverage beyond the recognized
structural family should be done by adding new `detectXxx`/`XxxInfo` pairs for additional
*measured* shapes (mirroring this one), or by eventually building the general
splitting/composition layer — whichever a future session decides, informed by which
additional shapes actually show up in real IAST/production patterns. This mirrors how
`PatternAnalyzer` already handles most of its fast-path tiers (`STATELESS_LOOP`,
`COUNTING_GLUSHKOV`, literal alternation, etc.): one detector per recognized shape, not one
generic engine for the whole eligibility class.

## 5. Concrete plan (as narrowed by §4a)

1. **`PatternAnalyzer`** (`reggie-codegen/.../analysis/PatternAnalyzer.java`): add
   `detectPrefixGuardedScan(ast)` returning a `PrefixGuardedScanInfo implements PatternInfo`
   descriptor or `null`, in the same style as `detectStatelessPattern`/
   `StatelessPatternInfo` and `detectCountingGlushkov`/`CountingGlushkovInfo`. Wire into
   `routeBitState` as a second substitution: when `result.strategy == BITSTATE_CAPTURE` and
   `detectPrefixGuardedScan(ast)` returns non-null, substitute `BITSTATE_BYTECODE` with that
   descriptor as `patternInfo`.
2. **`BitStateBytecodeGenerator`** (new,
   `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/BitStateBytecodeGenerator.java`),
   consumes a `PrefixGuardedScanInfo` descriptor (not an `NFA`) and emits the two
   straight-line branches (with-prefix, without-prefix) directly, mirroring
   `CommandPatternPrototype`'s logic but data-driven from the descriptor's extracted
   literals/char-sets. No general NFA-splitting/composition layer (deferred, §4a). Reuses
   `BytecodeUtil.pushInt`, `LocalVarAllocator`, and `generateCharSetCheck` from the existing
   codegen package.
3. **`RuntimeCompiler`**: add a `case BITSTATE_BYTECODE:` arm in the `generateBytecode`
   switch (currently `BITSTATE_CAPTURE` never reaches this switch — see research notes,
   §"RuntimeCompiler" below). No fallback `PikeVMMatcher` field is needed (§3, revised) —
   generated classes for this tier are pure always-native classes, the same shape as
   `ONEPASS_NFA`/`STATELESS_LOOP` classes.
4. **Dual-path rule** (AGENTS.md #2): mirror the same routing/generation change in
   `ReggieMatcherBytecodeGenerator.java` (`reggie-processor`).
5. **`StructuralHash`** (AGENTS.md #3): add `PrefixGuardedScanInfo`'s fields if the
   structural-hash class-cache path is used for this strategy.
6. **Tests**: correctness parity suite against COMMAND and at least one structurally
   similar synthetic pattern (different literals/char-classes) to confirm the detector is
   genuinely data-driven and not COMMAND-string-specific, plus negative tests confirming
   patterns just outside the recognized shape correctly decline to `BITSTATE_CAPTURE`
   (interpreter) rather than misfiring. No ReDoS/budget test is needed for this tier (§3,
   revised: work is compile-time-bounded, not input-dependent).
7. **Benchmark**: extend `IastRegexpBenchmark` with `bitstateBytecodeXxx` methods (as the
   prototype's `protoCommandFind/Capture` did) to measure the real generator against the
   prototype ceiling, JDK, interpreter, and RE2J.

## 6. Open questions for reviewer sign-off before implementation

- Is the semantic (continuation-dependent) choice-point definition (§4) correctly and
  completely specified for a mechanical AST check, or are there pattern shapes where
  "distinguishable by local lookahead with a shared continuation" is ambiguous to decide
  syntactically and needs a conservative decline-to-generate default?
- Is the narrow syntactic special-case for backtrack-free mandatory quantifiers (§3: "must
  be followed only by optional whitespace/anchors terminating in an unconstrained trailing
  `.*`-style capture") too narrow, too broad, or exactly right for a first cut? This is the
  main correctness-risk surface since it's a heuristic, not a general proof.
- Should `BITSTATE_BYTECODE` reuse `BITSTATE_NFA_CACHE`'s pattern-keyed caching model, or
  move to the structural-hash class-cache model used by other bytecode-backed strategies
  (§"RuntimeCompiler" research notes) — the latter is more consistent with how every other
  bytecode strategy is cached today.
