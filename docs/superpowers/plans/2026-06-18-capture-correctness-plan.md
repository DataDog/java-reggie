# Capture-correctness — IMPLEMENTATION PLAN (Classes A/B/C/E)

Status: DRAFT for the plan→adversarial-review→improve loop. NO code until approved.
Date: 2026-06-18
From the converged design `2026-06-18-capture-correctness-design.md` (2 review iterations; Option B
dropped). Strategy: route hazardous capture patterns to PIKEVM_CAPTURE (native, O(n), ReDoS-safe) via
precise decline predicates + one PikeVM empty-iteration fix. JDK is the oracle; gate → 0 (ratchet 78).

## Plan-review round-1 (2026-06-18) — major refinements; give-back fix is largely SHIPPED
Evidence-tested; supersedes the body where it conflicts:
- **Line drift +3:** ambiguous DFA_UNROLLED **:951** / DFA_SWITCH **:960**; non-ambiguous DFA_UNROLLED
  **:1019** / DFA_SWITCH **:1029**; MGG **:384** (body **:6424**). `(.*.)0{2}` and `(a|ab)(c|bcd)`
  confirmed flowing the non-ambiguous path to :1019 (both wrong there).
- **Trigger C predicate REWRITE (it missed its own pin):** "last consuming element is a greedy
  quantifier" does NOT fire on `(.*.)0{2}` (last element is the bare `.`; the give-back is driven by the
  non-last `.*`). Correct predicate: **the group body contains ANY greedy quantifier whose repeated
  charset, after skipping any nullable in-body suffix, intersects the group's successor first-set.**
  Reuse the EXISTING `getSuffixFirstCharSetSkippingNullable(ConcatNode, fromIndex)` (:2029) — it is the
  successor-first-set primitive. Add nested-group recursion (pin `((.*.)x)y`).
- **The give-back fix is mostly ALREADY IN THE CODE — don't reinvent.** `PatternAnalyzer:753`
  `if (requiresBacktrackingForGroups(ast)) return RECURSIVE_DESCENT`; `requiresBacktrackingForGroups`
  (:1869) already does greedy-charset ∩ successor via `getGreedyGroupCharSet` (:1902) +
  `getSuffixFirstCharSetSkippingNullable` + `intersects`. Two real defects: **(a) ORDERING** — MGG
  (:384) fires before the :753 guard, so `(\w+)0`/`(\d+)5` reach MGG and return NO_MATCH; **(b)**
  `getGreedyGroupCharSet` only recognizes `(x*)` direct-child / trailing-optional, NOT the `(.*.)`
  greedy-not-last shape, so `(.*.)0{2}` escapes :753. **Minimal fix: hoist/consult :753 before MGG, and
  extend `getGreedyGroupCharSet` to the greedy-not-last shape.** Not a new predicate from scratch.
- **CRITICAL ReDoS obligation (was unaddressed):** when a give-back pattern declines, it currently
  falls to **RECURSIVE_DESCENT — a BACKTRACKING engine**. The plan's hard constraint is O(n)/ReDoS-safe.
  MUST verify RECURSIVE_DESCENT is ReDoS-bounded for these shapes (memoized/linear?), OR route the
  give-back family to **PIKEVM_CAPTURE** (definitely O(n)) instead. Resolve before implementing.
  (Reuse-vs-PikeVM is now the key open question — see [[project_reggie_safe_backtracking_investigation]].)
