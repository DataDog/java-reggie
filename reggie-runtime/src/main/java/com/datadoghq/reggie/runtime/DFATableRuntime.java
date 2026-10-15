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

/** Runtime helpers used by generated table-driven DFA matchers. */
public final class DFATableRuntime {
  private DFATableRuntime() {}

  public static int[] decodeRleIntArray(String[] chunks, int length) {
    int[] result = new int[length];
    int out = 0;
    CharReader reader = new CharReader(chunks);
    while (out < length && reader.hasNext()) {
      int value = readInt(reader);
      int count = readInt(reader);
      for (int i = 0; i < count && out < length; i++) {
        result[out++] = value;
      }
    }
    if (out != length) {
      throw new IllegalArgumentException("Invalid encoded DFA int table");
    }
    return result;
  }

  public static boolean[] decodeBooleanArray(String[] chunks, int length) {
    boolean[] result = new boolean[length];
    CharReader reader = new CharReader(chunks);
    for (int i = 0; i < length; i++) {
      if (!reader.hasNext()) {
        throw new IllegalArgumentException("Invalid encoded DFA boolean table");
      }
      result[i] = reader.next() != 0;
    }
    return result;
  }

  public static char[] decodeCharArray(String[] chunks, int length) {
    char[] result = new char[length];
    CharReader reader = new CharReader(chunks);
    for (int i = 0; i < length; i++) {
      if (!reader.hasNext()) {
        throw new IllegalArgumentException("Invalid encoded DFA char table");
      }
      result[i] = reader.next();
    }
    return result;
  }

  public static boolean matches(
      CharSequence input,
      int startState,
      int classCount,
      int[] transitions,
      boolean[] accepting,
      int[] asciiClasses,
      boolean[] startAscii,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses) {
    if (input == null) {
      return false;
    }
    int state = startState;
    for (int pos = 0; pos < input.length(); pos++) {
      state =
          nextState(
              state,
              input.charAt(pos),
              classCount,
              transitions,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses);
      if (state < 0) {
        return false;
      }
    }
    return state < accepting.length && accepting[state];
  }

  public static boolean matchesBounded(
      CharSequence input,
      int start,
      int end,
      int startState,
      int classCount,
      int[] transitions,
      boolean[] accepting,
      int[] asciiClasses,
      boolean[] startAscii,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses) {
    if (input == null || start < 0 || end < start || end > input.length()) {
      return false;
    }
    int state = startState;
    for (int pos = start; pos < end; pos++) {
      state =
          nextState(
              state,
              input.charAt(pos),
              classCount,
              transitions,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses);
      if (state < 0) {
        return false;
      }
    }
    return state < accepting.length && accepting[state];
  }

  public static int findFrom(
      CharSequence input,
      int start,
      int startState,
      int classCount,
      int[] transitions,
      boolean[] accepting,
      int[] asciiClasses,
      boolean[] startAscii,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses) {
    if (input == null) {
      return -1;
    }
    int length = input.length();
    int from = Math.max(0, start);
    if (from > length) {
      return -1;
    }
    if (startState < accepting.length && accepting[startState]) {
      return from;
    }
    return leftmostStart(
        input,
        from,
        length,
        startState,
        classCount,
        transitions,
        accepting,
        asciiClasses,
        startAscii,
        rangeStarts,
        rangeEnds,
        rangeClasses,
        null);
  }

  public static boolean findBoundsFrom(
      CharSequence input,
      int start,
      int[] bounds,
      int startState,
      int classCount,
      int[] transitions,
      boolean[] accepting,
      int[] asciiClasses,
      boolean[] startAscii,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses) {
    if (input == null || bounds == null || bounds.length < 2) {
      return false;
    }
    int length = input.length();
    int from = Math.max(0, start);
    if (from > length) {
      return false;
    }

    if (startState < accepting.length && accepting[startState]) {
      bounds[0] = from;
      bounds[1] = from;
      return true;
    }

    int candidate =
        leftmostStart(
            input,
            from,
            length,
            startState,
            classCount,
            transitions,
            accepting,
            asciiClasses,
            startAscii,
            rangeStarts,
            rangeEnds,
            rangeClasses,
            bounds);
    if (candidate < 0) {
      return false;
    }
    bounds[0] = candidate;
    return true;
  }

