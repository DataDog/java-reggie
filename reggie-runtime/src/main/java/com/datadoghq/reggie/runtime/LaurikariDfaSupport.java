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

/**
 * Task 1.6b seam (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): {@link LaurikariDfaMatcher}
 * and {@link LaurikariEligibility} are package-private, so a JDK-oracle fuzz harness living in
 * another module (e.g. {@code reggie-integration-tests}, which only depends on this module's main
 * sourceset) cannot construct or gate one directly. This class is the minimal public crossing
 * point: it runs the same eligibility gate {@link BitStateMatcher}/{@code RuntimeCompiler} would
 * use in Phase 2, then upcasts the result to {@link ReggieMatcher} so the concrete engine stays
 * hidden from callers.
 */
public final class LaurikariDfaSupport {

  private LaurikariDfaSupport() {}

  /**
   * @return a {@link LaurikariDfaMatcher} over {@code nfa} if {@link
   *     LaurikariEligibility#isEligible} accepts it, otherwise {@code null}.
   */
  public static ReggieMatcher tryCreate(
      NFA nfa, String pattern, int groupCount, boolean usePosixLastMatch) {
    if (!LaurikariEligibility.isEligible(nfa, usePosixLastMatch)) return null;
    return new LaurikariDfaMatcher(nfa, pattern, groupCount);
  }
}
