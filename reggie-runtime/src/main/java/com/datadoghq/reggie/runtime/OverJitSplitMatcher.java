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

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Matcher for a pattern whose generated method exceeds the JIT size limit, built by {@code
 * RuntimeCompiler} when JDK fallback is allowed. Search calls (the find family and the API derived
 * from it: replace, split, findAll, cursor) delegate to {@code findHalf}; whole-input calls (the
 * matches/match family, bounded or not) delegate to {@code wholeHalf}. Every call is a single
 * delegation.
 *
 * <p>When {@code findHalf} is a {@link LaurikariDfaMatcher}, search calls delegate to {@code
 * wholeHalf} instead once {@link LaurikariDfaMatcher#saturated()} is true: from then on the
 * Laurikari half would delegate every call that needs a new DFA state to its PikeVM fallback, which
 * is slower than the JDK half. The call during which the cache fills still completes on the
 * Laurikari half, which handles that overflow itself.
 */
final class OverJitSplitMatcher extends ReggieMatcher {

  // package-private for tests: per-compile identity of the find half
  final ReggieMatcher findHalf;
  private final ReggieMatcher wholeHalf;

  /** {@code findHalf} when it is a {@link LaurikariDfaMatcher}, otherwise {@code null}. */
  private final LaurikariDfaMatcher laurikari;

  OverJitSplitMatcher(String pattern, ReggieMatcher findHalf, ReggieMatcher wholeHalf) {
    super(pattern);
    this.findHalf = findHalf;
    this.wholeHalf = wholeHalf;
    this.laurikari = findHalf instanceof LaurikariDfaMatcher l ? l : null;
    this.nameToIndex = findHalf.nameToIndex;
  }

  /** The half for search calls: {@code wholeHalf} once the Laurikari half is saturated. */
  private ReggieMatcher searchHalf() {
    LaurikariDfaMatcher l = laurikari;
    return l != null && l.saturated() ? wholeHalf : findHalf;
  }

  @Override
  public boolean isJdkFallback() {
    return false;
  }

  /**
   * Forwards to {@code findHalf} only: {@code wholeHalf} is shared by every matcher of the same
   * {@code RuntimeCompiler} cache entry, which sets its name map once when it is created.
   */
  @Override
  protected void setNameToIndex(Map<String, Integer> map) {
    super.setNameToIndex(map);
    findHalf.setNameToIndex(map);
  }

  // ---- whole-input family -> wholeHalf ----

  @Override
  public boolean matches(String input) {
    return wholeHalf.matches(input);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return wholeHalf.matchesBounded(input, start, end);
  }

  @Override
  public MatchResult match(String input) {
    return wholeHalf.match(input);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    return wholeHalf.matchBounded(input, start, end);
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    return wholeHalf.matchInto(input, groupStarts, groupEnds);
  }

  // ---- find family -> findHalf, or wholeHalf once saturated ----

  @Override
  public boolean find(String input) {
    return searchHalf().find(input);
  }

  @Override
  public int findFrom(String input, int start) {
    return searchHalf().findFrom(input, start);
  }

  @Override
  public boolean findBoundsFrom(String input, int start, int[] bounds) {
    return searchHalf().findBoundsFrom(input, start, bounds);
  }

  @Override
  public MatchResult findMatch(String input) {
    return searchHalf().findMatch(input);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    return searchHalf().findMatchFrom(input, start);
  }

  @Override
  public boolean findMatchInto(String input, int[] groupStarts, int[] groupEnds) {
    return searchHalf().findMatchInto(input, groupStarts, groupEnds);
  }

  @Override
  public boolean findMatchInto(String input, int start, int[] groupStarts, int[] groupEnds) {
    return searchHalf().findMatchInto(input, start, groupStarts, groupEnds);
  }

  @Override
  public String replaceFirst(String input, String replacement) {
    return searchHalf().replaceFirst(input, replacement);
  }

  @Override
  public String replaceAll(String input, String replacement) {
    return searchHalf().replaceAll(input, replacement);
  }

  @Override
  public String replaceAll(String input, Function<MatchResult, String> replacer) {
    return searchHalf().replaceAll(input, replacer);
  }

  @Override
  public String[] split(String input) {
    return searchHalf().split(input);
  }

  @Override
  public String[] split(String input, int limit) {
    return searchHalf().split(input, limit);
  }

  @Override
  public List<MatchResult> findAll(String input) {
    return searchHalf().findAll(input);
  }

  @Override
  public MatchCursor cursor(String input) {
    return searchHalf().cursor(input);
  }
}
