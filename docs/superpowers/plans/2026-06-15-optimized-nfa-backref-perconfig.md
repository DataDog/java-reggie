# OPTIMIZED_NFA_WITH_BACKREFS Per-Config Worklist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the 3 OPTIMIZED_NFA_WITH_BACKREFS over-match fuzz divergences by replacing the lockstep NFA simulation (for backref-bearing patterns only) with a per-configuration worklist that carries its own position and capture spans.

**Architecture:** For patterns whose NFA contains a backref state, `NFABytecodeGenerator` emits a *different* execution skeleton: an explicit worklist of configurations `(state, pos, captures)`. A backref advances *that config's own* `pos` (fixing Defect B — shared-cursor corruption); per-config captures fix Defect A (capture-merge over-match, Alt 1); a configuration-dedup key + compile-time active-variable-degree (AVD) bound keep it near-linear, with a runtime cap as a defensive backstop (Alt 3). All existing lockstep code for non-backref patterns is **untouched**.

**Tech Stack:** Java 21, ASM 9.7 bytecode generation, JUnit 5, Gradle. Generated matchers extend `ReggieMatcher`.

---

## Background facts (verified against the codebase, 2026-06-15)

- `NFABytecodeGenerator` (reggie-codegen, 9710 lines) is **shared** by both compilation paths: `RuntimeCompiler.java:1102` and `ReggieMatcherBytecodeGenerator.java` both `new NFABytecodeGenerator(...)` and call the same `generate*Method`s. **The core change is single-source**; the dual-path rule (AGENTS.md) is satisfied by re-testing both paths, not by duplicating logic.
- The simulation is strictly **lockstep**: one shared `posVar`, `for (pos<len) { generateNFAStep; generateEpsilonClosureWithGroups(nextStates, pos) }` (NFABytecodeGenerator.java:1801–1844). All live states share one position.
- **Defect A:** `generateBackreferenceCheck` (line 7065) reads the **global** `groupStarts[]`/`groupEnds[]` (args at 7452–7453); per-state config arrays exist under `usePosixLastMatch` but use single-parent propagation, so converging paths lose capture history.
- **Defect B:** `generateBackreferenceCheck` advances `posVar` in place (7199–7202) inside the zero-width closure — its own comment flags this as breaking the NFA invariant.
- **Theory constraint (AGENTS.md O(n) guarantee):** general REWB membership is NP-complete; per-config simulation is only kept tractable by the dedup key + AVD bound. All 3 failing patterns have **AVD = 1** (single referenced group `\1`), so they stay near-linear.
- NFA model (reggie-codegen `automaton/NFA.java`): `NFAState.id` (int), `.enterGroup`/`.exitGroup`/`.backrefCheck` (Integer, nullable), `.anchor`, `.assertionType`, `.getEpsilonTransitions()`, `.getTransitions()`; `NFA.getStartState()`, `.getAcceptStates()`, `.getGroupCount()`, `.getStates()`. `NFA.contentHashCode()` already hashes `backrefCheck` (line 318).
- The 12 generated methods for this strategy: `generateMatchesMethod` (1450), `generateFindMethod` (2443), `generateFindFromMethod` (2535), `generateMatchMethod` (5999), `generateMatchIntoMethod` (6332), `generateMatchBoundedMethod` (6723), `generateMatchesBoundedMethod` (7617), `generateMatchBoundedCharSequenceMethod` (7648), `generateFindMatchMethod` (7686), `generateFindMatchFromMethod` (7748), `generateFindBoundsFromMethod` (7889), `generateFindLongestMatchEndMethod` (8022).
- The 3 target patterns + their **exact** minimal repro inputs (from `doc/temp/prod-readiness/fuzz-inventory.md` §B — do NOT guess inputs; the divergence only manifests on these):
  - P1 `(c{0}|1)(\1_{3}|.{1}[0-c])` on `1_` — **group-span** divergence (matches()/find() agree; g1 `[0,0)`→`[0,1)`, g2 `[0,2)`→`[1,2)`). The test MUST compare group spans, not just match spans.
  - P2 `(0{1,}|b{2}){2}(?:[a]|-{1})*(\1|c)` on `000a` — over-match (JDK no-match, Reggie `[0,4)`).
  - P3 `(0{1}|b{2}){2,}(?:[c]|-{1})(\1.|.c)` on `00cb` — over-match (JDK no-match, Reggie `[0,4)`).
- The fuzz oracle (`RegexFuzzOracle.java:97`) compiles with `ReggieOptions.builder().allowJdkFallback().build()`; for these natively-routed patterns fallback does NOT engage, so this option still exercises the buggy native path. The pin test uses the same option.
- **Done (Task 0, commit a48de00):** `PerConfigBackrefRegressionTest` written; `targetsAgreeWithJdk_acrossInputs` fails on the P1 group-span assertion (correct), `regressionPatternsStayCorrect` green.

## Convention for bytecode-generation steps

Generated ASM cannot be authored line-perfect without iterating against the JVM verifier. Therefore each codegen step specifies: **(a)** the exact insertion point (`file:line` / method), **(b)** the Java-level *semantics* the emitted bytecode must implement (reference pseudocode — this is the contract, not literal ASM), **(c)** the existing emit helpers to reuse, **(d)** the verification command. Test steps are exact Java and are authored verbatim. Reuse helpers: `addStateToSet`, `checkStateInSetConst` (1169), `pushInt`/`BytecodeUtil.pushInt`, `generateNFAStep`, `LocalVariableAllocator` (`allocateInt`/`allocateRef`). Never hardcode slots (`highestSlot+1`); never `visitLdcInsn` an int.

