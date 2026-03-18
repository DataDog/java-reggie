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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Integration tests for stateless loop optimization. Tests end-to-end execution of patterns
 * optimized with STATELESS_LOOP strategy.
 */
class StatelessLoopIntegrationTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ==================== LOOKAHEAD + LITERAL TESTS ====================

  @Test
  void testLookaheadLiteral_BasicFind() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Should match
    assertTrue(matcher.find("user@example.com"));
    assertTrue(matcher.find("john123@example.com"));
    assertTrue(matcher.find("test_user@example.com"));

    // Should not match
    assertFalse(matcher.find("@example.com")); // No word chars before @
    assertFalse(matcher.find("user@other.com")); // Wrong domain
    assertFalse(matcher.find("userexample.com")); // No @
  }

  @Test
  void testLookaheadLiteral_Matches() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // matches() requires exact match (entire string)
    assertTrue(matcher.matches("user@example.com"));
    assertTrue(matcher.matches("a@example.com"));
    assertTrue(matcher.matches("test123@example.com"));

    // Should not match - doesn't end with literal
    assertFalse(matcher.matches("user@example.com.au"));
    assertFalse(matcher.matches("user@example.co"));

    // Should not match - no lookahead
    assertFalse(matcher.matches("@example.com"));
  }

  @Test
  void testLookaheadLiteral_FindFrom() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    String input = "Contact: user@example.com or admin@example.com";

    // Find first occurrence
    int pos1 = matcher.findFrom(input, 0);
    assertEquals(9, pos1); // "user@example.com" starts at position 9

    // Find second occurrence
    int pos2 = matcher.findFrom(input, pos1 + 1);
    assertEquals(29, pos2); // "admin@example.com" starts at position 29

    // No more occurrences
    int pos3 = matcher.findFrom(input, pos2 + 1);
    assertEquals(-1, pos3);
  }

  @Test
  void testLookaheadLiteral_WordCharVariations() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Various word character patterns before @
    assertTrue(matcher.find("abc@example.com"));
    assertTrue(matcher.find("test_user_123@example.com"));
    assertTrue(matcher.find("CamelCase@example.com"));
    assertTrue(matcher.find("under_score@example.com"));
    assertTrue(matcher.find("digits123@example.com"));
  }

  @Test
  void testLookaheadLiteral_EdgeCases() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Empty string
    assertFalse(matcher.find(""));
    assertFalse(matcher.matches(""));

    // Just the literal
    assertFalse(matcher.find("@example.com"));

    // Just word chars
    assertFalse(matcher.find("user"));

    // Word chars + @ but no domain
    assertFalse(matcher.find("user@"));

    // Multiple @ symbols
    assertTrue(matcher.find("user@@example.com")); // Still has \w+@ pattern
  }

  @Test
  void testLookaheadLiteral_MinimalMatch() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Minimal valid input: single word char + @example.com
    assertTrue(matcher.matches("a@example.com"));
    assertTrue(matcher.find("a@example.com"));
    assertEquals(0, matcher.findFrom("a@example.com", 0));
  }

  @Test
  void testLookaheadLiteral_LiteralInMiddle() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    String input = "Email is user@example.com here";
    assertTrue(matcher.find(input));

    // Verify position
    int pos = matcher.findFrom(input, 0);
    assertEquals(9, pos); // Starts at 'u' in "user@example.com"
  }

  @ParameterizedTest
  @CsvSource({
    "user@example.com, true",
    "test123@example.com, true",
    "a@example.com, true",
    "@example.com, false",
    "user@other.com, false",
    "user.example.com, false",
    "'', false"
  })
  void testLookaheadLiteral_ParameterizedMatches(String input, boolean expected) {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");
    assertEquals(expected, matcher.matches(input));
  }

  @ParameterizedTest
  @CsvSource({
    "user@example.com, true",
    "Contact: user@example.com, true",
    "user@example.com here, true",
    "@example.com, false",
    "user@other.com, false",
    "'', false"
  })
  void testLookaheadLiteral_ParameterizedFind(String input, boolean expected) {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");
    assertEquals(expected, matcher.find(input));
  }

  @Test
  void testLookaheadLiteral_NullInput() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    assertFalse(matcher.find(null));
    assertFalse(matcher.matches(null));
    assertEquals(-1, matcher.findFrom(null, 0));
  }

  @Test
  void testLookaheadLiteral_WordCharDefinition() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Word chars: [a-zA-Z0-9_]
    assertTrue(matcher.find("abc@example.com")); // lowercase
    assertTrue(matcher.find("ABC@example.com")); // uppercase
    assertTrue(matcher.find("123@example.com")); // digits
    assertTrue(matcher.find("a_b@example.com")); // underscore
    assertTrue(matcher.find("aB1_@example.com")); // mixed

    // Non-word chars should not match lookahead
    assertFalse(matcher.find("-@example.com"));
    assertFalse(matcher.find(".@example.com"));
    assertFalse(matcher.find(" @example.com"));
    assertFalse(matcher.find("!@example.com"));
  }

  @Test
  void testLookaheadLiteral_MultipleAtSymbols() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Multiple @ symbols - lookahead can match any of them
    assertTrue(matcher.find("user@host@example.com"));
    assertTrue(matcher.find("a@b@c@example.com"));

    // As long as there's at least one \w+@ pattern
    int pos = matcher.findFrom("user@host@example.com", 0);
    assertTrue(pos >= 0);
  }

  @Test
  void testLookaheadLiteral_LongInput() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Test with longer input
    String input = "This is a long email address: superlongusername123@example.com";
    assertTrue(matcher.find(input));

    int pos = matcher.findFrom(input, 0);
    assertEquals(30, pos); // Starts at 's' in "superlongusername123"
  }

  @Test
  void testLookaheadLiteral_ConsecutiveMatches() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    String input = "user@example.com admin@example.com";

    int count = 0;
    int pos = 0;
    while ((pos = matcher.findFrom(input, pos)) >= 0) {
      count++;
      pos++; // Move past current match
    }

    assertEquals(2, count, "Should find 2 matches");
  }

  @Test
  void testLookaheadLiteral_StartPositionValidation() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    String input = "user@example.com";

    // Valid positions
    assertEquals(0, matcher.findFrom(input, 0));
    assertEquals(-1, matcher.findFrom(input, 1)); // Past the start

    // Negative start should be treated as 0
    assertEquals(0, matcher.findFrom(input, -5));

    // Start past end of string
    assertEquals(-1, matcher.findFrom(input, 100));
  }

  @Test
  void testLookaheadLiteral_CaseSensitivity() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Literal is case-sensitive by default
    assertTrue(matcher.find("user@example.com"));
    assertFalse(matcher.find("user@EXAMPLE.COM"));
    assertFalse(matcher.find("user@Example.Com"));
  }

  // ==================== DIFFERENT PATTERNS ====================

  @Test
  void testLookaheadLiteral_DifferentDomain() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@test\\.org");

    assertTrue(matcher.find("user@test.org"));
    assertTrue(matcher.matches("admin@test.org"));
    assertFalse(matcher.find("user@test.com"));
  }

  @Test
  void testLookaheadLiteral_LongDomain() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com\\.au");

    assertTrue(matcher.find("user@example.com.au"));
    assertTrue(matcher.matches("test@example.com.au"));
    assertFalse(matcher.find("user@example.com"));
  }

  @Test
  void testLookaheadLiteral_DifferentLookahead() {
    ReggieMatcher matcher = Reggie.compile("(?=\\d+x).*xend");

    assertTrue(matcher.find("123xend"));
    assertTrue(matcher.matches("456xend"));
    assertFalse(matcher.find("xend")); // No digits before x
    assertFalse(matcher.find("abcxend")); // Letters not digits
  }

  // ==================== PERFORMANCE CHARACTERISTIC TESTS ====================

  @Test
  void testLookaheadLiteral_QuickReject() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Should quickly reject strings without the literal suffix
    // These should use String.indexOf() and return false immediately
    assertFalse(matcher.find("no match here"));
    assertFalse(matcher.find("user@other.com"));
    assertFalse(matcher.find("completely different"));
  }

  @Test
  void testLookaheadLiteral_QuickAccept() {
    ReggieMatcher matcher = Reggie.compile("(?=\\w+@).*@example\\.com");

    // Should quickly accept valid emails
    // Uses indexOf("@example.com") then verifies lookahead
    assertTrue(matcher.find("user@example.com"));
  }

  // ==================== CACHED COMPILATION TEST ====================

  @Test
  void testLookaheadLiteral_CachedCompilation() {
    // Test that cached() returns same instance for same pattern
    String pattern = "(?=\\w+@).*@example\\.com";

    ReggieMatcher matcher1 = Reggie.cached("test", pattern);
    ReggieMatcher matcher2 = Reggie.cached("test", pattern);

    // Should be same instance (cached)
    assertSame(matcher1, matcher2);

    // Should still work correctly
    assertTrue(matcher1.find("user@example.com"));
    assertTrue(matcher2.find("admin@example.com"));
  }
}
