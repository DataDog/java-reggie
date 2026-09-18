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
 * matching without backtracking. NFA is only used when capturing groups are needed: it searches
 * from the DFA's match start over the full input (context-true anchors), falling back to
 * re-matching the DFA span as a standalone string when the search finds nothing.
 */
public class HybridMatcher extends ReggieMatcher {
  private final ReggieMatcher dfaMatcher;
  private final ReggieMatcher nfaMatcher;

  /**
   * True when the DFA half was built with leftmost-first thread pruning (lazy-quantifier originals;
   * see SubsetConstructor#setLeftmostFirst). The pruned DFA encodes Perl's first-preference END for
   * search — correct for find()/findFrom()/findMatch — but boolean matches()/matchesBounded() are
   * path-existence questions and pruning only removes paths, so those delegate to the NFA half,
   * which answers them exactly as the standalone engine did.
   */
  private final boolean lazyFind;

  public HybridMatcher(String pattern, ReggieMatcher dfaMatcher, ReggieMatcher nfaMatcher) {
    this(pattern, dfaMatcher, nfaMatcher, false);
  }

  public HybridMatcher(
      String pattern, ReggieMatcher dfaMatcher, ReggieMatcher nfaMatcher, boolean lazyFind) {
    super(pattern);
    this.dfaMatcher = dfaMatcher;
    this.nfaMatcher = nfaMatcher;
    this.lazyFind = lazyFind;
  }

  @Override
  public boolean matches(String input) {
    // Pruned-DFA false-negative guard: see lazyFind. The unpruned NFA half answers
    // path-existence exactly like the engine the hybrid replaced.
    return lazyFind ? nfaMatcher.matches(input) : dfaMatcher.matches(input);
  }

  @Override
  public boolean find(String input) {
    return dfaMatcher.find(input);
  }

  @Override
  public int findFrom(String input, int start) {
    return dfaMatcher.findFrom(input, Math.max(0, start));
  }

  @Override
  public MatchResult match(String input) {
    if (lazyFind) {
      // The pruned DFA false-negatives on path-existence questions (see lazyFind); the NFA
      // half both decides and extracts, exactly as the standalone engine did.
      return enrich(nfaMatcher.match(input));
    }
    if (!dfaMatcher.matches(input)) {
      return null;
    }
    return enrich(nfaMatcher.match(input));
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    if (lazyFind) {
      return nfaMatcher.matchInto(input, groupStarts, groupEnds);
    }
    if (!dfaMatcher.matches(input)) {
      return false;
    }
    return nfaMatcher.matchInto(input, groupStarts, groupEnds);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return lazyFind
        ? nfaMatcher.matchesBounded(input, start, end)
        : dfaMatcher.matchesBounded(input, start, end);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    if (lazyFind) {
      return enrich(nfaMatcher.matchBounded(input, start, end));
    }
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

    // Re-match in context by searching the NFA half from the DFA's leftmost start over the
    // full input. Searching re-evaluates anchors against the real input: re-matching the DFA
    // span as a standalone string fired $/\Z/\z at the span boundary where they do not hold
    // in-context (c+(b$|.*b): b$ fires at the substring end, reporting [10,12) where the
    // in-context match via .*b is [10,14)), and re-derives the pruned-DFA end preference
    // instead of trusting it. The DFA start is a sound search floor: it is leftmost, and
    // anchor-diluted DFAs never enter the hybrid.
    // All halves search correctly: PikeVM/BitState by construction, the generated OPTIMIZED_NFA
    // half since its findFrom literal-scan jump is gated on verified match prefixes
    // (NfaFindFromRegressionTest guards the give-back family). If a search still misses, the
    // span re-match below extracts captures from the DFA span as a fallback.
    MatchResult nfaResult = nfaMatcher.findMatchFrom(input, dfaResult.start());
    if (nfaResult != null) {
      return enrich(nfaResult);
    }

    String matched = input.substring(dfaResult.start(), dfaResult.end());
    MatchResult spanMatch = nfaMatcher.match(matched);
    if (spanMatch == null) {
      return dfaResult;
    }

    return new OffsetMatchResult(input, enrich(spanMatch), dfaResult.start());
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
