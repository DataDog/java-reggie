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

## 6. Coverage of the measured JDK losses

| Benchmark family | Admitted? | Notes |
|---|---|---|
| IastRegexp UrlAuth/UrlQuery/Ldap/SqlAnsi/SqlMysql/SqlPostgres/QueryObfuscator Find+Capture (27 losses) | yes | the motivating shapes: deterministic class loops, lazy `.*?` scan, disjoint/bounded literal alternations, `^`/`$`/`(?m)$` guards |
| NFAFallback XmlTags (`(<\w+>).*?(</\w+>)`) | yes | lazy scan + `\w+` loop |
| ComplexNFABenchmark MultipleStars `(a*b*c*d*e*)` | no (v1) | empty-matching chain (min-width 0) → stays on fast-pathed BitState |
| SmokeBenchmark DfaSwitch `(abc|…|789)+` | no (v1) | quantified group over alternation → stays on BitState |
| AnchorPlacement UserPattern `$[^a-zA-Z0-9]\|^[0-9]` | yes | two single-element branches, `^`/`$` guards |
| ComplexEmail, LookaheadNoBoyerMoore | no | lookaround → HYBRID_DFA_LOOKAHEAD |
| RepeatedWordAdversarial | no | backreference |
| BitParallelGlushkov p1/p2 | no | different family, already specialized |
| StateExplosion AlternationHeavy/OptionalSequence Match | yes* | `LitAlternation` with overlapping prefixes is admitted as bounded sequential tries (9 tries max), so these matches() losses are covered too |
| DFATable.Matches / SplitBenchmark / specializedConcatGreedyGroup | individually at detection time | not the motivation; whatever the grammar admits |

Estimated: ~34 of the 52 losses addressed.

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

## 9. Staged implementation

1. `DeterministicChainInfo` + detector + declines (+ `StructuralHash`).
2. Generator v1: single chain (no alternation), no captures — prove the per-char shape on
   `[a-z]+@`-style patterns locally.
3. Captures + `LitAlternation` + `OptChain` + lazy scan + `^`/`$`.
4. Alternation of chains + find() first-set gates + budget/fallback.
5. Dual-path wiring, routing tests, parity tests, benchmark on workspace-jb.