After **every** task: `./gradlew spotlessApply` before committing. Both paths must stay green: `./gradlew :reggie-runtime:test` and `./gradlew :reggie-codegen:test`.

---

## Phase 0 — Test scaffolding & regression baseline

### Task 0: Pin targets and the regression corpus

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PerConfigBackrefRegressionTest.java`
- Reference: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/StrategyCorrectnessMetaTest.java` (`routeOf`)

- [ ] **Step 1: Write the regression + target test (initially the 3 targets fail)**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the OPTIMIZED_NFA_WITH_BACKREFS per-config worklist redesign.
 *
 * <p>The three TARGET patterns are over-matches under the legacy lockstep simulation and must agree
 * with the JDK after the per-config capture work (Phase 2) lands. The REGRESSION patterns already
 * pass and must keep passing through every phase.
 */
public class PerConfigBackrefRegressionTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // (pattern, input) pairs that diverge today; must agree after Phase 2.
  private static final String[][] TARGETS = {
    {"(c{0}|1)(\\1_{3}|.{1}[0-c])", ""},
    {"(0{1,}|b{2}){2}(?:[a]|-{1})*(\\1|c)", ""},
    {"(0{1}|b{2}){2,}(?:[c]|-{1})(\\1.|.c)", ""},
  };

  // Patterns that work natively today; guard against regressions in the new skeleton.
  private static final String[][] REGRESSION = {
    {"(a{2})\\1", "aaaa"},
    {"<(\\w+)>.*</\\1>", "<b>x</b>"},
    {"(\\w+)\\s+\\1", "hi hi"},
    {"(ab)\\1", "abab"},
    {"(a)(b)\\2\\1", "abba"},
    {"(.)\\1", "zz"},
  };

  @Test
  void targetsAgreeWithJdk_acrossInputs() {
    for (String[] pi : TARGETS) {
      // Exercise empty and a few short inputs around the divergence.
      for (String in : new String[] {pi[1], "1", "c", "0", "b", "0b", "-c"}) {
        assertAgrees(pi[0], in);
      }
    }
  }

  @Test
  void regressionPatternsStayCorrect() {
    for (String[] pi : REGRESSION) {
      assertAgrees(pi[0], pi[1]);
    }
  }

  /** matches(), find() boolean, and first-match span must all agree with java.util.regex. */
  private static void assertAgrees(String pattern, String input) {
    var reggie = Reggie.compile(pattern, WITH_FALLBACK);
    Matcher jdk = Pattern.compile(pattern).matcher(input);

    assertEquals(
        jdk.matches(), reggie.matches(input), "matches() /" + pattern + "/ on \"" + input + "\"");

    Matcher jf = Pattern.compile(pattern).matcher(input);
    boolean jdkFind = jf.find();
    assertEquals(jdkFind, reggie.find(input), "find() /" + pattern + "/ on \"" + input + "\"");
    if (jdkFind) {
      var rm = reggie.findMatch(input);
      assertEquals(jf.start(), rm.start(), "find start /" + pattern + "/ on \"" + input + "\"");
      assertEquals(jf.end(), rm.end(), "find end /" + pattern + "/ on \"" + input + "\"");
    }
  }
}
```

- [ ] **Step 2: Confirm routing is unchanged and run the baseline**

Run: `./gradlew :reggie-runtime:debugPattern -Ppattern="(c{0}|1)(\\1_{3}|.{1}[0-c])"`
Expected: strategy `OPTIMIZED_NFA_WITH_BACKREFS` (confirms these patterns reach the code we are changing). Repeat for P2, P3.

Run: `./gradlew :reggie-runtime:test --tests PerConfigBackrefRegressionTest`
Expected: `regressionPatternsStayCorrect` PASS; `targetsAgreeWithJdk_acrossInputs` FAIL (over-match). This failing target test is the Phase-2 acceptance gate.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PerConfigBackrefRegressionTest.java
git commit -m "test: pin per-config backref targets + regression corpus"
```

---

## Phase 1 — Defect B: per-config worklist skeleton (global captures)

Goal: route backref-bearing patterns through a new skeleton where each configuration owns its `pos`. Captures still use the **global** arrays (Phase 1 isolates the *position* fix). Acceptance: regression corpus stays green; targets still fail (their bug is capture-merge, addressed in Phase 2). Any pre-existing pos-corruption bug is fixed here.

