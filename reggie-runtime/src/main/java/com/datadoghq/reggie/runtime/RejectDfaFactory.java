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
import java.util.Arrays;

/**
 * Builds the over-approximating "reject DFA" fast-reject filter shared by {@link PikeVMMatcher} and
 * {@link BitStateMatcher}: a self-anchoring {@link LazyDFACache} over the NFA's transitions that
 * crosses every anchor as epsilon (accepting a superset of real matches). A DEAD/non-accepting scan
 * of the whole input is therefore a sound proof that no real match exists, independent of how many
 * distinct characters can begin a match — unlike the single-char {@code indexOf} prefilter, this
 * also covers patterns with several possible leading characters (e.g. SQL/URL/query-obfuscator
 * alternations).
 *
 * <p>Skipped (returns {@code null}) when the NFA has assertions or backreferences (the
 * over-approximation isn't sound for those — see {@code PikeVMMatcher.rejectDfa}'s field doc for
 * the original derivation), or when the over-approximation can match the empty string (it would
 * then accept at every position, making it useless as a filter).
 */
final class RejectDfaFactory {

  private RejectDfaFactory() {}

  static final class Bundle {
    final LazyDFACache dfa;
    final NfaStep step;

    private Bundle(LazyDFACache dfa, NfaStep step) {
      this.dfa = dfa;
      this.step = step;
    }
  }

  static Bundle build(NFA nfa) {
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.assertionType != null || s.backrefCheck != null) return null;
    }

    int stateCount = nfa.getStates().size();
    NFA.NFAState[] statesById = new NFA.NFAState[stateCount];
    for (NFA.NFAState s : nfa.getStates()) {
      statesById[s.id] = s;
    }
    boolean[] isAccept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      isAccept[s.id] = true;
    }

    int[] startAll = closure(statesById, stateCount, new int[] {nfa.getStartState().id});
    for (int id : startAll) {
      if (isAccept[id]) return null; // over-approximation matches empty -> useless as a filter
    }

    int[] acceptArr = new int[nfa.getAcceptStates().size()];
    int ai = 0;
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      acceptArr[ai++] = s.id;
    }

    LazyDFACache dfaCache = new LazyDFACache(startAll, acceptArr);
    NfaStep step =
        (cur, c) -> {
          int[] targets = transitionTargets(statesById, stateCount, cur, (char) c);
          int[] tc = closure(statesById, stateCount, targets);
          return union(tc, startAll);
        };
    return new Bundle(dfaCache, step);
  }

  /**
   * Sorted, de-duplicated epsilon closure crossing every anchor state (sound over-approximation).
   */
  private static int[] closure(NFA.NFAState[] statesById, int stateCount, int[] seed) {
    boolean[] inSet = new boolean[stateCount];
    int[] stack = new int[stateCount];
    int sp = 0;
    for (int id : seed) {
      if (!inSet[id]) {
        inSet[id] = true;
        stack[sp++] = id;
      }
    }
    int count = sp;
    while (sp > 0) {
      int id = stack[--sp];
      for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
        if (!inSet[e.id]) {
          inSet[e.id] = true;
          stack[sp++] = e.id;
          count++;
        }
      }
    }
    int[] out = new int[count];
    int oi = 0;
    for (int id = 0; id < stateCount; id++) {
      if (inSet[id]) out[oi++] = id;
    }
    return out;
  }

  /** Targets of consuming transitions on {@code ch} from the given NFA state ids (unsorted). */
  private static int[] transitionTargets(
      NFA.NFAState[] statesById, int stateCount, int[] stateIds, char ch) {
    boolean[] seen = new boolean[stateCount];
    int[] tmp = new int[stateCount];
    int n = 0;
    for (int id : stateIds) {
      for (NFA.Transition tr : statesById[id].getTransitions()) {
        if (tr.chars.contains(ch) && !seen[tr.target.id]) {
          seen[tr.target.id] = true;
          tmp[n++] = tr.target.id;
        }
      }
    }
    return Arrays.copyOf(tmp, n);
  }

  /** Sorted two-pointer union of two ascending, de-duplicated int arrays. */
  private static int[] union(int[] a, int[] b) {
    int[] merged = new int[a.length + b.length];
    int ai = 0, bi = 0, n = 0;
    while (ai < a.length && bi < b.length) {
      int av = a[ai], bv = b[bi];
      if (av < bv) {
        merged[n++] = av;
        ai++;
      } else if (bv < av) {
        merged[n++] = bv;
        bi++;
      } else {
        merged[n++] = av;
        ai++;
        bi++;
      }
    }
    while (ai < a.length) merged[n++] = a[ai++];
    while (bi < b.length) merged[n++] = b[bi++];
    return Arrays.copyOf(merged, n);
  }
}
