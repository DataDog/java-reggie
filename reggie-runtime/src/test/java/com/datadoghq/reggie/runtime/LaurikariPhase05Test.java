/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 0.5 checkpoint (impl plan §2, doc/2026-07-10-tdfa-capture-engine-impl-plan.md): 2-3
 * independent capture tags, exercising cross-tag merge conflicts that Phase 0's single
 * total-ordered "age" tag ({@link LaurikariDFACacheTest}) cannot reach at all.
 *
 * <p><b>Tie-break discipline</b> (design §4, "whole-mapping priority discard"): unlike Phase 0's
 * value-based "larger age wins," this driver tracks genuine priority order — a DFS through epsilon
 * transitions in {@code curStates}' array order (index 0 = highest priority), first-visit wins,
 * mirroring {@code PikeVMMatcher.addThread} exactly (see that method for the reference semantics
 * this driver replicates). {@code curStates}' order is therefore load-bearing and never
 * canonicalized/sorted, unlike Phase 0's driver.
 *
 * <p><b>Register encoding</b>: each of the {@code tagCount} registers stores an "age" (characters
 * consumed since that tag was last set), exactly generalizing Phase 0's single age register to N
 * independent ages instead of one — not "set to absolute position" (design/Task 1.2's eventual real
 * mechanism, which needs runtime-substituted register *operations* to stay cache-friendly under an
 * unbounded scan). Consuming a character ages every currently-set register by 1; closure resets a
 * specific tag's age to 0 the moment it passes through that tag's {@code enterGroup}/ {@code
 * exitGroup} marker state. The absolute tag position is recovered only once, at the end, as {@code
 * consumed - age} — same formula Phase 0's {@code acceptAge} uses for its one tag. Choosing this
 * encoding (over literal positions) is itself part of what Task 0.5.3 measures: if ages stay small
 * in practice for these patterns, {@link LaurikariStateSetKey}'s value-based caching stays viable
 * at this register shape; if they do not, that is precisely the state-space blowup signal this
 * checkpoint exists to surface before Phase 1 commits to the real tag count.
 *
 * <p><b>Task 0.5.1a's hand-derivation</b> is confined to each pattern's small {@code tagSpecs}
 * table below ({@code {groupNumber, isEnter}} pairs) — the closure/step driver itself is shared,
 * generic machinery (not per-pattern hardcoded), reading {@link NFA.NFAState#enterGroup}/{@link
 * NFA.NFAState#exitGroup} to resolve each spec to the concrete NFA state id(s) that carry it. This
 * is legitimate: what's hand-picked is *which* 2-3 group boundaries matter for this checkpoint, not
 * *how* a boundary maps to a state id (Phase 1's Task 1.1/1.2 generalizes this same lookup to
 * literally every group instead of 2-3 hand-picked ones — the mechanism doesn't change, only the
 * number of tags fed into it does).
 *
 * <p>Scope: this driver assumes an anchored whole-input match (mirrors {@code Matcher.matches()}
 * semantics) — it accepts only if the NFA's accept state is live after consuming every input
 * character, not the leftmost-find-localization semantics {@code LaurikariDFACacheTest} covers.
 * That split (locate the match span, then extract its captures) matches how Phase 1's eventual
 * capture engine is expected to compose with Phase 0's boolean/localization DFA (design §7) — this
 * checkpoint only needs to validate the extraction half.
 */
class LaurikariPhase05Test {

  // --- NFA plumbing, shared with LaurikariDFACacheTest's test-only driver -----------------------

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static NFA.NFAState[] statesById(NFA nfa) {
    NFA.NFAState[] arr = new NFA.NFAState[nfa.getStates().size()];
    for (NFA.NFAState s : nfa.getStates()) {
      arr[s.id] = s;
    }
    return arr;
  }

  /**
   * Resolves each {@code tagSpecs[t] = {groupNumber, isEnter (1/0)}} entry to the NFA state id(s)
   * that carry it: {@code tagOfState[id] = t} for every state matching tag {@code t}'s spec (see
   * class javadoc's "Task 0.5.1a's hand-derivation" note — more than one state id can map to the
   * same tag, e.g. {@code ((a)|())*}'s outer-close, reached via either alternation branch).
   */
  private static int[] tagOfState(NFA nfa, int stateCount, int[][] tagSpecs) {
    int[] tagOfState = new int[stateCount];
    Arrays.fill(tagOfState, -1);
    for (NFA.NFAState s : nfa.getStates()) {
      for (int t = 0; t < tagSpecs.length; t++) {
        int group = tagSpecs[t][0];
        boolean enter = tagSpecs[t][1] == 1;
        if (enter
            ? (s.enterGroup != null && s.enterGroup == group)
            : (s.exitGroup != null && s.exitGroup == group)) {
          tagOfState[s.id] = t;
        }
      }
    }
    return tagOfState;
  }

  private static int[] ageAll(int[] regs) {
    int[] out = new int[regs.length];
    for (int i = 0; i < regs.length; i++) {
      out[i] = regs[i] < 0 ? -1 : regs[i] + 1;
    }
    return out;
  }

  /**
   * Priority-ordered epsilon-closure DFS (first-visit wins per {@code visited}, mirroring {@code
   * PikeVMMatcher.addThread}), resetting {@code tagOfState[id]}'s age to 0 at each tag boundary
   * crossed and propagating every other tag's age unchanged.
   */
  private static void addClosure(
      NFA.NFAState[] statesById,
      boolean[] visited,
      List<Integer> outIds,
      List<int[]> outRegs,
      int[] tagOfState,
      int id,
      int[] regs) {
    if (visited[id]) return;
    visited[id] = true;
    int tag = tagOfState[id];
    int[] r = regs;
    if (tag >= 0) {
      r = regs.clone();
      r[tag] = 0;
    }
    outIds.add(id);
    outRegs.add(r);
    for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
      addClosure(statesById, visited, outIds, outRegs, tagOfState, e.id, r);
    }
  }

  /** {@link LaurikariNfaStep} implementing the whole-mapping priority-discard rule (design §4). */
  private static LaurikariNfaStep laurikariStep(NFA nfa, int[] tagOfState) {
    NFA.NFAState[] statesById = statesById(nfa);
    int stateCount = statesById.length;
    return (curStates, curRegs, c) -> {
      boolean[] seenTarget = new boolean[stateCount];
      List<Integer> seedIds = new ArrayList<>();
      List<int[]> seedRegs = new ArrayList<>();
      for (int i = 0; i < curStates.length; i++) {
        NFA.NFAState s = statesById[curStates[i]];
        for (NFA.Transition t : s.getTransitions()) {
          if (t.chars.contains((char) c)) {
            int tid = t.target.id;
            if (!seenTarget[tid]) {
              seenTarget[tid] = true;
              seedIds.add(tid);
              seedRegs.add(ageAll(curRegs[i]));
            }
          }
        }
      }
      boolean[] visited = new boolean[stateCount];
      List<Integer> outIds = new ArrayList<>();
      List<int[]> outRegs = new ArrayList<>();
      for (int i = 0; i < seedIds.size(); i++) {
        addClosure(
            statesById, visited, outIds, outRegs, tagOfState, seedIds.get(i), seedRegs.get(i));
      }
      int[] states = new int[outIds.size()];
      for (int i = 0; i < states.length; i++) states[i] = outIds.get(i);
      int[][] regs = outRegs.toArray(new int[0][]);
      return new LaurikariStepResult(states, regs);
    };
  }

  private static final class Built {
    final NFA nfa;
    final int[] initStates;
    final int[][] initRegs;
    final LaurikariNfaStep step;
    final int acceptId;
    final int tagCount;

    Built(
        NFA nfa,
        int[] initStates,
        int[][] initRegs,
        LaurikariNfaStep step,
        int acceptId,
        int tagCount) {
      this.nfa = nfa;
      this.initStates = initStates;
      this.initRegs = initRegs;
      this.step = step;
      this.acceptId = acceptId;
      this.tagCount = tagCount;
    }
  }

  private static Built build(String pattern, int groupCount, int[][] tagSpecs) throws Exception {
    NFA nfa = nfa(pattern, groupCount);
    NFA.NFAState[] statesById = statesById(nfa);
    int stateCount = statesById.length;
    int startId = nfa.getStartState().id;
    int tagCount = tagSpecs.length;
    int[] tagOfState = tagOfState(nfa, stateCount, tagSpecs);

    int[] allUnset = new int[tagCount];
    Arrays.fill(allUnset, -1);
    boolean[] visited = new boolean[stateCount];
    List<Integer> outIds = new ArrayList<>();
    List<int[]> outRegs = new ArrayList<>();
    addClosure(statesById, visited, outIds, outRegs, tagOfState, startId, allUnset);
    int[] initStates = new int[outIds.size()];
    for (int i = 0; i < initStates.length; i++) initStates[i] = outIds.get(i);
    int[][] initRegs = outRegs.toArray(new int[0][]);

    int acceptId = nfa.getAcceptStates().iterator().next().id;
    return new Built(nfa, initStates, initRegs, laurikariStep(nfa, tagOfState), acceptId, tagCount);
  }

  /**
   * Runs the whole {@code input} through {@code built}'s step function (uncached — this is a
   * correctness driver, not the growth-measurement one below) and returns the absolute tag
   * positions at the accept state once every character is consumed, or {@code null} if the accept
   * state isn't live at the end (no whole-input match).
   */
  private static int[] runToEnd(Built built, String input) {
    int[] curStates = built.initStates;
    int[][] curRegs = built.initRegs;
    int consumed = 0;
    for (int i = 0; i < input.length(); i++) {
      if (curStates.length == 0) return null;
      LaurikariStepResult r = built.step.apply(curStates, curRegs, input.charAt(i));
      curStates = r.states;
      curRegs = r.regs;
      consumed++;
    }
    int idx = -1;
    for (int i = 0; i < curStates.length; i++) {
      if (curStates[i] == built.acceptId) {
        idx = i;
        break;
      }
    }
    if (idx < 0) return null;
    int[] ages = curRegs[idx];
    int[] absolute = new int[built.tagCount];
    for (int t = 0; t < built.tagCount; t++) {
      absolute[t] = ages[t] < 0 ? -1 : consumed - ages[t];
    }
    return absolute;
  }

  private static PikeVMMatcher pikeVm(String pattern, int groupCount) throws Exception {
    NFA nfa = nfa(pattern, groupCount);
    return new PikeVMMatcher(nfa, pattern);
  }

  // --- (a) (a+)(b+): 2 tags, group1's close + group2's open --------------------------------------

  @Test
  void twoIndependentGroups_greedyBoundaries_matchPikeVM() throws Exception {
    int[][] tagSpecs = {{1, 0}, {2, 1}}; // tag0 = group1 exit, tag1 = group2 enter
    Built built = build("(a+)(b+)", 2, tagSpecs);
    PikeVMMatcher oracle = pikeVm("(a+)(b+)", 2);

    for (String input : new String[] {"ab", "aaabbb", "aaaaaaaabb", "ab" /* min case */}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);

      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);

      // tag0 = group1's exit = oracle.end(1); tag1 = group2's enter = oracle.start(2)
      assertArrayEquals(
          new int[] {oracleResult.end(1), oracleResult.start(2)},
          absolute,
          "capture boundaries diverged from PikeVMMatcher for input " + input);
    }
  }

  // --- (b) (ab)+: 2 tags, single group's open+close, re-set every loop iteration
  // ------------------

  @Test
  void loopBodyGroup_reSetOnEveryIteration_matchesPikeVM() throws Exception {
    int[][] tagSpecs = {{1, 1}, {1, 0}}; // tag0 = group1 enter, tag1 = group1 exit
    Built built = build("(ab)+", 1, tagSpecs);
    PikeVMMatcher oracle = pikeVm("(ab)+", 1);

    for (String input : new String[] {"ab", "abab", "ababab", "abababababab"}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);

      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);

      // Perl/greedy semantics: group 1's final captured span is its LAST iteration, i.e. the
      // input's
      // trailing "ab" — exactly what oracle.start(1)/end(1) already report.
      assertArrayEquals(
          new int[] {oracleResult.start(1), oracleResult.end(1)},
          absolute,
          "loop-back re-set boundaries diverged from PikeVMMatcher for input " + input);
    }
  }

  // --- (c) ((a)|())*: 3 tags, outer open/close + inner group's open (nullable-branch case) -------

  @Test
  void nestedGroupsWithNullableBranch_matchesPikeVM() throws Exception {
    // tag0 = outer group1 enter, tag1 = outer group1 exit, tag2 = inner group2 (the "(a)" branch)
    // enter. Group3 (the "()" branch) and group2's exit are deliberately untracked (design's
    // "3 tags" scope, see class/plan doc).
    int[][] tagSpecs = {{1, 1}, {1, 0}, {2, 1}};
    Built built = build("((a)|())*", 3, tagSpecs);
    PikeVMMatcher oracle = pikeVm("((a)|())*", 3);

    // "a" then "" then "a": last iteration is the empty branch, so tag2 (inner group2's open) must
    // reflect the LAST time group2 participated (an earlier iteration), not this run's final
    // iteration — the exact nullable-branch subtlety this pattern is chosen to exercise.
    for (String input : new String[] {"", "a", "aa", "aaa"}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);

      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);

      int expectedGroup2Start = oracleResult.start(2); // -1 if group 2 never participated
      assertArrayEquals(
          new int[] {oracleResult.start(1), oracleResult.end(1), expectedGroup2Start},
          absolute,
          "nested/nullable-branch boundaries diverged from PikeVMMatcher for input " + input);
    }
  }

  // --- Task 0.5.3: state-count growth from N=2 (two patterns) to N=3, via the generalized
  // ---------
  // --- LaurikariDFACache caching layer (Task 0.5.1's actual production interning path, not the ---
  // --- uncached driver above) over an adversarial input designed to keep several live candidate --
  // --- register-age vectors distinct at once, the same shape as LaurikariDFACacheTest's own
  // -------
  // --- denseAlternation stress case.
  //
  // Report (printed, not asserted — this is a measurement, not a pass/fail check per Task 0.5.3):
  // distinct DFA states materialized for each of the three patterns, to compare against Phase 0's
  // already-reported single-tag numbers and each other.

  @Test
  void stateCountGrowth_twoAndThreeTags_reportedForPhase05ExitCriteria() throws Exception {
    // Scale sweep per pattern (not a single data point) so the growth *rate* — not just one
    // sample — is visible for the exit-criteria report. (a+)(b+)'s tag0 (group1's close) never
    // resets once set, so its age grows for as long as the b+ tail keeps consuming — the same
    // already-accepted-and-mitigated growth mode Phase 0's own quotedString adversarial test
    // exercises (cap-and-fallback, not a new failure class). (ab)+/((a)|())*'s tags reset every
    // loop iteration, so their ages stay small regardless of scale — the interesting comparison
    // for "does tag COUNT itself drive blowup" (2 tags vs 3, both loop-reset).
    for (int n : new int[] {100, 400, 1600}) {
      report("(a+)(b+)", 2, new int[][] {{1, 0}, {2, 1}}, "a".repeat(n) + "b".repeat(n));
    }
    for (int n : new int[] {100, 400, 1600}) {
      report("(ab)+", 1, new int[][] {{1, 1}, {1, 0}}, "ab".repeat(n));
    }
    for (int n : new int[] {100, 400, 1600}) {
      report("((a)|())*", 3, new int[][] {{1, 1}, {1, 0}, {2, 1}}, "a".repeat(n));
    }
  }

  private static void report(String pattern, int groupCount, int[][] tagSpecs, String input)
      throws Exception {
    Built built = build(pattern, groupCount, tagSpecs);
    int[] acceptIds = {built.acceptId};
    LaurikariDFACache cache = new LaurikariDFACache(built.initStates, built.initRegs, acceptIds);

    int dfaState = 0;
    int[] curStates = built.initStates;
    int[][] curRegs = built.initRegs;
    boolean fellBack = false;
    for (int i = 0; i < input.length() && !fellBack; i++) {
      int next = cache.lookupOrCompute(dfaState, input.charAt(i), built.step);
      if (next == LaurikariDFACache.DEAD) break;
      if (next == LaurikariDFACache.FALLBACK) {
        fellBack = true;
        break;
      }
      dfaState = next;
    }
    System.out.println(
        "=== Phase 0.5 growth report: "
            + pattern
            + " (tagCount="
            + tagSpecs.length
            + ", inputLen="
            + input.length()
            + ") ===");
    System.out.println("    distinct DFA states materialized: " + cache.stateCount());
    System.out.println("    hit cap/fell back to uncached stepping: " + fellBack);
  }
}
