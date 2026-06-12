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
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for CapturePolicy.NAMED_ONLY group(int) JDK-compatible indexing. Named groups
 * must retain their original 1-based index regardless of how many unnamed groups precede them.
 */
public class CapturePolicyNamedOnlyTest {

  private static final ReggieOptions NAMED_ONLY = ReggieOptions.builder().namedOnly().build();

  @Test
  public void namedGroupRetainsJdkIndex() {
    // (a)=1, (b)=2, (?<name>c)=3 in JDK; NAMED_ONLY must keep named group at slot 3
    ReggieMatcher m = Reggie.compile("(a)(b)(?<name>c)", NAMED_ONLY);
    MatchResult r = m.match("abc");
    assertEquals("c", r.group(3), "named group must be at JDK-compatible index 3");
    assertNull(r.group(1), "stripped unnamed group 1 must be null");
    assertNull(r.group(2), "stripped unnamed group 2 must be null");
    assertEquals("c", r.group("name"));
    assertEquals(3, r.groupCount());
  }

  @Test
  public void namedGroupAtIndex1WithNoUnnamedPrefix() {
    // (?<x>a) is group 1 with no unnamed groups before it
    ReggieMatcher m = Reggie.compile("(?<x>a)(?<y>b)", NAMED_ONLY);
    MatchResult r = m.match("ab");
    assertEquals("a", r.group(1));
    assertEquals("b", r.group(2));
    assertEquals("a", r.group("x"));
    assertEquals("b", r.group("y"));
  }

  @Test
  public void multipleUnnamedBeforeNamed() {
    // (a)(b)(c)(?<d>d) — named group must be at index 4
    ReggieMatcher m = Reggie.compile("(a)(b)(c)(?<d>d)", NAMED_ONLY);
    MatchResult r = m.match("abcd");
    assertEquals("d", r.group(4));
    assertNull(r.group(1));
    assertNull(r.group(2));
    assertNull(r.group(3));
    assertEquals("d", r.group("d"));
    assertEquals(4, r.groupCount());
  }

  @Test
  public void findMatchPreservesJdkIndex() {
    // Embedded match: same index invariant must hold for findMatch()
    ReggieMatcher m = Reggie.compile("(a)(b)(?<name>c)", NAMED_ONLY);
    MatchResult r = m.findMatch("xxabcyy");
    assertEquals("c", r.group(3));
    assertNull(r.group(1));
    assertNull(r.group(2));
    assertEquals("c", r.group("name"));
  }

  @Test
  public void mixedNamedAndUnnamedGroups() {
    // (a)(?<x>b)(c)(?<y>d) — named at 2 and 4, unnamed at 1 and 3
    ReggieMatcher m = Reggie.compile("(a)(?<x>b)(c)(?<y>d)", NAMED_ONLY);
    MatchResult r = m.match("abcd");
    assertNull(r.group(1));
    assertEquals("b", r.group(2));
    assertNull(r.group(3));
    assertEquals("d", r.group(4));
    assertEquals("b", r.group("x"));
    assertEquals("d", r.group("y"));
  }
}
