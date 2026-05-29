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

    for (int candidate = from; candidate < length; candidate++) {
      char first = input.charAt(candidate);
      if (first < 128 && !startAscii[first]) {
        continue;
      }
      int state =
          nextState(
              startState,
              first,
              classCount,
              transitions,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses);
      if (state < 0) {
        continue;
      }
      if (state < accepting.length && accepting[state]) {
        return candidate;
      }
      for (int pos = candidate + 1; pos < length; pos++) {
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
          break;
        }
        if (state < accepting.length && accepting[state]) {
          return candidate;
        }
      }
    }
    return -1;
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

    for (int candidate = from; candidate < length; candidate++) {
      char first = input.charAt(candidate);
      if (first < 128 && !startAscii[first]) {
        continue;
      }
      int state =
          nextState(
              startState,
              first,
              classCount,
              transitions,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses);
      if (state < 0) {
        continue;
      }
      if (state < accepting.length && accepting[state]) {
        bounds[0] = candidate;
        bounds[1] = candidate + 1;
        return true;
      }
      for (int pos = candidate + 1; pos < length; pos++) {
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
          break;
        }
        if (state < accepting.length && accepting[state]) {
          bounds[0] = candidate;
          bounds[1] = pos + 1;
          return true;
        }
      }
    }
    return false;
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
