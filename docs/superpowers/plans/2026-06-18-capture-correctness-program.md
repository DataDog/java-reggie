# (b) Engine-wide capture-correctness program — design (pre-implementation)

Status: DRAFT for the design → adversarial-review → refine loop
Date: 2026-06-18
Branch: feat/pikevm-capture-cost
Builds on: the PikeVM single-pass drain fix (DONE) + the findAll differential oracle (the first oracle
to check group spans ≥1 on the *find* path). See [[project_drain_capture_effort]].

## Round-1 adversarial review — corrections folded (2026-06-18)
All four bugs CONFIRMED & reproduced; taxonomy sound. But component attribution and two diagnoses
were wrong — corrected here (evidence-backed), superseding the §2/§3 body where they conflict:
- **Component map (debugPattern-verified):** A and C route to **DFA_UNROLLED_WITH_GROUPS →
  `DFAUnrolledBytecodeGenerator`**, NOT MultiGroupGreedy/ConcatGreedyGroup. D is
  **`MultiGroupGreedyBytecodeGenerator` ONLY**, 2 sites (`:1666-1674` START in
  `generateAnchorMatchInline` `:1670`; and `generateAnchorMatchInlineForBounds` `:1729`).
  DFAUnrolled/DFASwitch START checks are **already origin-correct** (gate on outer `tryPos` vs 0) —
  do NOT touch them. So D = 1 generator / 2 sites, not 3 generators.
- **Class D diagnosis was INVERTED:** comparing the anchor pos to **`startOffsetVar` IS the bug**;
  **absolute 0 is the fix** (the §3.1 prescription was right; the §2-D "correct template" pointer was
  backwards — `:1666-1674` is the BUGGY site). JDK confirmed: `^`/`\A` anchor to absolute 0 on
  `find(start>0)`.
- **Class B rule was FACTUALLY WRONG:** `(a?)*` on "b" → JDK **binds** g1=`[1,1)` (not −1). True rule:
  a **pure zero-width-assertion body** (`\z`, empty `()`) leaves the group unbound on the empty
  iteration; an **optional-consumer body** (`a?`) binds it to the empty span. Do not encode
  "(x?)*→−1" anywhere. Scoped fallback (option ii) stays viable but its predicate must be
  conservative (fall back on ANY nullable-body quantified capturing group — `(a?)*` then also routes
  to JDK, which is safe).
- **A and C are NOT one fix (close §6.2):** shared substrate in DFAUnrolled (TDFA tags committed as
  transitions execute, `savedTags` snapshot taken with no reconciliation to the accepted path) but
  distinct: **A = which-branch** (a group on a non-chosen branch retains a tag write); **C =
  which-position** (the chosen branch's group-end tag resolves to greedy-max, not JDK's leftmost-
  longest). The proposed unifier fixes A, NOT C. Keep them separate.
- **C cannot be a "generator rollback":** DFAUnrolled is a non-backtracking TDFA — §3.3's "roll back
  the inner-group end" is unimplementable there. C's fix locus is **TDFA tag disambiguation in
  `SubsetConstructor`** (tag ops / priority), or fallback.
- **MISSED — a cheaper Class-A floor:** `FallbackPatternDetector` already has
  `hasNullableAlternationBranchAnywhere` (`:271-275`) and `hasStartClassAnchorInAlternationBranch`
  (`:259-266`) that detect Class-A shapes, but are gated to `OPTIMIZED_NFA` only — so `1|()b`
  (DFA_UNROLLED_WITH_GROUPS) escapes them. Extending these predicates' strategy scope to
  `DFA_*_WITH_GROUPS`/`PIKEVM_CAPTURE` is a lower-risk Class-A floor than per-generator surgery.

### Strategic consequence (decision needed): NATIVE fix vs correctness FLOOR
Native A/C = deep `SubsetConstructor` TDFA tag-disambiguation rework; native B = JDK-quirk
replication. Both high-risk. The cheap path (extend `FallbackPatternDetector` → route A/B/C to JDK)
makes the GATE green but those patterns then run on JDK (do NOT "beat re2j"). The user's "beat re2j
on all patterns" goal pulls toward native; risk/ROI pulls toward floor-for-the-broad-fuzz-space +
native-only-where-it-matters. **IAST capture patterns (COMMAND/URL/SQL, all PIKEVM_CAPTURE) must NOT
trip any fallback predicate — pin that.** Decide scope (native depth vs floor) before touching code.

## 1. Problem & evidence
The new `RegexFuzzOracle` findAll differential (group spans ≥1 on find) surfaced ~81 divergences on the
25k gate. They are **pre-existing** find-path group-capture bugs (proven: identical in the pre-step-2
engine; step-2 strictly reduced findings). Triage (12k sweep, reproduction-verified) buckets them into
**four shared root-cause classes** spanning PikeVM AND ~the codegen capture generators
(MultiGroupGreedy, ConcatGreedyGroup, and others) — NOT 15 independent bugs:

| Class | Signature | Reproduced example | Engines |
|---|---|---|---|
| **A** untaken-alternation-branch group not reset to −1 | JDK gN=`[-1,-1)`, reggie writes a span | `1\|()b` on `1` → g1 should be −1, reggie `[0,0)`; `(-?)b\|_` on `b__` | codegen (≈10), PikeVM (few) |
| **B** empty/zero-iteration quantified group | JDK leaves gN unset, reggie records the empty iteration | `b(\z){0,}\|[a-c]` on `b` → g1 should be −1, reggie `[1,1)` | PikeVM, codegen |
| **C** greedy give-back wrong inner-group end | inner group span doesn't match JDK's final backtracked span | `a(c{0,}.+1).`; `(.*.)0{2}` on `b00` → g1 `[0,1)` vs reggie `[0,2)` | codegen (≈8) |
| **D** codegen `^`/`$` findAll re-anchoring / count | spurious extra match; `^` matches at start>0 | `^([-]*)` on `0` → spurious `[1,1)` | codegen only (PikeVM already fixed) |

