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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Compact table representation for large pure DFA matchers. */
public final class DFATableData {
  public final int startState;
  public final int stateSlots;
  public final int classCount;
  public final int[] transitions;
  public final boolean[] accepting;
  public final int[] asciiClasses;
  public final char[] rangeStarts;
  public final char[] rangeEnds;
  public final int[] rangeClasses;

  private DFATableData(
      int startState,
      int stateSlots,
      int classCount,
      int[] transitions,
      boolean[] accepting,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses) {
    this.startState = startState;
    this.stateSlots = stateSlots;
    this.classCount = classCount;
    this.transitions = transitions;
    this.accepting = accepting;
    this.asciiClasses = asciiClasses;
    this.rangeStarts = rangeStarts;
    this.rangeEnds = rangeEnds;
    this.rangeClasses = rangeClasses;
  }

  public static DFATableData from(DFA dfa) {
    int maxStateId = 0;
    for (DFA.DFAState state : dfa.getAllStates()) {
      maxStateId = Math.max(maxStateId, state.id);
    }
    int stateSlots = maxStateId + 1;

    TreeSet<Integer> boundaries = new TreeSet<>();
    boundaries.add(0);
    boundaries.add(0x10000);
    for (DFA.DFAState state : dfa.getAllStates()) {
      for (CharSet chars : state.transitions.keySet()) {
        for (CharSet.Range range : chars.getRanges()) {
          boundaries.add((int) range.start);
          if (range.end != Character.MAX_VALUE) {
            boundaries.add(((int) range.end) + 1);
          }
        }
      }
    }

    List<Integer> points = new ArrayList<>(boundaries);
    Map<VectorKey, Integer> classIds = new HashMap<>();
    List<int[]> classVectors = new ArrayList<>();
    List<Character> rangeStarts = new ArrayList<>();
    List<Character> rangeEnds = new ArrayList<>();
    List<Integer> rangeClasses = new ArrayList<>();

    for (int i = 0; i < points.size() - 1; i++) {
      int start = points.get(i);
      int endExclusive = points.get(i + 1);
      if (start >= endExclusive) {
        continue;
      }

      char representative = (char) start;
      int[] vector = new int[stateSlots];
      Arrays.fill(vector, -1);
      for (DFA.DFAState state : dfa.getAllStates()) {
        for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
          if (entry.getKey().contains(representative)) {
            vector[state.id] = entry.getValue().target.id;
            break;
          }
        }
      }

      VectorKey key = new VectorKey(vector);
      Integer classId = classIds.get(key);
      if (classId == null) {
        classId = classVectors.size();
        classIds.put(key, classId);
        classVectors.add(vector);
      }

      char rangeStart = (char) start;
      char rangeEnd = (char) (endExclusive - 1);
      int last = rangeClasses.size() - 1;
      if (last >= 0
          && rangeClasses.get(last).intValue() == classId
          && ((int) rangeEnds.get(last)) + 1 == start) {
        rangeEnds.set(last, rangeEnd);
      } else {
        rangeStarts.add(rangeStart);
        rangeEnds.add(rangeEnd);
        rangeClasses.add(classId);
      }
    }

    int classCount = classVectors.size();
    int[] transitions = new int[stateSlots * classCount];
    Arrays.fill(transitions, -1);
    for (int classId = 0; classId < classCount; classId++) {
      int[] vector = classVectors.get(classId);
      for (int state = 0; state < stateSlots; state++) {
        transitions[state * classCount + classId] = vector[state];
      }
    }

    boolean[] accepting = new boolean[stateSlots];
    for (DFA.DFAState state : dfa.getAcceptStates()) {
      accepting[state.id] = true;
    }

    int[] asciiClasses = new int[128];
    for (int ch = 0; ch < asciiClasses.length; ch++) {
      asciiClasses[ch] = classFor((char) ch, rangeStarts, rangeEnds, rangeClasses);
    }

    return new DFATableData(
        dfa.getStartState().id,
        stateSlots,
        classCount,
        transitions,
        accepting,
        asciiClasses,
        toCharArray(rangeStarts),
        toCharArray(rangeEnds),
        toIntArray(rangeClasses));
  }

  public int estimatedBytes() {
    return transitions.length * Integer.BYTES
        + accepting.length
        + asciiClasses.length * Integer.BYTES
        + rangeStarts.length * Character.BYTES
        + rangeEnds.length * Character.BYTES
        + rangeClasses.length * Integer.BYTES;
  }

  private static int classFor(
      char ch, List<Character> starts, List<Character> ends, List<Integer> classes) {
    for (int i = 0; i < starts.size(); i++) {
      if (ch >= starts.get(i) && ch <= ends.get(i)) {
        return classes.get(i);
      }
    }
    return 0;
  }

  private static char[] toCharArray(List<Character> values) {
    char[] result = new char[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
  }

  private static int[] toIntArray(List<Integer> values) {
    int[] result = new int[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
  }

  private static final class VectorKey {
    private final int[] vector;
    private final int hash;

    private VectorKey(int[] vector) {
      this.vector = vector;
      this.hash = Arrays.hashCode(vector);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof VectorKey && Arrays.equals(vector, ((VectorKey) obj).vector);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
