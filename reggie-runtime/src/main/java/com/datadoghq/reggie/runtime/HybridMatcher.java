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
    // Use DFA to verify match
    if (!dfaMatcher.matches(input)) {
      return null;
    }
    // Use NFA for group extraction
    return nfaMatcher.match(input);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return dfaMatcher.matchesBounded(input, start, end);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    // Use DFA to verify bounded match
    MatchResult dfaResult = dfaMatcher.matchBounded(input, start, end);
    if (dfaResult == null) {
      return null;
    }
    // Use NFA for group extraction on bounded region
    return nfaMatcher.matchBounded(input, start, end);
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    // Use DFA to find match boundaries quickly
    MatchResult dfaResult = dfaMatcher.findMatchFrom(input, start);
    if (dfaResult == null) {
      return null;
    }

    // Extract substring and run NFA for group positions
    String matched = input.substring(dfaResult.start(), dfaResult.end());
    MatchResult nfaResult = nfaMatcher.match(matched);
    if (nfaResult == null) {
      // Shouldn't happen - DFA found a match but NFA didn't
      return dfaResult;
    }

    // Adjust NFA group positions by matchStart offset
    return new OffsetMatchResult(input, nfaResult, dfaResult.start());
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
  }
}
