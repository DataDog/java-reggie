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

/** Test specialized bytecode generation for greedy char class patterns like (\d+), ([a-z]*). */
public class GreedyCharClassTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testDigitsPlusMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");

    // Should match strings of only digits
    assertTrue(matcher.matches("123"), "Should match digits");
    assertTrue(matcher.matches("0"), "Should match single digit");
    assertTrue(matcher.matches("999999"), "Should match multiple digits");

    // Should not match non-digits or mixed content
    assertFalse(matcher.matches("abc"), "Should not match letters");
    assertFalse(matcher.matches("123abc"), "Should not match mixed content");
    assertFalse(matcher.matches(""), "Should not match empty string");
  }

  @Test
  public void testDigitsPlusFind() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");

    // Should find digits within strings
    assertTrue(matcher.find("abc123def"), "Should find digits in middle");
    assertTrue(matcher.find("123"), "Should find digits at start");
    assertTrue(matcher.find("abc123"), "Should find digits at end");

    // Should not find in strings without digits
    assertFalse(matcher.find("abc"), "Should not find without digits");
    assertFalse(matcher.find(""), "Should not find in empty string");
  }

  @Test
  public void testDigitsPlusFindFrom() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");

    // Test findFrom at different positions
    assertEquals(3, matcher.findFrom("abc123def456", 0), "Should find first occurrence");
    assertEquals(
        9, matcher.findFrom("abc123def456", 6), "Should find second occurrence from offset");
    assertEquals(-1, matcher.findFrom("abc", 0), "Should return -1 when not found");
  }

  @Test
  public void testLowercaseStarMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("([a-z]*)");

    // Should match zero or more lowercase
    assertTrue(matcher.matches(""), "Should match empty string (zero occurrences)");
    assertTrue(matcher.matches("abc"), "Should match lowercase");
    assertTrue(matcher.matches("xyz"), "Should match lowercase");

    // Should not match uppercase or digits
    assertFalse(matcher.matches("ABC"), "Should not match uppercase");
    assertFalse(matcher.matches("123"), "Should not match digits");
    assertFalse(matcher.matches("abc123"), "Should not match mixed");
  }

  @Test
  public void testWordCharsPlusMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)");

    // Should match word characters (alphanumeric + underscore)
    assertTrue(matcher.matches("abc"), "Should match letters");
    assertTrue(matcher.matches("123"), "Should match digits");
    assertTrue(matcher.matches("abc_123"), "Should match word chars with underscore");

    // Should not match special chars
    assertFalse(matcher.matches("abc-def"), "Should not match with hyphen");
    assertFalse(matcher.matches(""), "Should not match empty string");
  }
}
