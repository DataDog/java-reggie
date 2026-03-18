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

/** Tests for conditional patterns (?(n)...) and branch reset groups (?|...). */
public class ConditionalBranchResetTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testBranchResetSimple() {
    // (?|(a)|(b)) - both alternatives use group 1
    ReggieMatcher m = Reggie.compile("(?|(a)|(b))");

    MatchResult r1 = m.match("a");
    assertNotNull(r1, "Should match 'a'");
    assertEquals("a", r1.group(1), "Group 1 should be 'a'");

    MatchResult r2 = m.match("b");
    assertNotNull(r2, "Should match 'b'");
    assertEquals("b", r2.group(1), "Group 1 should be 'b'");
  }

  @Test
  void testBranchResetWithCapturingGroups() {
    // (?|(abc)|(xyz)) - both alternatives capture to group 1
    ReggieMatcher m = Reggie.compile("(?|(abc)|(xyz))");

    MatchResult r1 = m.match("abc");
    assertNotNull(r1, "Should match 'abc'");
    assertEquals("abc", r1.group(1), "Group 1 should be 'abc'");

    MatchResult r2 = m.match("xyz");
    assertNotNull(r2, "Should match 'xyz'");
    assertEquals("xyz", r2.group(1), "Group 1 should be 'xyz'");
  }

  @Test
  void testConditionalSimple() {
    // (a)?(?(1)b|c) - if 'a' matched, expect 'b', else expect 'c'
    ReggieMatcher m = Reggie.compile("(a)?(?(1)b|c)");

    MatchResult r1 = m.match("ab");
    assertNotNull(r1, "Should match 'ab'");
    assertEquals("a", r1.group(1), "Group 1 should be 'a'");

    MatchResult r2 = m.match("c");
    assertNotNull(r2, "Should match 'c'");
    // Group 1 didn't participate, so it should be null/empty
  }
}
