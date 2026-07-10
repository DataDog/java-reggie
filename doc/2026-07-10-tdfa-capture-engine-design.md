# TDFA (tagged-DFA) capture engine — design

Status: **draft / not started**. This is a forward design for a multi-week investment, written
to support a go/no-go decision. No code changes are proposed alongside this document.

## 1. Problem

`BitStateMatcher` (the bounded-backtracking DFS capture engine) falls back to `PikeVMMatcher`
(plain PikeVM thread simulation) whenever `stateCount * (spanLen + 1) > BUDGET_CELLS`
(`BitStateMatcher.BUDGET_CELLS = 1 << 18`). For `SQL_ANSI` (97 NFA states) this threshold is
~2700 input characters; the LONG-scale case of the `IastRegexpBenchmark` `SqlAnsiFind`/
`SqlMysqlFind`/`SqlPostgresqlFind` benchmarks (`@Param("scale")`, added in
`reggie-benchmark/.../IastRegexpBenchmark.java` on this branch, `perf/bitstate-hot-loop-flattening`
commit `a5287c7` — the scale parameter does not exist on `main`) exceeds it, so every `find()` at
that scale is served entirely by `PikeVMMatcher`'s full thread simulation.

Measured effect (full SHORT→MEDIUM→LONG run, 53 methods × 3 scales, all 42 scaled inputs
independently verified to match/not-match as intended before trusting the throughput numbers):
LONG-scale input for these three benchmarks is `"col_x = col_y AND ".repeat(2000)` plus a fixed
~75-char match/no-match suffix, ≈37KB. reggie/RE2J on `find()` drops from ~1.6x ahead at
SHORT/MEDIUM to a genuine loss at LONG:

| benchmark | reggie/jdk SHORT→MED→LONG | reggie/re2j SHORT→MED→LONG |
|---|---|---|
| `SqlAnsiFind` | 0.63x → 0.62x → 0.33x | 1.63x → 1.64x → **0.86x** |
| `SqlMysqlFind` | 0.66x → 0.60x → 0.31x | 1.75x → 1.67x → **0.84x** |
| `SqlPostgresqlFind` | 0.51x → 0.45x → 0.23x | 1.73x → 1.68x → **0.86x** |

The MEDIUM→LONG degradation is real but ~2x, not the ~2000x an earlier draft of this section
claimed — that figure had no benchmark backing it (caught by adversarial review; struck here,
no replacement source exists) and is retracted. The correct, evidence-backed claim is narrower:
these three patterns cross from *winning* against RE2J to *losing* against it specifically between
MEDIUM and LONG scale, consistent with LONG being the only scale that crosses the `BUDGET_CELLS`
threshold above.

**Open, unattributed observation from the same run** (not folded into the analysis below, no
root cause established yet): `LdapFind`, `UrlAuthFind`, and `UrlAuthCapture` degrade far worse
over the same SHORT→LONG range — down to 0.02x–0.09x vs JDK at LONG (a 10–50x loss, not SQL's
~3x), and `UrlQueryFind` drops to 0.93x vs RE2J at LONG (SQL's `Find` cases don't cross 1.0x until
LONG either). Whether this is the same `BUDGET_CELLS` mechanism or a different bottleneck — LDAP's
`LITERAL` group and the URL `AUTHORITY` branch are both "matched-region growth" inputs, unlike
SQL's "scan-prefix growth" — is unconfirmed and out of scope for this document; flagged here so it
isn't lost.

Async-profiler confirmed the cost is real interpreter tax inside `PikeVMMatcher`'s own
thread-simulation loop (per-character subset advance + capture-array bookkeeping across all live
threads), not allocation or GC. Two cheaper mitigations were spiked and rejected before this
document:

- **Fixed-size windowed `BitStateMatcher` scan** (sequential windows sized to fit `BUDGET_CELLS`,
  each an independent `search()` call). 2.9–3.4x real speedup, but **unsound**: `search()`'s
  consuming-leaf bound hard-caps consumption at the window boundary, so a match whose content
  (e.g. `SQL_ANSI`'s unbounded-width `'...'` literal or `/*...*/` comment) spans wider than one
  window is silently missed. Rejected per the "only proceed if valid" gate.
- **Dynamic window** (extend the window only while some thread is provably still alive). Requires
  knowing *which* start position is responsible for keeping the DFA state alive at the boundary —
  but `RejectDfaFactory`'s (and `PikeVMMatcher.findStep`'s) shared self-anchoring design discards
  that attribution by construction: `RejectDfaFactory.build()`'s step function unions a fresh
  `startAll` closure into *every* step's result unconditionally (`RejectDfaFactory.java`, the
  `union(tc, startAll)` line), not only on death. This is why `LazyDFACache.findFrom`/`findEnd`
  degrade to non-localizing behavior on patterns like `SQL_ANSI` whose broad alternation (7+
  branches covering common characters) keeps some thread alive almost everywhere in prose-like
  filler — the merged DFA state essentially never reaches a genuine `DEAD` transition. Recovering
  per-candidate attribution to make the window boundary decision soundly is exactly tag/priority
  tracking — i.e. the same investment as a TDFA, not a smaller one.