### Task 1: Backref detection + dispatch switch

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`

- [ ] **Step 1: Add `nfaHasBackref()` helper**

Insert near the other private predicate helpers (e.g. after the field block ~line 320). Semantics:

```java
/** True when any NFA state performs a backreference check (selects the per-config skeleton). */
private boolean nfaHasBackref() {
  for (NFA.NFAState s : nfa.getStates()) {
    if (s.backrefCheck != null) return true;
  }
  return false;
}
```

- [ ] **Step 2: Add a single dispatch helper that the 12 methods consult**

Add `private void generatePerConfigBody(MethodVisitor mv, LocalVariableAllocator allocator, PerConfigMode mode, String className)` (skeleton emitter, filled in Task 3–6) and an enum `PerConfigMode { MATCHES, FIND, FIND_FROM, MATCH, MATCH_INTO, MATCH_BOUNDED, FIND_MATCH, FIND_MATCH_FROM, FIND_BOUNDS_FROM, FIND_LONGEST_END }`. For Task 1 leave the body as `throw new UnsupportedOperationException("per-config not wired")` so the class still compiles.

- [ ] **Step 3: Gate `generateMatchesMethod` only (incremental)**

At the top of `generateMatchesMethod` (after the null check, ~line 1466), insert:

```java
if (nfaHasBackref()) {
  generatePerConfigBody(mv, allocator, PerConfigMode.MATCHES, className);
  mv.visitMaxs(0, 0);
  mv.visitEnd();
  return;
}
```

Leave the other 11 methods on the legacy path for now (they still call the lockstep closure; correctness for `matches()` is what Phase-1 tests check first).

- [ ] **Step 4: Verify it compiles and legacy path unaffected**

Run: `./gradlew :reggie-codegen:compileJava :reggie-runtime:test --tests StrategyCorrectnessMetaTest`
Expected: compiles; meta-test still green (no method yet emits the new body for a real pattern because `generatePerConfigBody` throws — so temporarily make Step 3 guard also require a system property `-Dreggie.perconfig=true`, OR proceed directly to Task 2–6 in one branch before exercising). **Decision:** gate Step 3 behind `Boolean.getBoolean("reggie.perconfig")` until Task 6 completes, then remove the property gate in Task 6 Step 5.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add -A && git commit -m "feat: per-config dispatch scaffold for backref NFAs (gated)"
```

### Task 2: Configuration storage + worklist primitives

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java` (inside `generatePerConfigBody` helpers)

- [ ] **Step 1: Define the config layout (Structure-of-Arrays, preallocated, allocation-free hot loop)**

A configuration is `(state, pos, captures)`. Phase 1 uses global captures, so a config is `(state, pos)`. Emit, at method entry, reusable int arrays sized `CAP` (constant, see Task 7 for value; use `int CAP = 256` in Phase 1):

```text
int[] wlState = new int[CAP];   // config NFA state id
int[] wlPos   = new int[CAP];   // config input position
int wlSize    = 0;              // stack pointer (LIFO worklist)
```

Allocate slots via `allocator.allocateRef()` (arrays) and `allocator.allocateInt()` (wlSize). Emit `NEWARRAY T_INT` once at entry. Document slots in a comment block (AGENTS.md best practice).

- [ ] **Step 2: Emit `pushConfig(stateId, pos)` and `popConfig()` as inline bytecode templates**

Semantics (inline, not a JVM method call — keep hot path allocation/ call-free):

```text
pushConfig(s, p):  wlState[wlSize] = s; wlPos[wlSize] = p; wlSize++;
popConfig():       wlSize--; cur = (wlState[wlSize], wlPos[wlSize]);
```

Provide Java helper methods on the generator that emit these templates: `emitPushConfig(mv, wlStateVar, wlPosVar, wlSizeVar, stateIdExprEmitter, posExprEmitter)`.

- [ ] **Step 3: Compile check**

Run: `./gradlew :reggie-codegen:compileJava`
Expected: compiles (templates not yet invoked from a reachable path).

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "feat: per-config worklist storage + push/pop templates"
```

### Task 3: Per-config zero-width closure (groups, anchors, assertions; backref handled in Task 4)

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`

- [ ] **Step 1: Emit the closure-of-one-config**

Reuse the *structure* of `generateEpsilonClosureWithGroups` (line 7221) but operating on a **single** config `(cur.state, cur.pos)`, expanding epsilon-reachable states and pushing each as a new config **at the same pos** (zero-width). Group enter/exit update the **global** `groupStarts[]`/`groupEnds[]` at `cur.pos` (Phase-1 semantics, identical to legacy global update). Anchors: evaluate against `cur.pos` exactly as the legacy switch at 7474–7535; if the anchor fails, do not push targets. Assertions: reuse `generateAssertionCheck` against `cur.pos`. **Do not** handle backref here yet (Task 4).

Reference pseudocode for the config-expansion inner loop:

```text
expandClosure(cur):
  for tEps in cur.state.epsilonTransitions:
    target = tEps
    if target.enterGroup != null:  groupStarts[target.enterGroup] = cur.pos   // global, Phase 1
    if target.exitGroup  != null:  groupEnds[target.exitGroup]   = cur.pos
    if target.anchor != null and !anchorHolds(target.anchor, cur.pos): continue
    pushConfig(target.id, cur.pos)
```

(Group updates actually occur when *entering* the state carrying enter/exit; mirror the legacy placement: the legacy code updates on the state being processed, not on the transition target. Match the legacy ordering exactly to preserve global-capture behavior.)

- [ ] **Step 2: Compile check**

Run: `./gradlew :reggie-codegen:compileJava`
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "feat: per-config zero-width closure (groups/anchors/assertions)"
```

### Task 4: Backref as a per-config consuming transition (Defect B fix)

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`

- [ ] **Step 1: Emit backref consume against the config's own pos**

When the closure reaches a state with `backrefCheck = g`, emit (reusing the regionMatches logic from `generateBackreferenceCheck` 7102–7172 — **but read the captured span and advance `cur.pos`, never a shared cursor**):

```text
onBackref(cur, g):
  gs = groupStarts[g]; ge = groupEnds[g];          // global in Phase 1
  if (gs < 0 || ge < gs) return;                    // uncaptured / partial -> dead config
  L = ge - gs;
  if (L == 0) { for t in state.epsilonTransitions: pushConfig(t.id, cur.pos); return; }
  if (cur.pos + L > len) return;                    // not enough input -> dead config
  if (!input.regionMatches([ic,] cur.pos, input, gs, L)) return;
  for t in state.epsilonTransitions: pushConfig(t.id, cur.pos + L);   // OWN pos advanced
