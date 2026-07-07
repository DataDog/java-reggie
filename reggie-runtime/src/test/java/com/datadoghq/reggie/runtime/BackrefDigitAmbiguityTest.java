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
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class BackrefDigitAmbiguityTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void backrefFollowedByGroupThenDigit() {
    // (cat(a(ract|tonic)|erpillar)) \1()2(3) on "cataract cataract23"
    String pat = "(cat(a(ract|tonic)|erpillar)) \\1()2(3)";
    String input = "cataract cataract23";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    MatchResult r = reg.match(input);
    assertNotNull(r, "Reggie should match");
    // Check each group
    for (int i = 1; i <= 5; i++) {
      System.out.printf("group(%d): JDK=%s Reggie=%s%n", i, jdk.group(i), r.group(i));
    }
    assertEquals(jdk.group(1), r.group(1));
    assertEquals(jdk.group(2), r.group(2));
    assertEquals(jdk.group(3), r.group(3));
    assertEquals(jdk.group(4), r.group(4));
    assertEquals(jdk.group(5), r.group(5));
  }

  @Test
  void backref1_followed_by_literal_2_with_1_group() {
    // JDK stop-early rule: (a)\12 with 1 group — collecting '2' would give 12 > groupCount(1),
    // so digit collection stops after '\1'. \1 is a backref to group 1; '2' is a literal.
    // Pattern: (a) + backref(1) + '2'. Matches "aa2".
    String pat = "(a)\\12";
    ReggieMatcher reg = Reggie.compileAllowingFallback(pat);
    assertNotNull(reg.match("aa2"), "(a)\\12 with 1 group = (a) + backref(1) + '2', matches 'aa2'");
    assertNull(reg.match("a\n"), "(a)\\12 must not match 'a\\n' under JDK semantics");
  }

  @Test
  void backref12_with_12_groups() {
    // Pattern with 12 groups: \12 must be backref 12, not \1 + '2'
    String pat = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(x)\\12";
    String input = "abcdefghijkxx";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertNotNull(reg.match(input), "Reggie should match \\12 as backref 12");
  }

  @Test
  void backref1_followed_by_9_with_1_group() {
    // JDK stop-early rule: (a)\19 with 1 group — collecting '9' would give 19 > groupCount(1),
    // so digit collection stops after '\1'. \1 is a backref to group 1; '9' is a literal.
    // Pattern: (a) + backref(1) + '9'. Matches "aa9".
    String pat = "(a)\\19";
    ReggieMatcher reg = Reggie.compileAllowingFallback(pat);
    assertNotNull(reg.match("aa9"), "(a)\\19 with 1 group = (a) + backref(1) + '9', matches 'aa9'");
    assertNull(reg.match("a\0019"), "(a)\\19 must not match 'a+SOH+9' under JDK semantics");
  }

  @Test
  void backref0groups_falls_to_octal() {
    // No groups: PCRE treats \1 as octal \001 (SOH). JDK does not match SOH (different semantics).
    // Reggie follows PCRE: \1 with 0 groups = octal 001 = U+0001 (SOH).
    String pat = "\\1";
    String input = "\001"; // SOH character
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertNotNull(
        reg.match(input), "Reggie should match \\1 as SOH (U+0001) per PCRE octal semantics");
  }

  @Test
  void pcreCanonicalBackrefCase() {
    // Primary PCRE test case from issue #34
    String pat = "(cat(a(ract|tonic)|erpillar)) \\1()2(3)";
    String input = "cataract cataract23";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertNotNull(reg.match(input), "Reggie should match canonical PCRE backref case");
  }

  /**
   * {@code (\w+)#\1(a?)(\2)} has trailing content ({@code (a?)(\2)}) after the group 1 / backref 1
   * pair, so PINNED_BACKREFERENCE now requires the group and its backreference to span the whole
   * pattern and rejects it. It routes to VARIABLE_CAPTURE_BACKREF instead, which is unaffected by
   * the B9 danger condition (backref to nullable group 2 inside capturing group 3) and matches
   * correctly without needing the JDK fallback.
   */
  @Test
  void pinnedIneligibleNullableBackrefInsideCapturingGroup_matchesNatively() {
    String pat = "(\\w+)#\\1(a?)(\\2)";
    String input = "hello#hello";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertFalse(
        reg instanceof JavaRegexFallbackMatcher,
        "(\\w+)#\\1(a?)(\\2) is not PINNED_BACKREFERENCE-eligible (trailing groups after the "
            + "backref), so it should match natively rather than falling back to JDK");
    MatchResult r = reg.match(input);
    assertNotNull(r, "Reggie should match");
    assertEquals(jdk.group(1), r.group(1));
    assertEquals(jdk.group(2), r.group(2));
    assertEquals(jdk.group(3), r.group(3));
  }
}
