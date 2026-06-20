# Reggie — capture-correctness & performance work, 2026-06-11 → 2026-06-18

Research / achievement report for the `feat/pikevm-capture-cost` branch (74 commits ahead of
`origin/main` @ `a924e431`; 106 files, +15,072 / −608). Goal context: make Reggie a viable
**re2j replacement for Datadog IAST**, which requires beating re2j on the *real* IAST workload
(large malformed payloads, full `findAll` drain) while preserving correctness and ReDoS-safety.

This week's work splits into five efforts (chronological). Each is summarized with the problem,
the change, and the evidence. Numbers are from the differential fuzz gate (Reggie vs `java.util.regex`,
JDK as oracle) and the JMH benchmarks (`IastRegexpBenchmark`, `IastTokenizerDrainBenchmark`).

---

## Effort 1 — `ReggieOption` / `@RegexPattern` substrate + delegating-stub fallback policy (06-11/06-12)

**Problem.** Reggie compiled patterns to one of several native strategies, but patterns the native
engines couldn't handle correctly fell back to `java.util.regex` *silently* — masking gaps and
shipping JDK ReDoS exposure without the caller's knowledge.

**Change.**
- Introduced a `ReggieOption` flag substrate and moved it into `reggie-annotations`; replaced the old
  `CapturePolicy` with an `EnumSet<ReggieOption>` in `ReggieOptions`
  (commits `745ef13`, `fd70aea`, `60407a9`, `d891b8d`).
- **Throw-by-default fallback policy** (Plan A): a pattern that cannot be served natively now throws
  rather than silently delegating, unless the caller opts in with `allowJdkFallback()`
  (`afe2d8d`, `d891b8d`). The differential fuzz oracle was updated to compile with
  `ALLOW_JDK_FALLBACK` so coverage is preserved while still surfacing native wrong-answers
  (`e9d4ffc`).
- **Compile-time `@RegexPattern` delegating stubs** (Plan B): the annotation processor classifies each
  annotated method as native / delegate-pikevm / delegate-fallback and emits delegating stubs
  accordingly (`3a89b54`, `6b991ce`, `ba1e5b8`); added `compilePikeVm` staging entrypoint + a
  name-map codec (`0b534f0`).

**Why it matters for release.** This turns "silent JDK fallback" into an explicit, auditable policy —
a prerequisite for honestly claiming native coverage and ReDoS-safety.

---

## Effort 2 — anchor/alternation PIKEVM routing + B5/B12 backref support (06-11/06-12)

**Problem.** Several pattern classes were mis-routed to DFA/specialized strategies that produced wrong
captures or wrong matches: anchor-diluted capturing alternations, quantified-group alternation
priority conflicts, and lazy/quantifier-prefix backreference patterns.

**Change.**
- Routed anchor-diluted capturing alternations and quantified-group-in-quantifier patterns to
  `PIKEVM_CAPTURE` (the priority-correct thread engine) before the DFA paths
  (`d7607a6`, `b011e48`, `fafe340`, `dc53a55`, `b9b208a`), with a `compileHybrid` pre-check and a
  revert of unsafe PikeVM promotions that had over-broadened (`496b56d`, `cae38f1`).
- Backref fixes: **B5** — guard lazy quantifiers in `VARIABLE_CAPTURE_BACKREF` (throw, not silent
  wrong) (`16696eb`, `94d1626`); **B7** — zero-length early-accept in the backref check for nullable
  groups (`5e784ba`); **B12** — emit quantifier-prefix bytecode in the backref generator and extend
  `isPrefixNodeHandleable` to unbounded/exact quantifier prefixes (`ac4b245`, `efa80e3`, `1133d2a`,
  `673df90`).

**Evidence.** Each fix was landed with spike/regression tests; the fuzz divergence budget was driven
down across the wave (see Effort 3).

---

## Effort 3 — capture-correctness wave across the codegen strategies (06-15)

