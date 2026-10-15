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

/**
 * Linear-time leftmost-start search for DFA matchers.
 *
 * <p>The DFA find loops run the DFA once per candidate start; each run lasts until the DFA dies, so
 * inputs where every run is long cost O(n^2). The loops count the characters their failed runs
 * consumed and, once that exceeds {@link #workLimit}, call {@link #findFrom}, which runs all
 * candidate starts in one left-to-right pass: one live run per DFA state, keeping the smallest
 * start when two runs reach the same state at the same position (their futures are identical). Cost
 * is O(stateCount * n).
 */
public final class DFALinearFind {
  private DFALinearFind() {}

  /** {@link Stepper#linStep} result: the state accepts at the given position. */
  public static final int ACCEPT = -2;

  /** {@link Stepper#linStep} result: the run dies at the given position. */
  public static final int DEAD = -1;

  /**
   * Work allowed per remaining input character before the find loops switch to this class. Final so
   * the JIT folds it into {@link #workLimit} as a constant.
   */
  private static final int WORK_FACTOR =
      Math.max(0, Integer.getInteger("reggie.dfa.linearFindWorkFactor", 4));

  /**
   * DFA_UNROLLED find loops searching at most this many characters skip the work count; their
   * failed runs consume at most span * (span + 1) / 2 characters. 0 when the work factor is 0.
   */
  public static final int SHORT_SPAN = WORK_FACTOR == 0 ? 0 : 256;

  /** One DFA step, implemented by generated DFA_UNROLLED and DFA_SWITCH matchers. */
  public interface Stepper {
    /**
     * Called once per {@link #findFrom} call before any step; the result is passed to {@link
     * #linStep} and {@link #linStartAccepts}. Generated matchers return their lookahead gate
     * results for every position in [{@code from}, input length], or null.
     */
    Object linPrepare(String input, int from, int len);

    /**
     * Processes {@code state} at {@code pos} exactly as the matcher's per-start run does: returns
     * {@link #ACCEPT} if the run accepts there, {@link #DEAD} if it dies, else the next state after
     * consuming the character at {@code pos}.
     */
    int linStep(String input, int state, int pos, Object prepared);

    /** Whether a run starting at {@code pos} accepts the empty match before consuming input. */
    boolean linStartAccepts(String input, int pos, Object prepared);
  }

  /**
   * Characters that failed runs may consume, summed over one find call, before the call switches to
   * {@link #findFrom}; {@code span} is the length of the searched range.
   */
  public static int workLimit(int span) {
    return (int) Math.min(Integer.MAX_VALUE, (long) WORK_FACTOR * span + 64L * WORK_FACTOR);
  }

  /**
   * Returns the smallest start in [{@code from}, {@code len}) at which a run of {@code s} accepts,
   * or -1. Runs still live at {@code len} are stepped once more for the end-of-input check; a start
   * at {@code len} itself is left to the caller. {@code input} is only passed to {@code s} and to
   * the multiline-start check.
   *
   * @param multilineStart start only where {@link LineTerminators#isMultilineStart} holds
   */
  public static int findFrom(
      Stepper s,
      String input,
      int from,
      int len,
      int startState,
      int stateCount,
      boolean multilineStart) {
    int[] states = new int[stateCount];
    int[] starts = new int[stateCount];
    int[] nextStates = new int[stateCount];
    int[] nextStarts = new int[stateCount];
    // mark[q] == p + 1: q already has a run at position p
    int[] mark = new int[stateCount];
    int count = 0;
    int best = -1;
    Object prepared = s.linPrepare(input, from, len);
    for (int p = from; p <= len; p++) {
      if (best < 0
          && p < len
          && (!multilineStart || LineTerminators.isMultilineStart(input, p, 0, len))) {
        if (s.linStartAccepts(input, p, prepared)) {
          best = p;
        } else if (mark[startState] != p + 1) {
          // p is larger than every live start, so appending keeps the list sorted by start
          mark[startState] = p + 1;
          states[count] = startState;
          starts[count++] = p;
        }
      }
      int nextCount = 0;
      for (int i = 0; i < count; i++) {
        int start = starts[i];
        if (best >= 0 && start >= best) break;
        int r = s.linStep(input, states[i], p, prepared);
        if (r == ACCEPT) {
          best = start; // later entries have larger starts
          break;
        }
        if (r >= 0 && mark[r] != p + 2) {
          mark[r] = p + 2;
          nextStates[nextCount] = r;
          nextStarts[nextCount++] = start;
        }
      }
      int[] t = states;
      states = nextStates;
      nextStates = t;
      t = starts;
      starts = nextStarts;
      nextStarts = t;
      count = nextCount;
      if (best >= 0 && count == 0) break;
    }
    return best;
  }
}
