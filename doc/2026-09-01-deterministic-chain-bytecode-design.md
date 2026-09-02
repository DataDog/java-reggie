# Deterministic Chain Bytecode — Design

Family: `DETERMINISTIC_CHAIN_BYTECODE` — a new always-native, straight-line strategy for
alternations of deterministic literal/char-class chains (URL authority, LDAP literal, SQL
literal/tokenizer shapes), selected instead of `BITSTATE_CAPTURE`.

## 1. Motivation

Measured on workspace-jb (Temurin 21.0.12, `IastRegexpBenchmark`/`ComplexNFABenchmark`/
`StateExplosionBenchmark` suites, see `.investigations/pr121-l1l-followup`):

- 38 of 52 remaining JDK losses route to `BITSTATE_CAPTURE` and lose 0.02x–0.5x on the MATCH
  path. Root cause: the DFS interpreter costs ~10–15 ns/consumed char (two EXPAND-job stack
  round-trips per char; visited-set arithmetic, anchor/group/accept checks per pop) vs JDK's
  compiled `Curly` loops at ~0.3–0.9 ns/char.
- The greedy char-class loop fast path in `BitStateMatcher.search` (already implemented,
  `find-greedy-loop-fastpath-impl`) reduces this to ~5.4–6.4 ns/char (~2.3x) — the interpreter
  ceiling. JDK's 0.29 ns/char on long runs is memchr-class SIMD; no interpreter reaches it.
- The only route to parity is generated, per-char straight-line code — the same reason
  `BITSTATE_BYTECODE` (prefix-guarded scan, `doc/2026-07-08-bitstate-bytecode-generator-design.md`)
  beats the interpreter on its family.

`LaurikariDfaMatcher` (TDFA) was rejected: it is another table-driven interpreter (~2–5 ns/char
ceiling, same order as the fast-pathed BitState), not a route to parity.

## 2. Why the existing DFA strategies cannot take these patterns

- `DFA_UNROLLED_WITH_GROUPS` declines or mis-routes exactly these patterns for a structural
  reason: subset construction merges NFA states across alternation branches. For
  `^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&](…)=(…)` the DFA start-ish states merge the
  `^`-anchored branch with the unanchored branch — the DFA can no longer tell whether the
  `^` guard held on the path taken (`isAnchorConditionDiluted`), and merged paths can reach a
  state with different capture bindings (`isCaptureAmbiguous`). PikeVM/BitState recover by
  evaluating anchors/captures per thread; a DFA cannot.
- `OnePass` eligibility is stricter still: every Thompson `class+`/`class*` loop introduces a
  split state with two epsilon edges, so no capturing pattern with a quantified char class can
  ever be OnePass.
- A per-branch structural compilation keeps the `^` guard in the branch prologue (where it is
  evaluable against the scan position) and writes captures exactly once along a deterministic
  path (no path-merge ambiguity). That is this family.

## 3. Family grammar and admission rules

Top level: a single Chain, or an Alternation of 2..B Chains. Each Chain:

```
Chain    := ['^'] Elem+ ['$']                 -- ^ non-multiline only (v1)
Elem     := Lit | Class1 | GreedyLoop | LazyLoop | OptChain | LitAlternation | Capture
Lit      := string literal (1+ chars)
Class1   := single char-class consume (exactly one char)
GreedyLoop:= CharClass (min>=1 | min>=0), max=-1, greedy
LazyLoop := CharClass (min=0, max=-1), lazy
OptChain := non-capturing sub-chain with quantifier {0,1}     -- two-attempt, all-or-nothing
LitAlternation := alternation of Lits                          -- sequential tries, priority order
Capture  := capturing group around a contiguous sub-chain (start/end recorded)
```

Admission rules (each is a linearity or correctness requirement, checked at detection time):

1. **Greedy-loop disjointness (no give-back):** for every `GreedyLoop`, `loopClass ∩
   firstSet(restOfChain) = ∅`. Then the maximal run is the ONLY candidate — the generated loop
   scans once and the next element either matches at the run end or the chain fails. No
   backtracking, no give-back. (`[^@]+@`, `'[^']*'`, `\w+>` all qualify. `(?i)` folding happens in
   the parser, so overlaps introduced by case folding are visible to this check and decline the
   chain.)
2. **Bounded overlap only elsewhere:** elements whose first chars overlap the previous
   element's class are admitted only when the retries are a bounded constant
   (`LitAlternation` tries ≤ its size, `OptChain` is one with/without attempt pair, restart from
   the same position). Unbounded give-back shapes (`a+a`, `\d*\d+`) decline — they stay on
   BitState, which is linear and correct.