**Problem.** The bytecode-generating capture strategies (the TDFA-style `DFA_UNROLLED_WITH_GROUPS`,
`DFA_SWITCH_WITH_GROUPS`, `SPECIALIZED_MULTI_GROUP_GREEDY`, `GREEDY_BACKTRACK`, `RECURSIVE_DESCENT`)
had a family of group-span bugs versus JDK.

**Change (representative).**
- **1A** — `DFAUnrolledWithGroups` zero-width group span recorded at the wrong (non-accept) position
  (`d9cb845`).
- **1B** — alternation capture binding + START-tag pairing (`3e1ec57`).
- **1C** — find-leftmost + empty-group-loop handling (`b9daaa2`).
- `PIKEVM_CAPTURE` anchor-in-quantified-group + trailing-empty-iteration handling (`7ab8e98`).
- `GREEDY_BACKTRACK` find-backtracking when the greedy content contains the suffix char (`56eab60`).
- `SPECIALIZED_MULTI_GROUP_GREEDY` `\A`/`\z`/`\Z` anchor support (`7ba193d`).
- `DFA_SWITCH` `\A` preservation through a non-capturing group quantifier (`52dbd72`).
- `RECURSIVE_DESCENT` backref capture/restore + alternation fallthrough (`5ce02c6`).
- `RegexFuzzShrinker` re-verifies a shrunken repro before emitting (so reported minimal repros are
  real, not shrinker artifacts) (`0678e54`).

**Evidence.** `KNOWN_FINDINGS_BUDGET` (the ≥25k-pattern differential fuzz gate, fixed seed) was lowered
**50 → 18** after this wave (`96d1e6d`).

---

## Effort 4 — performance wave: O(n) boolean find/matches + cheap closures (06-17)

**Problem.** Boolean `find()`/`matches()` and the per-character PikeVM step were slower than re2j on
the IAST patterns. Profiling pointed at the epsilon-closure fan-out and `CharSet.contains`.

**Change (all in `PikeVMMatcher` / `CharSet`, commits `87d79bd`, `f9cc34f`, `397578d`, `3eb3a82`,
`a9916bd`).**
- **`CharSet.contains` O(1) ASCII bitmap** — two `long` bitmaps for chars < 128, binary search above;
  `hashCode`/`equals` left range-based so the structural hash is unaffected (`87d79bd`).
- **T1.2 first-char prefilter + O(1) accept check** — skip start positions whose char cannot begin a
  match; track the first accepting clist thread incrementally (`f9cc34f`).
- **T1.4 self-anchoring boolean-find DFA** — a lazy DFA that re-injects the start closure each char
  (an implicit `.*?` prefix), so one O(n) left-to-right scan tracks every candidate start. Beats
  re2j's per-position retry for unanchored boolean find (`397578d`).
- **T1.6 strict boolean `matches()` DFA** — `matches()` is whole-input and priority-independent, so a
  strict lazy DFA's yes/no equals the thread sim; many-optional-group `matches()` went from ~323 to
  ~38,000 ops/ms, beating re2j and JDK (`3eb3a82`).
- **Anchor-aware boolean DFA for leading `^`/`\A`** — extends the self-anchoring DFA to admit a
  leading start anchor (pos-0 vs re-inject closure split), so anchored-prefix patterns also get the
  O(n) boolean path (`a9916bd`).

---

## Effort 5 — O(n) IAST drain + capture routing (06-18, this branch's headline)

**Problem (the strategic finding).** A representative drain benchmark (`IastTokenizerDrainBenchmark`,
512–2048 B malformed payloads, full `findAll`) showed Reggie was **catastrophically O(n²)** on
adversarial inputs — URL_QUESTION_RUN ≈130,000 µs and COMMAND_BLANK_LINES ≈153,000 µs at 2048 B,
560–860× slower than re2j. Two independent O(n²) sources: the per-start retry inside
`findMatchFrom`/`findStartFrom` (the classic PCRE "try every start" anti-pattern), and `findAll`'s
per-match relaunch.