  /**
   * Smallest start in [{@code from}, {@code length}) of a non-empty match (the start state is not
   * accepting), or -1. Runs the DFA once per candidate start; once the characters consumed by
   * failed runs exceed {@link DFALinearFind#workLimit}, finishes in {@link DFALinearFind#findFrom}.
   * If {@code bounds} is not null and a start is found, stores the end of the longest match from
   * that start in {@code bounds[1]}; otherwise leaves {@code bounds} unchanged.
   */
  private static int leftmostStart(
      CharSequence input,
      int from,
      int length,
      int startState,
      int classCount,
      int[] transitions,
      boolean[] accepting,
      int[] asciiClasses,
      boolean[] startAscii,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      int[] bounds) {
    int work = 0;
    for (int candidate = from; candidate < length; candidate++) {
      char first = input.charAt(candidate);
      if (first < 128 && !startAscii[first]) {
        continue;
      }
      int pos = candidate;
      int state = startState;
      int end = -1;
      while (true) {
        state =
            nextState(
                state,
                input.charAt(pos),
                classCount,
                transitions,
                asciiClasses,
                rangeStarts,
                rangeEnds,
                rangeClasses);
        pos++;
        if (state < 0) {
          break;
        }
        if (state < accepting.length && accepting[state]) {
          if (bounds == null) {
            return candidate;
          }
          end = pos;
        }
        if (pos >= length) {
          break;
        }
      }
      if (end >= 0) {
        bounds[1] = end;
        return candidate;
      }
      work += pos - candidate;
      if (work > DFALinearFind.workLimit(length - from)) {
        DFALinearFind.Stepper stepper =
            new TableStepper(
                input,
                classCount,
                transitions,
                accepting,
                asciiClasses,
                rangeStarts,
                rangeEnds,
                rangeClasses);
        int found =
            DFALinearFind.findFrom(
                stepper,
                null,
                candidate + 1,
                length,
                startState,
                transitions.length / classCount,
                false);
        if (found < 0 || bounds == null) {
          return found;
        }
        // The run from found accepts, so this returns found and sets bounds[1]
        return leftmostStart(
            input,
            found,
            length,
            startState,
            classCount,
            transitions,
            accepting,
            asciiClasses,
            startAscii,
            rangeStarts,
            rangeEnds,
            rangeClasses,
            bounds);
      }
    }
    return -1;
  }

  /**
   * Table DFA step for {@link DFALinearFind}. Ignores the String argument: it reads the {@code
   * CharSequence} it was built with (DFALinearFind passes no input, and no multiline start: table
   * DFAs carry no anchors or assertions).
   */
  private static final class TableStepper implements DFALinearFind.Stepper {
    private final CharSequence input;
    private final int classCount;
    private final int[] transitions;
    private final boolean[] accepting;
    private final int[] asciiClasses;
    private final char[] rangeStarts;
    private final char[] rangeEnds;
    private final int[] rangeClasses;

    TableStepper(
        CharSequence input,
        int classCount,
        int[] transitions,
        boolean[] accepting,
        int[] asciiClasses,
        char[] rangeStarts,
        char[] rangeEnds,
        int[] rangeClasses) {
      this.input = input;
      this.classCount = classCount;
      this.transitions = transitions;
      this.accepting = accepting;
      this.asciiClasses = asciiClasses;
      this.rangeStarts = rangeStarts;
      this.rangeEnds = rangeEnds;
      this.rangeClasses = rangeClasses;
    }

    @Override
    public Object linPrepare(String ignored, int from, int len) {
      return null;
    }

    @Override
    public int linStep(String ignored, int state, int pos, Object prepared) {
      if (state < accepting.length && accepting[state]) {
        return DFALinearFind.ACCEPT;
      }
      if (pos >= input.length()) {
        return DFALinearFind.DEAD;
      }
      int next =
          nextState(
              state,
              input.charAt(pos),
              classCount,
              transitions,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses);
      return next < 0 ? DFALinearFind.DEAD : next;
    }

    @Override
    public boolean linStartAccepts(String ignored, int pos, Object prepared) {
      return false; // findFrom/findBoundsFrom return before searching when the start accepts
    }
  }

  /**
   * DFA of a variable-width lookahead body, decoded from the string the DFA generators emit: chars
   * {@code startState, stateSlots, classCount, rangeCount}, then one accepting flag (0/1) per
   * state, the transitions (target + 1, 0 for none) indexed {@code state * classCount + class}, the
   * 128 ASCII classes, and the range starts, range ends and range classes. Each of these chars is
   * stored as two string chars, its high byte then its low byte.
   */
  public static final class GateTable {
    final int startState;
    final int stateSlots;
    final int classCount;
    final long accepting;
    final int[] transitions;
    final int[] asciiClasses;
    final char[] rangeStarts;
    final char[] rangeEnds;
    final int[] rangeClasses;