```

This is the literal Defect-B fix: the backref pushes a config at `cur.pos + L`; the shared `posVar` is never mutated.

- [ ] **Step 2: Compile check**

Run: `./gradlew :reggie-codegen:compileJava`
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "fix: backref consumes via config-local pos, not shared cursor (Defect B)"
```

### Task 5: Per-config char-consume step + accept detection for `matches()`

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`

- [ ] **Step 1: Emit the worklist driver for `matches()`**

```text
init: groupStarts/groupEnds filled -1; groupStarts[0]=0; wlSize=0;
      pushConfig(startState.id, 0); 
loop while wlSize>0:
  cur = popConfig();
  expandClosure(cur)             // Task 3+4: may push more configs (same or advanced pos)
  // char consume: only for the popped config's literal/charclass transitions
  if cur.pos < len:
    ch = input.charAt(cur.pos);
    for t in cur.state.transitions: if t.matches(ch): pushConfig(t.target.id, cur.pos+1);
  // accept: matches() requires whole-input match
  if cur.pos == len && cur.state in acceptStates: groupEnds[0]=len; return true;
return false;
```

Note: because closure pushes configs at the same `pos`, and consume/backref push at advanced `pos`, the LIFO worklist explores depth-first. **Correctness does not depend on order in Phase 1** (boolean accept). Capture/priority ordering is a Phase-2 concern.

- [ ] **Step 2: Remove the `reggie.perconfig` property gate from Task 1 Step 4 for `matches()` only** (the body is now real).

- [ ] **Step 3: Run regression + targets**

Run: `./gradlew :reggie-runtime:test --tests PerConfigBackrefRegressionTest`
Expected: `regressionPatternsStayCorrect` PASS (matches() path now per-config and still correct). `targetsAgreeWithJdk_acrossInputs` may still FAIL on find/findMatch (other methods still legacy) — that is acceptable at this step; matches() for targets should now be correct *if* the target divergence was matches()-based; document which sub-checks pass.

Run the full backref suite to catch regressions:
`./gradlew :reggie-runtime:test --tests "*Backref*"`
Expected: no new failures vs. baseline.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "feat: per-config worklist driver for matches() (Defect B complete)"
```

### Task 6: Wire the remaining 11 methods to the per-config skeleton

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`

- [ ] **Step 1: Generalize the driver by `PerConfigMode`**

Differences per mode (all share closure/consume/backref):
- `FIND`/`FIND_FROM`/`FIND_BOUNDS_FROM`/`FIND_MATCH`/`FIND_MATCH_FROM`: seed a config at every start offset `i` (or iterate the outer start loop as the legacy find does), accept on **any** config reaching an accept state; track earliest start then longest end for span (defer span semantics to Phase 2 — Phase 1 may report the legacy span for find* and only guarantee the boolean).
- `MATCH`/`MATCH_INTO`/`MATCH_BOUNDED`/`MATCH_BOUNDED_CHARSEQ`: anchored whole-region match; same accept rule as `matches()` against the region end.
- `FIND_LONGEST_MATCH_END`: return the max accept `pos`.

Add each method's `if (nfaHasBackref()) { generatePerConfigBody(mv, allocator, MODE, className); ...return; }` guard at its top (lines 2443, 2535, 5999, 6332, 6723, 7648, 7686, 7748, 7889, 8022; `generateMatchesBoundedMethod` 7617 delegates to `matchBounded`).

- [ ] **Step 2: Remove the `reggie.perconfig` gate entirely.**

- [ ] **Step 3: Run the full target + regression + meta tests**

Run: `./gradlew :reggie-runtime:test --tests PerConfigBackrefRegressionTest --tests StrategyCorrectnessMetaTest --tests "*Backref*"`
Expected: `regressionPatternsStayCorrect` PASS; meta-test PASS; backref suite no new failures. Targets: boolean checks PASS; span checks may still differ (Phase 2).

- [ ] **Step 4: Dual-path check (compile-time generator)**

Run: `./gradlew :reggie-benchmark:build` (exercises `@RegexPattern` / `ReggieMatcherBytecodeGenerator`).
Expected: builds; no `VerifyError`.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "feat: route all backref NFA methods through per-config worklist"
```

---

## Phase 2 — Alt A: per-config capture columns (fixes the 3 targets)

Goal: give each configuration its own capture spans so `\1` resolves against the spans on *its own* path. (The dedup key + priority ordering originally scoped here moved to Phase 3 / Task 8 after the design reviews — see that phase.)

### Task 7: Per-config capture columns — ✅ DONE (Alt A: outer columns only)

