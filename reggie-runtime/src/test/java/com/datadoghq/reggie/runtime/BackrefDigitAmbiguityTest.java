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
    // PCRE all-or-nothing: (a)\12 with 1 group → 12 > 1 → octal \012 = '\n'.
    // JDK uses descending-prefix (\1 + literal '2'), but Reggie follows PCRE semantics.
    String pat = "(a)\\12";
    ReggieMatcher reg = Reggie.compile(pat);
    assertNotNull(reg.match("a\n"), "PCRE: (a)\\12 = (a)\\n, matches 'a' + newline");
    assertNull(reg.match("aa2"), "PCRE: (a)\\12 must not match 'aa2' (\\12 is octal, not \\1+2)");
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
    // PCRE all-or-nothing: (a)\19 with 1 group → 19 > 1 → octal; '9' is not octal,
    // so only '1' is consumed as octal \001 (SOH), leaving '9' as a literal.
    // Pattern becomes (a)\x019. JDK uses descending-prefix, Reggie follows PCRE.
    String pat = "(a)\\19";
    ReggieMatcher reg = Reggie.compile(pat);
    assertNotNull(reg.match("a\0019"), "PCRE: (a)\\19 = (a)\\x019, matches 'a' + SOH + '9'");
    assertNull(reg.match("aa9"), "PCRE: (a)\\19 must not match 'aa9' (\\19 is not \\1+9)");
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
}
