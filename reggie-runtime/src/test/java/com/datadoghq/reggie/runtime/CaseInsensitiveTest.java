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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for case-insensitive flag (?i) */
public class CaseInsensitiveTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testGlobalCaseInsensitive() {
    // (?i) at start affects entire pattern
    ReggieMatcher m = Reggie.compile("(?i)abc");
    assertNotNull(m.match("abc"), "lowercase should match");
    assertNotNull(m.match("ABC"), "uppercase should match");
    assertNotNull(m.match("AbC"), "mixed case should match");
  }

  @Test
  void testScopedCaseInsensitive() {
    // (?i:...) only affects the group
    ReggieMatcher m = Reggie.compile("a(?i:bc)d");
    assertNotNull(m.match("abcd"), "lowercase should match");
    assertNotNull(m.match("aBCd"), "scoped uppercase should match");
    assertNull(m.match("Abcd"), "A outside scope should not match");
    assertNull(m.match("abcD"), "D outside scope should not match");
  }

  @Test
  void testCaseInsensitiveWithCapturingGroup() {
    // (?i) with capturing group
    ReggieMatcher m = Reggie.compile("(?i)(abc)");
    MatchResult r = m.match("ABC");
    assertNotNull(r, "should match");
    assertEquals("ABC", r.group(1), "group should capture uppercase");
  }

  @Test
  void testCaseInsensitiveInsideGroup() {
    // (?i) inside a capturing group
    ReggieMatcher m = Reggie.compile("(a(?i)bc)x");

    // 'a' must be lowercase, 'bc' can be any case
    assertNotNull(m.match("abcx"), "lowercase should match");
    assertNotNull(m.match("aBCx"), "BC uppercase should match");
    assertNull(m.match("Abcx"), "A uppercase should not match");

    MatchResult r = m.match("aBCx");
    assertNotNull(r);
    assertEquals("aBC", r.group(1));
  }

  @Test
  void testCaseInsensitiveAlternation() {
    // Test from PCRE: (a(?i)bc|BB)x
    ReggieMatcher m = Reggie.compile("(a(?i)bc|BB)x");

    MatchResult r1 = m.match("abcx");
    assertNotNull(r1);
    assertEquals("abc", r1.group(1));

    MatchResult r2 = m.match("aBCx");
    assertNotNull(r2);
    assertEquals("aBC", r2.group(1));

    MatchResult r3 = m.match("BBx");
    assertNotNull(r3);
    assertEquals("BB", r3.group(1));
  }

  @Test
  void testNoCaseInsensitive() {
    // Without flag, should be case sensitive
    ReggieMatcher m = Reggie.compile("abc");
    assertNotNull(m.match("abc"), "exact match should work");
    assertNull(m.match("ABC"), "different case should not match");
  }
}
