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

public class RecursiveDescentBackrefRegressionTest {

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

  private static void assertGroupsAgree(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatches = jm.matches();
    MatchResult rm = reggie.match(input);
    assertEquals(
        jdkMatches, rm != null, "match() boolean for /" + pattern + "/ on \"" + input + "\"");
    if (jdkMatches) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(
            List.of(jm.start(g), jm.end(g)),
            List.of(rm.start(g), rm.end(g)),
            "group " + g + " span for /" + pattern + "/ on \"" + input + "\"");
      }
    }
  }

  // ---- Failing tests ----

  @Test
  void greedyZeroRepCapture() throws Exception {
    assertRoute("(c+){0,}\\1+", PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT);
    assertAgrees("(c+){0,}\\1+", "cc");
  }

  @Test
  void altFallthrough1() throws Exception {
    assertRoute("(1*)()\\1{2}|[1]*.", PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT);
    assertAgrees("(1*)()\\1{2}|[1]*.", "c");
  }

  @Test
  void altFallthrough2() throws Exception {
    assertAgrees("(1*)()\\1{2}|[^1].", "-1");
  }

  @Test
  void optionalBackrefAlt1() throws Exception {
    assertRoute("([^1]_{0}){3,3}(\\1|c?[c])?", PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT);
    assertGroupsAgree("([^1]_{0}){3,3}(\\1|c?[c])?", "0\n\nc");
  }

  @Test
  void optionalBackrefAlt2() throws Exception {
    assertGroupsAgree("a|([^1]_{0}){3,3}(\\1|c?[c])?", "0\n\nc");
  }

  @Test
  void backrefInZeroRepGroup() throws Exception {
    assertRoute("(b|])?.(c{2}]{0}\\1{1}){0}", PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT);
    assertAgrees("(b|])?.(c{2}]{0}\\1{1}){0}", "b");
  }

  // ---- MANDATORY over-match control tests (must stay as no-match) ----

  @Test
  void control_unsetBackrefFails() throws Exception {
    assertAgrees("(a)?\\1", "");
    assertAgrees("(a)?\\1", "a");
  }

  @Test
  void control_unsetBackrefWithSuffix() throws Exception {
    assertAgrees("(x)?\\1y", "y");
  }

  @Test
  void control_setBackrefMustMatch() throws Exception {
    assertAgrees("(a)\\1", "aa");
    assertAgrees("(a)\\1", "ab");
  }
}
