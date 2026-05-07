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
import org.junit.jupiter.api.Test;

/**
 * Tests for backreferences with variable quantifiers. These patterns require backtracking support.
 */
public class TestBackrefQuantifiers {

  @Test
  void testBackrefWithExactQuantifier() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a)\\1{8,}");

    System.out.println("[DEBUG] Pattern: (a)\\1{8,}");
    assertTrue(m.matches("aaaaaaaaa"), "Should match 'aaaaaaaaa' (9 a's = 1 + 8)");
    assertTrue(m.matches("aaaaaaaaaa"), "Should match 'aaaaaaaaaa' (10 a's = 1 + 9)");
    assertFalse(m.matches("aaaaaaaa"), "Should NOT match 'aaaaaaaa' (8 a's = 1 + 7)");
  }

  @Test
  void testBackrefWithPlusQuantifier() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1+b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1+b");
    assertTrue(m.matches("aab"), "Should match 'aab'");
    assertTrue(m.matches("aaaab"), "Should match 'aaaab'");
    assertTrue(m.matches("b"), "Should match 'b'");

    // Also test findMatch (used by integration tests)
    assertNotNull(m.findMatch("aab"), "findMatch should match 'aab'");
    assertNotNull(m.findMatch("aaaab"), "findMatch should match 'aaaab'");
    assertNotNull(m.findMatch("b"), "findMatch should match 'b'");
  }

  @Test
  void testBackrefWithExactCount() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1{2}b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1{2}b");
    assertTrue(m.matches("aaab"), "Should match 'aaab'");
    assertTrue(m.matches("b"), "Should match 'b'");
    assertFalse(m.matches("aab"), "Should NOT match 'aab' (only 1 repeat, need 2)");
  }

  @Test
  void testBackrefWithRangeQuantifier() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a)\\1{2,3}(.)");

    System.out.println("[DEBUG] Pattern: ^(a)\\1{2,3}(.)");
    MatchResult r1 = m.match("aaab");
    assertNotNull(r1, "Should match 'aaab'");
    assertEquals("a", r1.group(1));
    assertEquals("b", r1.group(2));

    MatchResult r2 = m.match("aaaab");
    assertNotNull(r2, "Should match 'aaaab'");
    assertEquals("a", r2.group(1));
    assertEquals("b", r2.group(2));

    MatchResult r3 = m.match("aaaaab");
    assertNotNull(r3, "Should match 'aaaaab'");
    assertEquals("a", r3.group(1));
    assertEquals("a", r3.group(2));
  }

  @Test
  void testSelfReferencialBackref() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");

    System.out.println("[DEBUG] Pattern: ^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");

    MatchResult r1 = m.match("aaaa");
    assertNotNull(r1, "Should match 'aaaa'");

    MatchResult r2 = m.match("aaaaa");
    assertNotNull(r2, "Should match 'aaaaa'");

    MatchResult r3 = m.match("aaaaaa");
    assertNotNull(r3, "Should match 'aaaaaa'");
  }

  // Regression tests for non-self-referencing quantifier patterns.
  // These assert captured group content to catch silent groups[] overwrites of completed groups.

  @Test
  void testNonSelfRefQuantifiedGroup_capturePreserved() {
    // (a){3} - group 1 should reflect the last iteration: 'a'
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a){3}(b)$");
    MatchResult r = m.match("aaab");
    assertNotNull(r, "Should match 'aaab'");
    assertEquals("a", r.group(1), "group(1) should be 'a' (last iteration)");
    assertEquals("b", r.group(2), "group(2) must not be overwritten by quantifier loop");
  }

  @Test
  void testNonSelfRefQuantifiedGroup_followingGroupNotOverwritten() {
    // Ensures the partial-open write in the quantifier loop does NOT apply to non-self-ref groups,
    // so a completed earlier group is not silently reset to -1.
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(x)(a){2}(y)$");
    MatchResult r = m.match("xaay");
    assertNotNull(r, "Should match 'xaay'");
    assertEquals("x", r.group(1), "group(1) must not be overwritten");
    assertEquals("a", r.group(2), "group(2) should be last iteration of (a){2}");
    assertEquals("y", r.group(3), "group(3) must not be overwritten");
  }
}
