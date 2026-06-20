# Capture-correctness — design (Classes A/B/C) — DESIGN PHASE

Status: DRAFT for the design→adversarial-review→improve loop (then a plan, then propose impl). NO code.
Date: 2026-06-18
Supersedes the program sketch `2026-06-18-capture-correctness-program.md` with a grounded design.
Constraints (user): targeted NATIVE fixes, NO JDK fallback, must stay O(n)/ReDoS-safe, JDK is the oracle,
25k findAll gate → 0. Build the design rigorously before any implementation.

## Round-1 adversarial review (2026-06-18) — Option B dropped; Class-A trigger must be PRECISE
Evidence-tested; supersedes §3/§4 where they conflict:
- **OQ-1 CONFIRMED:** PikeVM (forced via `RuntimeCompiler.compilePikeVm`) matches JDK on ALL Class-A
  repros incl. boundaries (`1|()b`→−1, `()a|b`→[0,0), `()b|x`→−1/[0,0), `(-?)b|_`, `<(\w+)>|</(\w+)>`,
  `(\d{4})-(\d{2})|N/A`). Option A is viable for Class A.
- **OQ-3 REFUTES "decline on `hasCaptureAmbiguity`":** that flag fires for the WHOLE common `(x)|y`
  family — `(a)|b`, `(foo)|bar`, `(\d{4})-(\d{2})|N/A`, `(-?)b|_` are all `captureAmbiguous=TRUE` and
  **already CORRECT on the DFA**. A raw-flag decline would flip them to PikeVM = pure perf loss, zero
  correctness gain. **The trigger must be precise.**
- **PRECISE Class-A trigger (the fix):** decline to PIKEVM_CAPTURE only when a capturing group is
  **nullable (can match zero-width via an epsilon-only enter→exit path) AND has a bypass**
  (`computeGroupsWithBypass`). Rationale: the leak is the zero-width group fixup
  (`emitAcceptStateGroupActions`) committing an empty group on a branch the winner bypassed. A
  non-zero-width group (`(a)|b` — g1 consumes 'a') never leaks → stays on the fast DFA. Catches
  `1|()b`/`()b|x`; harmlessly routes `()a|b` (correct on both engines, rare/degenerate) to PikeVM.
  Reuses the existing bypass analysis + a nullable-group check (≈ `groupBodyNullable`).