3. **LazyLoop** = scan loop: at each position try the tail (gated by a first-set bitmap of the
   tail's first chars), else advance. Tail tries fail in O(1) on the gate; each scan position is
   visited once → O(n) per try-start. `.*?` (ANY-class) is admitted; captures in the tail are
   rewritten by each try (last write wins — correct because the winning try rewrites start and
   end before returning).
4. **Chain min-width ≥ 1 for unanchored branches** (v1): empty-matching branches
   (`(a*b*c*d*e*)`) decline — BitState keeps them (already fast-pathed 2.3x).
5. **No backreferences, no lookaround, no `\b`** — hard declines (RecursiveDescent /
   HYBRID_DFA_LOOKAHEAD own those).
6. **`^` (non-multiline):** branch is tried only at absolute scan position 0.
   **`$`:** both modes — non-multiline = position must equal region end; multiline = position at
   end or immediately before `\n`.
7. **`firstSet` gates:** every branch's first-set (computed over its first elements) is an
   ASCII bitmap (`CharClassBitmap`); a branch whose first-set includes non-ASCII declines (v1 —
   the generated scan gate is an 8-bit-class check; extension later if a real pattern needs it).

## 4. Linear-time guarantee and the find() budget

Per-branch tries at scan positions can still repeat work: a failing try from position p may
consume a long deterministic run, and the next gate-hit position may re-consume the same run
(e.g. `//[^@]+@` against `/////////////////`). JDK is O(n²) on such inputs; Reggie must not be.

Every generated find-family method therefore carries a **work counter** and a **fallback**:

- The counter increments once per consumed char (scan positions + try consumption combined) and
  per branch-try start.
- Budget: `C × (regionLen + 1)` where `C = 4 + totalElemCount` (a pattern constant) — generous
  enough that any single linear pass with all tries never trips it, tight enough that quadratic
  behavior trips it early (within the first constant fraction of input).
- On overflow, the whole call is re-run by a lazily-constructed fallback matcher built by
  parsing the embedded `patternText` with `RegexParser` + `ThompsonBuilder` into a
  `PikeVMMatcher` (cold path, once per matcher instance, mirrors `BitStateMatcher`'s own
  budget→PikeVM delegation and keeps the guarantee: PikeVM is linear with per-thread anchor
  evaluation). Parse-at-overflow (instead of a constructor-injected fallback instance) keeps
  the generated class constructor signature identical across the runtime and annotation
  processor paths, so both generators emit byte-identical classes (dual-path rule).
- `matches()`/`match()` need no budget: a single anchored try is O(n) by construction.

## 5. Generated entry points and shape

Mirror the `BitStateBytecodeGenerator` surface: `matches`, `match`, `find`, `findFrom`,
`findMatch`, `findMatchFrom`, `findBoundsFrom` (+ `findMatchInto`/`findAll` inherited from
`ReggieMatcher` defaults). All straight-line + local loops, zero allocation on the match path
(capture slots are locals; a `MatchResult` is constructed only on success). Generated class
lives in package `com.datadoghq.reggie.runtime` (so the package-private `PikeVMMatcher`
fallback is reachable), named `ReggieMatcher$<structural hash suffix>` like the other
strategies.

`find()` scan loop: for each position `p` (advanced by a first-set gate over an ASCII bitmap of
`branchFirstSets`), try branches in priority order. Anchored branches are tried only at `p == 0`
(or their anchor condition). First-try gates are 2–3 instructions; a try that fails on its first
element costs O(1).

Expected per-char cost on the winning path: literal/class checks identical in shape to JDK's
compiled nodes plus one budget-counter increment — target ≤ ~1–2 ns/char (vs 5.4–6.4 fast-pathed
BitState, 0.3–0.9 JDK).

## 6. Coverage of the measured JDK losses (as measured after stage 5)

| Benchmark family | Admitted? | Measured result (reg/jdk, vs pre-strategy reference) |
|---|---|---|
| IastRegexp UrlAuth Find/Capture | yes | **5.56 / 3.65** — was 0.31/0.18; the routed family is now faster than JDK, not merely at parity |
| IastRegexp UrlQuery Find/Capture | yes | **10.59 / 9.45** — was 0.24/0.23 |
| IastRegexp Ldap Find | yes | **2.80** — was 0.10 |
| IastRegexp Sql* Find / QueryObfuscator Find | no (v1) — V2 targets | 0.41–0.64, unchanged: branch-1 compound alternation + `\b` + string-literal loops (`(?:''|[^'])*`) and greedy give-back loops are outside the v1 grammar — see §10 |
| NFAFallback XmlTags (`(<\w+>).*(</\w+>)`, greedy) | no (v1) — V2 target | ~0.30, unchanged: non-terminal greedy loop needs give-back. (The lazy IAST variant `.*?` **is** admitted — it is what the Ldap/XmlTags routing tests pin.) |
| ComplexNFABenchmark MultipleStars `(a*b*c*d*e*)` | no (v1) | ~0.29–0.33: terminal-only greedy loops mean every `x*` here is non-terminal (next `b*` follows) — stays on fast-pathed BitState by design |
| SmokeBenchmark DfaSwitch `(abc|…|789)+` | no (v1) | quantified group over alternation → stays on BitState |
| AnchorPlacement UserPattern `$…\|^[0-9]` | no (v1) | 0.13–0.39, unchanged: mid-chain `$` is not a branch guard — decline |
| ComplexEmail, LookaheadNoBoyerMoore | no | lookaround → HYBRID_DFA_LOOKAHEAD; RE2J also refuses these patterns (lookahead is outside RE2's regular-language set) |
| RepeatedWordAdversarial | no | backreference (RE2J refuses it too) |
| BitParallelGlushkov p1/p2 | no | different family, already specialized |
| StateExplosion AlternationHeavy / LargeAlternationWithStar / OverlappingAlternation | no | quantified group over alternation → fallback/BitState, unchanged |
| DFATable.Matches / SplitBenchmark / specializedConcatGreedyGroup | individually at detection time | not the motivation; whatever the grammar admits |

v1 as measured: ~12 of the 52 losses eliminated (all as wins, several 3–10× over JDK); ~25 remain, of which the V2 grammar (§10) targets the Sql\*/QueryObfuscator/XmlTags-greedy family (~5 scenarios).

## 7. Wiring (dual-path, structural hash)

- `PatternAnalyzer.detectDeterministicChain(ast)` → `DeterministicChainInfo implements
  PatternInfo` (branch list, per-branch: anchored flag, first-set bitmap, element list with
  resolved `CharSet`s, capture slots, `$` mode). Substitution point: in the same
  `routeBitState`-style replacement where `detectPrefixGuardedScan` substitutes
  `BITSTATE_BYTECODE` — this family also substitutes `BITSTATE_CAPTURE` results when the
  detector hits.
- **Structural hash rule:** every new field on `DeterministicChainInfo` must be folded into
  `StructuralHash.java`, or the structural cache silently returns wrong classes. All CharSets
  hash via the existing range-based `contentHashCode`.
- **Dual-path rule:** `RuntimeCompiler.java` (runtime) and `ReggieMatcherBytecodeGenerator.java`
  (annotation processor) both invoke the one shared `DeterministicChainBytecodeGenerator` —
  the generator lives in `reggie-codegen` and emits identical bytes from both call sites.
- Declines are silent (fall through to the existing BITSTATE_CAPTURE route); routing is pinned
  by unit tests the way `IastPatternRoutingTest` pins other families.

## 8. Test plan

- Detector unit tests: each admitted benchmark pattern parses to the expected element list;
  each give-back shape (`a+a`, `(?i)[a-z]+z`, `\d*\d+`), empty-chain, lazy-in-middle-of-loop,
  anchored-only shapes decline.
- Parity vs `java.util.regex` oracle over the benchmark inputs (SHORT/MEDIUM/LONG) for all
  entry points (`find`/`findFrom`/`findMatchFrom`/`matches`/`match`/`findBoundsFrom`), group
  spans included — same style as `BitStateGreedyLoopFastPathTest`.
- Adversarial linearity tests: the quadratic-trigger inputs (`////…`, `(((…`, all-digit runs)
  must complete within budget and delegate to the fallback (observable `fallbackCount()`),
  never hang.
- Fuzz: the existing `doc/fuzz` harness comparing generated vs JDK over random inputs from the
  family's grammar.
- Benchmarks: workspace-jb, the loss-class JMH filter, before/after against the reference run
  (`/tmp/reggie-jdkloss-ref-results.json` on completion of the in-flight rerun).

## 9. Staged implementation (v1 — complete)

1. `DeterministicChainInfo` + detector + declines (+ `StructuralHash`) — `8efdb80`.
2. Generator v1: single chain (no alternation), no captures — proved the per-char shape
   locally (0.53–1.05 ns/char, at/below JDK) — `e4d6d3d`.
3. Captures + `LitAlternation` + `OptChain` + lazy scan + `^`/`$` — `d1d40f7`.
4. Alternation of chains + find() first-set gates + budget/PikeVM fallback — `02549f5`.
5. Dual-path wiring, routing tests, parity tests, workspace-jb benchmark — `6490610`;
   fuzz gate 27 ≤ 28 known-findings budget (the reroute fixed one pre-existing divergence),
   smoke sweep 0. Benchmark verdict: routed family 2.8–10.6× faster than JDK.

## 10. V2 grammar — greedy give-back, ALT_CHAIN, LOOP_ALT (design; targets Sql\*, QueryObfuscator, XmlTags-greedy)

All four V2 targets decline today (detector-verified). Case-insensitivity is not a gap — the
parser normalizes `(?i)` letters to two-range `CharClassNode`s at parse time, which v1
`CLASS1` already handles.

### Elements

- **E1 WORD_BOUNDARY** — `\b` parses to `AnchorNode(WORD_BOUNDARY)`. Zero-width check
  `isWord(prev) != isWord(cur)` with bounds; ASCII word set `[a-zA-Z0-9_]` (JDK without
  `UNICODE_CHARACTER_CLASS`; the fuzz oracle polices parity).
- **E2 ALT_CHAIN** — generalizes `LIT_ALT`: mid-sequence alternation whose alternatives are
  chain sequences (OPTs/loops allowed), sharing the downstream tail through the existing
  alt-index dispatcher + path flag + `effFail` retry-chain. Terminal variant (alternation ends
  the seq) emits per-alt success with no shared tail.
- **E3 GIVEBACK_LOOP (single-char body)** — non-terminal greedy loop over a single-char
  set/disjunction: consume to max, then give back one position at a time retrying the whole
  downstream tail — exactly JDK's backtracking order (one iteration per step, which for
  single-char bodies is `pos--`). Budget charged per give-back retry; overflow → the existing
  PikeVM parse-at-overflow fallback. Covers XmlTags `.*`, Sql `\/*[\s\S]*\*\/`, and
  single-char LOOP_ALT bodies.
- **E4 LOOP_ALT + journal give-back** — loop body is an ALT_CHAIN of 1–2-char alternatives
  (`''`, `%3D`). When non-terminal, an iteration-boundary **journal** (int[] of iteration-end
  positions) is recorded during consumption; give-back pops the journal. This matches JDK's
  retry-at-each-successive-shorter-boundary exactly.

### Journal buffer (D1: journal, ThreadLocal scratch — no per-call allocation)

Chain matchers are method-local-only by the concurrency contract, so generated instances are
shared and concurrently used — the journal cannot be an instance field. It is a scratch buffer
behind a `ThreadLocal<int[]>` on the runtime side (`ReggieMatcher.scratch(int minLen)`-style
static accessor; one `INVOKESTATIC` per `findBoundsFrom`), grown to the next power of two ≥
`len+1` and kept cached — steady-state zero allocation, no malloc-arena churn. Only classes
whose admitted shape contains a multi-char give-back loop touch it (admission-time flag).
Re-entrancy is safe: matching makes no calls into user code, and the PikeVM fallback path does
not use the journal.

### Emitter composition

Give-back retries wrap the shared tail emission with a re-entry label and suspend through the
same `ctx.retryFail`/`effFail`/`TailLevel` continuation walk the v1 retry-chain uses, so nested
retries (LIT_ALT/OPT inside the tail) unwind correctly. Capture ends of downstream groups are
rewritten by the tail re-run (v3 mechanism); the journal is dead once the call returns.

### Stages (D2: two increments, V2-C is the goal)

- **V2-α (landed, `520a719`)**: E1 + terminal E2 + E3. Unlocks XmlTagsMatch (greedy) and Sql block
  comments. Sql branch-1's numeric alternation moved to V2-β: its alternatives embed OPTs, and an
  ALT_CHAIN body may contain only flat constructs — a retryable inside a body needs the shared
  rest's failures to re-enter the winning body's live retry, which requires per-body local slot
  frames the JVM verifier rejects (Bad local variable type) without a two-pass emission.
  Landed with two latent retry-discipline fixes (the lazy loop now registers its own retry label;
  emitOpt re-enters the nested's live retry before the skip path) — the give-back composition
  exposed them, and the fuzz gate corpus shift (fewer compile-rejects shift the shared window
  RNGs) was recalibrated 28 → 37 with every raw finding verified non-chain.
- **V2-β**: E4 (journal) + mid-seq E2 + the per-body slot pre-initialization (two-pass emission or
  a scratch local bank) that lifts the flat-body restriction. Unlocks Sql string literals (all
  dialects) + Sql branch-1 numerics + QueryObfuscator. Gets its own fuzz window; budget/journal
  interactions are the correctness hot spot.

`StructuralHash`: new `ChainElem` kinds fold via the stage-1 `structuralHashCode` pattern —
remember at implementation time.
