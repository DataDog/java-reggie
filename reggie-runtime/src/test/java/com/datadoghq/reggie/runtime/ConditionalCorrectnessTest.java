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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Correctness tests for PCRE conditional patterns. Verifies that (?(condition)yes|no) patterns work
 * correctly.
 */
public class ConditionalCorrectnessTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testBalancedParentheses_WithParens() {
    // Pattern: (\()?blah(?(1)\))
    // If group 1 (opening paren) matched, require closing paren
    // Should match: "(blah)"
    ReggieMatcher m = RuntimeCompiler.compile("(\\()?blah(?(1)\\))");
    assertTrue(m.matches("(blah)"), "Should match '(blah)' - balanced parens");
  }

  @Test
  public void testBalancedParentheses_WithoutParens() {
    // Pattern: (\()?blah(?(1)\))
    // If group 1 didn't match, don't require closing paren
    // Should match: "blah"
    ReggieMatcher m = RuntimeCompiler.compile("(\\()?blah(?(1)\\))");

    // Debug: test with explicit group extraction
    boolean result = m.matches("blah");
    if (!result) {
      System.err.println("DEBUG: Pattern failed to match 'blah'");
      System.err.println("  Expected: group 1 should NOT match (no opening paren)");
      System.err.println("  Then conditional should match empty (no closing paren required)");
    }

    assertTrue(result, "Should match 'blah' - no parens");
  }

  @Test
  public void testBalancedParentheses_UnbalancedOpen() {
    // Pattern: (\()?blah(?(1)\))
    // Should NOT match: "(blah" - opening paren but no closing
    ReggieMatcher m = RuntimeCompiler.compile("(\\()?blah(?(1)\\))");
    assertFalse(m.matches("(blah"), "Should NOT match '(blah' - unbalanced");
  }

  @Test
  public void testBalancedParentheses_UnbalancedClose() {
    // Pattern: (\()?blah(?(1)\))
    // Should NOT match: "blah)" - closing paren but no opening
    ReggieMatcher m = RuntimeCompiler.compile("(\\()?blah(?(1)\\))");
    assertFalse(m.matches("blah)"), "Should NOT match 'blah)' - unbalanced");
  }

  @Test
  public void testConditional_SimpleAlternation() {
    // Pattern: (a)?(?(1)b|c)
    // If 'a' matched, require 'b', else require 'c'
    ReggieMatcher m = RuntimeCompiler.compile("(a)?(?(1)b|c)");

    assertTrue(m.matches("ab"), "Should match 'ab' - a matched, then b");
    assertTrue(m.matches("c"), "Should match 'c' - a didn't match, then c");
    assertFalse(m.matches("ac"), "Should NOT match 'ac' - a matched but got c");
    assertFalse(m.matches("b"), "Should NOT match 'b' - a didn't match but got b");
  }

  @Test
  public void testConditional_NoElseBranch() {
    // Pattern: (x)?(?(1)y)
    // If 'x' matched, require 'y', else match empty
    ReggieMatcher m = RuntimeCompiler.compile("(x)?(?(1)y)");

    assertTrue(m.matches("xy"), "Should match 'xy' - x matched, then y");
    assertTrue(m.matches(""), "Should match empty - x didn't match, no else branch");
    assertFalse(m.matches("x"), "Should NOT match 'x' - x matched but no y");
    assertFalse(m.matches("y"), "Should NOT match 'y' - x didn't match but got y");
  }

  @Test
  public void testConditional_WithQuantifiers() {
    // Pattern: (a+)?(?(1)b+|c+)
    // If 'a+' matched, require 'b+', else require 'c+'
    ReggieMatcher m = RuntimeCompiler.compile("(a+)?(?(1)b+|c+)");

    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("aaabbb"), "Should match 'aaabbb'");
    assertTrue(m.matches("c"), "Should match 'c'");
    assertTrue(m.matches("ccc"), "Should match 'ccc'");
    assertFalse(m.matches("ac"), "Should NOT match 'ac'");
    assertFalse(m.matches("b"), "Should NOT match 'b'");
  }

  @Test
  public void testConditional_NestedGroups() {
    // Pattern: ((a))?b(?(1)c)
    // Group 1 is outer group, group 2 is inner
    // If outer group matched, require 'c'
    ReggieMatcher m = RuntimeCompiler.compile("((a))?b(?(1)c)");

    assertTrue(m.matches("abc"), "Should match 'abc' - group 1 matched");
    assertTrue(m.matches("b"), "Should match 'b' - group 1 didn't match");
    assertFalse(m.matches("ab"), "Should NOT match 'ab' - group 1 matched but no c");
    assertFalse(m.matches("bc"), "Should NOT match 'bc' - group 1 didn't match but got c");
  }

  @Test
  public void testConditional_MultipleConditions() {
    // Pattern: (a)?(b)?(?(1)x)(?(2)y)
    // Two separate conditionals checking different groups
    ReggieMatcher m = RuntimeCompiler.compile("(a)?(b)?(?(1)x)(?(2)y)");

    assertTrue(m.matches("abxy"), "Should match 'abxy' - both matched");
    assertTrue(m.matches("ax"), "Should match 'ax' - only a matched");
    assertTrue(m.matches("by"), "Should match 'by' - only b matched");
    assertTrue(m.matches(""), "Should match empty - neither matched");
    assertFalse(m.matches("a"), "Should NOT match 'a' - a matched but no x");
    assertFalse(m.matches("b"), "Should NOT match 'b' - b matched but no y");
  }
}
