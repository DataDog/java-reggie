# DFA_TABLE Performance — Research & Three Proposals

**Goal:** Lift DFA_TABLE from its current sub-1× JDK ratio to a comfortable N× margin,
grounded in published state-of-the-art and in the actual reggie implementation.

**Status:** Research/proposal only. No code changed. Implementation deferred pending review.

---

## 0. Premise check (challenge before investing)

The benchmark report (`benchmark-all-strategies-vs-jdk.md`) lists DFA_TABLE at **0.29×**
with representative pattern `(?:abc){100}`. **That number is stale.** After the Phase-D /
regression-fix work, pure-literal bounded repeats route through `extractLiteralBody()` +
`QuantifierNode{n,n}` detection to **SPECIALIZED_FIXED_SEQUENCE**, not DFA_TABLE
(`PatternAnalyzer.detectFixedSequence`). So `(?:abc){100}` no longer exercises DFA_TABLE at all.

**Action item before proposals 2–3:** re-benchmark with patterns that actually route to
DFA_TABLE *today* — i.e. group-free, anchor-free DFAs with **> 300 states** and estimated table
≤ 1 MB (`PatternAnalyzer` constants `DFA_SWITCH_STATE_LIMIT = 300`,
`DFA_TABLE_ESTIMATED_BYTES_LIMIT = 1<<20`). Representative classes:
- Wide literal-alternation unions (`foo|bar|baz|…` at scale, no shared prefix).
- Multi-`.*` skeletons (`.*a.*b.*c.*d…`) that determinize into large products.
- Char-class-heavy patterns with many distinct equivalence classes.

Proposal 1 below is justified by code inspection regardless of which pattern is benchmarked;
proposals 2–3 should be prioritized by the re-benchmark.

---

## 1. Where the time actually goes (code-grounded)

### 1.1 Dominant cost — `find()` is O(n²)

`DFATableRuntime.findFrom` (lines 156–197) and `findBoundsFrom` (227–271) implement
**restart-the-DFA-at-every-candidate**:

```java
for (int candidate = from; candidate < length; candidate++) {     // O(n) start positions
  ...
  for (int pos = candidate + 1; pos < length; pos++) {            // O(n) re-scan each time
    state = nextState(state, input.charAt(pos), ...);
    ...
  }
}
```