**Change.**
- **Single continuing-cursor PikeVM find** (`33c23b7`): `findMatchResultFrom` rewritten from per-start
  retry to one left-to-right pass with lowest-priority re-seeding (RE2 "Mark" prefix; dedup-by-PC
  keeps it O(n·m)), and the **anchor origin pinned to absolute 0** — which also fixed a latent
  `^`/`\A` `findAll` re-anchoring bug (JDK is the oracle for start anchors in `findAll`).
- **Boolean-DFA fast-reject** (`33c23b7`): when the boolean find DFA proves no match exists at/after a
  position, skip the thread sim. Plus an **over-approximating "reject DFA"** that crosses every anchor
  as epsilon (sound superset; no state fracture) so anchored patterns (COMMAND `(?m)^`+`\b`,
  SQL `(?m)$`+`\b`) also get the fast-reject.
- **`findAll` differential fuzz oracle** (`33c23b7`): the first oracle to check per-match group spans
  (≥1) on the *find* path; surfaced a large pre-existing capture-bug surface.
- **Capture routing fixes** (`a181b32`, `b6d83f0`, `f06e984`): `MULTI_GROUP_GREEDY` declines give-back
  patterns (fixing `(\w+)0`→"ab00" which returned **no match**); nullable-group-in-alternation
  (`1|()b`) and interacting variable-length capturing alternations (`(a|ab)(c|bcd)`) route to PikeVM
  for correct spans. Class-D codegen `^` re-anchoring fixed in `MultiGroupGreedy`.

**Evidence — drain benchmark @ 2048 B (AverageTime µs/op, lower is better; quick JMH mode):**

| Scenario | reggie before | reggie after | re2j | jdk |
|---|---|---|---|---|
| URL_QUESTION_RUN | ~130,000 → 191 | **8.5** | 125 | 5,572 |
| URL_QUERY | 179 | **8.5** | 128 | 23 |
| URL_AUTHORITY | 161 | **8.5** | 123 | 17 |
| COMMAND_BLANK_LINES | ~153,000 → 152 | **8.5** | 84 | 15,334 |
| SQL_ANSI_UNTERMINATED | 102 | **8.5** | 286 | 104 |
| SQL_MYSQL_UNTERMINATED | 133 | **8.5** | 306 | 112 |

Reggie now **beats re2j 10–31×** on every adversarial / zero-match IAST drain. Residual:
`COMMAND_SINGLE_TOKEN` (a benign single-match input) stays ~2× behind re2j — needs an anchored
span-capture pass (deferred; not a ReDoS vector). LDAP is excluded — its lazy quantifier `.*?` does not
compile (separate tracked blocker).

**Approach-B (RE2 two-pass) was designed, adversarially reviewed, and dropped:** the review showed 3 of
4 benchmark scenarios are zero-match, so the win is entirely in the cheap fast-reject pass — the
reverse-DFA / BitState / OnePass machinery never executes on this workload and is not justified.

---

## Methodology note

Efforts 4–5 (and the capture-correctness routing) were run as explicit **design → adversarial-review →
improve → plan → review → propose** loops (plan docs under `docs/superpowers/plans/`). Two notable
outcomes: a multi-register TDFA rework was retired by review in favour of routing the
genuinely-broken patterns to the already-correct, O(n)/ReDoS-safe PikeVM; and several "broad" routing
changes were caught (by the strategy-assertion tests + backref routing) and narrowed to precise,
test-respecting predicates. The lesson recorded for future work: the strategy router is a mature,
multi-strategy system with assertion tests — capture fixes must be surgical, not broad.

## Fuzz-gate trajectory (differential vs JDK, fixed seed, ≥25k patterns)

`50` (pre-week) → `18` (after the 06-15 capture wave) → `78` (when the new `findAll` group-span oracle
exposed pre-existing find-path capture debt) → **`69`** (after Class A routing). The increase to 78 is
not a regression — it is newly-*visible* pre-existing debt (proven: the pre-change engine produced the
same divergences). The residual 69 are rare/degenerate capture-span cases (empty `()`, `{0}`,
assertions-in-loops), all native + ReDoS-safe (only inner group spans differ; boolean/group-0/linearity
intact). See `doc/temp/prod-readiness/fuzz-inventory.md` and the capture-correctness design/plan docs.
