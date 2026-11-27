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

import java.util.Objects;

/** Stateless native matcher for the standalone extended grapheme cluster pattern. */
final class GraphemeMatcher extends ReggieMatcher {
  GraphemeMatcher(String pattern) {
    super(pattern);
    markNativeRichApi();
  }

  @Override
  public boolean matches(String input) {
    return input != null
        && !input.isEmpty()
        && GraphemeScanner.nextEnd(input, 0, input.length()) == input.length();
  }

  @Override
  public boolean find(String input) {
    return input != null && !input.isEmpty();
  }

  @Override
  public int findFrom(String input, int start) {
    int from = Math.max(0, start);
    return input != null && from < input.length() ? from : -1;
  }

  @Override
  public MatchResult match(String input) {
    return matches(input) ? result(input, 0, input.length()) : null;
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int from = findFrom(input, start);
    return from < 0
        ? null
        : result(input, from, GraphemeScanner.nextEnd(input, from, input.length()));
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    Objects.checkFromToIndex(start, end, input.length());
    return start < end && GraphemeScanner.nextEnd(input, start, end) == end;
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    return matchesBounded(input, start, end) ? result(input.toString(), start, end) : null;
  }

  @Override
  public boolean findBoundsFrom(String input, int start, int[] bounds) {
    int from = findFrom(input, start);
    if (from < 0) return false;
    int end = GraphemeScanner.nextEnd(input, from, input.length());
    bounds[0] = from;
    bounds[1] = end;
    return true;
  }

  @Override
  public boolean matchInto(String input, int[] starts, int[] ends) {
    checkArrays(input, starts, ends);
    if (!matches(input)) return false;
    store(starts, ends, 0, input.length());
    return true;
  }

  @Override
  public boolean findMatchInto(String input, int start, int[] starts, int[] ends) {
    checkArrays(input, starts, ends);
    int from = findFrom(input, start);
    if (from < 0) return false;
    store(starts, ends, from, GraphemeScanner.nextEnd(input, from, input.length()));
    return true;
  }

  private static void checkArrays(String input, int[] starts, int[] ends) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(starts, "groupStarts");
    Objects.requireNonNull(ends, "groupEnds");
  }

  private static void store(int[] starts, int[] ends, int from, int end) {
    if (starts.length == 0 || ends.length == 0) {
      throw new IndexOutOfBoundsException(
          "group arrays must have length at least 1 for this pattern");
    }
    starts[0] = from;
    ends[0] = end;
  }

  private static MatchResult result(String input, int start, int end) {
    return new MatchResultImpl(input, new int[] {start}, new int[] {end}, 0);
  }
}
