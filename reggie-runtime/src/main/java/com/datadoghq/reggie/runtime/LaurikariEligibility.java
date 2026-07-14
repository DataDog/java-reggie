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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Impl-plan Task 1.3 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): eligibility gate for
 * {@link LaurikariDfaMatcher}, deciding whether an NFA is safe to route through the Laurikari
 * tagged-DFA capture engine rather than {@link PikeVMMatcher}'s thread simulation.
 *
 * <p><b>Anchor scope (TDFA Phase 2 end-anchor/{@code \b} extension):</b> every {@link
 * NFA.AnchorType} except {@code RESET_MATCH} (untouched, always-passing) is handleable: the {@code
 * START}-family ({@code START}/{@code STRING_START}/{@code START_MULTILINE}) via the
 * build-time-precomputed anchor-blocked closures {@link LaurikariCaptureNfaStep} always used, and
 * {@code END}/{@code STRING_END}/{@code STRING_END_ABSOLUTE}/{@code END_MULTILINE}/{@code
 * WORD_BOUNDARY} via a live per-occurrence {@code PikeVMMatcher.checkAnchor} call inside {@code
 * addClosure} (see that class). Only the {@code START}-family needs the "leading-only" structural
 * restriction below — an anchor inside a loop can fire across empty iterations, which the
 * subset/register model here cannot represent for anchors baked into precomputed closures — the 5
 * new types are evaluated fresh on every occurrence, so no equivalent restriction applies to them
 * (worked through adversarial pattern shapes; see the TDFA Phase 2 plan).
 */
final class LaurikariEligibility {

  private LaurikariEligibility() {}

