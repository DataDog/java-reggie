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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Task 2.1a (doc/2026-07-10-tdfa-capture-engine-impl-plan.md): confirms that patterns
 * routed to {@link HybridMatcher} via {@code usePosixLastMatch} never end up served by the
 * Laurikari TDFA matcher instead.
 *
 * <p>{@code (^)(a)+b} is the reachable case: {@code routeBitState} rewrites its {@code
 * PIKEVM_CAPTURE} result to {@code BITSTATE_CAPTURE} (the anchor-only capturing group {@code (^)}
 * qualifies it), but {@code usePosixLastMatch} stays {@code true} (a capturing group, {@code (a)},
 * sits inside a repeating quantifier). {@code RuntimeCompiler#compileInternal} returns from its
 * {@code BITSTATE_CAPTURE} early-return branch before ever reaching the {@code shouldUseHybrid}
 * check, so this pattern is actually compiled via {@code BitStateEntry}, not {@code HybridMatcher}
 * -- disjointness holds by control flow, not by this pattern being routed elsewhere. {@code
 * LaurikariEligibility.isEligible} independently rejects {@code usePosixLastMatch} patterns, so no
 * Laurikari matcher gets attached to it either.
 */
public class LaurikariHybridDisjointnessTest {

  @Test
  void anchorOnlyGroupWithPosixQuantifiedGroup_routesToBitStateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(^)(a)+b"),
        "(^)(a)+b must route to BITSTATE_CAPTURE, not HybridMatcher");
  }

  @Test
  void anchorOnlyGroupWithPosixQuantifiedGroup_compilesToBitStateMatcherNotHybrid() {
    ReggieMatcher m = RuntimeCompiler.compile("(^)(a)+b");
    assertTrue(
        m instanceof BitStateMatcher,
        "(^)(a)+b must compile to BitStateMatcher, not " + m.getClass().getSimpleName());
  }

  @Test
  void anchorOnlyGroupWithPosixQuantifiedGroup_matchesJdk() {
    String pattern = "(^)(a)+b";
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = RuntimeCompiler.compile(pattern);

    assertEquals(jdk.matcher("aab").matches(), reggie.matches("aab"));
    assertEquals(jdk.matcher("ab").matches(), reggie.matches("ab"));
    assertEquals(jdk.matcher("b").matches(), reggie.matches("b"));
  }
}
