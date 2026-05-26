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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for two bounded-quantifier bugs in the find()/findMatch() path:
 *
 * <ol>
 *   <li>{@code STATELESS_LOOP}: {@code generateFindMatchFromMethod} ignored the quantifier's upper
 *       bound when greedy-extending the match end, so {@code [0-9]{5}} matched all digits and
 *       {@code [0-9]{5,7}} matched up to the input length. The fix caps the matchEnd scan at {@code
 *       matchStart + maxReps}.
 *   <li>{@code DFA_SWITCH}: {@code MultiRangeOptimization} silently fell back to scanning only the
 *       first range when the multi-range layout was not the hand-written {@code [a-zA-Z]} or {@code
 *       [a-zA-Z0-9]} shape. For a pattern starting with {@code [-_]?[0-9]} the SWAR scan searched
 *       only for {@code '-'} and missed inputs that started with a digit or underscore — the
 *       symptom the user reported as "{@code [-_]?[0-9]{5,}} truncates digits". The fix gates
 *       {@code MultiRangeOptimization} to the two supported shapes.
 * </ol>
 */
public class BoundedQuantifierRegressionTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // --- STATELESS_LOOP upper-bound cap --------------------------------------------------

  @Test
  void exactlyN_doesNotOverConsume() {
    expectFindMatch("[0-9]{5}", "1234567890", 0, 5);
    expectFindMatch("[0-9]{5}", "abc1234567xyz", 3, 8);
    expectFindMatch("a{5}", "aaaaaaa", 0, 5);
  }

  @Test
  void boundedRange_capsAtUpperBound() {
    expectFindMatch("[0-9]{5,7}", "1234567890", 0, 7);
    expectFindMatch("[0-9]{5,7}", "12345", 0, 5);
    expectFindMatch("[0-9]{5,7}", "123456", 0, 6);
    expectFindMatch("a{5,7}", "aaaaaaa", 0, 7);
  }

  @Test
  void wideBoundedRange_capsAtUpperBound() {
    expectFindMatch("[0-9]{5,99}", "1".repeat(150), 0, 99);
    expectFindMatch("[0-9]{5,99}", "1".repeat(50), 0, 50);
  }

  @Test
  void unboundedRange_unchanged() {
    expectFindMatch("[0-9]{5,}", "1".repeat(150), 0, 150);
    expectFindMatch("[0-9]{5,}", "12345", 0, 5);
  }

  // --- DFA_SWITCH multi-range first-char filter ----------------------------------------

  @Test
  void multiRangePrefixed_findsAllStartingChars() {
    String regex = "[-_]?[0-9]{5,99}";
    expectFindMatch(regex, "12345", 0, 5);
    expectFindMatch(regex, "lib-1234567890.so", 3, 14);
    expectFindMatch(regex, "lib_1234567890.so", 3, 14);
    expectFindMatch(regex, "1234567890", 0, 10);
  }

  @Test
  void multiRangePrefixed_atVariousBounds() {
    // {5,1}-bound below the DFA_UNROLLED→DFA_SWITCH threshold still goes through DFA_UNROLLED
    // and worked pre-fix; the fix here is about not regressing it when SWAR is enabled.
    expectFindMatch("[-_]?[0-9]{5,10}", "1234567890", 0, 10);
    // Bounds that push the DFA past ~20 states route to DFA_SWITCH.
    expectFindMatch("[-_]?[0-9]{1,99}", "12345", 0, 5);
    expectFindMatch("[-_]?[0-9]{2,99}", "12345", 0, 5);
  }

  // --- Helpers --------------------------------------------------------------------------

  private static void expectFindMatch(String regex, String input, int start, int end) {
    Pattern jdk = Pattern.compile(regex);
    Matcher jm = jdk.matcher(input);
    if (!(jm.find() && jm.start() == start && jm.end() == end)) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK did not match /"
              + regex
              + "/ on '"
              + input
              + "' as ["
              + start
              + ","
              + end
              + ")");
    }
    ReggieMatcher m = Reggie.compile(regex);
    MatchResult mr = m.findMatch(input);
    String expected = "[" + start + "," + end + ")";
    String actual = mr == null ? "none" : "[" + mr.start() + "," + mr.end() + ")";
    assertEquals(
        expected,
        actual,
        () -> "Reggie find('" + input + "') for /" + regex + "/ should be " + expected);
  }
}