  /**
   * @param nfa the pattern's NFA
   * @param usePosixLastMatch {@code PatternAnalyzer.MatchingStrategyResult.usePosixLastMatch} —
   *     must be checked explicitly (design §5: neither the backref/lookaround/atomic-group checks
   *     below nor the anchor check cover it). Excludes capture-ambiguous patterns like {@code
   *     (a|a)+}, whose correct group spans require POSIX-last-match semantics, not the
   *     leftmost-first priority this engine replicates.
   * @return {@code true} if {@code nfa} is safe to route through {@link LaurikariDfaMatcher}
   */
  static boolean isEligible(NFA nfa, boolean usePosixLastMatch) {
    if (usePosixLastMatch) return false;

    boolean hasStartAnchor = false;
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.assertionType != null || s.backrefCheck != null) return false;
      if (s.atomicEntry >= 0 || s.atomicExit >= 0) return false; // atomic groups not DFA-safe
      // RESET_MATCH (\K) is the only anchor type with no explicit gate: PikeVMMatcher.checkAnchor
      // always passes it, and LaurikariCaptureNfaStep.addClosure never special-cases it either.
      NFA.AnchorType a = s.anchor;
      if (a == NFA.AnchorType.START
          || a == NFA.AnchorType.STRING_START
          || a == NFA.AnchorType.START_MULTILINE) {
        hasStartAnchor = true;
      }
    }

    // A capturing group's enter/exit marker sitting on an epsilon-only cycle (a loop body that can
    // match zero-width, looping back to itself entirely via epsilon transitions) is not DFA-safe:
    // LaurikariCaptureNfaStep.addClosure's DFS marks a state visited on first arrival, so a cyclic
    // re-arrival at the same state with a fresher (later) register vector — the extra zero-width
    // loop iteration JDK/PCRE backtracking takes whenever it lets downstream content match — never
    // propagates past that already-visited state. Conservative: rejects only patterns containing
    // such a cycle, never admits anything not already eligible.
    if (hasTaggedEpsilonCycle(nfa, LaurikariTagNumbering.tagOfState(nfa))) return false;

    if (!hasStartAnchor) return true; // anchor-free: always eligible

    // Anchored: sound only when every start anchor is leading — i.e. NOT reachable after
    // consuming a character (an anchor inside a loop can fire across empty iterations, which the
    // subset/register model here cannot represent) — same check as PikeVMMatcher.findDfaEligible.
    Set<Integer> reached = new HashSet<>();
    ArrayDeque<NFA.NFAState> q = new ArrayDeque<>();
    for (NFA.NFAState s : nfa.getStates()) {
      for (NFA.Transition t : s.getTransitions()) {
        if (reached.add(t.target.id)) q.add(t.target);
      }
    }
    while (!q.isEmpty()) {
      NFA.NFAState s = q.poll();
      NFA.AnchorType a = s.anchor;
      if (a == NFA.AnchorType.START
          || a == NFA.AnchorType.STRING_START
          || a == NFA.AnchorType.START_MULTILINE) {
        return false; // start anchor reachable after a consume -> not leading-only
      }
      for (NFA.NFAState e : s.getEpsilonTransitions()) {
        if (reached.add(e.id)) q.add(e);
      }
    }
    return true;
  }

  /**
   * @return {@code true} if the epsilon-only subgraph of {@code nfa} contains a cycle passing
   *     through a real capturing-group marker state (tag {@code >= 2} — excludes tag {@code 0}/
   *     {@code 1}, group 0's implicit whole-match open/close) whose enclosing loop construct is
   *     <b>not</b> in tail position — i.e. more pattern content (a consuming transition) follows
   *     the loop, which is exactly the shape where JDK/PCRE's extra zero-width loop iteration
   *     (taken whenever it lets that downstream content match) is observable. A tagged epsilon
   *     cycle with nothing but accept states downstream (e.g. {@code ((a)|())*} at the end of a
   *     pattern) is unaffected — no downstream content means no observable difference in which
   *     iteration's tag value wins.
   *     <p>The "enclosing loop construct" is the epsilon cycle's full strongly-connected component
   *     over ALL transitions (epsilon and consuming) — this also pulls in sibling loop-body
   *     branches that consume characters before looping back (e.g. {@code (a)} in {@code
   *     ((a)|())*}, which does loop back to the same states via a consuming transition, just not
   *     within a single epsilon-only closure step), so a normal consuming branch of the same loop
   *     isn't mistaken for downstream content.
   */
  private static boolean hasTaggedEpsilonCycle(NFA nfa, int[] tagOfState) {
    int[] color = new int[tagOfState.length]; // 0 = white, 1 = gray (on stack), 2 = black (done)
    List<Integer> stack = new ArrayList<>();
    List<Set<Integer>> taggedCycles = new ArrayList<>();
    for (NFA.NFAState s : nfa.getStates()) {
      if (color[s.id] == 0) collectTaggedEpsilonCycles(s, color, stack, tagOfState, taggedCycles);
    }
    if (taggedCycles.isEmpty()) return false;

    int[] sccOf = computeSccs(nfa);
    for (Set<Integer> cycle : taggedCycles) {
      int scc = sccOf[cycle.iterator().next()];
      Set<Integer> sccMembers = new HashSet<>();
      for (NFA.NFAState s : nfa.getStates()) {
        if (sccOf[s.id] == scc) sccMembers.add(s.id);
      }
      if (!tailPositionOnly(nfa, sccMembers)) return true;
    }
    return false;
  }

  /** Tarjan's algorithm over both epsilon and consuming transitions. */
  private static int[] computeSccs(NFA nfa) {
    int n = nfa.getStates().size();
    int[] index = new int[n];
    int[] lowlink = new int[n];
    int[] sccOf = new int[n];
    boolean[] onStack = new boolean[n];
    Arrays.fill(index, -1);
    Deque<Integer> stack = new ArrayDeque<>();
    int[] counter = {0};
    int[] sccCounter = {0};
    for (NFA.NFAState s : nfa.getStates()) {
      if (index[s.id] == -1) {
        tarjan(s, index, lowlink, onStack, stack, counter, sccCounter, sccOf);
      }
    }
    return sccOf;
  }

  private static void tarjan(
      NFA.NFAState v,
      int[] index,
      int[] lowlink,
      boolean[] onStack,
      Deque<Integer> stack,
      int[] counter,
      int[] sccCounter,
      int[] sccOf) {
    index[v.id] = counter[0];
    lowlink[v.id] = counter[0];
    counter[0]++;
    stack.push(v.id);
    onStack[v.id] = true;

    List<NFA.NFAState> successors = new ArrayList<>(v.getEpsilonTransitions());
    for (NFA.Transition t : v.getTransitions()) successors.add(t.target);

    for (NFA.NFAState w : successors) {
      if (index[w.id] == -1) {
        tarjan(w, index, lowlink, onStack, stack, counter, sccCounter, sccOf);
        lowlink[v.id] = Math.min(lowlink[v.id], lowlink[w.id]);
      } else if (onStack[w.id]) {
        lowlink[v.id] = Math.min(lowlink[v.id], index[w.id]);
      }
    }

    if (lowlink[v.id] == index[v.id]) {
      int scc = sccCounter[0]++;
      int w;
      do {
        w = stack.pop();
        onStack[w] = false;
        sccOf[w] = scc;
      } while (w != v.id);
    }
  }

  private static void collectTaggedEpsilonCycles(
      NFA.NFAState s, int[] color, List<Integer> stack, int[] tagOfState, List<Set<Integer>> out) {
    color[s.id] = 1;
    stack.add(s.id);
    for (NFA.NFAState e : s.getEpsilonTransitions()) {
      if (color[e.id] == 1) {
        int idx = stack.indexOf(e.id); // back edge -> cycle is stack[idx..top]
        Set<Integer> members = new HashSet<>();
        boolean tagged = false;
        for (int i = idx; i < stack.size(); i++) {
          int id = stack.get(i);
          members.add(id);
          if (tagOfState[id] >= 2) tagged = true;
        }
        if (tagged) out.add(members);
      } else if (color[e.id] == 0) {
        collectTaggedEpsilonCycles(e, color, stack, tagOfState, out);
      }
    }
    stack.remove(stack.size() - 1);
    color[s.id] = 2;
  }

  /**
   * @return {@code true} if nothing reachable from {@code sccMembers} (directly, or via epsilon
   *     transitions leaving the SCC) has a consuming transition — i.e. the loop construct sits at
   *     the tail of the pattern, so no downstream content's outcome can depend on which loop
   *     iteration's register vector the cyclic closure kept.
   */
  private static boolean tailPositionOnly(NFA nfa, Set<Integer> sccMembers) {
    Set<Integer> visited = new HashSet<>(sccMembers);
    ArrayDeque<NFA.NFAState> queue = new ArrayDeque<>();
    for (NFA.NFAState s : nfa.getStates()) {
      if (!sccMembers.contains(s.id)) continue;
      for (NFA.Transition t : s.getTransitions()) {
        if (!sccMembers.contains(t.target.id)) return false; // consuming exit -> downstream content
      }
      for (NFA.NFAState e : s.getEpsilonTransitions()) {
        if (!sccMembers.contains(e.id) && visited.add(e.id)) queue.add(e);
      }
    }
    while (!queue.isEmpty()) {
      NFA.NFAState s = queue.poll();
      if (!s.getTransitions().isEmpty()) return false;
      for (NFA.NFAState e : s.getEpsilonTransitions()) {
        if (visited.add(e.id)) queue.add(e);
      }
    }
    return true;
  }
}