**Decision (2026-06-16):** Adversarial review + a spike showed the 3 target over-matches are caused
entirely by **Defect A1** (cross-config clobbering of the *shared global* capture arrays), not by
the same-pos within-closure merge (**Defect A2**). Outer capture columns alone — without the inner
closure becoming capture-aware ("Alt B") — flip all 3 targets green AND leave P1's spans intact.
Alt B's inner-closure rewrite was therefore **not built**; A2 is recorded as a deferred gap below.

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`

- [x] **Step 1: Outer per-config capture columns**

Each outer worklist slot gets its own capture row:

```text
int[] wlGS = new int[CAP * (groupCount+1)];   // wlGS[i*(gc+1)+g] = config i's start of group g
int[] wlGE = new int[CAP * (groupCount+1)];
```

The global `groupStarts/groupEnds` become a **scratch working row**: loaded from the popped
config's row on each outer pop (`System.arraycopy(wlGS, wlSize*width, groupStarts, 0, width)`),
mutated in-place by the inner closure's enterGroup/exitGroup, read by the backref, and **snapshotted
into the child slot** by `emitPushConfig` on every outer push (char consume, non-empty backref).
New helper `emitRowCopy`. Arrays preallocated once at method entry → hot loop allocation-free.
The inner `seen[]` closure is unchanged (still state-only dedup — that is the A2 gap).

- [x] **Step 2: Backref reads config-local spans**

No code change needed beyond Step 1: the backref already reads `groupStarts/groupEnds`, which now
hold the popped config's own row (loaded on pop), so `\1` resolves against the spans on *its own*
config's path.

- [x] **Step 3: Run targets** — `targetsAgreeWithJdk_acrossInputs` PASS (boolean + span);
  `regressionPatternsStayCorrect` PASS; `StrategyCorrectnessMetaTest` + `*Backref*` + full
  `:reggie-runtime:test` PASS; `:reggie-benchmark:build` clean (no VerifyError).

- [x] **Step 4: Commit** — `fix: per-config capture columns for backref resolution (Alt A, fixes 3 targets)`

#### Deferred gap — Defect A2 (same-pos within-closure capture merge)

The inner epsilon-closure still dedups by `stateId` only (`emitPerConfigInnerPush`), so two eps
paths reaching the same state at the same pos with *different* group spans are merged
(last-write-wins on the working row). This cannot affect the 3 targets or the current regression
corpus (verified: the backref states are reached after their referenced group is closed, so no
same-pos re-entry occurs). **A2 is closed by Task 8** — the priority-ordered frontier dedups on
`(state, pos, referenced spans)` with full per-config rows, so divergent-span paths get distinct
keys and are no longer merged. (This supersedes the earlier "build Alt B only if the fuzz gate needs
it" note: Task 8's restructure *is* the Alt C unification, and closing A2 falls out of it.)

## Phase 3 — Correct + bounded backref matching: PikeVM engine → AVD gate → cap

Goal: every backref pattern Reggie matches itself (i.e. not JDK-delegated) is correct (JDK-equal
spans) **and** bounded (no exponential blow-up, no silent drops). Order: **Task 8 → Task 10 → Task 9.**

- **Task 8** delivers the principled priority + capture engine. **Decision (2026-06-16): start with a
  backref-capable interpreted PikeVM** (reuse the already-correct priority machinery), then a
  **benchmark gate vs JDK** decides whether to stay interpreted or escalate to a native design
  (8-minimal / 8-full). This is the linchpin: it makes spans JDK-correct, makes the search
  finite/polynomial (refspan-keyed memo), and closes the Task 7 **A2** gap.
- **Task 10** adds a compile-time **AVD gate** that bounds the polynomial *degree* and routes
  over-budget patterns to fallback, riding on the referenced-group set Task 8 needs.
- **Task 9** adds a fixed **cap / memo ceiling** as a pure DoS backstop.

> **Engine is benchmark-selected (Task 8 Step 4).** Tasks 9 & 10 apply to *whichever* engine wins:
> if the interpreted PikeVM ships, the cap/memo live in `PikeVMMatcher` and the AVD gate routes
> PikeVM-vs-JDK-fallback; if a native design is escalated to, they live in the generated engine as
> originally written. The cross-cutting design below holds either way.

### Cross-cutting design (applies to Tasks 8, 9, 10)

These were settled across the 2026-06-16 design reviews; each task references them rather than
re-deriving:

1. **Target semantics = Perl / `java.util.regex`, NOT POSIX.** The regression + meta tests compare
   directly to `java.util.regex`: **leftmost match, first-alternative priority, greedy/lazy as
   written, last-iteration capture for repeated groups.** The misleadingly-named `usePosixLastMatch`
   flag in the legacy methods happens to encode "repeated group keeps its last iteration", which
   *is* Java's behavior — but the **per-config path does not implement it at all today** (it does
   last-write-wins on the working row). Task 8 must add real priority selection on this path.
2. **Dedup key = `(stateId, pos, referenced-group spans)` — referenced groups ONLY.** Including
   non-referenced groups would break the AVD bound. Two configs equal on this key but differing in
   *non-referenced* spans MUST be merged keeping the **higher-priority** one (Task 8) — otherwise the
   reported non-referenced spans go wrong (this is what would regress P1's group-2 span). So
   **dedup and priority are inseparable**; they are one task, not two.
3. **Referenced-group set is shared analysis.** Task 8's dedup key and Task 10's AVD both need "which
   groups are targets of some `\k`". Compute once (`collectBackrefsInSubtree`) and thread it to both
   the generator (Task 8) and the analyzer (Task 10).
4. **Fixed, preallocated, reused resource arrays — no input-length scaling.** Both the dedup
   structure (Task 8) and the worklist arrays (Task 9) are fixed-size, allocated once as
   `ReggieMatcher` instance fields (mirroring `initNFAState`, `:187`), reused across match calls.
   `8*len` scaling is rejected (O(n²) worst case is un-preallocatable; defeats the cap).
5. **Unified fallback model — no AOT-vs-runtime asymmetry.** Both the compile-time AVD gate (Task 10)
   and the runtime cap-overflow (Task 9) route to fallback via the **same** existing
   `allowJdkFallback` flag (runtime `ReggieOptions.allowJdkFallback()`; AOT
   `ReggieOption.ALLOW_JDK_FALLBACK` via `resolveRealization(boolean)`):
   - **allowed** → delegate to `java.util.regex` (AVD gate → `DELEGATE_FALLBACK` /
     `JavaRegexFallbackMatcher`; cap-overflow → base-class `jdk*` helpers; the AOT path *can*
     delegate at runtime when annotated `ALLOW_JDK_FALLBACK`).
   - **not allowed** → **fail** (AOT: build error; runtime: throw).
   The generator takes one generation-time boolean and emits delegate-or-throw — no runtime field,
   no path-specific branching.

### Task 8: principled backref engine

> **OUTCOME (2026-06-16): shape B built, FAILED the benchmark gate → escalating to 8-full.** The
> interpreted `BackrefBacktrackMatcher` (memoized priority-DFS) is **correct** (12,817 differential
> checks vs JDK; one documented self-ref deviation) but **30-100× slower than `java.util.regex`** on
> common backref patterns — memoization is pure overhead on patterns JDK doesn't blow up on, and
> allocation tuning can't close a 50× gap. Per the pre-stated criterion (<1× → escalate), routing was
> reverted (commit `ff58f19`); the interpreted matcher + differential tests are **kept as the
> correctness oracle**. **Now building "8-full": the same memoized-priority-DFS algorithm generated in
> bytecode**, validated against the oracle. ⚠️ Open risk: the memo overhead is algorithmic, so even
> compiled it may not beat JDK on simple AVD=1 patterns — calibrate against the existing Task-7 native
> engine. See "Escalation alternatives → 8-full" below.

#### (historical) Shape B — interpreted PikeVM-style backref matcher

> **Decided 2026-06-16 (design pass → adversarial review → fork resolution).** Two earlier framings
> were rejected: "small open-addressing hash" (under-scoped — dedup without priority regresses P1's
> non-referenced spans) and "priority-ordered frontier generated in bytecode" (design v1 —
> principled but high-risk: explicit-stack DFS faithfulness, PikeVM priority corrections like
> `clistViaMultipleAnchors`, large test burden). **Chosen approach: extend the already-correct
> interpreted `PikeVMMatcher` to handle backreferences**, reusing its proven Perl-priority +
> capture machinery (risks A/B/F from the review do not apply to interpreted code). Then **gate on a
> benchmark**: if the interpreted engine is **slower than `java.util.regex`** on backref patterns,
> escalate to a native design (8-minimal dedup, or 8-full generated DFS). The principled
> algorithm is the same memoized-priority-backtracking as design v1, but expressed in interpreted
> Java where it is straightforward and testable.

**Status of the generated per-config engine (Tasks 1–7):** retained as the **native-escalation
candidate**, not deleted. If the benchmark gate fails, it (plus 8-minimal/8-full) is the fallback
path. Until then, backref patterns route to the PikeVM engine for correctness.

> **Shape decided 2026-06-16: B — new sibling matcher, `PikeVMMatcher` untouched.** PikeVM is strictly
> lock-step (`clist`/`nlist`, `stepChar(pos+1)`), which cannot express a backref's variable `+L`
> advance. Rather than destabilize the shipping `PIKEVM_CAPTURE` engine by generalizing its loop
> (option A), build a **separate interpreted memoized-priority backtracker** (design v1's algorithm)
> that *reuses PikeVM's conventions* — capture-array slot layout, NFA priority-ordered epsilon edges,
> and the `*Backref*` / priority test corpus — but leaves `PikeVMMatcher` alone.

**Files:**
- Create: `reggie-runtime/.../<BackrefBacktrackMatcher>.java` (new interpreted engine extending
  `ReggieMatcher`; mirrors `PikeVMMatcher`'s construction + capture/priority conventions)
- Modify: routing — `reggie-codegen/.../analysis/PatternAnalyzer.java` and the delegate realization
  (`RuntimeCompiler` + `ReggieMatcherBytecodeGenerator.resolveRealization`) so AVD-eligible
  `OPTIMIZED_NFA_WITH_BACKREFS` patterns route to the new matcher
- Test: `PerConfigBackrefRegressionTest`, `*Backref*`, `StrategyCorrectnessMetaTest`

- [ ] **Step 1: Build the interpreted memoized-priority backtracker**

New matcher running the NFA as priority-ordered DFS: expand epsilon edges in priority order (greedy /
first-alternative first, per `getEpsilonTransitions()` order); on `state.backrefCheck`, read the
referenced group's span from the current capture vector, consume `L` matching chars, continue at
`pos+L`; memoize on **`(state, pos, referenced-group spans)`** (cross-cutting #2) so divergent-`\k`-span
paths are distinct and the first (highest-priority) arrival wins. First accepting path in preorder is
the Perl-correct match (incl. non-referenced spans). Use an explicit stack (no deep recursion) to
avoid `StackOverflowError` on large inputs.

- [ ] **Step 2: Route AVD-eligible backref patterns to it**

Make `OPTIMIZED_NFA_WITH_BACKREFS` (within the Task 10 AVD bound) realize as the backref PikeVM
(`DELEGATE_PIKEVM`) on both paths. Governed by the unified fallback model (cross-cutting #5) for
patterns outside the bound.

- [ ] **Step 3: Validate spans, not just booleans**

Run: `./gradlew :reggie-runtime:test --tests "*Backref*" --tests StrategyCorrectnessMetaTest --tests PerConfigBackrefRegressionTest`
Expected all green — **especially** P1's non-referenced group-2 span (the priority canary) and the
alternation/quantifier span cases in `StrategyCorrectnessMetaTest`. Also `./gradlew :reggie-benchmark:build`.

- [ ] **Step 4: Benchmark gate vs JDK (decides escalation)**

Benchmark backref patterns (reuse `reggie-benchmark` / `AllStrategyVsJdkBenchmark`) Reggie-PikeVM vs
`java.util.regex`. **If Reggie ≥ 1× JDK on affected patterns → ship this; Tasks 9/10 adjust to the
PikeVM engine (cap/memo live in `PikeVMMatcher`; AVD gate routes PikeVM-vs-fallback).** **If < 1× JDK
→ escalate** to 8-minimal (native dedup on all spans) or 8-full (generated priority-DFS), recorded as
alternatives below.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "feat: backref-capable PikeVM — principled Perl-priority backref matching"
```

