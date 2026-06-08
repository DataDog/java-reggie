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
 * Tests for combined modifier flags like (?m-i) — set multiline and unset case-insensitive
 * simultaneously.
 *
 * <p>This tests the most complex scoped-flag edge case: mixing add/remove flags like (?m-i).
 */
public class CombinedModifierTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void combinedSetAndUnset_mi() {
    // (?m-i): set multiline, unset case-insensitive
    // Inside outer (?i), then hit (?m-i): case-insensitive turns off
    // Pattern: (?i)a(?m-i)b
    // 'a' - case-insensitive, 'b' - case-sensitive (because (?m-i) turns off i)
    ReggieMatcher m = Reggie.compile("(?i)a(?m-i)b");
    assertNotNull(m.findMatch("ab"), "ab should match");
    assertNotNull(m.findMatch("Ab"), "Ab should match (a case-insensitive)");
    assertNull(m.findMatch("aB"), "aB should NOT match (b case-sensitive after (?m-i))");
  }

  @Test
  void parseMixedFlagSyntax_setAndUnset() {
    // Pattern with (?i) mid-alternation and (?m-i) to restore: key issue #32 scenario
    // (?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)
    // "aBd" should match: a(?i)[b-c](?m-i)d
    // 'a' matches 'a', (?i)[b-c] case-insensitively matches 'B', (?m-i)d matches 'd'
    ReggieMatcher m = Reggie.compile("(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)");

    MatchResult r = m.findMatch("aBd");
    assertNotNull(r, "aBd should match");
    assertEquals("aB", r.group(1), "group(1) should be 'aB'");
  }

  @Test
  void parseMixedFlagSyntax_caseInsensitiveCharClass() {
    // (?i)[b-c] should match uppercase B and C
    ReggieMatcher m = Reggie.compile("(?i)[b-c]");
    assertNotNull(m.findMatch("b"), "b should match");
    assertNotNull(m.findMatch("c"), "c should match");
    assertNotNull(m.findMatch("B"), "B should match (case-insensitive)");
    assertNotNull(m.findMatch("C"), "C should match (case-insensitive)");
    assertNull(m.findMatch("a"), "a should not match");
  }

  @Test
  void globalModifierSetUnset_imx() {
    // (?im-x) — set i and m, unset x
    // Just verify it parses without error
    ReggieMatcher m = Reggie.compile("(?im-x)abc");
    assertNotNull(m.findMatch("abc"), "abc should match");
    assertNotNull(m.findMatch("ABC"), "ABC should match (case-insensitive)");
  }

  @Test
  void jdkParity_complexScopedPattern() {
    // Verify Reggie matches JDK for the key issue #32 pattern on all test cases
    String pattern = "(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)";
    String[][] cases = {
      {"aBd", "aB"},
      {"xY", "xY"},
      {"Zambesi", "Z"},
    };

    for (String[] tc : cases) {
      String input = tc[0], expectedGroup1 = tc[1];

      Matcher jdkMatcher = Pattern.compile(pattern).matcher(input);
      assertTrue(jdkMatcher.find(), "JDK should match '" + input + "'");
      assertEquals(expectedGroup1, jdkMatcher.group(1), "JDK group(1) for '" + input + "'");

      MatchResult r = Reggie.compile(pattern).findMatch(input);
      assertNotNull(r, "Reggie should match '" + input + "'");
      assertEquals(
          expectedGroup1, r.group(1), "Reggie group(1) for '" + input + "' should match JDK");
    }
  }
}