Goal: drive the 25k findAll gate to **0** (then reset `KNOWN_FINDINGS_BUDGET`), i.e. native group
spans match JDK for find/findAll across all strategies. JDK is the oracle.

## 2. Root cause per class (to be confirmed during impl)
- **D (most localized, highest confidence).** Generated `findMatchFrom(input,start)` evaluates the
  start anchor against the wrong origin. `MultiGroupGreedyBytecodeGenerator:1675-1682` checks
  `pos==0` literally in `matchFromPosition` where `pos` begins at `startPos`; the CORRECT template is
  right above it (`:1666-1674`, comparing `posVar` to `startOffsetVar`). Same bug shape in
  `DFAUnrolledBytecodeGenerator:3408-3413` and `DFASwitchBytecodeGenerator:2995-2998`. NOTE: for
  findAll the desired origin is **absolute 0** (JDK `Matcher.find(start)` anchors `^`/`\A` at input
  start, never at `start`) — exactly the invariant the PikeVM fix established. So the codegen fix is:
  the start-anchor must test against the **absolute input origin (0)**, independent of the scan start.
- **A (untaken branch).** A capturing group inside a not-taken alternation branch must read −1. The
  agent's claim that "all generators init slots to −1 and never mis-write" is CONTRADICTED by
  reproduced evidence (`1|()b`, `(-?)b|_`) → the greedy/multi-group generators write the group from a
  branch that the final match did not commit to (or fail to clear on branch abandonment). The fix is
  per-generator branch-local reset (or commit-on-accept) — investigate MultiGroupGreedy/ConcatGreedy
  group-write + branch-fail paths.
- **B (empty-iteration).** JDK's `Loop`/`GroupCurly` semantics: a quantified group whose only/last
  iteration matched empty does NOT bind the group (`(\z)*`, `(x?)*` → −1). PikeVM records the empty
  iteration's enterGroup/exitGroup. **This is genuine JDK-quirk territory** — the highest-risk class.
- **C (greedy give-back).** The recorded inner-group end reflects the max-greedy extent, not JDK's
  final backtracked extent after the outer pattern forces give-back. Generator backtracking records
  captures eagerly and doesn't roll them back on give-back.

## 3. Fix strategy — by root-cause class, shared where possible
Order by confidence/leverage (D → A → C → B):
1. **D — codegen start-anchor origin.** Fix the 3 generators to test `^`/`\A` against absolute 0
   (reuse the existing `startOffsetVar`-style correct template; thread an absolute-origin). Lowest
   risk, localized, mirrors the PikeVM fix. Pin `^([-]*)`, `^x|y`, `(?m)^` findAll differential tests.
2. **A — untaken-branch reset.** In the greedy/multi-group generators, ensure a group whose branch is
   not part of the committed match reads −1 (reset on branch entry, or only commit captures of the
   winning path). Pin `1|()b`, `(-?)b|_`, `(0)[^..]c|.`.
3. **C — greedy give-back rollback.** Roll back (or recompute) inner-group end when the outer match
   gives back characters. May reduce to "capture on the committed path only," shared with A.
4. **B — empty-iteration binding.** Match JDK's empty-last-iteration rule. HIGH RISK. Options:
   (i) replicate the JDK quirk precisely in PikeVM `updateCaptures`/loop-back handling + each codegen
   loop emitter; (ii) **scoped fallback**: detect nullable-body quantified capturing groups and route
   those patterns to JDK fallback (correctness floor) until a precise fix is justified. Recommend (ii)
   first (these are rare, not IAST), revisit (i) only if a real pattern needs it.

## 4. Eligibility / floor
Any pattern class where the native fix is unproven routes to JDK fallback (never wrong). The IAST
capture patterns (COMMAND/URL/SQL, all PIKEVM_CAPTURE) must stay native and must pass — pin them.

## 5. Verification
- Extend the differential corpus with one pinned test per class (the reproduced examples above).
- 25k findAll gate → 0 divergences; THEN set `KNOWN_FINDINGS_BUDGET=0` (or the true residual) and
  document any intentional JDK-fallback-routed class in `doc/temp/prod-readiness/fuzz-inventory.md`.
- Full runtime + integration suites green; spotlessApply before push.

## 6. Risks / open questions (seed the review)
1. **Class B is JDK-quirk-defined** — is bug-for-bug JDK parity worth it, or is scoped fallback the
   right floor? (Recommend fallback first.)
2. **A vs C overlap** — are they one root cause ("only commit winning-path captures; reset/rollback
   abandoned branches") or two? Unifying them shrinks the work.
3. **No shared capture-emission helper exists** (confirmed) — must D/A/C be fixed in each generator,
   or can a shared emit-helper be introduced without destabilizing 10 generators? (Per CLAUDE.md:
   propose helpers before adding them.)
4. **Interaction with the PikeVM single-pass** — the PikeVM A/B residuals (`b(\z){0,}|[a-c]`) must be
   fixed in `updateCaptures`/loop-back without regressing the just-landed drain fix or the 50k gate.
5. **Gate budget semantics** — bump-and-document now (so CI is green on the honest pre-existing
   baseline) vs hold the gate red until fixed? Decide before touching generators.
