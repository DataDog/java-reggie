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
 * Interpreted memoized-priority backtracker for backreference patterns (Task 8, "shape B").
 *
 * <p>Runs the Thompson NFA as a priority-ordered DFS (Perl leftmost / first-alternative / greedy
 * order, encoded by {@link NFA.NFAState#getEpsilonTransitions()} insertion order), with memoization
 * on {@code (state, pos, referenced-group spans)} so that (a) divergent backref-span paths stay
 * distinct, (b) the first arrival in preorder — the highest-priority one — wins, and (c) the search
 * is finite/polynomial. The first accepting configuration found in preorder is the Perl-correct
 * match, including non-referenced group spans.
 *
 * <p>This deliberately mirrors {@link PikeVMMatcher}'s capture-slot convention (slot {@code 2k} =
 * start, {@code 2k+1} = end of group {@code k}; group 0 = whole match) and reuses the same anchor
 * semantics, but is a separate engine: PikeVM is strictly lock-step (one char per step) and cannot
 * express a backref's variable {@code +L} advance.
 *
 * <p>First increment: implements the boolean + rich first-match API exercised by the backref
 * regression suite ({@code matches}, {@code find}, {@code findFrom}, {@code match}, {@code
 * findMatch}, {@code findMatchFrom}). {@code matchInto}/{@code matchBounded}/{@code
 * findLongestMatchEnd} inherit the base defaults for now.
 *
 * <p>TODO (before the benchmark gate): the memo currently keys on a {@code String} and clones
 * capture vectors per frame — correct but allocation-heavy. Replace with a preallocated
 * open-addressing int table + reused capture storage once correctness is locked.
 */
public final class BackrefBacktrackMatcher extends ReggieMatcher {

  private final int groupCount;
  private final int slotCount;
  private final NFA.NFAState[] byId; // dense by state id (ids may be sparse; sized to maxId+1)
  private final boolean[] isAcceptById;
  private final int startStateId;
  private final int[] referencedGroups; // sorted; groups that are targets of some \k
  private final boolean caseInsensitive;

  public BackrefBacktrackMatcher(NFA nfa, String pattern) {
    super(pattern);
    this.groupCount = nfa.getGroupCount();
    this.slotCount = 2 * (groupCount + 1);
    int maxId = 0;
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.id > maxId) maxId = s.id;
    }
    this.byId = new NFA.NFAState[maxId + 1];
    this.isAcceptById = new boolean[maxId + 1];
    Set<Integer> refs = new HashSet<>();
    for (NFA.NFAState s : nfa.getStates()) {
      byId[s.id] = s;
      if (s.backrefCheck != null) {
        refs.add(s.backrefCheck);
      }
    }
    for (NFA.NFAState a : nfa.getAcceptStates()) {
      isAcceptById[a.id] = true;
    }
    this.startStateId = nfa.getStartState().id;
    int[] r = new int[refs.size()];
    int i = 0;
    for (int g : refs) r[i++] = g;
    Arrays.sort(r);
    this.referencedGroups = r;
    this.caseInsensitive = pattern.contains("(?i)");
  }

  // ---- One DFS frame: (state, pos, caps). caps is shared until the frame is popped and cloned.
  // ----
  private static final class Frame {
    final int state;
    final int pos;
    final int[] caps;

    Frame(int state, int pos, int[] caps) {
      this.state = state;
      this.pos = pos;
      this.caps = caps;
    }
  }

  @Override
  public boolean matches(String input) {
    return runSearch(input, 0, input.length(), true) != null;
  }

  @Override
  public boolean find(String input) {
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    int len = input.length();
    int clamped = Math.max(0, start);
    if (clamped > len) return -1;
    for (int s = clamped; s <= len; s++) {
      if (runSearch(input, s, len, false) != null) {
        return s;
      }
    }
    return -1;
  }

  @Override
  public MatchResult match(String input) {
    int[] caps = runSearch(input, 0, input.length(), true);
    return caps == null ? null : buildResult(input, caps);
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int len = input.length();
    int clamped = Math.max(0, start);
    if (clamped > len) return null;
    for (int s = clamped; s <= len; s++) {
      int[] caps = runSearch(input, s, len, false);
      if (caps != null) {
        return buildResult(input, caps);
      }
    }
    return null;
  }

  /**
   * Priority-ordered memoized DFS from a fixed match start. Returns the winning capture vector
   * (group 0 = whole match) or {@code null}.
   *
   * @param matchStart where group 0 begins (the seed position)
   * @param regionEnd end of the searchable region (input length)
   * @param wholeInput when true (matches/match), accept only at {@code pos == regionEnd}; otherwise
   *     (find*), accept at the first accepting state reached in preorder
   */
  private int[] runSearch(String input, int matchStart, int regionEnd, boolean wholeInput) {
    final int regionStart = 0; // anchors evaluate against the absolute input origin
    int[] seed = new int[slotCount];
    Arrays.fill(seed, -1);
    seed[0] = matchStart;

    Deque<Frame> stack = new ArrayDeque<>();
    stack.push(new Frame(startStateId, matchStart, seed));
    Set<String> visited = new HashSet<>();

    while (!stack.isEmpty()) {
      Frame f = stack.pop();
      String key = memoKey(f.state, f.pos, f.caps);
      if (!visited.add(key)) {
        continue; // first (higher-priority) arrival already explored this configuration
      }

      // Accept check (first accept popped in preorder == highest-priority match).
      if (isAcceptById[f.state] && (!wholeInput || f.pos == regionEnd)) {
        int[] win = f.caps.clone();
        win[1] = f.pos; // group-0 end
        return win;
      }

      NFA.NFAState s = byId[f.state];
      // Apply this state's group boundaries to a working copy that all successors inherit.
      int[] c = f.caps.clone();
      if (s.enterGroup != null) c[2 * s.enterGroup] = f.pos;
      if (s.exitGroup != null) c[2 * s.exitGroup + 1] = f.pos;

      // Build successors in priority order, then push reversed so the highest-priority is on top.
      List<Frame> succ = new ArrayList<>();
      if (s.backrefCheck != null) {
        int g = s.backrefCheck;
        int gs = c[2 * g];
        int ge = c[2 * g + 1];
        if (gs >= 0) {
          int l = ge - gs;
          if (l == 0) {
            for (NFA.NFAState t : s.getEpsilonTransitions()) succ.add(new Frame(t.id, f.pos, c));
          } else if (l > 0
              && f.pos + l <= regionEnd
              && input.regionMatches(caseInsensitive, f.pos, input, gs, l)) {
            for (NFA.NFAState t : s.getEpsilonTransitions()) {
              succ.add(new Frame(t.id, f.pos + l, c));
            }
          }
        }
      } else if (s.anchor != null) {
        if (checkAnchor(s.anchor, input, f.pos, regionStart, regionEnd)) {
          for (NFA.NFAState t : s.getEpsilonTransitions()) succ.add(new Frame(t.id, f.pos, c));
        }
      } else {
        for (NFA.NFAState t : s.getEpsilonTransitions()) succ.add(new Frame(t.id, f.pos, c));
        if (f.pos < regionEnd) {
          char ch = input.charAt(f.pos);
          for (NFA.Transition tr : s.getTransitions()) {
            if (tr.chars.contains(ch)) succ.add(new Frame(tr.target.id, f.pos + 1, c));
          }
        }
      }

      for (int i = succ.size() - 1; i >= 0; i--) {
        stack.push(succ.get(i));
      }
    }
    return null;
  }

  private String memoKey(int state, int pos, int[] caps) {
    StringBuilder sb = new StringBuilder(16 + 4 * referencedGroups.length);
    sb.append(state).append(',').append(pos);
    for (int g : referencedGroups) {
      sb.append(',').append(caps[2 * g]).append(':').append(caps[2 * g + 1]);
    }
    return sb.toString();
  }

  private MatchResult buildResult(String input, int[] caps) {
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    for (int g = 0; g <= groupCount; g++) {
      starts[g] = caps[2 * g];
      ends[g] = caps[2 * g + 1];
    }
    return new MatchResultImpl(input, starts, ends, groupCount);
  }

  /** Mirrors {@link PikeVMMatcher}'s anchor semantics. */
  private static boolean checkAnchor(
      NFA.AnchorType anchor, String input, int pos, int regionStart, int regionEnd) {
    switch (anchor) {
      case START:
      case STRING_START:
        return pos == regionStart;
      case END:
      case STRING_END:
        if (pos == regionEnd) return true;
        return pos == regionEnd - 1 && input.charAt(pos) == '\n';
      case STRING_END_ABSOLUTE:
        return pos == regionEnd;
      case START_MULTILINE:
        return pos == regionStart || (pos > 0 && input.charAt(pos - 1) == '\n');
      case END_MULTILINE:
        return pos == regionEnd || (pos < regionEnd && input.charAt(pos) == '\n');
      case WORD_BOUNDARY:
        {
          boolean beforeWord = pos > 0 && Character.isLetterOrDigit(input.charAt(pos - 1));
          boolean afterWord = pos < regionEnd && Character.isLetterOrDigit(input.charAt(pos));
          return beforeWord != afterWord;
        }
      case NON_WORD_BOUNDARY:
        {
          boolean beforeWord = pos > 0 && Character.isLetterOrDigit(input.charAt(pos - 1));
          boolean afterWord = pos < regionEnd && Character.isLetterOrDigit(input.charAt(pos));
          return beforeWord == afterWord;
        }
      case RESET_MATCH:
        return true;
      default:
        return false;
    }
  }
}
