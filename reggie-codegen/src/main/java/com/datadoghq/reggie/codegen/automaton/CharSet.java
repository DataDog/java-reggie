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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  // ASCII (0..127) membership bitmap, derived from {@link #ranges} at construction. asciiBits0
  // covers chars 0..63, asciiBits1 covers 64..127. This gives a branchless O(1) {@link #contains}
  // fast path for the ASCII case (the hot path in PikeVM/closure transition scans), avoiding the
  // ranges binary search + List/Range indirection. NOT part of equals/hashCode — those stay
  // range-based, so the structural cache (StructuralHash / NFA.contentHashCode) is unaffected.
  private final long asciiBits0;
  private final long asciiBits1;

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
    long b0 = 0L, b1 = 0L;
    for (Range r : ranges) {
      int hi = r.end > 127 ? 127 : r.end;
      for (int c = r.start; c <= hi; c++) {
        if (c < 64) b0 |= 1L << c;
        else b1 |= 1L << (c - 64);
      }
    }
    this.asciiBits0 = b0;
    this.asciiBits1 = b1;
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

  // Unicode general category CharSets, computed from BMP (U+0000..U+FFFF)

  /** \p{Lu} — uppercase letters */
  public static final CharSet UNICODE_Lu = buildUnicodeCategoryRanges(Character.UPPERCASE_LETTER);

  /** \p{Ll} — lowercase letters */
  public static final CharSet UNICODE_Ll = buildUnicodeCategoryRanges(Character.LOWERCASE_LETTER);

  /** \p{Lt} — titlecase letters */
  public static final CharSet UNICODE_Lt = buildUnicodeCategoryRanges(Character.TITLECASE_LETTER);

  /** \p{Lm} — modifier letters */
  public static final CharSet UNICODE_Lm = buildUnicodeCategoryRanges(Character.MODIFIER_LETTER);

  /** \p{Lo} — other letters */
  public static final CharSet UNICODE_Lo = buildUnicodeCategoryRanges(Character.OTHER_LETTER);

  /** \p{L} — any letter */
  public static final CharSet UNICODE_L =
      buildUnicodeCategoryRanges(
          Character.UPPERCASE_LETTER,
          Character.LOWERCASE_LETTER,
          Character.TITLECASE_LETTER,
          Character.MODIFIER_LETTER,
          Character.OTHER_LETTER);

  /** \p{Nd} — decimal digit numbers */
  public static final CharSet UNICODE_Nd =
      buildUnicodeCategoryRanges(Character.DECIMAL_DIGIT_NUMBER);

  /** \p{Nl} — letter numbers */
  public static final CharSet UNICODE_Nl = buildUnicodeCategoryRanges(Character.LETTER_NUMBER);

  /** \p{No} — other numbers */
  public static final CharSet UNICODE_No = buildUnicodeCategoryRanges(Character.OTHER_NUMBER);

  /** \p{N} — any number */
  public static final CharSet UNICODE_N =
      buildUnicodeCategoryRanges(
          Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER);

  /** \p{Zs} — space separators */
  public static final CharSet UNICODE_Zs = buildUnicodeCategoryRanges(Character.SPACE_SEPARATOR);

  /** \p{Zl} — line separators */
  public static final CharSet UNICODE_Zl = buildUnicodeCategoryRanges(Character.LINE_SEPARATOR);

  /** \p{Zp} — paragraph separators */
  public static final CharSet UNICODE_Zp =
      buildUnicodeCategoryRanges(Character.PARAGRAPH_SEPARATOR);

  /** \p{Z} — any separator */
  public static final CharSet UNICODE_Z =
      buildUnicodeCategoryRanges(
          Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR);

  /** \p{Sm} — math symbols */
  public static final CharSet UNICODE_Sm = buildUnicodeCategoryRanges(Character.MATH_SYMBOL);

  /** \p{Sc} — currency symbols */
  public static final CharSet UNICODE_Sc = buildUnicodeCategoryRanges(Character.CURRENCY_SYMBOL);

  /** \p{Sk} — modifier symbols */
  public static final CharSet UNICODE_Sk = buildUnicodeCategoryRanges(Character.MODIFIER_SYMBOL);

  /** \p{So} — other symbols */
  public static final CharSet UNICODE_So = buildUnicodeCategoryRanges(Character.OTHER_SYMBOL);

  /** \p{S} — any symbol */
  public static final CharSet UNICODE_S =
      buildUnicodeCategoryRanges(
          Character.MATH_SYMBOL,
          Character.CURRENCY_SYMBOL,
          Character.MODIFIER_SYMBOL,
          Character.OTHER_SYMBOL);

  /** \p{Pc} — connector punctuation */
  public static final CharSet UNICODE_Pc =
      buildUnicodeCategoryRanges(Character.CONNECTOR_PUNCTUATION);

  /** \p{Pd} — dash punctuation */
  public static final CharSet UNICODE_Pd = buildUnicodeCategoryRanges(Character.DASH_PUNCTUATION);

  /** \p{Ps} — start punctuation */
  public static final CharSet UNICODE_Ps = buildUnicodeCategoryRanges(Character.START_PUNCTUATION);

  /** \p{Pe} — end punctuation */
  public static final CharSet UNICODE_Pe = buildUnicodeCategoryRanges(Character.END_PUNCTUATION);

  /** \p{Pi} — initial quote punctuation */
  public static final CharSet UNICODE_Pi =
      buildUnicodeCategoryRanges(Character.INITIAL_QUOTE_PUNCTUATION);

  /** \p{Pf} — final quote punctuation */
  public static final CharSet UNICODE_Pf =
      buildUnicodeCategoryRanges(Character.FINAL_QUOTE_PUNCTUATION);

  /** \p{Po} — other punctuation */
  public static final CharSet UNICODE_Po = buildUnicodeCategoryRanges(Character.OTHER_PUNCTUATION);

  /** \p{P} — any punctuation */
  public static final CharSet UNICODE_P =
      buildUnicodeCategoryRanges(
          Character.CONNECTOR_PUNCTUATION,
          Character.DASH_PUNCTUATION,
          Character.START_PUNCTUATION,
          Character.END_PUNCTUATION,
          Character.INITIAL_QUOTE_PUNCTUATION,
          Character.FINAL_QUOTE_PUNCTUATION,
          Character.OTHER_PUNCTUATION);

  /** \p{Mn} — non-spacing marks */
  public static final CharSet UNICODE_Mn = buildUnicodeCategoryRanges(Character.NON_SPACING_MARK);

  /** \p{Mc} — combining spacing marks */
  public static final CharSet UNICODE_Mc =
      buildUnicodeCategoryRanges(Character.COMBINING_SPACING_MARK);

  /** \p{Me} — enclosing marks */
  public static final CharSet UNICODE_Me = buildUnicodeCategoryRanges(Character.ENCLOSING_MARK);

  /** \p{M} — any mark */
  public static final CharSet UNICODE_M =
      buildUnicodeCategoryRanges(
          Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK);

  /** \p{Cc} — control characters */
  public static final CharSet UNICODE_Cc = buildUnicodeCategoryRanges(Character.CONTROL);

  /** \p{Cf} — format characters */
  public static final CharSet UNICODE_Cf = buildUnicodeCategoryRanges(Character.FORMAT);

  /** \p{Cs} — surrogate characters */
  public static final CharSet UNICODE_Cs = buildUnicodeCategoryRanges(Character.SURROGATE);

  /** \p{Co} — private use characters */
  public static final CharSet UNICODE_Co = buildUnicodeCategoryRanges(Character.PRIVATE_USE);

  /** \p{Cn} — unassigned characters */
  public static final CharSet UNICODE_Cn = buildUnicodeCategoryRanges(Character.UNASSIGNED);

  /** \p{C} — any control/other character */
  public static final CharSet UNICODE_C =
      buildUnicodeCategoryRanges(
          Character.CONTROL,
          Character.FORMAT,
          Character.SURROGATE,
          Character.PRIVATE_USE,
          Character.UNASSIGNED);

  private static final Map<String, CharSet> UNICODE_CATEGORIES = buildCategoryMap();

  private static Map<String, CharSet> buildCategoryMap() {
    Map<String, CharSet> map = new HashMap<>();
    map.put("L", UNICODE_L);
    map.put("Lu", UNICODE_Lu);
    map.put("Ll", UNICODE_Ll);
    map.put("Lt", UNICODE_Lt);
    map.put("Lm", UNICODE_Lm);
    map.put("Lo", UNICODE_Lo);
    map.put("N", UNICODE_N);
    map.put("Nd", UNICODE_Nd);
    map.put("Nl", UNICODE_Nl);
    map.put("No", UNICODE_No);
    map.put("Z", UNICODE_Z);
    map.put("Zs", UNICODE_Zs);
    map.put("Zl", UNICODE_Zl);
    map.put("Zp", UNICODE_Zp);
    map.put("S", UNICODE_S);
    map.put("Sm", UNICODE_Sm);
    map.put("Sc", UNICODE_Sc);
    map.put("Sk", UNICODE_Sk);
    map.put("So", UNICODE_So);
    map.put("P", UNICODE_P);
    map.put("Pc", UNICODE_Pc);
    map.put("Pd", UNICODE_Pd);
    map.put("Ps", UNICODE_Ps);
    map.put("Pe", UNICODE_Pe);
    map.put("Pi", UNICODE_Pi);
    map.put("Pf", UNICODE_Pf);
    map.put("Po", UNICODE_Po);
    map.put("M", UNICODE_M);
    map.put("Mn", UNICODE_Mn);
    map.put("Mc", UNICODE_Mc);
    map.put("Me", UNICODE_Me);
    map.put("C", UNICODE_C);
    map.put("Cc", UNICODE_Cc);
    map.put("Cf", UNICODE_Cf);
    map.put("Cs", UNICODE_Cs);
    map.put("Co", UNICODE_Co);
    map.put("Cn", UNICODE_Cn);
    return Collections.unmodifiableMap(map);
  }

  /**
   * Returns the CharSet for a Unicode general category name (e.g. "L", "Lu", "N", "Nd").
   *
   * @param category the PCRE Unicode category name
   * @return the CharSet for that category, or {@code null} if unknown
   */
  public static CharSet ofUnicodeCategory(String category) {
    return UNICODE_CATEGORIES.get(category);
  }

  private static CharSet buildUnicodeCategoryRanges(int... javaCharTypes) {
    List<Range> result = new ArrayList<>();
    int start = -1;
    for (int c = 0; c <= 0xFFFF; c++) {
      int type = Character.getType(c);
      boolean matches = false;
      for (int t : javaCharTypes) {
        if (type == t) {
          matches = true;
          break;
        }
      }
      if (matches && start < 0) {
        start = c;
      } else if (!matches && start >= 0) {
        result.add(new Range((char) start, (char) (c - 1)));
        start = -1;
      }
    }
    if (start >= 0) {
      result.add(new Range((char) start, (char) 0xFFFF));
    }
    if (result.isEmpty()) {
      return empty();
    }
    return new CharSet(List.copyOf(result));
  }

  // Query methods

  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  public boolean contains(char ch) {
    // ASCII fast path: branchless bitmap test (the hot path in transition scans).
    if (ch < 128) {
      long w = ch < 64 ? asciiBits0 : asciiBits1;
      return ((w >>> (ch & 63)) & 1L) != 0L;
    }
    // Non-ASCII: binary search since ranges are sorted.
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
