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

import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Impl-plan Task 1.2 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): full priority-ordered
 * register-merge determinization over the real {@code 2 * (groupCount + 1)} tags {@link
 * LaurikariTagNumbering} derives, generalizing {@code LaurikariPhase05Test}'s hand-rolled,
 * 2-3-tag-only test driver into shippable production code that works for any {@code groupCount}.
 *
 * <p><b>Register encoding: ages, not absolute positions.</b> {@link LaurikariNfaStep#apply} has no
 * {@code pos} parameter, so — exactly like {@code LaurikariPhase05Test}'s driver — each register
 * holds an <em>age</em> (characters consumed since that tag was last set, or {@code -1} if never
 * set), not an absolute position. Consuming a character ages every currently-set register by 1;
 * closure resets a specific tag's age to {@code 0} the moment it passes through that tag's marker
 * state (a real {@code enterGroup}/{@code exitGroup} state, or an accept state for tag 1 — see
 * {@link LaurikariTagNumbering}). The absolute tag position is recovered once, at the end, as
 * {@code totalConsumed - age} (see {@link #absolutePositions}) — the same formula {@code
 * LaurikariDFACache#acceptAge}/{@code LaurikariPhase05Test#runToEnd} already use for their smaller
 * tag counts. This is a deliberate scope decision, not an oversight: switching to literal absolute
 * positions would need runtime-substituted register operations threaded through a {@code pos}
 * parameter this interface doesn't have — that is exactly the "eventual real mechanism" the {@code
 * LaurikariNfaStep} class javadoc already earmarks as future work, not something Task 1.2 asks for.
 *
 * <p><b>Priority order</b> (design §4, "whole-mapping priority discard"): a DFS through epsilon
 * transitions in {@code curStates}' array order (index 0 = highest priority), first-visit wins —
 * mirrors {@code PikeVMMatcher.addThread}'s {@code for (int i = 0; i < epsilons.size(); i++)}
 * traversal over {@code state.getEpsilonTransitions()} exactly (that list's insertion order is
 * itself documented as load-bearing Perl-priority order by {@code NFA.NFAState}). {@code
 * curStates}' order is therefore load-bearing and must never be sorted/canonicalized, matching
 * {@link LaurikariStateSetKey}'s order-sensitive {@code equals}/{@code hashCode}.
 *
 * <p><b>Scope</b>: no lookaround, backreference, or atomic-group handling — those NFA constructs
 * are simply not traversed specially here (this class only walks {@code getEpsilonTransitions()}/
 * {@code getTransitions()}), matching this task's explicit exclusion of eligibility gating (Task
 * 1.3, not built here).
 *
 * <p><b>Task 1.4a — self-anchoring {@link #applyFind}:</b> {@code find()}-family semantics require
 * re-injecting a fresh "candidate starts here" closure at every character step, exactly mirroring
 * {@code PikeVMMatcher}'s thread-list reinjection at the tail of {@code clist}/{@code nlist} (an
 * implicit lowest-priority {@code .*?} prefix). {@link #apply} and {@link #applyFind} share the
 * same closure machinery via a private {@code apply(..., boolean reinject)}: when {@code reinject}
 * is true, the epsilon-closure of the start state (tag 0 = age 0, computed once in the constructor
 * and cached as {@link #initialStates}/{@link #initialRegs}) is scanned for {@code c}-consuming
 * transitions too, appended AFTER every existing candidate's seeds — i.e. at the lowest priority —
 * so an already-visited target from an older (more leftmost) start is left untouched by {@code
 * visited}'s first-visit-wins rule. The existing {@code totalConsumed - age} formula in {@link
 * #absolutePositions} needs no special-casing for reinjected candidates: age is still "characters
 * consumed since tag 0 was set," regardless of when within the scan that set happened.
 */
final class LaurikariCaptureNfaStep implements LaurikariNfaStep {

  private final NFA.NFAState[] statesById;
  private final int[] tagOfState;
  private final boolean[] isAccept;
  private final int[] initialStates;
  private final int[][] initialRegs;
  final int tagCount;

  LaurikariCaptureNfaStep(NFA nfa, int groupCount) {
    this.statesById = statesById(nfa);
    this.tagOfState = LaurikariTagNumbering.tagOfState(nfa);
    this.isAccept = isAccept(nfa, statesById.length);
    this.tagCount = LaurikariTagNumbering.tagCount(groupCount);
    LaurikariStepResult initial = initial(nfa, this);
    this.initialStates = initial.states;
    this.initialRegs = initial.regs;
  }

  private static boolean[] isAccept(NFA nfa, int stateCount) {
    boolean[] accept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      accept[s.id] = true;
    }
    return accept;
  }

  private static NFA.NFAState[] statesById(NFA nfa) {
    NFA.NFAState[] arr = new NFA.NFAState[nfa.getStates().size()];
    for (NFA.NFAState s : nfa.getStates()) {
      arr[s.id] = s;
    }
    return arr;
  }

  /**
   * @return the initial (states, regs) pair for an anchored match attempt starting at the current
   *     position: the epsilon-closure of {@code nfa.getStartState()}, with tag 0 (group 0's open —
   *     never carried by any NFA state, see {@link LaurikariTagNumbering}) seeded to age 0 and
   *     every other tag unset ({@code -1}).
   */
  static LaurikariStepResult initial(NFA nfa, LaurikariCaptureNfaStep step) {
    int[] allUnset = new int[step.tagCount];
    Arrays.fill(allUnset, -1);
    allUnset[0] = 0; // group 0 opens here, age 0
    boolean[] visited = new boolean[step.statesById.length];
    List<Integer> outIds = new ArrayList<>();
    List<int[]> outRegs = new ArrayList<>();
    step.addClosure(visited, outIds, outRegs, nfa.getStartState().id, allUnset);
    return step.truncateAtFirstAccept(outIds, outRegs);
  }

  /**
   * @return {@code regs}, aged by one character (every set register incremented by 1; unset {@code
   *     -1} registers stay unset).
   */
  private static int[] ageAll(int[] regs) {
    int[] out = new int[regs.length];
    for (int i = 0; i < regs.length; i++) {
      out[i] = regs[i] < 0 ? -1 : regs[i] + 1;
    }
    return out;
  }

  /**
   * Priority-ordered epsilon-closure DFS (first-visit wins per {@code visited}, mirroring {@code
   * PikeVMMatcher.addThread}'s traversal order exactly), resetting {@code tagOfState[id]}'s age to
   * 0 at each tag boundary crossed and propagating every other tag's age unchanged. This is Task
   * 1.2's step 2/3: the register ops a transition carries fall out of this rule directly — pass-
   * through tags are copied via {@code ageAll} before the DFS starts, the one tag matching this
   * state's marker (if any) is overwritten to age 0 (the "set-to-current-pos" op, expressed in age
   * terms), and a target NFA state already visited by a higher-priority arrival discards this
   * arrival's entire register vector (step 3's whole-mapping discard) since {@code visited[id]}
   * short-circuits before this state's mapping is ever recorded.
   */
  private void addClosure(
      boolean[] visited, List<Integer> outIds, List<int[]> outRegs, int id, int[] regs) {
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
      addClosure(visited, outIds, outRegs, e.id, r);
    }
  }

  @Override
  public LaurikariStepResult apply(int[] curStates, int[][] curRegs, int c) {
    return apply(curStates, curRegs, c, false);
  }

  /**
   * Self-anchoring variant of {@link #apply} for {@code find()}-family semantics: re-injects a
   * fresh lowest-priority "candidate starts here" closure at this character step, on top of {@code
   * apply}'s ordinary carried-forward candidates. See the class javadoc for why this composes
   * correctly with leftmost-first priority and the age-based {@link #absolutePositions} formula.
   */
  LaurikariStepResult applyFind(int[] curStates, int[][] curRegs, int c) {
    return apply(curStates, curRegs, c, true);
  }

  private LaurikariStepResult apply(int[] curStates, int[][] curRegs, int c, boolean reinject) {
    int stateCount = statesById.length;
    // Step 1: consuming transitions from every NFA state in the current subset, in the same
    // priority order PikeVMMatcher.addThread walks (curStates' array order, index 0 highest
    // priority) -- first arrival to a given target state wins (step 3's whole-mapping discard).
    boolean[] seenTarget = new boolean[stateCount];
    List<Integer> seedIds = new ArrayList<>();
    List<int[]> seedRegs = new ArrayList<>();
    collectConsumingSeeds(curStates, curRegs, c, seenTarget, seedIds, seedRegs);
    if (reinject) {
      // Lowest priority: appended after every existing candidate's seeds, so an already-visited
      // target from an older (more leftmost) start is left untouched by addClosure's
      // first-visit-wins rule below.
      collectConsumingSeeds(initialStates, initialRegs, c, seenTarget, seedIds, seedRegs);
    }
    // Step 2/3: epsilon-close each seed in the same priority order, applying each transition's
    // register ops (copy for pass-through tags, set-to-current-pos -- age 0 -- for the tag(s)
    // matching that transition's enterGroup/exitGroup) via addClosure, and discarding a
    // lower-priority arrival's whole mapping the moment a target is already visited.
    boolean[] visited = new boolean[stateCount];
    List<Integer> outIds = new ArrayList<>();
    List<int[]> outRegs = new ArrayList<>();
    for (int i = 0; i < seedIds.size(); i++) {
      addClosure(visited, outIds, outRegs, seedIds.get(i), seedRegs.get(i));
    }
    // Step 4: the caller (LaurikariDFACache) interns (states, regs) via LaurikariStateSetKey.
    return truncateAtFirstAccept(outIds, outRegs);
  }

  /**
   * Priority-kill (mirrors {@code PikeVMMatcher}'s {@code clistSize = t} truncation at its
   * first-accept index): once a higher-priority arrival reaches an accept state, every
   * strictly-lower-priority arrival in this subset can never win a leftmost-first match and must
   * not survive into the next step's seed collection. The accept member itself is kept (its
   * register vector is what {@code LaurikariDFACache#acceptRegs} reads), but nothing after it in
   * priority order is retained. Applies uniformly to every closure result, including {@link
   * #initial} — an accept reachable ahead of a lower-priority consuming state at position 0 (e.g.
   * {@code "|a"}) must kill that lower-priority state just as it would mid-scan.
   */
  private LaurikariStepResult truncateAtFirstAccept(List<Integer> outIds, List<int[]> outRegs) {
    int size = outIds.size();
    for (int i = 0; i < size; i++) {
      if (isAccept[outIds.get(i)]) {
        size = i + 1;
        break;
      }
    }
    int[] states = new int[size];
    int[][] regsOut = new int[size][];
    for (int i = 0; i < size; i++) {
      states[i] = outIds.get(i);
      regsOut[i] = outRegs.get(i);
    }
    return new LaurikariStepResult(states, regsOut);
  }

  /**
   * Appends, to {@code seedIds}/{@code seedRegs}, one aged seed per distinct target state reached
   * by a {@code c}-consuming transition from {@code states}[i] (skipping targets already present in
   * {@code seenTarget}, and marking newly-added ones) — the shared core of step 1 for both {@code
   * apply}'s ordinary candidates and {@code applyFind}'s reinjected fresh-start candidate.
   */
  private void collectConsumingSeeds(
      int[] states,
      int[][] regs,
      int c,
      boolean[] seenTarget,
      List<Integer> seedIds,
      List<int[]> seedRegs) {
    for (int i = 0; i < states.length; i++) {
      NFA.NFAState s = statesById[states[i]];
      for (NFA.Transition t : s.getTransitions()) {
        if (t.chars.contains((char) c)) {
          int targetId = t.target.id;
          if (!seenTarget[targetId]) {
            seenTarget[targetId] = true;
            seedIds.add(targetId);
            seedRegs.add(ageAll(regs[i]));
          }
        }
      }
    }
  }

  /**
   * @return absolute tag positions ({@code totalConsumed - age}, or {@code -1} for a tag never set)
   *     recovered from an accept-state register vector after {@code totalConsumed} characters have
   *     been consumed from the anchored start.
   */
  int[] absolutePositions(int[] acceptRegs, int totalConsumed) {
    int[] absolute = new int[tagCount];
    for (int t = 0; t < tagCount; t++) {
      absolute[t] = acceptRegs[t] < 0 ? -1 : totalConsumed - acceptRegs[t];
    }
    return absolute;
  }
}
