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
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class GreedyBacktrackFindRegressionTest {

  private static void assertRoute(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    assertEquals(
        expected,
        StrategyCorrectnessMetaTest.routeOf(pattern),
        "routing changed for /" + pattern + "/ — fix would not be exercised");
  }

  private static void assertAgrees(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    boolean jdkMatches = jdk.matcher(input).matches();
    assertEquals(
        jdkMatches,
        reggie.matches(input),
        "matches() mismatch for /" + pattern + "/ on \"" + input + "\"");
    Matcher jm = jdk.matcher(input);
    boolean jdkFind = jm.find();
    assertEquals(
        jdkFind, reggie.find(input), "find() mismatch for /" + pattern + "/ on \"" + input + "\"");
    MatchResult r = reggie.findMatch(input);
    if (jdkFind) {
      assertEquals(
          List.of(jm.start(), jm.end()),
          r == null ? null : List.of(r.start(), r.end()),
          "findMatch span mismatch for /" + pattern + "/ on \"" + input + "\"");
    } else {
      assertEquals(null, r, "findMatch should be null for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  @Test
  void findWhenPriorCharsEqualDelimiter() throws Exception {
    // (.+)_ routes to BITSTATE_CAPTURE (substituted for PIKEVM_CAPTURE): GREEDY_BACKTRACK's
    // indexOf scan overshoots on inputs ending with '\n' (e.g. "-_\n") because '.' excludes '\n'
    // but the scan stops on it.
    assertRoute("(.+)_", PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE);
    assertAgrees("(.+)_", "__");
  }

  @Test
  void findControl_simpleCase() throws Exception {
    assertAgrees("(.+)_", "-_");
    assertAgrees("(.+)_", "a_");
    assertAgrees("(.+)_", "ab_");
  }

  @Test
  void findControl_noMatch() throws Exception {
    assertAgrees("(.+)_", "");
    assertAgrees("(.+)_", "a");
    assertAgrees("(.+)_", "__a");
  }

  @Test
  void findControl_multipleUnderscores() throws Exception {
    assertAgrees("(.+)_", "___");
    assertAgrees("(.+)_", "a__b_");
  }
}
