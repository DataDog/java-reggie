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
 * Runtime for bit-parallel Glushkov (position-automaton) matchers. The active set of positions is a
 * single 64-bit word; one input character advances it by expanding along the {@code follow}
 * relation and intersecting with the character's entry mask.
 *
 * <p>This engine computes <strong>leftmost-longest</strong> matches. It must only be used for
 * patterns that are leftmost-longest-safe (greedy / non-overlapping alternation), for which
 * leftmost-longest coincides with {@code java.util.regex} leftmost-first semantics; priority-
 * conflicting patterns are routed to other strategies by the analyzer and never reach this code.
 *
 * <p>Unanchored {@code find} uses two linear passes: a backward pass over a reverse automaton finds
 * the leftmost match start, then a forward anchored pass finds the longest end at that start (the
 * RE2 forward+reverse technique).
 */
public final class BitParallelGlushkovRuntime {
  private BitParallelGlushkovRuntime() {}

  /**
   * Decodes a {@code long[]} encoded as 4 chars per value (bits 48-63, 32-47, 16-31, 0-15, high to
   * low) spread across chunked strings. Mirrors the {@code decodeCharArray} convention used by
   * {@code DFATableRuntime}, extended to 64-bit values.
   */
  public static long[] decodeLongArray(String[] chunks, int length) {
    long[] result = new long[length];
    int out = 0;
    int chunkIndex = 0;
    int charIndex = 0;
    while (out < length) {
      long value = 0L;
      for (int shift = 48; shift >= 0; shift -= 16) {
        while (chunkIndex < chunks.length && charIndex >= chunks[chunkIndex].length()) {
          chunkIndex++;
          charIndex = 0;
        }
        if (chunkIndex >= chunks.length) {
          throw new IllegalArgumentException("Invalid encoded long array");
        }
        value |= ((long) chunks[chunkIndex].charAt(charIndex++)) << shift;
      }
      result[out++] = value;
    }
    return result;
  }

  /** Whole-input anchored match. */
  public static boolean matches(
      CharSequence input,
      long initial,
      long accept,
      boolean nullable,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    if (input == null) {
      return false;
    }
    return matchesBounded(
        input,
        0,
        input.length(),
        initial,
        accept,
        nullable,
        follow,
        asciiClasses,
        rangeStarts,
        rangeEnds,
        rangeClasses,
        entry);
  }

  /** Anchored match of the whole region {@code [start, end)}. */
  public static boolean matchesBounded(
      CharSequence input,
      int start,
      int end,
      long initial,
      long accept,
      boolean nullable,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    if (input == null || start < 0 || end < start || end > input.length()) {
      return false;
    }
    if (start == end) {
      return nullable;
    }
    long active =
        initial
            & entry[
                classOf(input.charAt(start), asciiClasses, rangeStarts, rangeEnds, rangeClasses)];
    for (int pos = start + 1; pos < end; pos++) {
      if (active == 0L) {
        return false;
      }
      active =
          expand(active, follow)
              & entry[
                  classOf(input.charAt(pos), asciiClasses, rangeStarts, rangeEnds, rangeClasses)];
    }
    return (active & accept) != 0L;
  }

  /**
   * Longest anchored match end starting at {@code start} within {@code [start, end)}, or {@code -1}
   * if no match starts at {@code start}. Returns {@code start} for the empty match when nullable.
   */
  public static int longestEndFrom(
      CharSequence input,
      int start,
      int end,
      long initial,
      long accept,
      boolean nullable,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    int lastAccept = nullable ? start : -1;
    long active = 0L;
    boolean first = true;
    for (int pos = start; pos < end; pos++) {
      int cls = classOf(input.charAt(pos), asciiClasses, rangeStarts, rangeEnds, rangeClasses);
      long entered = (first ? initial : expand(active, follow)) & entry[cls];
      first = false;
      if (entered == 0L) {
        break;
      }
      active = entered;
      if ((active & accept) != 0L) {
        lastAccept = pos + 1;
      }
    }
    return lastAccept;
  }

