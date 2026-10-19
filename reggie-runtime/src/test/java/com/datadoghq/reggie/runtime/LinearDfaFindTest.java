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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * find() on the DFA strategies must be linear in the input length. Each input below has a long run
 * that every match attempt consumes before it fails, so running the DFA once per start position
 * takes quadratic time.
 *
 * <p>The {@code linearFindTest} Gradle task runs this class again with {@code
 * reggie.dfa.linearFindWorkFactor=0}, so every find hands over to {@link DFALinearFind} after its
 * first failed run.
 */
class LinearDfaFindTest {

  private static final int N = 200_000;

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "a*a*a*a*b;a;!b;DFA_UNROLLED;1",
        "(?:a|ab)+c;a;!c;DFA_UNROLLED;-1",
        "a*a*a*b$;a;!b;DFA_UNROLLED;1",
        "(?<!x)(?:a|ab)+c;a;!c;DFA_UNROLLED_WITH_ASSERTIONS;-1",
        "(?:a|ab)+c(?=\\d);a;!c;DFA_UNROLLED_WITH_ASSERTIONS;-1",
        "[ab]*a[ab]{5}c;a;!c;DFA_SWITCH;-1",
        "[a-z0-9]*(?:[a-z][0-9]){40}!;a0;a!;DFA_SWITCH;-1",
        "[a-z0-9]*(?:[a-z][0-9]){150}!;a0;a!;DFA_TABLE;-1",
        "(?=[ab]*c)a;a;!c;DFA_UNROLLED_WITH_ASSERTIONS;-1",
        "a(?=[ab]*c);a;!c;DFA_UNROLLED_WITH_ASSERTIONS;-1",
        "(?=[ab]*c)a?;a;!c;DFA_UNROLLED_WITH_ASSERTIONS;1",
        "(?![ab]*!)a;a;!;DFA_UNROLLED_WITH_ASSERTIONS;-1",
        "(?=[ab]*c)[ab]*a[ab]{5};a;!;DFA_SWITCH_WITH_ASSERTIONS;-1",
        "(?=[ab]*c)(?:[a-z][0-9]){40};a;!;DFA_SWITCH_WITH_ASSERTIONS;-1",
        "(?=[ab]*c)(?:[a-z][0-9]){60};a;!;DFA_SWITCH_WITH_ASSERTIONS;-1",
      })
  void findIsLinear(String regex, String unit, String tail, String strategy, int fromEnd) {
    RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(regex);
    assertEquals(strategy, info.routing.split(" ")[0], "routing of /" + regex + "/");
    ReggieMatcher m = info.matcher;
    String input = unit.repeat(N / unit.length()) + tail;
    int expected = fromEnd < 0 ? -1 : input.length() - fromEnd;
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          assertEquals(expected, m.findFrom(input, 0), "findFrom");
          MatchResult r = m.findMatch(input);
          assertEquals(expected, r == null ? -1 : r.start(), "findMatch");
          assertEquals(expected, m.findFrom(input, 1), "findFrom(1)");
        },
        "/" + regex + "/ on " + input.length() + " chars");
  }

  private static final String[] DIFF_PATTERNS = {
    "a*a*a*a*b",
    "(?:a|ab)+c",
    "a*a*a*b$",
    "(?<!x)(?:a|ab)+c",
    "(?:a|ab)+c(?=\\d)",
    "(?<=x)a*a*b",
    "[ab]*a[ab]{5}c",
    "(?m)^a*a*b",
    "a*a*b(?!a)",
    "(?<=x)[ab]*a[ab]{5}c",
    "[ab]*a[ab]{5}c(?=1)",
    "(?<=x)([ab]*)a[ab]{5}c",
    "([ab]*)a[ab]{5}c(?=1)",
    "(?m)^[ab]*a[ab]{5}c",
    "[ab]*a[ab]{5}(?:c|$)",
    "[a-z0-9]*(?:[a-z][0-9]){40}!",
    "[ab]*a[ab]{3}c+|(?:[a-z][0-9]){150}",
    "x*",
    "(?<=b)a*",
    "(?=[ab]*c)a",
    "(?![ab]*c)a",
    "a(?=[ab]*c)",
    "(?=[ab]*c)a?",
    "(?=[ab]*c)[ab]*a[ab]{5}",
    "(?![ab]*!)[ab]*a[ab]{5}",
  };

  static String[] diffPatterns() {
    return DIFF_PATTERNS;
  }

  @ParameterizedTest
  @MethodSource("diffPatterns")
  void findAgreesWithJdkOnLongRuns(String regex) {
    assertAgreesWithJdk(regex);
  }

  /**
   * Lookahead gates in a DFA_SWITCH matcher whose state switch is split into helper methods, at the
   * start state and at the accepting state.
   */
  @ParameterizedTest
  @CsvSource({
    "(?=[ab]*c)(?:[a-z][0-9]){60}",
    "(?:[a-z][0-9]){60}(?=[ab]*c)",
    "(?:[a-z][0-9]){60}(?![ab]*!)",
  })
  void splitSwitchGatesAgreeWithJdk(String regex) {
    assertEquals(
        "DFA_SWITCH_WITH_ASSERTIONS",
        RuntimeCompiler.describeRouting(regex).routing.split(" ")[0],
        "routing of /" + regex + "/");
    String pairs = "a1".repeat(60);
    String[] inputs = {
      pairs + "abc",
      pairs + "ab!",
      "ab" + pairs + "c",
      "aab" + pairs + "ab!" + pairs + "bc",
      "abc" + pairs,
      "x" + pairs,
      pairs.substring(2) + "ab!a1abc",
    };
    ReggieMatcher m = RuntimeCompiler.compile(regex);
    Pattern jdk = Pattern.compile(regex);
    for (String input : inputs) {
      for (int from : new int[] {0, 1, 2}) {
        Matcher j = jdk.matcher(input);
        boolean found = j.find(from);
        String where = "/" + regex + "/ from " + from + " on " + input;
        assertEquals(found ? j.start() : -1, m.findFrom(input, from), "findFrom " + where);
        MatchResult r = m.findMatchFrom(input, from);
        assertEquals(found ? j.end() : -1, r == null ? -1 : r.end(), "findMatchFrom " + where);
      }
    }
  }

  private static void assertAgreesWithJdk(String regex) {
    ReggieMatcher m = RuntimeCompiler.compile(regex);
    Pattern jdk = Pattern.compile(regex);
    Random rnd = new Random(42);
    String alphabet = "aaaaaaaaaaaaaabbcx1!\n";
    for (int iter = 0; iter < 300; iter++) {
      StringBuilder sb = new StringBuilder();
      int len = 40 + rnd.nextInt(160);
      while (sb.length() < len) {
        if (rnd.nextInt(8) == 0) {
          sb.append("a".repeat(5 + rnd.nextInt(30)));
        } else {
          sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
      }
      String input = sb.toString();
      for (int from : new int[] {0, 1, len / 3, len / 2}) {
        Matcher j = jdk.matcher(input);
        boolean found = j.find(from);
        String where = "/" + regex + "/ from " + from + " on \"" + input + "\"";
        assertEquals(found ? j.start() : -1, m.findFrom(input, from), "findFrom " + where);
        MatchResult r = m.findMatchFrom(input, from);
        assertEquals(found, r != null, "findMatchFrom " + where);
        if (found) {
          assertEquals(j.start(), r.start(), "findMatchFrom start " + where);
          assertEquals(j.end(), r.end(), "findMatchFrom end " + where);
        }
      }
    }
  }
}