- **Trigger E TIGHTEN (over-declines):** single-alternation predicate fires on correct common patterns
  (`(https|http)`, `(a|ab)`, `(ab|abc)x`, `(a|ab)\d`, `(a|ab)c` — all correct today). The real defect
  needs **TWO interacting variable-length capturing alternations in sequence** (`(a|ab)(c|bcd)` — the
  second resolves the first's ambiguity). Restrict to that; KEEP-pin the listed common ones.
- **Class B lines + scope:** rebind is at TWO mirrored sites **:734 (addThread)** + **:805
  (addThreadToNlist)**, both gated by `groupBodyNullable` (precomputed `computeGroupBodyNullable` :987).
  The rule needs a NEW precompute distinguishing "empty ONLY via zero-width assertion" (`(\z)`,`()`) from
  "empty via optional consumer" (`a?`) — `groupBodyNullable` marks both true. Apply at BOTH sites.
- **Trigger A NOT refuted** — stands as written. IAST patterns confirmed unaffected (none touch the
  DFA-with-groups/MGG paths).
- **Give-back landscape has ≥4 strategies** (MGG, CONCAT_GREEDY :400, GREEDY_BACKTRACK :377,
  RECURSIVE_DESCENT :753) — enumerate them; the fix lives in the shared :753 routing, not per-strategy.

### Converged plan (post plan-review)
Trigger A (ambiguous path, :951/:960) → PikeVM; **give-back (Class C + MGG NO_MATCH family) → PIKEVM_CAPTURE**
— RESOLVED: route to PikeVM (provably O(n·m), verified-correct), NOT RECURSIVE_DESCENT, to honor the
ReDoS-safe constraint unconditionally (RECURSIVE_DESCENT's bound is unproven — deferred R&D, see
[[project_reggie_safe_backtracking_investigation]]). Mechanism: extend the give-back detector
(`getGreedyGroupCharSet` for the `(.*.)` greedy-not-last shape) and consult it BEFORE the MGG check
(:384) and at the non-ambiguous DFA sites (:1019/:1029), routing to PIKEVM_CAPTURE. (Reusing the existing
:753 RECURSIVE_DESCENT router is a deferred perf option, only if/when RD is proven ReDoS-bounded.)
Trigger E tightened to two-interacting variable-length capturing alternations (:1019/:1029) → PikeVM;
Class B PikeVM empty-iteration fix at :734+:805 with a new assertion-only-empty precompute.
**Plan is converged (design: 2 reviews; plan: 1 review + ReDoS resolution). Ready to propose implementation.**

## 0. Routing mechanism (confirmed)
`PatternAnalyzer.analyzeForCapture` already declines to PIKEVM_CAPTURE via a chain of
`FallbackPatternDetector` predicates (B10/B15/B16) in BOTH branches of `if (dfa.isCaptureAmbiguous())`:
- **ambiguous path** (PatternAnalyzer.java:902-978): state-count routes to DFA_UNROLLED_WITH_GROUPS
  (:948-957) / DFA_SWITCH_WITH_GROUPS (:958-967) after the B-predicates.
- **non-ambiguous path** (:980-1046): same shape, DFA_UNROLLED at :1016-1025 / DFA_SWITCH at :1026-1035.
- **MGG path**: `detectMultiGroupGreedyPattern(ast)` (:384, body :6424) → SPECIALIZED_MULTI_GROUP_GREEDY.
Adding a trigger = add a predicate check that returns the PIKEVM_CAPTURE result before the buggy
strategy is selected, at the matching site(s). Primitives confirmed present: `getFirstCharSet(RegexNode)`
(PatternAnalyzer:1969, private instance), `CharSet.intersects`/`.intersection` (CharSet.java:575/516),
`FallbackPatternDetector.subtreeIsNullable` (:715), `computeGroupsWithBypass` (SubsetConstructor:783).

## 1. Trigger A — nullable capturing group AND bypass  (ambiguous path)
- **Predicate (precise, preferred):** extend `SubsetConstructor` to expose the set
  `ambiguousNullableBypassGroups = groupsWithBypass ∩ {g : group g body is epsilon-nullable}` on the
  `DFA` object (a new `DFA.hasNullableAmbiguousGroup()` boolean alongside `captureAmbiguous`). The
  nullable test reuses the epsilon-only enter→exit reachability already computed for bypass. Decline
  when true. This is per-group-correct (avoids the two-different-groups over-decline of an AST approx).
- **Fallback encoding (if the DFA plumbing is undesirable):** AST predicate
  `FallbackPatternDetector.hasNullableCapturingGroup(ast)` (a capturing GroupNode whose child
  `subtreeIsNullable`); check it INSIDE the `isCaptureAmbiguous()==true` block (bypass already implied),
  before :948 and :958. Over-declines only the rare two-groups case (perf-only).
- **Insertion:** ambiguous path, before :948 (DFA_UNROLLED) and :958 (DFA_SWITCH).
- **Pins:** decline `1|()b`→−1, `()b|x`→−1, `(x*)y|z`→−1, `(\d*)-|;`→−1; KEEP on DFA + correct
  `(a)|b`, `(foo)|bar`, `(\d{4})-(\d{2})|N/A`, `(\s*)x`; harmless route `()a|b`→[0,0) (correct on both).

## 2. Trigger C — greedy give-back  (non-ambiguous DFA path AND MGG path) [HIGHEST PRIORITY]
- **Predicate:** `givebackHazard(node)` — exists a capturing group G whose **last consuming element is a
  greedy quantifier** Q (`QuantifierNode.greedy && (min≥1 || max>1 || max==-1)`) such that
  `charsetOf(Q.child) ∩ getFirstCharSet(successor-of-G) ≠ ∅`, where successor-of-G is the concatenation
  tail following G (and, if G is the whole tail, the element following G's enclosing quantifier). Uses
  `getFirstCharSet` + `CharSet.intersects`. "charsetOf(Q.child)" = the greedy-repeated element's charset
  (CharClassNode.chars, LiteralNode → singleton, `.` → all) — the REPEATED element, not a trailing fixed
  literal (round-2 refinement). Implement as a private method in PatternAnalyzer (needs getFirstCharSet).
- **Insertion (two sites):**
  - non-ambiguous DFA path, before :1016 (DFA_UNROLLED) and :1026 (DFA_SWITCH).
  - MGG: in `detectMultiGroupGreedyPattern` (:6424) return "not MGG" (or decline to PIKEVM) when
    `givebackHazard` holds — this is the boolean-miss family (`(\w+)0`, `(\d+)5`), highest priority.
- **Pins:** decline + correct `(.*.)0{2}`→[0,1) on "b00"; `(\w+)0`→[0,3) on "ab00" (was NO_MATCH);
  `(\d+)5`→[0,3) on "1235"; `([abc]+)c` on "abcc". KEEP on fast path (disjoint) `(\w+)\s`, `(\d+)\.(\d+)`,
  `([a-z]+)[0-9]`, `(a+)b`. Use give-back-FORCING inputs in pins (e.g. "1255" not "a55") + cold cache.

## 3. Trigger E — branch-length-ambiguous capturing alternation  (non-ambiguous DFA path)
- **Predicate:** `altBranchLengthAmbiguity(node)` — an AlternationNode whose two branches have
  intersecting first-sets (`getFirstCharSet(b1).intersects(getFirstCharSet(b2))`) AND differing minimum
  lengths, where the alternation participates in / precedes a capturing group whose span depends on the
  branch choice. Over-approximation acceptable (decline → PikeVM, perf only). Private method in
  PatternAnalyzer.
- **Insertion:** non-ambiguous DFA path, before :1016 and :1026 (same site as Trigger C).
- **Pins:** decline + correct `(a|ab)(c|bcd)`→g1=[0,1) g2=[1,4) on "abcd". KEEP `(a|b)c`,
  `(cat|dog)s` (non-overlapping branches) on DFA.

## 4. Class B — PikeVM empty-iteration fix  (runtime, independent)
- **Location:** `PikeVMMatcher` loop-back / `updateCaptures` (the trailing-empty-iteration rebind around
  the `groupBodyNullable` logic, ~:650/:741). **Rule (round-1-corrected):** on a quantified group whose
  iteration matched the empty string, bind the group ONLY if the body consumed or is an
  optional-CONSUMER body (`a?` → bind to empty span); a pure zero-width-assertion body (`\z`, empty `()`)
  must leave the group unbound on that empty iteration.
- **Pins:** `b(\z){0,}|[a-c]`→g1=−1 on "b"; `(a?)*`→g1=[k,k) (binds) on a matching input; the IAST
  capture patterns + 50k gate unaffected.
- Note: declining-to-PikeVM does NOT fix Class B (PikeVM is also wrong) — this is the only engine-side fix.

## 5. Over-decline / perf measurement (must run before/after)
- JMH (`IastRegexpBenchmark` capture variants) on the IAST capture patterns (must be UNAFFECTED — none
  trip the triggers; confirm) + a small corpus of common capture patterns that MUST stay on the DFA
  (Trigger pins §1-§3 "KEEP" set). Measure that no KEEP pattern flips strategy (assert via
  `debugPattern`/strategy probe) and no material perf regression on the IAST set.
- Quantify the decline blast radius: count, over a representative corpus, how many patterns flip
  DFA→PikeVM; `log`/document it (no silent broadening).

## 6. Verification & sequencing
1. **Class B first** (independent, runtime, unblocks PikeVM correctness that the triggers rely on).
2. **Trigger C** (highest priority — boolean misses; both DFA + MGG sites).
3. **Trigger A** (ambiguous path).
4. **Trigger E** (alternation).
After each: cold-cache regression pins green; 25k findAll gate drops (ratchet `KNOWN_FINDINGS_BUDGET`
toward 0, attributing the drop per class in `doc/temp/prod-readiness/fuzz-inventory.md`); full runtime +
integration suites green; IAST drain benchmark unaffected; spotlessApply. JDK is the oracle throughout.

## 7. Risks / open questions (seed the plan review)
1. **Trigger C successor computation** — when the greedy group is the LAST element (no successor in its
   concat), the "successor" is the element after the group's enclosing quantifier/group. Is the
   successor-walk well-defined for nested cases? Mis-defining it → false neg (silent wrong) or
   over-decline. Needs a precise spec + pins for nested shapes.
2. **getFirstCharSet returns null** (undeterminable) — what's the safe default? Likely "treat as
   intersecting" (decline = safe, perf-only) to avoid false negatives.
3. **Trigger E precision** — "differing min lengths + intersecting first-sets" may over-decline common
   alternations; measure blast radius; refine if it pulls common patterns off the DFA.
4. **MGG decline mechanics** — returning "not MGG" from `detectMultiGroupGreedyPattern` falls through to
   the next strategy; confirm that next strategy is correct (PIKEVM_CAPTURE or another DFA that's ALSO
   give-back-correct?), else explicitly return PIKEVM_CAPTURE.
5. **Trigger A DFA plumbing vs AST approx** — is exposing `hasNullableAmbiguousGroup` on DFA worth the
   per-group precision, or is the AST approx's rare over-decline acceptable? Decide in review.
6. **Interaction with existing B10/B15/B16** — do the new predicates overlap/conflict with the existing
   decliners (double-decline is harmless, but verify no pattern that SHOULD stay native is now declined)?
7. **Class B scope** — does the empty-iteration fix risk regressing the just-landed single-pass drain or
   the 50k gate? Gate it tightly to the zero-width-assertion-body case.
