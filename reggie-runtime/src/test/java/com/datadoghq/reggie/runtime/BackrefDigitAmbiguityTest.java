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
    // PCRE semantics: (a)\12 with 1 group.
    // The greedy collector reads '1' and '2' giving refNum=12; 12 > 1 group with first digit 1-7
    // triggers octal fallback. parseOctalEscapeInternal reads \012 = newline (U+000A).
    // Only "a\n" matches; the old JDK/backref interpretation "aa2" must NOT match.
    String pat = "(a)\\12";
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertNotNull(reg.match("a\n"), "Reggie should match (a)\\12 on 'a<newline>' per PCRE octal");
    assertNull(reg.match("aa2"), "Old JDK backref interpretation 'aa2' must not match");
    assertNull(
        reg.match("a2"), "'a2' must not match (digit '2' was consumed into octal candidate)");
  }

  @Test
  void backref12_with_12_groups() {
    // Pattern with 12 groups: \12 must be backref 12, not octal \012.
    // refNum=12 <= 12 groups -> BackreferenceNode(12), no octal fallback.
    String pat = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(x)\\12";
    String input = "abcdefghijkxx";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertNotNull(reg.match(input), "Reggie should match \\12 as backref 12");
    // Boundary: one character short must not match (the backref-12 repetition is required).
    assertNull(
        reg.match("abcdefghijklx"),
        "One-character-short input must not match (backref-12 repetition missing)");
  }

  @Test
  void backref1_followed_by_9_with_1_group() {
    // PCRE semantics: (a)\19 with 1 group.
    // The greedy collector reads '1' and '9' giving refNum=19; 19 > 1 group with first digit 1-7
    // triggers octal fallback. parseOctalEscapeInternal stops at '9' (> '7'), so it reads only
    // '1' -> U+0001 (SOH). The trailing '9' is re-read by the outer loop as a literal.
    // The only matching input is exactly 3 chars: 'a', U+0001 (SOH), '9'.
    String pat = "(a)\\19";
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    // Three-char input: 'a', SOH (U+0001), '9'
    assertNotNull(
        reg.match("a\u00019"),
        "Reggie must match (a)\\19 on the 3-char string 'a', U+0001 (SOH), '9'");
    assertNull(reg.match("9"), "'9' alone must not match (missing group-1 'a' and SOH)");
    assertNull(
        reg.match("aa9"), "Old backref interpretation 'aa9' must not match under PCRE semantics");
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

  @Test
  void multiDigitOctalFallback_100_with_1_group() {
    // (abc)\100 with 1 group: refNum=100 > 1 group, first digit 1-7 -> octal fallback.
    // \100 (octal) = 64 decimal = '@' (U+0040). Group-1 matches "abc" first.
    // The broken descending-prefix loop would have matched "abcabc00" (backref-1 "abc" + "00");
    // under the fix that must be null.
    String pat = "(abc)\\100";
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    assertNotNull(reg.match("abc@"), "Reggie should match (abc)\\100 on 'abc@' (octal \\100='@')");
    assertNull(
        reg.match("abcabc00"),
        "Old broken descending-prefix result 'abcabc00' must not match under the fix");
  }

  @Test
  void noRegression_backslash8_with_0_groups() {
    // \8 with 0 groups: the fix does not touch the \8/\9 branch.
    // Compile must not throw (match-time evaluation, not parse-time group-existence check).
    // Match-time behavior for \8/\9 with 0 groups is out-of-scope and not asserted here.
    assertDoesNotThrow(
        () -> Reggie.compile("\\8", WITH_FALLBACK), "Compiling \\8 with 0 groups must not throw");
  }
}
