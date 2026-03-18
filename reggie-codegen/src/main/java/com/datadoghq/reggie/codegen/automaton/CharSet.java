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
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a set of characters as a collection of non-overlapping ranges. Used heavily in DFA
 * construction for transition partitioning.
 *
 * <p>Invariants: - Ranges are sorted by start character - Ranges are non-overlapping and
 * non-adjacent - Empty CharSet has empty ranges list
 */
public final class CharSet {

  private final List<Range> ranges;

  /** Represents an inclusive character range [start, end]. */
  public static final class Range {
    public final char start;
    public final char end;

    public Range(char start, char end) {
      if (start > end) {
        throw new IllegalArgumentException("Invalid range: " + (int) start + " > " + (int) end);
      }
      this.start = start;
      this.end = end;
    }

    public boolean contains(char ch) {
      return ch >= start && ch <= end;
    }

    public boolean overlaps(Range other) {
      return start <= other.end && other.start <= end;
    }

    public boolean isAdjacent(Range other) {
      return end + 1 == other.start || other.end + 1 == start;
    }

    public int size() {
      return end - start + 1;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Range)) return false;
      Range range = (Range) o;
      return start == range.start && end == range.end;
    }

    @Override
    public int hashCode() {
      return Objects.hash(start, end);
    }

    @Override
    public String toString() {
      if (start == end) {
        return charToString(start);
      }
      return charToString(start) + "-" + charToString(end);
    }

    private static String charToString(char ch) {
      if (ch >= 32 && ch < 127) {
        return "'" + ch + "'";
      }
      return "\\u" + String.format("%04x", (int) ch);
    }
  }

  // Private constructor - use factory methods
  private CharSet(List<Range> ranges) {
    this.ranges = ranges;
  }

  // Factory methods

  public static CharSet empty() {
    return new CharSet(Collections.emptyList());
  }

  public static CharSet of(char ch) {
    return new CharSet(List.of(new Range(ch, ch)));
  }

  public static CharSet range(char start, char end) {
    return new CharSet(List.of(new Range(start, end)));
  }

  public static CharSet fromRanges(List<Range> ranges) {
    if (ranges.isEmpty()) {
      return empty();
    }
    // Normalize: sort, merge overlapping/adjacent ranges
    List<Range> sorted = new ArrayList<>(ranges);
    sorted.sort((a, b) -> Character.compare(a.start, b.start));

    List<Range> merged = new ArrayList<>();
    Range current = sorted.get(0);

    for (int i = 1; i < sorted.size(); i++) {
      Range next = sorted.get(i);
      if (current.overlaps(next) || current.isAdjacent(next)) {
        // Merge
        current = new Range(current.start, (char) Math.max(current.end, next.end));
      } else {
        merged.add(current);
        current = next;
      }
    }
    merged.add(current);

    return new CharSet(List.copyOf(merged));
  }

  // Predefined character sets

  public static final CharSet DIGIT = range('0', '9');
  public static final CharSet LOWER = range('a', 'z');
  public static final CharSet UPPER = range('A', 'Z');
  public static final CharSet ALPHA = LOWER.union(UPPER);
  public static final CharSet ALNUM = ALPHA.union(DIGIT);
  public static final CharSet WORD = ALNUM.union(of('_'));

  public static final CharSet WHITESPACE =
      fromRanges(
          List.of(
              new Range(' ', ' '),
              new Range('\t', '\t'),
              new Range('\n', '\n'),
              new Range('\r', '\r'),
              new Range('\f', '\f')));

  public static final CharSet ANY = range((char) 0, (char) 0xFFFF);

  /** All characters except newline (\n). Used for . in non-dotall mode. */
  public static final CharSet ANY_EXCEPT_NEWLINE = ANY.minus(of('\n'));

  // Query methods

  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  public boolean contains(char ch) {
    // Binary search since ranges are sorted
    int left = 0, right = ranges.size() - 1;
    while (left <= right) {
      int mid = (left + right) >>> 1;
      Range range = ranges.get(mid);
      if (ch < range.start) {
        right = mid - 1;
      } else if (ch > range.end) {
        left = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  public boolean isSingleChar() {
    return ranges.size() == 1 && ranges.get(0).start == ranges.get(0).end;
  }

  public char getSingleChar() {
    if (!isSingleChar()) {
      throw new IllegalStateException("Not a single character: " + this);
    }
    return ranges.get(0).start;
  }

  public boolean isSimpleRange() {
    return ranges.size() == 1;
  }

  public char rangeStart() {
    if (!isSimpleRange()) {
      throw new IllegalStateException("Not a simple range: " + this);
    }
    return ranges.get(0).start;
  }

  public char rangeEnd() {
    if (!isSimpleRange()) {
      throw new IllegalStateException("Not a simple range: " + this);
    }
    return ranges.get(0).end;
  }

  public Range getSimpleRange() {
    if (!isSimpleRange()) {
      throw new IllegalStateException("Not a simple range: " + this);
    }
    return ranges.get(0);
  }

  public List<Range> getRanges() {
    return ranges;
  }

  /**
   * Check if this charset represents "any character" - either full Unicode range or all characters
   * except newline (which is what '.' produces in non-DOTALL mode). This is used for detecting
   * patterns like .* or .{n,m} in optimizations.
   */
  public boolean isAnyChar() {
    // Check for full unicode range [\u0000-\uFFFF]
    if (ranges.size() == 1) {
      Range r = ranges.get(0);
      return r.start == '\u0000' && r.end == '\uFFFF';
    }
    // Check for any-except-newline: [\u0000-\u0009] [\u000B-\uFFFF]
    if (ranges.size() == 2) {
      Range r1 = ranges.get(0);
      Range r2 = ranges.get(1);
      return r1.start == '\u0000'
          && r1.end == '\u0009'
          && r2.start == '\u000B'
          && r2.end == '\uFFFF';
    }
    return false;
  }

  // Set operations

  public CharSet union(CharSet other) {
    if (this.isEmpty()) return other;
    if (other.isEmpty()) return this;

    List<Range> combined = new ArrayList<>();
    combined.addAll(this.ranges);
    combined.addAll(other.ranges);

    return fromRanges(combined);
  }

  public CharSet intersection(CharSet other) {
    if (this.isEmpty() || other.isEmpty()) {
      return empty();
    }

    List<Range> result = new ArrayList<>();
    int i = 0, j = 0;

    while (i < this.ranges.size() && j < other.ranges.size()) {
      Range r1 = this.ranges.get(i);
      Range r2 = other.ranges.get(j);

      if (r1.overlaps(r2)) {
        char start = (char) Math.max(r1.start, r2.start);
        char end = (char) Math.min(r1.end, r2.end);
        result.add(new Range(start, end));
      }

      // Advance the range that ends first
      if (r1.end < r2.end) {
        i++;
      } else {
        j++;
      }
    }

    return new CharSet(result);
  }

  public CharSet complement() {
    if (this.isEmpty()) {
      return ANY;
    }

    List<Range> result = new ArrayList<>();
    char pos = 0;

    for (Range range : ranges) {
      if (pos < range.start) {
        result.add(new Range(pos, (char) (range.start - 1)));
      }
      pos = (char) (range.end + 1);
      if (pos == 0) {
        // Wrapped around - covered entire range
        break;
      }
    }

    if (pos != 0) {
      result.add(new Range(pos, (char) 0xFFFF));
    }

    return new CharSet(result);
  }

  public CharSet minus(CharSet other) {
    return this.intersection(other.complement());
  }

  public boolean intersects(CharSet other) {
    return !this.intersection(other).isEmpty();
  }

  public boolean isDisjoint(CharSet other) {
    return !intersects(other);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CharSet)) return false;
    CharSet charSet = (CharSet) o;
    return ranges.equals(charSet.ranges);
  }

  @Override
  public int hashCode() {
    return ranges.hashCode();
  }

  @Override
  public String toString() {
    if (isEmpty()) return "[]";
    if (ranges.size() == 1) {
      Range r = ranges.get(0);
      if (r.start == r.end) {
        return "[" + r.toString() + "]";
      }
    }
    return ranges.toString();
  }
}
