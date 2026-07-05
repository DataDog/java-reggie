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

import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.Map;

/**
 * BitState NFA-with-capture engine, routed to by {@code
 * PatternAnalyzer.MatchingStrategy#BITSTATE_CAPTURE} (see {@code
 * PatternAnalyzer.isBitStateEligible}). Selected for the same leftmost-greedy, native
 * group-extraction semantics as {@link PikeVMMatcher}, for patterns free of backreferences,
 * lookaround, atomic groups, possessive quantifiers, and the anchored-nullable-repeated-body shape.
 *
 * <p><b>P1 scope note:</b> the bespoke bitset/job-stack backtracking interpreter described in
 * {@code doc/2026-07-03-bitstate-capture-engine-design.md} §3–§5 (EXPAND/RESTORE job stack,
 * per-search visited bitmap, mutable {@code caps} + undo log) is <b>not yet implemented</b> in this
 * class. Landing the routing skeleton (enum value, eligibility predicate, call-site wiring, this
 * class) ahead of the performance-critical algorithm lets every consumer of {@code
 * MatchingStrategy.BITSTATE_CAPTURE} be exercised and tested against a correctness-safe engine
 * today; the O(n·m)-with-smaller-constant traversal is a follow-up perf task. Until then, every
 * method below delegates to a lazily-constructed {@link PikeVMMatcher} built from the same {@link
 * NFA} — identical results, no speed-up yet. This mirrors the design doc's own fallback mechanism
 * (§8: delegate whole-call to {@code PikeVMMatcher} when the interpreter can't handle a call
 * efficiently), applied unconditionally for P1.
 */
final class BitStateMatcher extends ReggieMatcher {

  private final NFA nfa;
  private PikeVMMatcher delegate;

  BitStateMatcher(NFA nfa, String pattern) {
    super(pattern);
    this.nfa = nfa;
  }

  private PikeVMMatcher delegate() {
    PikeVMMatcher d = delegate;
    if (d == null) {
      d = new PikeVMMatcher(nfa, pattern);
      if (!nameToIndex.isEmpty()) {
        d.setNameToIndex(nameToIndex);
      }
      delegate = d;
    }
    return d;
  }

  @Override
  protected void setNameToIndex(Map<String, Integer> map) {
    super.setNameToIndex(map);
    if (delegate != null) {
      delegate.setNameToIndex(map);
    }
  }

  @Override
  boolean embedsNameMap() {
    return true;
  }

  @Override
  public boolean matches(String input) {
    return delegate().matches(input);
  }

  @Override
  public boolean find(String input) {
    return delegate().find(input);
  }

  @Override
  public int findFrom(String input, int start) {
    return delegate().findFrom(input, start);
  }

  @Override
  public MatchResult match(String input) {
    return delegate().match(input);
  }

  @Override
  public MatchResult findMatch(String input) {
    return delegate().findMatch(input);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    return delegate().findMatchFrom(input, start);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return delegate().matchesBounded(input, start, end);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    return delegate().matchBounded(input, start, end);
  }
}
