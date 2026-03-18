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

/**
 * Test conditional patterns: (?(1)yes|no) Tests the implementation of conditional matching based on
 * group capture.
 */
class ConditionalPatternTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testBasicConditional() {
    // Pattern: (a)?(?(1)b|c)
    // If group 1 matched (captured 'a'), then match 'b', else match 'c'
    ReggieMatcher m = Reggie.compile("(a)?(?(1)b|c)");

    assertTrue(m.matches("ab"), "Should match 'ab' (group 1 captured, take then branch)");
    assertTrue(m.matches("c"), "Should match 'c' (group 1 not captured, take else branch)");
    assertFalse(m.matches("ac"), "Should not match 'ac'");
    assertFalse(m.matches("b"), "Should not match 'b'");
  }

  @Test
  void testConditionalWithoutElse() {
    // Pattern: (a)?(?(1)b)
    // If group 1 matched, then match 'b', else match empty
    ReggieMatcher m = Reggie.compile("(a)?(?(1)b)");

    assertTrue(m.matches("ab"), "Should match 'ab' (group 1 captured, take then branch)");
    assertTrue(m.matches(""), "Should match '' (group 1 not captured, else is empty)");
    assertFalse(m.matches("a"), "Should not match 'a' (group 1 captured but no 'b')");
    assertFalse(m.matches("b"), "Should not match 'b' (group 1 not captured but has 'b')");
  }

  @Test
  void testConditionalWithParens() {
    // Classic PCRE test: (\()?blah(?(1)\))
    // Match 'blah' optionally wrapped in parentheses
    ReggieMatcher m = Reggie.compile("(\\()?blah(?(1)\\))");

    assertTrue(m.matches("blah"), "Should match 'blah'");
    assertTrue(m.matches("(blah)"), "Should match '(blah)'");
    assertFalse(m.matches("(blah"), "Should not match '(blah' (unmatched paren)");
    assertFalse(m.matches("blah)"), "Should not match 'blah)' (unmatched paren)");
  }

  @Test
  void testNestedConditional() {
    // Pattern: (a)?(b)?(?(1)(?(2)c|d)|e)
    // Complex: if group 1 matched, then check group 2 (if group 2 matched, 'c', else 'd'), else 'e'
    ReggieMatcher m = Reggie.compile("(a)?(b)?(?(1)(?(2)c|d)|e)");

    assertTrue(m.matches("abc"), "Should match 'abc' (both groups, take c)");
    assertTrue(m.matches("ad"), "Should match 'ad' (only group 1, take d)");
    assertTrue(m.matches("e"), "Should match 'e' (no groups, take e)");
    assertFalse(m.matches("ae"), "Should not match 'ae'");
  }

  @Test
  void testConditionalWithFind() {
    // Pattern: x(a)?(?(1)b|c)y
    ReggieMatcher m = Reggie.compile("x(a)?(?(1)b|c)y");

    assertTrue(m.find("xxaby"), "Should find 'xaby'");
    assertTrue(m.find("xxcy"), "Should find 'xcy'");
    assertFalse(m.find("xxacy"), "Should not find in 'xxacy'");
  }

  @Test
  void testConditionalWithGroupExtraction() {
    // Pattern: (a)?(?(1)(b+)|(c+))
    // Group 1: (a)?, Group 2: (b+) in then branch, Group 3: (c+) in else branch
    // When 'a' is present: group 1='a', group 2='bbb', group 3=null
    // When 'a' is absent: group 1=null, group 2=null, group 3='ccc'
    ReggieMatcher m = Reggie.compile("(a)?(?(1)(b+)|(c+))");

    MatchResult r1 = m.match("abbb");
    assertNotNull(r1, "Should match 'abbb'");
    assertEquals("abbb", r1.group(0));
    assertEquals("a", r1.group(1));
    assertEquals("bbb", r1.group(2));
    assertNull(r1.group(3)); // Else branch not taken

    MatchResult r2 = m.match("ccc");
    assertNotNull(r2, "Should match 'ccc'");
    assertEquals("ccc", r2.group(0));
    assertNull(r2.group(1)); // (a)? didn't match
    assertNull(r2.group(2)); // Then branch not taken
    assertEquals("ccc", r2.group(3)); // Else branch taken
  }
}