- **OQ-2 Class C:** PikeVM is correct for it (`(.*.)0{2}`→[0,1), `a(c{0,}.+1).`→correct) BUT
  `captureAmbiguous=FALSE` → the Class-A trigger won't route it; needs a SEPARATE greedy-give-back
  trigger. **CACHE QUESTION RESOLVED (2026-06-18):** `(.*.)0{2}`→"b00" gives g1=[0,2) DETERMINISTICALLY
  — isolated, cold, repeated, and after cache pollution. It is a **genuine DFA greedy-give-back capture
  defect, NOT a STRUCTURE_CACHE collision.** Trigger = greedy-give-back hazard: a greedy quantified
  capturing group whose body's trailing charset intersects its successor's first-set (`.` vs `0` here),
  so the successor forces give-back that the single-register tag doesn't correct. Detectable from NFA/AST
  (cf. the Route-A review's "successor-disjointness"). Decline such patterns → PikeVM (verified correct).
- **OQ-4: DROP Option B (multi-register TDFA).** No correctness case requires it; the over-decline
  worry is eliminated by the precise trigger. The deep rework is unjustified.
- **Class B confirmed shared:** `b(\z){0,}|[a-c]`→"b" gives [1,1) on BOTH PikeVM and DFA (JDK −1). Fix in
  PikeVMMatcher per §3, gated to the zero-width-body-vs-optional-consumer rule.
- Gate is `KNOWN_FINDINGS_BUDGET=78` today (not 0); ratchet down per class. `<(\w+)>|</(\w+)>` is
  ALREADY PIKEVM_CAPTURE (not a DFA baseline).

### Converged recommendation (post-review)
A-precise-trigger → PikeVM (Class A); separate give-back trigger → PikeVM (Class C, after cache
verification); PikeVM empty-iteration fix (Class B); **no Option B**. All native, ReDoS-safe, keeps the
common `(x)|y` family on the fast DFA. Next: a second review pass on the precise triggers, then the plan.

## Round-2 adversarial review (2026-06-18) — triggers validated/refined; TWO MORE families found
- **Trigger A VALIDATED PRECISE:** zero false negatives, zero false positives on the tested spread.
  `nullable(epsilon-only enter→exit) AND bypass` catches every Class-A wrong-span (`1|()b`, `()b|x`,
  `(x*)y|z`, `(\d*)-|;`) and flips NONE of the common family (`(a)|b` byp but non-nullable; `(\s*)x`
  nullable but no bypass; `(\d{4})-(\d{2})|N/A` etc.). **Keep Trigger A as written.**
- **Trigger C REFINED + WIDENED:** (i) "body trailing charset" = the charset of the **greedy-repeated
  element that is the last consuming step** of the group body (the `.` of `.*.`, the `\w` of `\w+`),
  NOT a trailing fixed literal — intersect THAT with the successor first-set (avoids over-declining
  `(.*X)` with a disjoint literal X). (ii) **The give-back hazard also hits
  SPECIALIZED_MULTI_GROUP_GREEDY, producing NO_MATCH (a BOOLEAN miss — worse than a wrong span):**
  `(\w+)(\d)`→"ab1", `(\w+)0`→"ab00", `(\d+)5`→"1235", `([abc]+)c`→"abcc" all return no match (JDK
  matches; PikeVM correct). This is the known `bug_specialized_multi_group_greedy_backtracking`. **The
  give-back trigger must apply to SPECIALIZED_MULTI_GROUP_GREEDY as well as DFA_UNROLLED_WITH_GROUPS.**
  These `(\w+)<digit>`-shape patterns are NOT rare/degenerate → higher priority than the original
  span-only classes.
- **NEW Trigger E — branch-length-ambiguous capturing alternation:** `(a|ab)(c|bcd)`→"abcd" gives wrong
  spans on DFA_UNROLLED (g1=[0,2) vs JDK [0,1)); `captureAmbiguous=FALSE`, no nullable group, no
  quantifier → falls outside A and C. PikeVM correct. Trigger: a capturing alternation whose branches
  have overlapping prefixes / differing lengths feeding a capturing group → PikeVM.
- **Coverage:** all divergences route to PikeVM and PikeVM is correct EXCEPT **Class B**
  (`b(\z){0,}|[a-c]` — PikeVM also wrong) → the independent PikeVM empty-iteration fix stays mandatory.
- All decline-to-PikeVM patterns stay O(n)/ReDoS-safe (PikeVM is O(n)); no JDK fallback.

### FINAL converged design (post round-2)
Three precise decline triggers → PIKEVM_CAPTURE + one PikeVM fix, all native + ReDoS-safe:
1. **A:** nullable capturing group AND bypass.
2. **C/give-back:** greedy quantified capturing group whose greedy-element charset ∩ successor
   first-set ≠ ∅ — applied to BOTH DFA_UNROLLED_WITH_GROUPS and SPECIALIZED_MULTI_GROUP_GREEDY
   (fixes the NO_MATCH family too). [highest priority — boolean misses, least rare]
3. **E:** capturing alternation with overlapping-prefix / length-differing branches.
4. **B:** PikeVM empty-iteration fix (zero-width-body-vs-optional-consumer rule).
Open for the plan phase: precise predicate encodings (where in PatternAnalyzer/SubsetConstructor each
fires), the over-decline perf measurement, and per-trigger regression pins (cold-cache, give-back-forcing
inputs). Then a plan-review pass, then propose implementation.

## 1. The bugs (all reproduced; pre-existing; native-only — the new findAll oracle exposed them)
Find-path group spans (≥1) diverge from JDK on three root-cause classes (degenerate/rare — 0/14 common
patterns affected; the native engine stays ReDoS-safe O(n) on all of them, only inner spans are wrong):
- **A — untaken-alternation-branch group not reset to −1.** `1|()b` on "1" → JDK g1=−1, reggie [0,0).
  Boundary: `()a|b` on "a" → g1 **must stay [0,0)** (winning branch includes the empty group).
- **B — empty/zero-iteration quantified group binds when JDK leaves it unset.** `b(\z){0,}|[a-c]` on "b"
  → JDK g1=−1, reggie [1,1). True JDK rule (verified): a pure zero-width-assertion body (`\z`, `()`)
  stays unbound on the empty iteration; an optional-CONSUMER body (`a?`) BINDS to empty (`(a?)*`→[k,k)).
- **C — greedy give-back wrong inner-group end.** `(.*.)0{2}` on "b00" → JDK g1=[0,1), reggie [0,2).

## 2. Grounded root cause (investigation, evidence-cited)
- **DFA_UNROLLED_WITH_GROUPS uses a SINGLE-register tag model.** `int[2*(groupCount+1)]` (one slot per
  group-start/end tagId); a `TagOperation` writes `tags[tagId]=pos`, last-writer-wins; no register
  copies, no per-path registers (DFAUnrolledBytecodeGenerator:1410-1428,1647-1659). Disambiguation =
  `srcRank` priority + a "C2.4" competing-thread suppression (SubsetConstructor:949-1115). It cannot
  resolve two threads in one DFA state that disagree on a tag's value when the group's enter/exit span
  multiple DFA states — exactly Classes A/C. Class-A's empty-group leak is finalized by the zero-width
  fixup `emitAcceptStateGroupActions` (DFAUnrolledBytecodeGenerator:1686-1709) committing g1 even when
  the winning thread bypassed the group.
- **THE ROUTING GAP (key).** `hasCaptureAmbiguity` (SubsetConstructor:758-775) returns TRUE for `1|()b`,
  but `PatternAnalyzer:902-957` still routes anchorless, unnamed, capture-ambiguous patterns to
  DFA_UNROLLED_WITH_GROUPS (a comment claims "C2 priority-ordered TDFA gives correct spans" — false for
  `1|()b`). The guard detects the hazard but does not decline. Note the SAME guard, when combined with
  anchors/named groups, DOES route to PIKEVM_CAPTURE (`:814`) — so the decline path exists.
- **PIKEVM_CAPTURE is Reggie's own priority-correct, O(n), ReDoS-safe capture engine** (per-thread
  capture arrays; an untaken alternation branch never enters its group → −1). It is NOT JDK fallback.
  Reproduced: untaken-branch (Class A) cases route correctly on PikeVM (`(\d+)|x`, `(https?)|ftp`); the
  only PikeVM capture divergences in triage were Class B (`b(\z){0,}|[a-c]`) + a multi-anchor edge.

