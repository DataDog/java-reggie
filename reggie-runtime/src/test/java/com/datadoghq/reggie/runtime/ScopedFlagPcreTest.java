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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PCRE conformance tests for scoped inline flags (issue #32).
 *
 * <p>These test cases come directly from pcre-capturing-groups.txt lines 81-84 and 279-286, which
 * are the patterns that issue #32 targets.
 */
public class ScopedFlagPcreTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ---- helpers ----

  private void assertGroup1(String pattern, String input, String expectedGroup1) {
    ReggieMatcher m = Reggie.compile(pattern);
    MatchResult r = m.findMatch(input);
    assertNotNull(r, "Pattern '" + pattern + "' should match '" + input + "'");
    assertEquals(
        expectedGroup1,
        r.group(1),
        "Pattern '" + pattern + "' on '" + input + "': group(1) mismatch");
  }

  private String jdkGroup1(String pattern, String input) {
    Matcher m = Pattern.compile(pattern).matcher(input);
    return m.find() ? m.group(1) : null;
  }

  // ---- lines 81-84: (a(?i)bc|BB)x ----

  @Test
  void inlineIInAlternation_noOuterFlag_lowercase() {
    // (a(?i)bc|BB)x on "abcx" => group1 = "abc"
    String expected = jdkGroup1("(a(?i)bc|BB)x", "abcx");
    assertNotNull(expected, "JDK should match");
    assertGroup1("(a(?i)bc|BB)x", "abcx", expected);
  }

  @Test
  void inlineIInAlternation_withOuterFlag_mixedCase() {
    // (?i)(a(?i)bc|BB)x on "aBCx" => group1 = "aBC"
    String expected = jdkGroup1("(?i)(a(?i)bc|BB)x", "aBCx");
    assertNotNull(expected, "JDK should match");
    assertGroup1("(?i)(a(?i)bc|BB)x", "aBCx", expected);
  }

  @Test
  void inlineIInAlternation_noOuterFlag_altBB() {
    // (a(?i)bc|BB)x on "bbx" - inline (?i) makes rest of group case-insensitive (global in JDK)
    String expected = jdkGroup1("(a(?i)bc|BB)x", "bbx");
    ReggieMatcher m = Reggie.compile("(a(?i)bc|BB)x");
    MatchResult r = m.findMatch("bbx");
    if (expected != null) {
      assertNotNull(
          r,
          "Pattern '(a(?i)bc|BB)x' should match 'bbx' (JDK matches with group1='"
              + expected
              + "')");
      assertEquals(expected, r.group(1));
    } else {
      assertNull(r, "Pattern '(a(?i)bc|BB)x' should NOT match 'bbx' (JDK doesn't match)");
    }
  }

  @Test
  void inlineIInAlternation_withOuterFlag_BB() {
    // (?i)(a(?i)bc|BB)x on "BBx" => group1 = "BB"
    String expected = jdkGroup1("(?i)(a(?i)bc|BB)x", "BBx");
    assertNotNull(expected, "JDK should match");
    assertGroup1("(?i)(a(?i)bc|BB)x", "BBx", expected);
  }

  // ---- lines 279-280: (a(?i)b)c ----

  @Test
  void inlineIInGroup_lowercase() {
    // (a(?i)b)c on "abc" => group1 = "ab"
    assertGroup1("(a(?i)b)c", "abc", "ab");
  }

  @Test
  void inlineIInGroup_withOuterFlag_mixed() {
    // (?i)(a(?i)b)c on "aBc" => group1 = "aB"
    assertGroup1("(?i)(a(?i)b)c", "aBc", "aB");
  }

  // ---- lines 281-286: ^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) ----

  @Test
  void complexScopedFlags_ab_matches_ab() {
    // ^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) on "ab" => group1 = "ab"
    assertGroup1("^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)", "ab", "ab");
  }

  @Test
  void complexScopedFlags_outerI_aBd_matches_aB() {
    // (?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) on "aBd" => group1 = "aB"
    // With outer (?i): 'a' matches 'a', (?i)[b-c] matches 'B', (?m-i)d matches 'd' (case restored)
    assertGroup1("(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)", "aBd", "aB");
  }

  @Test
  void complexScopedFlags_xy_matches_xy() {
    // ^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) on "xy" => group1 = "xy"
    assertGroup1("^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)", "xy", "xy");
  }

  @Test
  void complexScopedFlags_outerI_xY_matches_xY() {
    // (?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) on "xY" => group1 = "xY"
    assertGroup1("(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)", "xY", "xY");
  }

  @Test
  void complexScopedFlags_zebra_matches_z() {
    // ^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) on "zebra" => group1 = "z"
    assertGroup1("^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)", "zebra", "z");
  }

  @Test
  void complexScopedFlags_outerI_Zambesi_matches_Z() {
    // (?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z) on "Zambesi" => group1 = "Z"
    assertGroup1("(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)", "Zambesi", "Z");
  }

  // ---- Additional edge cases for inline flags inside alternations ----

  @Test
  void inlineFlag_restoredAfterGroup() {
    // "(?i:a)B" - 'a' is case-insensitive, 'B' is case-sensitive
    ReggieMatcher m = Reggie.compile("(?i:a)B");
    assertNotNull(m.findMatch("aB"), "aB should match");
    assertNotNull(m.findMatch("AB"), "AB should match (A case-insensitive)");
    assertNull(m.findMatch("Ab"), "Ab should NOT match (b not B)");
  }

  @Test
  void inlineFlagTurnOff_insideGroup() {
    // (?i)a(?-i)b: 'a' case-insensitive, 'b' case-sensitive (flag turned off)
    ReggieMatcher m = Reggie.compile("(?i)a(?-i)b");
    assertNotNull(m.findMatch("ab"), "ab should match");
    assertNotNull(m.findMatch("Ab"), "Ab should match");
    assertNull(m.findMatch("aB"), "aB should NOT match (b is case-sensitive)");
  }
}