    private GateTable(String bytes) {
      char[] e = new char[bytes.length() / 2];
      for (int i = 0; i < e.length; i++) {
        e[i] = (char) (bytes.charAt(2 * i) << 8 | bytes.charAt(2 * i + 1));
      }
      startState = e[0];
      stateSlots = e[1];
      classCount = e[2];
      int rangeCount = e[3];
      int at = 4;
      long acc = 0;
      for (int s = 0; s < stateSlots; s++) {
        if (e[at++] != 0) acc |= 1L << s;
      }
      accepting = acc;
      transitions = new int[stateSlots * classCount];
      for (int i = 0; i < transitions.length; i++) {
        transitions[i] = e[at++] - 1;
      }
      asciiClasses = new int[128];
      for (int i = 0; i < 128; i++) {
        asciiClasses[i] = e[at++];
      }
      rangeStarts = new char[rangeCount];
      rangeEnds = new char[rangeCount];
      rangeClasses = new int[rangeCount];
      for (int i = 0; i < rangeCount; i++) rangeStarts[i] = e[at++];
      for (int i = 0; i < rangeCount; i++) rangeEnds[i] = e[at++];
      for (int i = 0; i < rangeCount; i++) rangeClasses[i] = e[at++];
    }
  }

  /** Decodes the gate tables of one matcher (at most 64 states each). */
  public static GateTable[] gateTables(String[] encoded) {
    GateTable[] tables = new GateTable[encoded.length];
    for (int i = 0; i < encoded.length; i++) {
      tables[i] = new GateTable(encoded[i]);
    }
    return tables;
  }

  /**
   * For each gate, whether its DFA run anchored at {@code p} reaches an accepting state before it
   * dies or reaches the end of {@code input}, for every p in [{@code from}, input.length()]. Read
   * the result with {@link #gateBit}.
   */
  public static Object gateBits(GateTable[] gates, String input, int from) {
    int len = input.length();
    long[][] bits = new long[gates.length][];
    for (int g = 0; g < gates.length; g++) {
      GateTable t = gates[g];
      // b[0] = from; bit i of words 1.. is position from + i
      long[] b = new long[2 + ((len - from) >>> 6)];
      b[0] = from;
      // live: states whose run from the current position accepts (at len, only accepting ones)
      long live = t.accepting;
      for (int p = len; ; p--) {
        if ((live >>> t.startState & 1) != 0) {
          b[1 + ((p - from) >>> 6)] |= 1L << (p - from);
        }
        if (p == from) break;
        int cls =
            charClass(
                input.charAt(p - 1), t.asciiClasses, t.rangeStarts, t.rangeEnds, t.rangeClasses);
        long next = t.accepting;
        for (int s = 0, i = cls; s < t.stateSlots; s++, i += t.classCount) {
          int target = t.transitions[i];
          if (target >= 0 && (live >>> target & 1) != 0) next |= 1L << s;
        }
        live = next;
      }
      bits[g] = b;
    }
    return bits;
  }

  /** Bit of {@link #gateBits} for gate {@code gate} at {@code pos}. */
  public static boolean gateBit(Object bits, int gate, int pos) {
    long[] b = ((long[][]) bits)[gate];
    int i = pos - (int) b[0];
    return (b[1 + (i >>> 6)] >>> i & 1) != 0;
  }

  private static int nextState(
      int state,
      char ch,
      int classCount,
      int[] transitions,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses) {
    if (state < 0) {
      return -1;
    }
    int cls = charClass(ch, asciiClasses, rangeStarts, rangeEnds, rangeClasses);
    int index = state * classCount + cls;
    return index >= 0 && index < transitions.length ? transitions[index] : -1;
  }

  private static int charClass(
      char ch, int[] asciiClasses, char[] rangeStarts, char[] rangeEnds, int[] rangeClasses) {
    if (ch < 128) {
      return asciiClasses[ch];
    }
    int low = 0;
    int high = rangeStarts.length - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (ch < rangeStarts[mid]) {
        high = mid - 1;
      } else if (ch > rangeEnds[mid]) {
        low = mid + 1;
      } else {
        return rangeClasses[mid];
      }
    }
    return 0;
  }

  private static int readInt(CharReader reader) {
    int high = reader.next();
    int low = reader.next();
    return (high << 16) | low;
  }

  private static final class CharReader {
    private final String[] chunks;
    private int chunkIndex;
    private int charIndex;

    private CharReader(String[] chunks) {
      this.chunks = chunks;
    }

    private boolean hasNext() {
      while (chunkIndex < chunks.length && charIndex >= chunks[chunkIndex].length()) {
        chunkIndex++;
        charIndex = 0;
      }
      return chunkIndex < chunks.length;
    }

    private char next() {
      if (!hasNext()) {
        throw new IllegalArgumentException("Unexpected end of encoded DFA table");
      }
      return chunks[chunkIndex].charAt(charIndex++);
    }
  }
}