## 3. The design space (two native, ReDoS-safe options) + per-class fit
### Option A — close the routing gap: route capture-ambiguous patterns to PIKEVM_CAPTURE (recommended)
Make `hasCaptureAmbiguity` (and the bypass/priority-conflict signals) actually DECLINE
DFA_UNROLLED_WITH_GROUPS → PIKEVM_CAPTURE, instead of proceeding. Small, surgical (one routing
condition at PatternAnalyzer:902-957), reuses the already-correct PikeVM, stays O(n)/ReDoS-safe. Only
the **rare ambiguous** patterns lose DFA speed (still linear); common non-ambiguous patterns keep the
fast DFA. Fixes **Class A** (PikeVM is correct for untaken-branch). Does NOT fix **Class B** (PikeVM
shares the empty-iteration bug). **Class C**: only helps if (i) PikeVM is correct for it AND (ii) the
ambiguity guard flags it — both UNVERIFIED (OQ-1, OQ-2).

### Option B — full multi-register TDFA (Laurikari/Trofimovich) in SubsetConstructor
Replace the single-register tag model with per-tag register banks + register-copy operations so the DFA
itself disambiguates per path; keeps DFA speed even for ambiguous patterns. Deep, high-risk: rewrites
`computeTagOperations`, must extend/replace `dfaStateOrdering` (keyed on the bare NFA-state Set,
SubsetConstructor:33 — a path/register dimension breaks the memoization), and re-validate every guard.
Justified ONLY if a COMMON, perf-critical pattern is both capture-ambiguous AND must stay on the DFA
(not PikeVM). Evidence so far: such patterns are rare/degenerate → **Option B looks unjustified.**

