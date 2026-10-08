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
package com.datadoghq.reggie.codegen.automaton;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural limits of the runtime priority-ordered backtracker ({@code BackrefBacktrackMatcher}).
 * Shared by the runtime compiler and the annotation processor so both pipelines agree on which
 * patterns the backtracker executes.
 */
public final class BacktrackSupport {

  /**
   * Unbounded loops the backtracker can gate: one bit of a frame's {@code long} progress mask each.
   */
  public static final int MAX_LOOP_HEADS = 64;

  private BacktrackSupport() {}

  /**
   * True when a pattern analyzed as {@code strategy} runs on the backtracker instead of its
   * generated engine: generic backreference patterns and lookaround patterns, whose generated
   * matchers keep the longest match end and lose alternation priority in group spans, and
   * RECURSIVE_DESCENT patterns containing a lookaround, which the descent generator does not
   * evaluate. {@code nfa} is the pattern's NFA (only patterns that do not require recursive descent
   * have one).
   */
  public static boolean routesToBacktracker(PatternAnalyzer.MatchingStrategy strategy, NFA nfa) {
    switch (strategy) {
      case OPTIMIZED_NFA_WITH_BACKREFS:
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
      case HYBRID_DFA_LOOKAHEAD:
      case SPECIALIZED_MULTIPLE_LOOKAHEADS:
      case SPECIALIZED_LITERAL_LOOKAHEADS:
        return true;
      case RECURSIVE_DESCENT:
        for (NFA.NFAState s : nfa.getStates()) {
          if (s.assertionType != null) {
            return true;
          }
        }
        return false;
      default:
        return false;
    }
  }

  /**
   * Loop-back epsilon edges of the main NFA as {@code {source id, epsilon index, head id}}: the
   * epsilon edges into a state still on the stack of a depth-first walk from the start. Thompson
   * NFAs are reducible, so these are exactly the back edges of {@code X*} / {@code X+} and their
   * lazy forms. Edges into counted-loop markers are left out (the counter bounds those loops).
   * Lookaround bodies are walked from their own entry states: their loops gate the sub-search of
   * the backtracker's lookaround-body capture search.
   */
  public static List<int[]> loopBackEdges(NFA nfa) {
    int n = 0;
    for (NFA.NFAState s : nfa.getStates()) {
      n = Math.max(n, s.id + 1);
    }
    NFA.NFAState[] byId = new NFA.NFAState[n];
    for (NFA.NFAState s : nfa.getStates()) {
      byId[s.id] = s;
    }
    byte[] color = new byte[n]; // 0 unvisited, 1 on the DFS stack, 2 done
    int[] stack = new int[n];
    int[] cursor = new int[n];
    List<int[]> back = new ArrayList<>();
    List<Integer> roots = new ArrayList<>();
    roots.add(nfa.getStartState().id);
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.assertionType != null) {
        roots.add(s.assertionStartState.id);
      }
    }
    for (int root : roots) {
      if (color[root] == 0) {
        color[root] = 1;
        stack[0] = root;
        walkBackEdges(byId, color, stack, cursor, back);
      }
    }
    return back;
  }

  /** Iterative colored DFS from {@code stack[0]}, appending back edges to {@code back}. */
  private static void walkBackEdges(
      NFA.NFAState[] byId, byte[] color, int[] stack, int[] cursor, List<int[]> back) {
    int sp = 1;
    while (sp > 0) {
      int u = stack[sp - 1];
      NFA.NFAState s = byId[u];
      int eps = s.getEpsilonTransitions().size();
      int chars = s.getTransitions().size();
      int e = cursor[u]++;
      int t;
      if (e < eps) {
        t = s.getEpsilonTransitions().get(e).id;
      } else if (e < eps + chars) {
        t = s.getTransitions().get(e - eps).target.id;
      } else if (s.countedLoopId != null && e < eps + chars + 2) {
        t = e == eps + chars ? s.countedLoopBodyEntryId : s.countedLoopStopId;
      } else {
        color[u] = 2;
        sp--;
        continue;
      }
      if (color[t] == 0) {
        color[t] = 1;
        stack[sp++] = t;
      } else if (color[t] == 1 && e < eps && byId[t].countedLoopId == null) {
        back.add(new int[] {u, e, t});
      }
    }
  }

  /**
   * Returns why the backtracker cannot execute {@code nfa}, or {@code null} when it can.
   * Conditionals and atomic groups are not executed; lookarounds are, provided their bodies hold
   * only characters, anchors, epsilons, group boundaries and backreferences (no nested lookarounds,
   * conditionals, atomic groups or counted-loop markers). At most {@link #MAX_LOOP_HEADS} unbounded
   * loops are supported.
   */
  public static String unsupportedFeature(NFA nfa) {
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.conditionalGroup != null) {
        return "a conditional (?(n)...)";
      }
      if (s.atomicEntry >= 0 || s.atomicExit >= 0) {
        return "an atomic group";
      }
      if (s.assertionType != null && !isPlainAssertionBody(s.assertionStartState)) {
        return "a lookaround containing lookarounds, conditionals, atomic groups or counted loops";
      }
    }
    Set<Integer> heads = new HashSet<>();
    for (int[] be : loopBackEdges(nfa)) {
      heads.add(be[2]);
    }
    if (heads.size() > MAX_LOOP_HEADS) {
      return "more than " + MAX_LOOP_HEADS + " unbounded loops";
    }
    return null;
  }

  private static boolean isPlainAssertionBody(NFA.NFAState start) {
    Set<NFA.NFAState> seen = new HashSet<>();
    ArrayDeque<NFA.NFAState> queue = new ArrayDeque<>();
    seen.add(start);
    queue.add(start);
    while (!queue.isEmpty()) {
      NFA.NFAState s = queue.poll();
      if (s.assertionType != null
          || s.conditionalGroup != null
          || s.atomicEntry >= 0
          || s.atomicExit >= 0
          || s.countedLoopId != null) {
        return false;
      }
      for (NFA.NFAState t : s.getEpsilonTransitions()) {
        if (seen.add(t)) {
          queue.add(t);
        }
      }
      for (NFA.Transition t : s.getTransitions()) {
        if (seen.add(t.target)) {
          queue.add(t.target);
        }
      }
    }
    return true;
  }
}
