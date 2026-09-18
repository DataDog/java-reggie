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
 * sits inside a repeating quantifier). Start-anchored patterns are ADMITTED to hybrid (the earlier
 * exclusion was a measured performance gate on DFA_SWITCH methods that exceeded HotSpot's
 * HugeMethodLimit and ran interpreted — fixed by size-aware bucketing in
 * DFASwitchBytecodeGenerator), so this pattern compiles to {@code HybridMatcher} whose NFA half
 * mirrors the standalone engine (BitStateMatcher via {@code newHybridNfaHalf}). Disjointness holds
 * via {@code LaurikariEligibility}: it independently rejects {@code usePosixLastMatch} patterns, so
 * the hybrid's BitState half gets no Laurikari matcher attached.
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
  void anchorOnlyGroupWithPosixQuantifiedGroup_compilesToHybridWithBitStateHalf() {
    // Start-anchored BITSTATE patterns are admitted to hybrid (DFA serves matches/find, the
    // BitState NFA half serves captures — see RuntimeCompiler's hybrid admission block and
    // newHybridNfaHalf). Disjointness is preserved by LaurikariEligibility rejecting
    // usePosixLastMatch, not by routing this pattern away from hybrid.
    ReggieMatcher m = RuntimeCompiler.compile("(^)(a)+b");
    assertTrue(
        EngineRouting.unwrap(m) instanceof HybridMatcher,
        "(^)(a)+b must compile to HybridMatcher, not "
            + EngineRouting.engineClass(m).getSimpleName());
  }

  @Test
  void anchorOnlyGroupWithPosixQuantifiedGroup_matchesJdk() {
    String pattern = "(^)(a)+b";
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = RuntimeCompiler.compile(pattern);

    assertEquals(jdk.matcher("aab").matches(), reggie.matches("aab"));
    assertEquals(jdk.matcher("ab").matches(), reggie.matches("ab"));
    assertEquals(jdk.matcher("b").matches(), reggie.matches("b"));

    // POSIX last-match spans through the hybrid's BitState NFA half: (a)+ keeps the LAST
    // iteration's span. JDK on "aab": (^)=[0,0), (a)=[1,2).
    MatchResult r = reggie.match("aab");
    java.util.regex.Matcher jm = jdk.matcher("aab");
    assertTrue(jm.matches());
    assertTrue(r != null, "(^)(a)+b must match 'aab' with captures");
    assertEquals(jm.start(1), r.start(1));
    assertEquals(jm.end(1), r.end(1));
    assertEquals(jm.start(2), r.start(2));
    assertEquals(jm.end(2), r.end(2));
  }
}