  /**
   * Smallest match start position {@code >= from} in {@code input}, or {@code -1} if none. Scans
   * the reverse automaton backward, injecting an accepting-position seed at every position;
   * whenever the reverse run reaches an initial (first) position a forward match starts there.
   */
  public static int leftmostStart(
      CharSequence input,
      int from,
      long initial,
      long accept,
      long[] followReverse,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    int len = input.length();
    long revActive = 0L;
    int leftmost = -1;
    for (int pos = len - 1; pos >= from; pos--) {
      int cls = classOf(input.charAt(pos), asciiClasses, rangeStarts, rangeEnds, rangeClasses);
      revActive = (expand(revActive, followReverse) | accept) & entry[cls];
      if ((revActive & initial) != 0L) {
        leftmost = pos;
      }
    }
    return leftmost;
  }

  /**
   * Leftmost match start position {@code >= from}, or {@code -1} if no match exists.
   *
   * @param startsAnywhere when {@code true} every character activates an initial position, so the
   *     leftmost match start is always {@code from} — the reverse scan is skipped
   */
  public static int findFrom(
      CharSequence input,
      int from,
      long initial,
      long accept,
      boolean nullable,
      boolean startsAnywhere,
      long[] follow,
      long[] followReverse,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    if (input == null) {
      return -1;
    }
    int len = input.length();
    int start = Math.max(0, from);
    if (start > len) {
      return -1;
    }
    if (nullable || startsAnywhere) {
      // For nullable, start=from is trivially correct.
      // For startsAnywhere, any character can begin a match, so the leftmost start is from;
      // we still need to verify a match exists — longestEndFrom returns -1 if none does.
      if (startsAnywhere && !nullable) {
        int end =
            longestEndFrom(
                input,
                start,
                len,
                initial,
                accept,
                false,
                follow,
                asciiClasses,
                rangeStarts,
                rangeEnds,
                rangeClasses,
                entry);
        return end < 0 ? -1 : start;
      }
      return start;
    }
    return leftmostStart(
        input,
        start,
        initial,
        accept,
        followReverse,
        asciiClasses,
        rangeStarts,
        rangeEnds,
        rangeClasses,
        entry);
  }

  /**
   * Leftmost-longest match bounds {@code >= from}. On success writes {@code [start, end)} into
   * {@code bounds} and returns {@code true}; otherwise returns {@code false}.
   *
   * @param startsAnywhere when {@code true} every character activates an initial position, so the
   *     reverse scan is skipped and only a single forward pass is performed
   */
  public static boolean findBoundsFrom(
      CharSequence input,
      int from,
      int[] bounds,
      long initial,
      long accept,
      boolean nullable,
      boolean startsAnywhere,
      long[] follow,
      long[] followReverse,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    if (input == null || bounds == null || bounds.length < 2) {
      return false;
    }
    int len = input.length();
    int from0 = Math.max(0, from);
    if (from0 > len) {
      return false;
    }
    // When startsAnywhere, the leftmost start is always from0 — skip the reverse scan entirely.
    int start;
    if (nullable) {
      start = from0;
    } else if (startsAnywhere) {
      start = from0; // reverse scan not needed; longestEndFrom will return -1 if no match exists
    } else {
      start =
          leftmostStart(
              input,
              from0,
              initial,
              accept,
              followReverse,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses,
              entry);
    }
    if (start < 0) {
      return false;
    }
    int end =
        longestEndFrom(
            input,
            start,
            len,
            initial,
            accept,
            nullable,
            follow,
            asciiClasses,
            rangeStarts,
            rangeEnds,
            rangeClasses,
            entry);
    if (end < 0) {
      return false;
    }
    bounds[0] = start;
    bounds[1] = end;
    return true;
  }

  /** Expand the active set along the follow relation (union of follow[p] over set bits p). */
  static long expand(long active, long[] follow) {
    long result = 0L;
    long s = active;
    while (s != 0L) {
      int p = Long.numberOfTrailingZeros(s);
      s &= s - 1;
      result |= follow[p];
    }
    return result;
  }

  /** Map a character to its equivalence-class id. */
  static int classOf(
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
}