For an embedded match (or a non-match) this is **O(n · matchLen)**, worst case O(n²). A textbook
unanchored DFA `find` is a **single left-to-right O(n) pass**: feed the start state's
self-loop (or reset-to-start on dead) and track the last accepting position. RE2's
`InlinedSearchLoop` does exactly one pass over the input
([re2/dfa.cc](https://github.com/google/re2/blob/main/re2/dfa.cc)). This is the **same defect
already fixed for LAZY_DFA** (`LazyDFACache.findFrom`, RE2-style O(n) walk) — DFA_TABLE never got
the equivalent fix.

This alone almost certainly explains the sub-1× number more than any per-character constant.

### 1.2 Secondary cost — per-character constants

`nextState` (274–289) + `charClass` (291–309), called once per char:

1. **8-argument static call per char.** Even when inlined, all equivalence-class arrays
   (`transitions`, `asciiClasses`, `rangeStarts`, `rangeEnds`, `rangeClasses`) are passed every
   iteration; if the JIT declines to inline (method size), it is a call per byte — fatal.
2. **Two data-dependent memory loads per char:** `asciiClasses[ch]` → then
   `transitions[state*classCount + cls]`. The second load address depends on the first load's
   result → cannot pipeline; latency-bound (~2 load-to-use latencies/char). This is the classic
   pointer-chasing DFA ceiling (~200–400 MB/s), the *same* structure RE2 uses
   (`s->next_[bytemap[c]]`) — RE2 just keeps it to **one** bytemap load on **bytes** (256-entry),
   whereas reggie does an ASCII branch + a **binary search per non-ASCII char** (296–307).
3. **`input.charAt(pos)` via `CharSequence`** (INVOKEINTERFACE) — potentially megamorphic; no
   pre-extraction to a `char[]`/`byte[]` for a tight bounds-check-elided loop.
4. **Per-char bounds check** `index >= 0 && index < transitions.length` (288).
5. **No literal / first-byte acceleration.** JDK's `Pattern` applies Boyer–Moore (`BnM`) literal
   prefix scanning and counted-loop (`Curly`) handling, letting it *skip* input; DFA_TABLE grinds
   every byte. This is a primary reason JDK beats a naïve DFA on embedded inputs.

### 1.3 Why patterns land here at all — determinization blow-up

DFA_TABLE is only selected at **> 300 DFA states** (`PatternAnalyzer`). That state count is the
*symptom* of subset-construction explosion (bounded repetition, multi-`.*`, alternation products).
The two algorithmic proposals attack the explosion itself so the automaton never reaches 300 states.

---

## 2. State of the art (literature)

| Technique | Core idea | Relevance |
|---|---|---|
| **Single-pass cached DFA** (RE2) | One O(n) scan; `next_[bytemap[c]]` single load; bytemap = byte→equiv-class; bail to NFA if cache thrashes. | Fixes §1.1 + tightens §1.2. [re2/dfa.cc](https://github.com/google/re2/blob/main/re2/dfa.cc), [DeepWiki: execution engines](https://deepwiki.com/google/re2/2.4-execution-engines) |
| **Bit-parallel Glushkov NFA** (Navarro–Raffinot) | ε-free NFA of m+1 states; active set = bit-mask in a machine word; O(n) with branch-free, *independent* loads; no determinization. | Replaces DFA_TABLE for NFAs that fit in 1–few words. [Navarro–Raffinot, *New Techniques for RE Searching*](https://users.dcc.uchile.cl/~gnavarro/ps/algor04.2.pdf) |
| **Hyperscan LimEx + SIMD acceleration** | Bit-parallel Glushkov scaled to ~512 states via exceptions; SIMD literal pre-filters (Teddy/FDR); substitute cheap SIMD tests for automaton steps. | Productionized large-scale bit-parallel + acceleration. [Hyperscan, NSDI '19](https://www.usenix.org/system/files/nsdi19-wang-xiang.pdf) |
| **Counting / counting-set automata** (Turoňová, Holík, Lengál, Saarikivi, Veanes, Vojnar) | Registers hold counters/sets-of-counters with constant-time ops; automaton size **independent of repetition bound** `{n,m}`. | Kills the bounded-repetition state blow-up. [OOPSLA 2020](https://www.microsoft.com/en-us/research/publication/counting-set-automata/), [Synchronizing Counting, arXiv 2301.12851](https://arxiv.org/pdf/2301.12851), [Automata w/ Bounded Repetition in RE2](https://link.springer.com/chapter/10.1007/978-3-031-25312-6_27) |
| **ReDoS via counting** (USENIX Sec '22) | Bounded quantifiers are an attack surface for table-DFA matchers. | Motivates moving `{n,m}` off the unrolled DFA. [Counting in Regexes Considered Harmful](https://www.usenix.org/system/files/sec22fall_turonova.pdf) |

---

## 3. Three proposals (ordered by ROI / risk)

### Proposal 1 — O(n) single-pass execution + tight byte-class loop *(engineering; lowest risk, highest ROI)*

**What:**
1. Rewrite `findFrom`/`findBoundsFrom` as a **single left-to-right O(n) pass**, mirroring the
   already-shipped `LazyDFACache.findFrom`: track last accepting position; on dead state reset to
   start at the current position (no re-scan of consumed input). Leftmost-longest semantics preserved.
2. **Pre-extract input once** to `char[]` (or `byte[]` when the pattern's alphabet is Latin-1) so
   the inner loop is a bounds-check-elidable array walk, not `CharSequence.charAt` interface dispatch.
3. **Collapse `nextState`/`charClass`** into an inlined two-instruction step for the ASCII fast
   path (single `asciiClasses[ch]` load → single `transitions[base+cls]` load), hoist all arrays
   and `classCount` into locals, drop the per-char explicit bounds check (rely on JIT range-check
   elimination over the extracted array).
4. **First-byte acceleration for `find()`:** reuse `startAscii` to build a viable-start byte
   predicate and `indexOf`/memchr-style skip to the next candidate (vectorizable later via the
   Java Vector API), matching JDK's `BnM` literal skipping.

**Targets:** every DFA_TABLE pattern. **Expected:** O(n²)→O(n) is the headline; (1) alone should
flip the ratio above 1× on embedded inputs; with (2)–(4), target **3–10×** JDK.
**Risk:** low — pure runtime/codegen change, no new automaton model, capture/anchor-free domain
unchanged. **Effort:** small–medium.

### Proposal 2 — Bit-parallel Glushkov NFA engine *(algorithmic; replaces DFA_TABLE for word-sized NFAs)*

**What:** For patterns whose Glushkov NFA has **m+1 ≤ 64** positions (or ≤ ~256 via `long[]` /
Vector API), skip determinization entirely and simulate the NFA with a bit-mask active set:
per char, successor-expand the active mask (table-split lookups — *independent* loads, unlike the
DFA's dependent chain) then AND with the char-class entry mask
([Navarro–Raffinot](https://users.dcc.uchile.cl/~gnavarro/ps/algor04.2.pdf)). O(n), branch-free
inner loop, **near-zero memory** (no state table), no subset-construction explosion.

**Targets:** the alternation-union and multi-`.*` patterns that *cause* the > 300-state blow-up
but whose NFA is small. Add a new strategy (e.g. `BITPARALLEL_NFA`) selected when
`nfaStateCount ≤ wordBudget` *before* determinization is attempted.
**Does not help** large `{n,m}` (m exceeds the word budget) — that is Proposal 3.
**Expected:** order-of-magnitude over both JDK and the current DFA_TABLE on its target class.
**Risk:** medium — new execution path; reuses the existing capture/anchor-free precondition.
**Effort:** medium.

### Proposal 3 — Counting(-set) automata for `{n,m}` *(algorithmic; the published ReDoS-safe answer)*

**What:** Compile bounded repetition `(?:…){n,m}` to a **counting automaton** whose counter is a
register, then (if needed for determinism) to a **counting-set automaton** — size **independent of
the bound** ([OOPSLA 2020](https://www.microsoft.com/en-us/research/publication/counting-set-automata/);
the *synchronizing* subclass, which the authors show covers nearly all real regexes, matches in
time linear in input and independent of the bound, [arXiv 2301.12851](https://arxiv.org/pdf/2301.12851)).
This structurally mirrors JDK's `Curly` counted loop but stays in reggie's native O(n) engine and
generalizes to counting nested in classes/alternation. Also closes the
[bounded-repetition ReDoS surface](https://www.usenix.org/system/files/sec22fall_turonova.pdf).

**Targets:** the historical worst case (`(?:abc){100}`-style, and the non-pure-literal variants
that still reach DFA_TABLE/LAZY_DFA after the FIXED_SEQUENCE fast-path). Avoids materializing
~300 states from `{100}` in the first place.
**Expected:** parity-to-better vs JDK on counted loops, and **no blow-up / no ReDoS** on adversarial
bounds where the current DFA path degrades.
**Risk:** highest (new compiler stage: Antimirov-style CA construction + sphere/CsA determinization).
**Effort:** large. Best scoped as a follow-up once 1–2 land.

---

## 4. Recommended sequencing

1. **Re-benchmark** DFA_TABLE with patterns that actually route there today (§0).
2. **Proposal 1** — fix the O(n²) `find` + tighten the inner loop. Likely flips the ratio above 1×
   immediately; low risk; validates the diagnosis.
3. **Proposal 2** — bit-parallel Glushkov for word-sized NFAs; captures the alternation/`.*` blow-up class.
4. **Proposal 3** — counting-set automata for `{n,m}`; the principled, ReDoS-safe endgame.

## Sources

- Navarro & Raffinot, *New Techniques for Regular Expression Searching* — https://users.dcc.uchile.cl/~gnavarro/ps/algor04.2.pdf
- Wang et al., *Hyperscan: A Fast Multi-pattern Regex Matcher for Modern CPUs*, NSDI '19 — https://www.usenix.org/system/files/nsdi19-wang-xiang.pdf
- Turoňová et al., *Regex Matching with Counting-Set Automata*, OOPSLA 2020 — https://www.microsoft.com/en-us/research/publication/counting-set-automata/
- Holík et al., *Fast Matching of Regular Patterns with Synchronizing Counting* (TR) — https://arxiv.org/pdf/2301.12851
- *Automata with Bounded Repetition in RE2* — https://link.springer.com/chapter/10.1007/978-3-031-25312-6_27
- Turoňová et al., *Counting in Regexes Considered Harmful*, USENIX Security '22 — https://www.usenix.org/system/files/sec22fall_turonova.pdf
- google/re2 DFA source & execution-engine notes — https://github.com/google/re2/blob/main/re2/dfa.cc · https://deepwiki.com/google/re2/2.4-execution-engines
