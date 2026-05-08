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
 * Hybrid matcher that uses DFA for fast matching and NFA for group extraction. DFA provides O(n)
 * matching without backtracking. NFA is only used when capturing groups are needed, and only on the
 * matched substring (bounded).
 */
public class HybridMatcher extends ReggieMatcher {
  private final ReggieMatcher dfaMatcher;
  private final ReggieMatcher nfaMatcher;

  public HybridMatcher(String pattern, ReggieMatcher dfaMatcher, ReggieMatcher nfaMatcher) {
    super(pattern);
    this.dfaMatcher = dfaMatcher;
    this.nfaMatcher = nfaMatcher;
  }

  @Override
  public boolean matches(String input) {
    return dfaMatcher.matches(input);
  }

  @Override
  public boolean find(String input) {
    return dfaMatcher.find(input);
  }

  @Override
  public int findFrom(String input, int start) {
    return dfaMatcher.findFrom(input, start);
  }

  @Override
  public MatchResult match(String input) {
    if (!dfaMatcher.matches(input)) {
      return null;
    }
    return enrich(nfaMatcher.match(input));
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return dfaMatcher.matchesBounded(input, start, end);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    if (!dfaMatcher.matchesBounded(input, start, end)) {
      return null;
    }
    return enrich(nfaMatcher.matchBounded(input, start, end));
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    MatchResult dfaResult = dfaMatcher.findMatchFrom(input, start);
    if (dfaResult == null) {
      return null;
    }

    String matched = input.substring(dfaResult.start(), dfaResult.end());
    MatchResult nfaResult = nfaMatcher.match(matched);
    if (nfaResult == null) {
      return dfaResult;
    }

    return new OffsetMatchResult(input, enrich(nfaResult), dfaResult.start());
  }

  private MatchResult enrich(MatchResult r) {
    if (!nameToIndex.isEmpty() && r instanceof MatchResultImpl) {
      return ((MatchResultImpl) r).withNames(nameToIndex);
    }
    return r;
  }

  /**
   * MatchResult wrapper that adjusts positions by an offset. Public to allow reuse by generated
   * code.
   */
  public static class OffsetMatchResult implements MatchResult {
    private final String input;
    private final MatchResult delegate;
    private final int offset;

    OffsetMatchResult(String input, MatchResult delegate, int offset) {
      this.input = input;
      this.delegate = delegate;
      this.offset = offset;
    }

    @Override
    public int start() {
      return delegate.start() + offset;
    }

    @Override
    public int end() {
      return delegate.end() + offset;
    }

    @Override
    public String group() {
      return delegate.group();
    }

    @Override
    public int start(int group) {
      int s = delegate.start(group);
      return s >= 0 ? s + offset : -1;
    }

    @Override
    public int end(int group) {
      int e = delegate.end(group);
      return e >= 0 ? e + offset : -1;
    }

    @Override
    public String group(int group) {
      int s = delegate.start(group);
      int e = delegate.end(group);
      if (s < 0 || e < 0) {
        return null;
      }
      return input.substring(s + offset, e + offset);
    }

    @Override
    public int groupCount() {
      return delegate.groupCount();
    }

    @Override
    public String group(String name) {
      int s = delegate.start(name);
      int e = delegate.end(name);
      if (s < 0 || e < 0) return null;
      return input.substring(s + offset, e + offset);
    }

    @Override
    public int start(String name) {
      int s = delegate.start(name);
      return s >= 0 ? s + offset : -1;
    }

    @Override
    public int end(String name) {
      int e = delegate.end(name);
      return e >= 0 ? e + offset : -1;
    }

    @Override
    public boolean hasNamedGroups() {
      return delegate.hasNamedGroups();
    }
  }
}
