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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Leftmost-start soundness of the LAZY_DFA boolean find (LazyDFACache.findFrom with the generated
 * plain step).
 *
 * <p>findFrom previously restarted a dead attempt at {@code pos + 1} — the position after the death
 * — which skips any viable match start lying INSIDE the dead attempt's consumed span {@code
 * [matchStart, pos)}. On {@code "xax" + "ba"*75} the attempt from 0 consumes {@code x, a} and dies
 * at 2, but start 2 (the second {@code x}) begins a full match; the old scan resumed at 3 and
 * reported 3, while the leftmost match starts at 2. The restart is now {@code matchStart + 1},
 * matching the NFA fallback's semantics.
 *
 * <p>The union/self-anchoring closures (PikeVM findStep/rejectStep, BitState rejectStep) re-inject
 * the start state at every position, so their union covers every start in the dead span and they
 * keep the O(n) restart at {@code pos + 1} via {@link LazyDFACache#findFromUnion}.
 */
public class LazyDfaLeftmostTest {

  private static final String LARGE_NFA_PATTERN = "x(?:a+b+|b+a+){75}";

  private static void assertLeftmost(String input, int expectedStart) throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.LAZY_DFA,
        StrategyCorrectnessMetaTest.routeOf(LARGE_NFA_PATTERN),
        "routing changed — the findFrom leftmost path would not be exercised");

    Matcher jdk = Pattern.compile(LARGE_NFA_PATTERN).matcher(input);
    assertTrue(jdk.find(), "JDK must find a match");
    assertEquals(
        expectedStart,
        jdk.start(),
        "test construction error: expected leftmost start does not match JDK");

    ReggieMatcher matcher = Reggie.compile(LARGE_NFA_PATTERN);
    assertEquals(expectedStart, matcher.findFrom(input, 0), "leftmost start via findFrom");

    // End-to-end parity: the public find() goes through the same DFA scan.
    assertTrue(matcher.find(input), "find() parity");
    int[] starts = new int[1];
    int[] ends = new int[1];
    assertTrue(matcher.findMatchInto(input, starts, ends), "findMatchInto parity");
    assertEquals(expectedStart, starts[0], "find() span start must be leftmost");
    assertEquals(jdk.end(), ends[0], "find() span end");
  }

  @Test
  void viableStartInsideDeadAttemptSpan() throws Exception {
    // Attempt from 0 consumes "xa" then dies at pos 2; start 2 (the 'x') begins a full
    // 75-unit match. The old pos+1 restart skipped it and reported 3.
    StringBuilder sb = new StringBuilder("xax");
    for (int i = 0; i < 75; i++) {
      sb.append("ba");
    }
    assertLeftmost(sb.toString(), 2);
  }

  @Test
  void deepSkipInsideDeadSpan() throws Exception {
    // The dead attempt consumes several "ba" units before dying, so the viable start lies
    // far inside the consumed span — restarts must still walk back one start at a time.
    StringBuilder sb = new StringBuilder("xbaba");
    sb.append("x");
    for (int i = 0; i < 75; i++) {
      sb.append("ba");
    }
    // Leftmost: the 'x' at position 5.
    Matcher jdk = Pattern.compile(LARGE_NFA_PATTERN).matcher(sb.toString());
    assertTrue(jdk.find());
    assertEquals(jdk.start(), Reggie.compile(LARGE_NFA_PATTERN).findFrom(sb.toString(), 0));
  }

  @Test
  void controlsMatchImmediatelyOrAfterDeadFirstChar() throws Exception {
    // Match at 0 — no restart at all.
    StringBuilder sb = new StringBuilder("x");
    for (int i = 0; i < 75; i++) {
      sb.append("ab");
    }
    assertLeftmost(sb.toString(), 0);

    // Attempt from 0 dies at pos 1 (second 'x' cannot continue any unit); restart lands
    // exactly on the viable start 1 — correct under both restart rules.
    sb = new StringBuilder("xx");
    for (int i = 0; i < 75; i++) {
      sb.append("ab");
    }
    assertLeftmost(sb.toString(), 1);
  }

  @Test
  void noMatchParity() {
    ReggieMatcher matcher = Reggie.compile(LARGE_NFA_PATTERN);
    for (String input : new String[] {"", "x", "y", "zzz", "xaba", "bababa"}) {
      boolean jdk = Pattern.compile(LARGE_NFA_PATTERN).matcher(input).find();
      assertEquals(jdk, matcher.find(input), "no-match parity on [" + input + "]");
    }
  }
}
