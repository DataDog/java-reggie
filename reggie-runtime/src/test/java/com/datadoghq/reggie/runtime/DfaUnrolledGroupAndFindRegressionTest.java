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

public class DfaUnrolledGroupAndFindRegressionTest {

  private static void assertRoute(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    assertEquals(
        expected,
        StrategyCorrectnessMetaTest.routeOf(pattern),
        "routing changed for /" + pattern + "/ — fix would not be exercised");
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

  // ---- Sub-task 1A tests ----

  @Test
  void a1_trailingEmptyGroup() throws Exception {
    assertRoute(".+()", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree(".+()", "0");
  }

  @Test
  void a1_emptyAltGroupDash() throws Exception {
    assertRoute("-(|)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("-(|)", "-");
  }

  @Test
  void a1_emptyAltGroupB() throws Exception {
    assertRoute("b(|)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("b(|)", "b");
  }

  @Test
  void a1_endAnchorGroup() throws Exception {
    assertRoute("1+(\\z)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("1+(\\z)", "1");
  }

  @Test
  void a1_optionalThenDot() throws Exception {
    // Routing check: pattern uses the DFA_UNROLLED_WITH_GROUPS strategy.
    assertRoute("-{1}(a?.*).x", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    // Zero-width group at accepting state: when (a?.*) matches empty and the accept state holds
    // BOTH ENTER and EXIT for the group, group 1 should be [1,1) not the stale [0,1) start.
    // Use a simpler input where the group IS zero-width at the only accepting state.
    assertGroupsAgree("-{1}(a?.*)", "-");
  }

  @Test
  void a1_control_normalGroup() throws Exception {
    // Patterns that route to DFA_UNROLLED_WITH_GROUPS — verify existing group tracking unaffected
    assertRoute("(fo|foo)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("(fo|foo)", "fo");
    assertGroupsAgree("(fo|foo)", "foo");
  }

  // ---- Sub-task 1B tests ----

  @Test
  void a2_groupFirstAlt() throws Exception {
    assertRoute("(b)|b", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("(b)|b", "b");
  }

  @Test
  void a2_groupSecondAlt() throws Exception {
    assertRoute("b|(b)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("b|(b)", "b");
  }

  @Test
  void a2_dotOrGroup() throws Exception {
    assertRoute(".|([^c])", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree(".|([^c])", "_");
  }

  @Test
  void a2_singleGroupStartLost() throws Exception {
    assertRoute("(c*.)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree("(c*.)", "c");
  }

  @Test
  void a2_control_groupMustMatch() throws Exception {
    assertGroupsAgree("(a)|b", "a");
    assertGroupsAgree("(a)|b", "b");
  }

  // ---- Sub-task 1C tests ----

  @Test
  void c_findMatchesWhatMatchesFinds1() throws Exception {
    assertRoute("(.1[1])+", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertAgrees("(.1[1])+", "011");
  }

  @Test
  void c_findMatchesWhatMatchesFinds2() throws Exception {
    assertRoute("(.c)+", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertAgrees("(.c)+", "0c");
  }

  @Test
  void c_findMatchesWhatMatchesFinds3() throws Exception {
    assertAgrees("(.c)+", "-c");
  }

  @Test
  void c_findLeftmost() throws Exception {
    assertAgrees("(.c)+", "-cc");
  }

  @Test
  void c_emptyGroupPlusUnderscore() throws Exception {
    assertRoute("[_]()+", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertAgrees("[_]()+", "_");
  }

  @Test
  void c_emptyGroupPlusZero() throws Exception {
    assertAgrees("[0]()+", "0");
  }

  @Test
  void c_emptyGroupPlusRange() throws Exception {
    assertAgrees("[0-c]()+", "b");
  }

  @Test
  void c_control_leftmostUnaffected() {
    assertAgrees("(ab)+", "xababy");
  }

  // ---- Fix 4: consuming group ENTER+EXIT in accepting state ----

  private static void assertFindGroupsAgree(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    Matcher jm = jdk.matcher(input);
    boolean jdkFind = jm.find();
    MatchResult rm = reggie.findMatch(input);
    assertEquals(
        jdkFind, rm != null, "findMatch() boolean for /" + pattern + "/ on \"" + input + "\"");
    if (jdkFind) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(
            List.of(jm.start(g), jm.end(g)),
            List.of(rm.start(g), rm.end(g)),
            "group " + g + " span for findMatch() /" + pattern + "/ on \"" + input + "\"");
      }
    }
  }

  @Test
  void d_consumingGroupInAcceptState_match() throws Exception {
    assertRoute(".+(0)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertGroupsAgree(".+(0)", "_0");
  }

  @Test
  void d_consumingGroupInAcceptState_findMatch() throws Exception {
    assertRoute(".+(0)", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS);
    assertFindGroupsAgree(".+(0)", "_0");
  }
}