#### Escalation alternatives (only if Step 4 benchmark < 1× JDK)

- **8-minimal:** in the generated per-config engine, dedup key = `(state, pos, ALL spans)` (never
  merges span-divergent configs → no priority logic, no P1 regression); relies on the Task 9 cap for
  the (non-AVD, weaker) bound; closes A2 only at the outer level. Low risk, native speed, best-effort
  priority.
- **8-full:** generated priority-ordered memoized DFS (design v1) — principled + native + tight AVD
  bound, but carries review risks A (explicit-stack DFS faithfulness), B (no recursion in bytecode),
  F (replicating PikeVM priority corrections). Highest risk/effort.

### Task 10: Compile-time active-variable-degree gate (rides on Task 8's referenced-group key)

> **Decisions (2026-06-16):** new `FallbackPatternDetector.needsFallback` reason joining the existing
> `OPTIMIZED_NFA_WITH_BACKREFS` rules (`:116-140`). **Scope: gate ONLY `OPTIMIZED_NFA_WITH_BACKREFS`**
> (the per-config Thompson engine — the one the AVD polynomial bound is derived for). **AVD basis:
> AST-conservative** (reuse the referenced-group analysis from Task 8 / `collectBackrefsInSubtree`).
> **Threshold = 2** (AVD>2 → fallback). Only meaningful *after* Task 8 makes the worklist polynomial.
>
> **Measured coverage cost (corpus of 162 distinct backref patterns):** 93.2% are single-reference
> (AVD=1, unaffected); upper bound on over-routing at threshold=2 is **6 patterns (3.7%)** (only
> ≥3-distinct-reference patterns can be affected; AVD ≤ distinct-referenced-groups). The
> AST-conservative-vs-precise penalty is a *subset* of those, concentrated in staggered
> self-referencing patterns (`^(a\1?)(a\1?)(a\2?)(a\3?)$`); real-world usage is essentially all
> AVD=1. Over-routed patterns remain *correct* via fallback.