- **A non-self-anchoring find-DFA for the anchored case**, extending `PikeVMMatcher.findDfaEligible`
  (currently restricted to patterns whose only anchors are `START`/`STRING_START`/
  `START_MULTILINE`, handled via the mid-line/after-newline reinject-closure split in the
  constructor around `PikeVMMatcher.java:255-282`) to end anchors and `\b`. Analysis: naively
  dropping the every-step reinject and relying only on `LazyDFACache.findFrom`'s restart-on-`DEAD`
  is **incorrect** — restart-on-`DEAD` only tries a new start at wherever the *previous* attempt
  died, silently skipping intermediate candidate starts that could independently match. A
  single-scalar "leftmost live start" tag fixes that correctness gap but reintroduces the same
  state-space/cache-reuse tension the tag machinery below has to solve anyway, for none of the
  capture payoff. Not pursued as a separate, smaller project — folded into this design instead.

Net: there is no cheap fix left on the table. The next tier is a genuinely tagged automaton.

## 2. Goals / non-goals

**Goals**
- Eliminate the fallback-cliff class of regression: give `find()`/`findFrom()`/`match()` an O(n)
  (or near-O(n), budget-bounded) path with real capture-group boundaries, for the pattern class
  BitState/PikeVM already serve natively (no backrefs, no lookaround assertions, no atomic
  groups — the existing `noAssertionsOrBackrefs`/`findDfaEligible`-style eligibility boundary).