### Class B (needed regardless of A vs B) — PikeVM empty-iteration fix
PikeVM's `updateCaptures`/loop-back binds a group on a zero-width-assertion-body empty iteration that
JDK leaves unset. Targeted fix in PikeVMMatcher: on a quantified group whose iteration matched empty via
a pure zero-width body, do not (re)bind; preserve the `(a?)*`→bind-to-empty behavior. Independent of the
DFA routing. (This is the JDK-quirk piece; corrected rule in §1-B.)

## 4. Recommended shape (to be confirmed by the review)
1. **Class A:** Option A — decline capture-ambiguous → PikeVM. Verify PikeVM correctness on the full
   Class-A corpus; verify no currently-correct DFA pattern regresses (the guard must not over-decline
   common patterns like `(a)|b`, `<(\w+)>|</(\w+)>` which are NOT ambiguous and must stay on DFA).
2. **Class C:** investigate whether it is ambiguity-flagged + PikeVM-correct (→ folds into Option A), or
   needs its own handling. If PikeVM is also wrong for greedy give-back inner-spans, escalate.
3. **Class B:** the PikeVM empty-iteration fix, gated to the precise JDK rule.
4. Reserve **Option B (multi-register TDFA)** only if the review finds a common ambiguous+perf-critical
   pattern that can't tolerate PikeVM.

## 5. Correctness obligations
- 25k findAll gate → 0 (ratchet `KNOWN_FINDINGS_BUDGET` down per class); smoke fuzz 0; runtime suite green.
- Pin the reproduced repros per class + the boundary `()a|b`→[0,0); pin common patterns stay on DFA (no
  over-decline) and stay correct.
- ReDoS-safety preserved: PikeVM and DFA are both O(n); no JDK fallback introduced.
- IAST capture patterns (COMMAND/URL/SQL, PIKEVM_CAPTURE) unaffected.

## 6. Risks / open questions (seed the adversarial review)
1. **OQ-1 — Is PikeVM correct for the WHOLE Class-A corpus** (every reproduced untaken-branch repro),
   including the boundary `()a|b`→[0,0) and named-group alternations? If any Class-A case is also wrong
   on PikeVM, Option A is incomplete.
2. **OQ-2 — Class C on PikeVM:** does PikeVM give JDK's greedy give-back inner-span for `(.*.)0{2}`,
   `a(c{0,}.+1).`? And does `hasCaptureAmbiguity` even flag Class-C patterns (they may have no bypass →
   not flagged → Option A won't route them)? If PikeVM-correct but not-flagged, we need a different
   decline trigger for Class C.
3. **OQ-3 — Over-decline / perf regression:** does declining on `hasCaptureAmbiguity` pull COMMON
   patterns off the fast DFA onto PikeVM? Quantify which patterns flip and the perf cost. The guard
   must be precise (it's "necessary but not sufficient" per the earlier Route-A review — production
   layers B10/B15/B16 add AST gates; do those interact?).
4. **OQ-4 — Is Option B ever justified?** Find (or rule out) a common, perf-critical, capture-ambiguous
   pattern that must stay on the DFA. If none exists, Option B (the deep TDFA rework) should be dropped.
5. **OQ-5 — Class B precise rule + scope:** confirm the zero-width-assertion-body vs optional-consumer
   distinction in PikeVM, and that the fix doesn't regress the IAST patterns or the 50k gate.
6. **OQ-6 — The `()a` boolean-miss** (codegen returns no match at all) found during boundary probing —
   is it in-scope (a separate boolean bug) or out? Triage its strategy + root cause.
