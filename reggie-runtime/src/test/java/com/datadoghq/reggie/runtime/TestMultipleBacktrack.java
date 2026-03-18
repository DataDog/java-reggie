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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for patterns with multiple backtracking quantifiers. Issue: (\S+)\s+(\S+)\( was failing
 * because only the first backtracking quantifier was handled, not the second.
 */
public class TestMultipleBacktrack {

  @Test
  void testTwoGroupsWithTrailingParen() {
    // This is the problematic pattern from the SOA test case
    String pattern = "(\\S+)\\s+(\\S+)\\(";
    String input = "a b(";

    // JDK should match
    assertTrue(Pattern.compile(pattern).matcher(input).matches(), "JDK should match");

    // Reggie should also match
    assertTrue(
        Reggie.compile(pattern).matches(input),
        "Reggie should match pattern with two backtracking groups");
  }

  @Test
  void testSOAPattern() {
    // Full SOA pattern from PCRE tests
    String pattern = "(?i)^(\\d+)\\s+IN\\s+SOA\\s+(\\S+)\\s+(\\S+)\\s*\\(\\s*$";
    String input = "1 IN SOA non-sp1 non-sp2(";

    assertTrue(Pattern.compile(pattern).matcher(input).matches(), "JDK should match SOA pattern");
    assertTrue(Reggie.compile(pattern).matches(input), "Reggie should match SOA pattern");
  }

  @Test
  void testThreeBacktrackingQuantifiers() {
    // Pattern with three backtracking quantifiers followed by literal
    String pattern = "(\\S+)\\s+(\\S+)\\s+(\\S+)\\(";
    String input = "a b c(";

    assertTrue(Pattern.compile(pattern).matcher(input).matches());
    assertTrue(
        Reggie.compile(pattern).matches(input),
        "Reggie should handle three backtracking quantifiers");
  }

  @Test
  void testSimpleTwoGroups() {
    // Two groups without trailing paren - should always work
    String pattern = "(\\S+)\\s+(\\S+)";
    String input = "a b";

    assertTrue(Pattern.compile(pattern).matcher(input).matches());
    assertTrue(Reggie.compile(pattern).matches(input));
  }

  @Test
  void testNoGroupsWithParen() {
    // No capturing groups - should work
    String pattern = "\\S+\\s+\\S+\\(";
    String input = "a b(";

    assertTrue(Pattern.compile(pattern).matcher(input).matches());
    assertTrue(Reggie.compile(pattern).matches(input));
  }
}