- Preserve today's leftmost-first (Perl/PCRE greedy) priority semantics exactly, for the patterns
  TDFA is eligible for — this is the same semantics `PikeVMMatcher`'s thread ordering already
  implements and that `CorrectnessTest` already asserts against JDK `Pattern`. This is explicitly
  the *wrong* semantics for `usePosixLastMatch` patterns (see §5's eligibility note) — those must
  stay excluded, not be given leftmost-first tags.
- Keep the existing fallback architecture pattern: eligible-but-too-large inputs degrade
  gracefully to `PikeVMMatcher`, exactly as `BitStateMatcher` degrades today. No correctness
  regression is acceptable; performance regression on ineligible/oversized inputs is acceptable
  (status quo).

**Non-goals**
- Do not attempt backreferences, lookaround assertions, or atomic groups in the TDFA itself —
  those stay on `PikeVMMatcher`/`BackrefBacktrackMatcher` exactly as today.
- Do not change public API, `Reggie.compile()` semantics, or bytecode generation strategy
  selection in this phase. This is a `reggie-runtime` matcher-internal change, mirroring how
  `BitStateMatcher` itself was introduced as an additional native strategy alongside PikeVM.

## 3. Background: the two existing self-anchoring DFA builders

Both `RejectDfaFactory.build()` and `PikeVMMatcher`'s boolean `findDfa`/`matchesDfa` construction
share the same shape: subset-construct over the NFA via `LazyDFACache`, with a step function that
unions in a fresh start-closure at every character (the "self-anchoring" trick that makes one scan
implicitly try every start position at once, in O(n) instead of the O(n²) that trying each start
independently would cost). This buys correctness and speed for the *boolean* question ("does a
match exist from here"), but the union at merge time is exactly what erases which candidate start
produced which surviving thread — there is no way to ask a self-anchoring DFA "which start is
still alive" without also tracking that as part of the state, which is the core idea below.

`PikeVMMatcher.findDfaEligible` already demonstrates the one case where *no* tag tracking is
needed: `START`/`STRING_START`/`START_MULTILINE` anchors are **past-context-only** — whether they
fire at a position depends only on whether the previous character was `\n` (or it's position 0),
which the step function already knows locally without threading any extra state. That's why they
can be handled by splitting the reinject closure into "mid-line" vs "after-newline" variants
(`reinjectClosureIds` / `reinjectAfterNlClosureIds`) instead of needing per-position tags. End
anchors (`$`, `\Z`, `\z`, `END_MULTILINE`) and `\b` don't have this property in general — `\b`
needs one character of lookahead (feasible, symmetric to lookbehind), but "is this the true end of
string" is a property of `regionEnd`, which the *caller* (`search()`/`findPosFrom()`) already
threads separately from the scan bound (see `BitStateMatcher.search`'s `regionEnd` parameter,
added specifically so a narrowed scan bound never corrupts `$`/`\z`/`\b`/`END_MULTILINE` checks).
The TDFA should keep that same separation: anchor *evaluation* stays a per-step/per-accept check
against the true end of input, not something baked into subset-construction identity.

## 4. What a TDFA adds: tags and registers

Standard reference: Ville Laurikari, *NFAs with Tagged Transitions, their Conversion to Deterministic
Automata and Application to Regular Expression Recognition* (2000) — the algorithm RE2/RE2J's
authors describe but did not implement (confirmed via RE2J 1.8 source inspection this session:
RE2J has no BitState/OnePass/TDFA tier, only a plain per-thread PikeVM `Machine.java`). This is new
ground *relative to RE2J*, but **not relative to this codebase**: `PatternAnalyzer` already has an
unrelated "Tagged DFA" mechanism — `MatchingStrategyResult.useTaggedDFA`
(`PatternAnalyzer.java:3420`) drives `DFAUnrolledBytecodeGenerator.generateTaggedDFAMatching`
(`DFAUnrolledBytecodeGenerator.java:1557`) for the `DFA_UNROLLED_WITH_GROUPS` strategy
(`PatternAnalyzer.java:3376`, "<20 states — unrolled with inline group tracking"), and is hashed
into `StructuralHash.java:85/135`. That mechanism solves a narrower, different problem:
compile-time-unrolled bytecode with inline group tracking and priority-cut semantics, bounded to
small (<20-state) explicit DFAs — it does not do Laurikari-style register-based determinization
over arbitrarily large NFAs at runtime, which is what this design proposes. The two are
architecturally distinct, but the shared "Tagged DFA" vocabulary is a real collision risk during
implementation and code review — this is why the classes proposed below are named `Laurikari*`
rather than `Tagged*`, to keep them unambiguously distinct from `useTaggedDFA`/
`DFA_UNROLLED_WITH_GROUPS` in searches and reviews.

**Tags.** Each capture-group boundary (`NFA.NFAState.enterGroup`/`exitGroup`, nullable `Integer`
group-number fields already present in the Thompson construction) becomes a *tag*: a marker that
must record the current input position whenever the NFA transitions through it. `groupCount`
explicit capture groups → `2 * (groupCount + 1)` tags (open + close per group, plus the implicit
whole-match group 0) — the same slot count `PikeVMMatcher.java:192`'s
`int slotCount = 2 * (groupCount + 1)` already uses for `clistCaptures`/`nlistCaptures`.

**Registers.** A tagged DFA state doesn't just carry an NFA-state subset (like `StateSetKey`
today); it carries, per NFA state in the subset, a mapping from tag id → *register* (an integer
naming a memory cell that holds a recorded position or "unset"). Transitions carry register
*operations*: `copy(r_dst, r_src)`, `set-to-current-pos(r_dst)`, or `set-unset(r_dst)`. Concretely
this generalizes what `PikeVMMatcher.clistCaptures[stateId]` already does at runtime (an
`int[stateCount][slotCount]` capture array copied thread-to-thread on every step) into something
computed and cached **once per (DFA-state, char) transition**, ahead of time, instead of being
recomputed by walking live threads on every character of every search.

**Priority/conflict resolution.** When two NFA states carrying *different* register mappings merge
into the same subset during determinization (i.e., two "threads" reach the same NFA state), the
existing `PikeVMMatcher` thread-list ordering already defines which one wins: earlier-added threads
have priority (this is exactly the leftmost-first / greedy-first semantics `clistIds`/`nlistIds`
maintain today by appending in priority order and never re-adding a state already in the list —
see `inClist`/`inNlist` guards). The TDFA's determinization must replicate that same ordering rule
at *build* time: when constructing the subset for a given (state, char) transition, process
predecessor NFA states in the same priority order PikeVM's `addThread` does, and when a target NFA
state is already in the new subset, discard the lower-priority arrival's register mapping instead
of merging it. This is the same rule, moved from run time to build time — not a new semantics
decision.

## 5. Architecture sketch

New sibling classes to the existing DFA machinery, following the same shape `LazyDFACache` /
`RejectDfaFactory` already establish:

- `LaurikariDFACache` (sibling to `LazyDFACache`): lazily-materialized, subset-constructed DFA whose
  states are `(NFA-subset, register-mapping)` pairs, keyed for interning the same way
  `StateSetKey` interns plain subsets today. Bounded at a cap (mirroring
  `LazyDFACache.DEFAULT_CAP`/`BitStateMatcher.BUDGET_CELLS`); on overflow, **freeze and fall back**
  — exactly the existing `FALLBACK` sentinel / `frozen` field pattern in `LazyDFACache`, just handed
  off to `PikeVMMatcher` instead of to raw NFA stepping.
- `LaurikariNfaStep`: like `NfaStep`, but returns both the next state-id set and the register
  operations to apply, so the caller can update its own register-file array.
- A new matcher, e.g. `LaurikariDfaMatcher extends ReggieMatcher`, or an extension of
  `BitStateMatcher`'s existing "NATIVE" role — parallel to how `BitStateMatcher` today decides
  per-call whether it's within budget and only asks `PikeVMMatcher` for oversized/ineligible
  inputs. Eligibility gate: reuse `noAssertionsOrBackrefs`-style checks (no backrefs, no lookaround,
  no atomic groups) — the same class of pattern `RejectDfaFactory`/`findDfaEligible` already carve
  out, extended (per §3) to cover end anchors and `\b` via per-step/accept-time checks against the
  true `regionEnd`, not via subset-identity fracturing — **and additionally excluding
  `usePosixLastMatch` patterns** (see the eligibility note at the end of this list).

**Relationship to `HybridMatcher`.** `RuntimeCompiler.shouldUseHybrid`/`compileHybrid`
(`RuntimeCompiler.java:763-841`) already builds a "DFA for fast matching, NFA for group extraction"
matcher today, for `MatchingStrategy.OPTIMIZED_NFA`-eligible patterns and for
`usePosixLastMatch` patterns specifically. That is the same top-level shape TDFA's value
proposition in §4 is pitched on (replacing per-thread capture-array copying with a cheaper,
precomputed mechanism) — but `HybridMatcher`'s NFA extraction pass only runs over the *already
DFA-bounded matched substring*, so its cost is independent of haystack size; it is not exposed to
the `BUDGET_CELLS` fallback-cliff this design exists to fix. `PatternAnalyzer` already routes
capture-ambiguous/`usePosixLastMatch` patterns to `HybridMatcher` specifically *because* the
DFA-then-JDK-NFA extraction path gives correct POSIX-last-match group spans where leftmost-first
NFA priority (what TDFA replicates, per §4) does not — so TDFA must not compete for that pattern
class; see the eligibility exclusion above. For the general capturing patterns `HybridMatcher`
already serves cheaply, TDFA offers no motivating win (Hybrid's cost model is already
haystack-size-independent) — TDFA's target is specifically the fallback-cliff class in §1, which is
disjoint from what `HybridMatcher` handles today. Phase 2 (§7) should confirm this stays disjoint
in `PatternAnalyzer`'s eventual strategy table rather than assume it.

**Eligibility gate must also exclude `usePosixLastMatch` patterns.** `PatternAnalyzer` sets
`usePosixLastMatch` (`PatternAnalyzer.java:3426`, `needsPosixSemantics` driven purely by AST shape —
capturing groups inside a repeating quantifier, e.g. `(a|a)+`) independently of the
backref/lookaround/atomic-group checks `findDfaEligible`/`noAssertionsOrBackrefs` perform; grep
confirms neither method references it. A capture-ambiguous pattern like `(a|a)+` would pass the
gate as literally described above and get routed through leftmost-first tagged determinization,
reproducing the exact wrong-group-span bug `usePosixLastMatch`/`HybridMatcher` routing exists to
avoid — and critically, §6's proposed oracle (`PikeVMMatcher`) is *also* leftmost-first, so
differential fuzzing against it would not catch this specific divergence either. The eligibility
gate must check `usePosixLastMatch` explicitly, not rely on the backref/lookaround/atomic-group
checks to cover it, and any fuzz corpus for this pattern class needs `java.util.regex` (not
`PikeVMMatcher`) as the oracle.

**Register-file management at runtime.** Once a `LaurikariDFACache` transition is resolved, applying
its register ops to a small `int[]` register file per active scan is O(registers-touched-this-step),
not O(live-threads) — this is the actual performance win: it replaces PikeVM's per-character,
per-thread capture-array copy with a per-character, precomputed, DFA-transition-indexed op list.

**State-space bounding.** Register mappings are structurally bounded the same way plain subsets
are (finitely many NFA states × finitely many distinct "which predecessor thread's mapping won"
outcomes per merge), so in principle the reachable `(subset, mapping)` space is still finite, but
it can be much larger than the plain-subset space `LazyDFACache` already builds for the same
pattern — this is the paper's well-known practical risk, and needs the same lazy-materialize +
cap + freeze-to-fallback discipline `LazyDFACache` already uses, not upfront full determinization.

**Explicit guardrail: no post-hoc minimization by subset-equivalence.** The interning described
above (hash-consing exact `(subset, mapping)` state identity during determinization) is safe —
it is what determinization already is. Do **not** additionally minimize the resulting automaton by
classical Hopcroft/Moore-style equivalence (merging two states whose *future* NFA-subset behavior
is identical): this is a well-known Laurikari-family correctness pitfall — two states can agree on
every future transition while holding different live register mappings, and merging them silently
corrupts which register produced which capture. If state-space size ever needs a second lever
beyond cap-and-fallback, the correct technique is *operation-aware* minimization/register
allocation (already named, without being resolved, in `doc/2026-07-05-reggie-vs-re2j-algorithm-research.md`
item 2) — not naive subset-equivalence. This is called out explicitly here because nothing in the
existing `LazyDFACache`/`RejectDfaFactory` code this design models itself on performs any
minimization at all (they intern by exact subset identity only), so there is no established local
precedent to lean on if a future implementer reaches for it as a blowup mitigation.

## 6. Correctness strategy

- **Ground truth**: `PikeVMMatcher`'s existing thread simulation (already checked against JDK
  `Pattern` via `CorrectnessTest`) is the oracle. Every `LaurikariDFACache`-served `find`/`match`/
  `findFrom` call must be differentially fuzzed against the same call on `PikeVMMatcher` over the
  existing fuzz corpus infrastructure — this work must introduce zero *net-new* divergences on top
  of whatever `AlgorithmicFuzzTest.KNOWN_FINDINGS_BUDGET` currently is (re-check the live constant;
  as of this writing it is 28, not 0 — the `project_reggie_prod_readiness` memory note's "zero known
  divergences" claim is stale relative to the current fuzz window) before any pattern class is
  allowed to route to the new engine.
- **Priority/merge correctness is the highest-risk correctness surface**, not the plain-subset
  transitions (those are already proven by `LazyDFACache`/`RejectDfaFactory`'s existing tests).
  Needs dedicated adversarial cases: nested groups with empty-body iterations (the same class
  `groupBodyNullable`/trailing-empty-iteration rebind logic in `PikeVMMatcher` exists to handle
  correctly today), unrolled-quantifier multi-anchor paths (`clistViaMultipleAnchors`'s reason for
  existing), and alternation branches with shared prefixes but different capture groups.
- **Anchor correctness**: end-anchor/`\b` checks must be verified identical to
  `PikeVMMatcher.checkAnchor`'s existing switch-case semantics, using the same `regionEnd`-vs-scan-
  bound separation already fixed once for `BitStateMatcher.search` this session — do not
  re-introduce that class of bug in the new engine.

## 7. Phased rollout (de-risking the most uncertain parts first)

**Phase 0 — spike, boolean/localization-only tag.** Build a `LaurikariDFACache` variant with exactly
*one* tag: "leftmost live start position" (no per-group captures). This is the "single min-start
tag" middle ground identified during the dynamic-window analysis — a strict, much smaller subset
of the full design (trivial conflict resolution: smaller start always wins, no group-ambiguity
logic) that directly fixes the motivating fallback-cliff problem (real localization for `find()`
on `SQL_ANSI`-shaped patterns) even if full capture tags are never built. Success criteria: (a)
correct localization on the exact adversarial case that broke the windowed-scan prototype
(unbounded-width literal/comment spanning what would have been a window boundary), (b) state count
for `SQL_ANSI`-class patterns stays within a cache budget comparable to `LazyDFACache.DEFAULT_CAP`,
(c) measurable win over the current full-`PikeVMMatcher`-punt on the LONG-scale IAST benchmarks.

**Phase 0 passing is necessary but NOT sufficient evidence for Phase 1's feasibility.** With one
tag under a total order (smaller start always wins), every register-merge has exactly one possible
outcome — the state-space dimension §5 names as the actual risk driver ("finitely many... outcomes
per merge") is pinned at 1 in this spike. Phase 1's real risk comes from `2 * (groupCount + 1)`
*independent* open/close tags whose winning predecessor can differ tag-by-tag across nesting,
alternation, and quantifier unrolling — a thread can be "ahead" on one group's tag and "behind" on
another simultaneously, which does not collapse via a total order the way a single scalar does.
Phase 0 cannot exercise that combinatorial mechanism at all, so its success measures only the
cap-and-fallback plumbing, not the actual multi-tag blowup risk.

**Phase 0.5 — intermediate checkpoint, 2-3 independent tags.** Before committing to the full
`2 * (groupCount + 1)`-tag Phase 1, spike a small, fixed number of independent tags (e.g. two
nested groups, or one group inside a quantifier) specifically to exercise cross-tag merge conflicts
— the mechanism Phase 0 cannot. This is the cheapest available signal on whether the real
multi-tag state-space blowup is tractable before the multi-week Phase 1 investment. Only proceed to
Phase 1 if this checkpoint's state-space growth is still within a workable budget.

**Phase 1 — full capture tags**, only if Phase 0 *and* Phase 0.5 succeed: extend to
`2 * (groupCount + 1)` tags per the full design in §4, gated on the eligibility boundary in §5
(including the `usePosixLastMatch` exclusion), differentially fuzzed per §6.

**Phase 2 — integration**: wire into `BitStateMatcher`'s existing decision point (budget check →
fallback selection) as a new, preferred-when-eligible tier, or as a new strategy in
`PatternAnalyzer`'s strategy selection — decide based on Phase 1 findings about how cleanly it
composes with the existing bytecode-generation dual-path rule (`RuntimeCompiler.java` /
`ReggieMatcherBytecodeGenerator.java`) if compile-time codegen support is later desired. Runtime-
only integration (skip bytecode generation) is the lower-risk default for this phase.

## 8. Effort and risk

This is a multi-week R&D effort, not a spike-sized change — comparable in scope to the original
`BitStateMatcher` capture-engine build (shipped in PR #94, 2026-07-07) but with a genuinely novel
determinization algorithm (subset construction is well-understood in this codebase; tagged subset
construction with priority-ordered register-merge is not) rather than an adaptation of an existing
technique. Key risks:

| Risk | Mitigation |
|---|---|
| Tagged state-space blowup makes the cache unusable for real patterns | Phase 0.5's multi-tag checkpoint measures this — Phase 0 alone does NOT, since its single total-ordered tag can't exercise cross-tag merge conflicts (§7) |
| Priority/merge conflict resolution subtly diverges from PikeVM's leftmost-first semantics on some AST shape | Differential fuzzing against `PikeVMMatcher` as oracle before any pattern class routes to the new engine (§6) |
| Anchor handling (end anchors, `\b`) reintroduces the `regionEnd`-vs-scan-bound bug class already found and fixed once this session | Explicit test mirroring `BitStateMatcher.search`'s `regionEnd` separation; do not bake anchor satisfaction into subset identity |
| `usePosixLastMatch` capture-ambiguous patterns (e.g. `(a|a)+`) silently get leftmost-first tags, reproducing the wrong-group-span bug `HybridMatcher` exists to avoid | Eligibility gate explicitly excludes `usePosixLastMatch` (§5); fuzz that pattern class against `java.util.regex`, not `PikeVMMatcher`, since PikeVMMatcher shares the same blind spot |
| Effort doesn't pay off if the fallback-cliff pattern class is rare in practice | Phase 0's success criteria are tied directly to the measured IAST LONG-scale regression, not a hypothetical |

## 9. Alternatives if this is not greenlit

Two cheaper mitigations remain available independent of this design, already identified this
session and not requiring any of the above:

1. **Raise `BUDGET_CELLS`** (`BitStateMatcher.java:61`) to push the fallback-cliff threshold
   further out. Cheap, does not eliminate the cliff, just documents/moves it — reasonable as an
   interim step regardless of whether TDFA is pursued.
2. **Accept and document the residual cliff** as a known, structural, PikeVM-simulation-overhead
   limitation (consistent with how `doc/agents-fallback-and-limitations.md` already documents
   other performance-only gaps).

Both are non-exclusive with pursuing this design later; neither requires reverting if TDFA work
starts.
