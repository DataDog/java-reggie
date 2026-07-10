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
 * Runtime for counting bit-parallel Glushkov matchers. Extends the standard bit-parallel Glushkov
 * engine with a repetition counter that enforces {@code {min,max}} quantifiers on the body
 * automaton.
 *
 * <p>The active set is a single 64-bit word (one bit per body position). After each character step
 * the engine checks whether the accept mask is set; when it is the counter advances and, if it
 * exceeds {@code counterMax}, the accept positions are cleared so the run continues past the
 * quantifier boundary.
 *
 * <p>All public methods are {@code static} — no instances are ever created.
 */
public final class CountingGlushkovRuntime {
  private CountingGlushkovRuntime() {}

  /**
   * Decodes a {@code long[]} encoded as 4 chars per value (bits 48-63, 32-47, 16-31, 0-15, high to
   * low) spread across chunked strings. Mirrors the {@code decodeLongArray} convention used by
   * {@code BitParallelGlushkovRuntime}.
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
      int counterMin,
      int counterMax,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    return matchesBounded(
        input,
        0,
        input != null ? input.length() : 0,
        initial,
        accept,
        nullable,
        counterMin,
        counterMax,
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
      int counterMin,
      int counterMax,
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
      return counterMin == 0 || (nullable && counterMin == 1);
    }

    // First character: seed with initial (not follow expansion)
    int cls0 = classOf(input.charAt(start), asciiClasses, rangeStarts, rangeEnds, rangeClasses);
    long active = initial & entry[cls0];
    if (active == 0L) {
      return false;
    }
    int counter = 0;
    if ((active & accept) != 0L) {
      counter++;
      if (counter > counterMax) {
        active &= ~accept;
        if (active == 0L) {
          return false;
        }
      }
    }

    // Subsequent characters
    for (int pos = start + 1; pos < end; pos++) {
      active =
          expand(active, follow)
              & entry[
                  classOf(input.charAt(pos), asciiClasses, rangeStarts, rangeEnds, rangeClasses)];
      if (active == 0L) {
        return false;
      }
      if ((active & accept) != 0L) {
        counter++;
        if (counter > counterMax) {
          active &= ~accept;
          if (active == 0L) {
            return false;
          }
        }
      }
    }
    return (active & accept) != 0L && counter >= counterMin;
  }

  /**
   * Longest anchored match end starting at {@code start} within {@code [start, end)}, or {@code -1}
   * if no match starts at {@code start}. Returns {@code start} for the empty match when {@code
   * counterMin == 0} or when {@code nullable && counterMin == 1}.
   */
  public static int longestEndFrom(
      CharSequence input,
      int start,
      int end,
      long initial,
      long accept,
      boolean nullable,
      int counterMin,
      int counterMax,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    int lastAccept = (counterMin == 0 || (nullable && counterMin == 1)) ? start : -1;
    long active = 0L;
    boolean first = true;
    int counter = 0;
    for (int pos = start; pos < end; pos++) {
      int cls = classOf(input.charAt(pos), asciiClasses, rangeStarts, rangeEnds, rangeClasses);
      long entered = (first ? initial : expand(active, follow)) & entry[cls];
      first = false;
      if (entered == 0L) {
        break;
      }
      active = entered;
      if ((active & accept) != 0L) {
        counter++;
        if (counter > counterMax) {
          active &= ~accept;
          if (active == 0L) {
            break;
          }
        } else if (counter >= counterMin) {
          lastAccept = pos + 1;
        }
      }
    }
    return lastAccept;
  }

  /**
   * Leftmost match start position {@code >= from} in {@code input}, or {@code -1} if none. Scans
   * forward trying each start position.
   */
  public static int findFrom(
      CharSequence input,
      int from,
      long initial,
      long accept,
      boolean nullable,
      int counterMin,
      int counterMax,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    if (input == null) {
      return -1;
    }
    int len = input.length();
    long minCharsNeeded = (long) counterMin * minCharsPerRep(nullable, initial, accept, follow);
    for (int start = Math.max(0, from); start <= len; start++) {
      if (minCharsNeeded > len - start) {
        // Not enough input remains from this start to ever reach counterMin repetitions (see
        // minCharsPerRep) -- skip the O(span) longestEndFrom scan rather than re-discovering the
        // same impossibility one character at a time.
        if (start == len) {
          break;
        }
        continue;
      }
      int end =
          longestEndFrom(
              input,
              start,
              len,
              initial,
              accept,
              nullable,
              counterMin,
              counterMax,
              follow,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses,
              entry);
      if (end >= 0) {
        return start;
      }
      if (start == len) {
        break;
      }
    }
    return -1;
  }

  /**
   * Leftmost-longest match bounds {@code >= from}. On success writes {@code [start, end)} into
   * {@code bounds[0]} and {@code bounds[1]} and returns {@code true}; otherwise returns {@code
   * false}.
   */
  public static boolean findBoundsFrom(
      CharSequence input,
      int from,
      int[] bounds,
      long initial,
      long accept,
      boolean nullable,
      int counterMin,
      int counterMax,
      long[] follow,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    if (input == null || bounds == null || bounds.length < 2) {
      return false;
    }
    int len = input.length();
    long minCharsNeeded = (long) counterMin * minCharsPerRep(nullable, initial, accept, follow);
    for (int start = Math.max(0, from); start <= len; start++) {
      if (minCharsNeeded > len - start) {
        // See findFrom: not enough input remains to ever satisfy counterMin repetitions.
        if (start == len) {
          break;
        }
        continue;
      }
      int end =
          longestEndFrom(
              input,
              start,
              len,
              initial,
              accept,
              nullable,
              counterMin,
              counterMax,
              follow,
              asciiClasses,
              rangeStarts,
              rangeEnds,
              rangeClasses,
              entry);
      if (end >= 0) {
        bounds[0] = start;
        bounds[1] = end;
        return true;
      }
      if (start == len) {
        break;
      }
    }
    return false;
  }

  /**
   * Structural (character-independent) lower bound on how many input characters a single body
   * repetition must consume, computed once per {@link #findFrom}/{@link #findBoundsFrom} call and
   * used to skip candidate start positions that mathematically cannot reach {@code counterMin}
   * repetitions before running out of input -- avoiding an O(span) {@link #longestEndFrom} scan
   * that would otherwise re-discover the same impossibility character by character at every
   * candidate start.
   *
   * <p>Performs a BFS over the {@code follow} relation from {@code initial} to {@code accept},
   * ignoring character-class compatibility entirely. Ignoring character constraints can only
   * <em>overestimate</em> which positions are reachable in a given number of steps (never
   * underestimate), so the returned step count is always {@code <=} the true minimum -- an
   * admissible lower bound safe to multiply by {@code counterMin} for a "definitely impossible"
   * pruning check. Returns {@code 0} when the body is nullable (a repetition may consume zero
   * characters, so no positive bound holds) and {@code 1} as a safe (non-pruning) fallback if the
   * BFS cannot establish a tighter bound within the 64-position budget.
   */
  static int minCharsPerRep(boolean nullable, long initial, long accept, long[] follow) {
    if (nullable) {
      return 0;
    }
    if ((initial & accept) != 0L) {
      return 1;
    }
    long visited = initial;
    long frontier = initial;
    for (int steps = 1; steps <= 64; steps++) {
      frontier = expand(frontier, follow) & ~visited;
      if (frontier == 0L) {
        return 1;
      }
      if ((frontier & accept) != 0L) {
        return steps + 1;
      }
      visited |= frontier;
    }
    return 1;
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