> **TODO — gate the OTHER backref engines separately (recorded 2026-06-16).** `VARIABLE_CAPTURE_BACKREF`,
> `FIXED_REPETITION_BACKREF`, and `RECURSIVE_DESCENT` are **backtracking** engines
> (`PatternAnalyzer.java:80,375`) with exponential worst-case time on ambiguous/high-AVD patterns —
> the per-config AVD bound does NOT apply to them. They need their own engine-specific complexity
> guard, not this AVD rule. Out of scope here; file as a follow-up so the O(n)-by-construction goal
> eventually covers every native backref engine.

**Files:**
- Modify: `reggie-codegen/.../codegen/analysis/PatternAnalyzer.java` and/or `reggie-codegen/.../codegen/analysis/FallbackPatternDetector.java`
- Test: `reggie-codegen/src/test/java/.../analysis/` (new `ActiveVariableDegreeTest.java`)

- [ ] **Step 1: Compute AVD (AST-conservative)** — max overlap of referenced-group live spans (a
  referenced group is live across the smallest subtree spanning its definition and all its `\k`
  uses). Reuse Task 8's referenced-group set. For the 3 targets AVD=1.

- [ ] **Step 2: Route high-AVD patterns to fallback** — if `AVD > 2` **and**
  `strategy == OPTIMIZED_NFA_WITH_BACKREFS`, add a `FallbackPatternDetector` reason `"backref
  active-variable-degree N exceeds native bound: would not run in linear time"` (handled by the
  unified `allowJdkFallback` model — cross-cutting #5). Do NOT gate the other backref strategies.
  Update the AGENTS.md fallback table.

- [ ] **Step 3: Unit-test** — `ActiveVariableDegreeTest`: AVD==1 for the 3 targets; a chosen AVD≥3
  pattern routes to fallback. `./gradlew :reggie-codegen:test --tests ActiveVariableDegreeTest`.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "feat: compile-time AVD gate keeps native backref patterns O(n)"
```

### Task 9: Post-dedup configuration cap (DoS backstop, unified fallback)

**Depends on Tasks 8 + 10.** After dedup (Task 8) the worklist is finite and polynomial
(≈ `states × distinct referenced-span vectors`; O(states·n²) worst case at AVD=1), and Task 10 keeps
the degree low. So the cap is **a pure resource governor, not a correctness crutch** — hitting it
means "this pattern×input is too expensive natively" → unified fallback model (cross-cutting #5).
(Pre-dedup, the cap did double duty — bounding memory *and* corrupting answers by dropping configs;
Task 8 removes that.)

**Files:**
- Modify: `reggie-codegen/.../codegen/NFABytecodeGenerator.java`, `reggie-runtime/.../ReggieMatcher.java`
- Test: `PerConfigBackrefRegressionTest.java`

- [ ] **Step 1: Base-class delegation helpers** — `ReggieMatcher` already has the lazy `jdkRich`
  Pattern (`:96`) and `jdkMatch`/`jdkFindMatch`/`jdkFindMatchFrom` (`:148-164`, MatchResult; boolean
  = `!= null`). Add the missing siblings the boolean/int/array per-config methods need:
  `jdkMatches→boolean`, `jdkFindLongestEnd→int`, array-fill for `matchInto`, region for
  `matchBounded`. (Delegation is base-class, NOT an emitted field.)

- [ ] **Step 2: Enforce the fixed cap** — `CAP` is a fixed hard ceiling (no `8*len`). On
  `wlSize > CAP`, the generated overflow branch emits delegate-or-throw per the generation-time
  `allowJdkFallback` flag (cross-cutting #5). Worklist arrays are reused `ReggieMatcher` instance
  fields (cross-cutting #4).

- [ ] **Step 3: Overflow tests (both fallback modes)** — engineer a pattern×input exceeding the cap;
  assert (a) with fallback it agrees with the JDK via the `jdk*` delegate, (b) without fallback it
  throws cleanly (no wrong answer). Post-dedup the cap may be unreachable by corpus patterns — if so,
  force a tiny cap synthetically and document that the corpus does not reach the production cap.

- [ ] **Step 4: Run + commit**

```bash
./gradlew :reggie-runtime:test --tests PerConfigBackrefRegressionTest
./gradlew spotlessApply && git add -A && git commit -m "feat: post-dedup config-cap backstop (delegate if fallback allowed, else fail)"
```

---

## Phase 4 — Integration, hash, gate, bookkeeping

### Task 11: StructuralHash audit

**Files:**
- Modify (if needed): `reggie-codegen/.../codegen/analysis/StructuralHash.java`

- [ ] **Step 1: Determine whether any hashed input changed**

We add **no** new `DFAState`/`DFATransition`/`NFAState`/`PatternInfo` field — `backrefCheck` is already hashed (`NFA.contentHashCode` line 318) and the strategy is already part of the cache key. The AVD-routing decision (Task 10) changes the *selected strategy* / fallback routing, already reflected. **Likely no change required**, BUT verify one thing introduced by Task 9: the `allowJdkFallback` generation-time boolean now changes which bytecode is emitted for the **same** strategy (delegate vs throw on cap overflow). Confirm `allowJdkFallback` is already part of the compiled-class cache key (it is a `ReggieOption`); if the cache key is keyed only on pattern+strategy and not options, add `allowJdkFallback` to `StructuralHash.compute()` per the AGENTS.md checklist — otherwise two compiles of the same pattern with different fallback settings could collide.

- [ ] **Step 2: Verify cache correctness**

Run: `./gradlew :reggie-runtime:test --tests StrategyCorrectnessMetaTest`
Expected: PASS (no stale-cache wrong results).

### Task 12: Full gate + budget update

**Files:**
- Modify: `reggie-integration-tests/.../AlgorithmicFuzzTest.java` (`KNOWN_FINDINGS_BUDGET`, line 56)
- Modify: `doc/temp/prod-readiness/fuzz-inventory.md`

- [ ] **Step 1: Run the zero-divergence gate**

Run: `./gradlew :reggie-integration-tests:test --tests AlgorithmicFuzzTest.zeroDivergenceGate -Dreggie.fuzz.size=25000`
Expected: findings drop by the 3 OPTIMIZED_NFA_WITH_BACKREFS repros (18 → 15, or fewer if siblings also resolve).

- [ ] **Step 2: Lower `KNOWN_FINDINGS_BUDGET` to the measured value and document in `fuzz-inventory.md`.**

- [ ] **Step 3: Full build (both paths, coverage)**

Run: `./gradlew clean build`
Expected: PASS, coverage thresholds met.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply && git add -A && git commit -m "test: lower fuzz budget after per-config backref fix"
```

---

## Self-review checklist (run before handing off to execution)

- [ ] **Spec coverage:** Defect B (Task 4 — config-local pos), per-config captures (Task 7 — Alt A), priority frontier + dedup + A2 (Task 8 — Alt C), AVD gate (Task 10), cap backstop (Task 9). ✅
- [ ] **Regression guard:** Task 0 corpus + `*Backref*` suite run after every code task. ✅
- [ ] **Dual-path:** both `RuntimeCompiler` and `ReggieMatcherBytecodeGenerator` delegate to the shared generator; verified via `:reggie-runtime:test` + `:reggie-benchmark:build`. ✅
- [ ] **No new dependencies; allocation-free hot loop** (worklist + dedup arrays preallocated once as reused `ReggieMatcher` instance fields — Phase 3 cross-cutting #4). ✅
- [ ] **Type consistency:** `PerConfigMode` enum + `generatePerConfigBody` signature used identically across all 12 method guards. ✅
- [ ] **Semantics target:** spans must match `java.util.regex` = **leftmost / first-alternative / last-iteration (Perl)**, NOT POSIX leftmost-longest. Task 8's priority ordering is what delivers this; P1's non-referenced group-2 span is the canary. (See Phase 3 cross-cutting #1.)
