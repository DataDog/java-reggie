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
package com.datadoghq.reggie.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for nested quantifier patterns like ([ab]+)+. These patterns require the inner quantifier
 * to consume multiple chars per outer iteration.
 */
public class NestedQuantifierTest {

  @Test
  void testSimpleNestedQuantifier() {
    // Pattern ([ab]+)+ on "abba" - inner + matches "abba" in one outer iteration
    String pattern = "([ab]+)+";
    String input = "abba";

    Pattern jdkPattern = Pattern.compile(pattern);
    Matcher jdkMatcher = jdkPattern.matcher(input);
    assertTrue(jdkMatcher.matches());
    String jdkGroup1 = jdkMatcher.group(1);

    ReggieMatcher reggie = Reggie.compile(pattern);
    assertTrue(reggie.matches(input), "Reggie should match");
    MatchResult mr = reggie.match(input);
    assertNotNull(mr, "MatchResult should not be null");
    assertEquals(jdkGroup1, mr.group(1), "Group 1 should match JDK");
  }

  @Test
  void testNegatedCharsetNestedQuantifier() {
    // Pattern ([^"]+)* on "abc" - inner + matches "abc"
    String pattern = "([^\"]+)*";
    String input = "abc";

    Pattern jdkPattern = Pattern.compile(pattern);
    Matcher jdkMatcher = jdkPattern.matcher(input);
    assertTrue(jdkMatcher.matches());
    String jdkGroup1 = jdkMatcher.group(1);

    ReggieMatcher reggie = Reggie.compile(pattern);
    assertTrue(reggie.matches(input), "Reggie should match");
    MatchResult mr = reggie.match(input);
    assertNotNull(mr, "MatchResult should not be null");
    assertEquals(jdkGroup1, mr.group(1), "Group 1 should match JDK");
  }

  @Test
  void testNestedQuantifierWithAlternation() {
    // Pattern ([^"]+|\.)* on "abc" - first alt [^"]+ matches "abc"
    // Note: \\. in Java string = \. in regex = escaped period (literal '.')
    String pattern = "([^\"]+|\\.)*";
    String input = "abc";

    Pattern jdkPattern = Pattern.compile(pattern);
    Matcher jdkMatcher = jdkPattern.matcher(input);
    assertTrue(jdkMatcher.matches());
    String jdkGroup1 = jdkMatcher.group(1);

    ReggieMatcher reggie = Reggie.compile(pattern);
    assertTrue(reggie.matches(input), "Reggie should match");
    MatchResult mr = reggie.match(input);
    assertNotNull(mr, "MatchResult should not be null");
    assertEquals(jdkGroup1, mr.group(1), "Group 1 should match JDK");
  }

  @Test
  void testSimplePlusQuantifier() {
    // Simple case: (a)+ on "aaa" - each iteration matches 'a', last captured
    String pattern = "(a)+";
    String input = "aaa";

    Pattern jdkPattern = Pattern.compile(pattern);
    Matcher jdkMatcher = jdkPattern.matcher(input);
    assertTrue(jdkMatcher.matches());
    assertEquals("a", jdkMatcher.group(1));

    ReggieMatcher reggie = Reggie.compile(pattern);
    assertTrue(reggie.matches(input));
    MatchResult mr = reggie.match(input);
    assertEquals("a", mr.group(1));
  }

  @Test
  void testCharClassPlusQuantifier() {
    // ([a-z])+ on "abc" - each iteration matches one char, last is 'c'
    String pattern = "([a-z])+";
    String input = "abc";

    Pattern jdkPattern = Pattern.compile(pattern);
    Matcher jdkMatcher = jdkPattern.matcher(input);
    assertTrue(jdkMatcher.matches());
    assertEquals("c", jdkMatcher.group(1));

    ReggieMatcher reggie = Reggie.compile(pattern);
    assertTrue(reggie.matches(input));
    MatchResult mr = reggie.match(input);
    assertEquals("c", mr.group(1));
  }
}
