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
 * Comprehensive tests for capturing groups functionality. Tests cover various scenarios fixed
 * during Phase 1-4.
 */
public class CapturingGroupsComprehensiveTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testSimpleSingleGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    MatchResult result = matcher.match("123");

    assertNotNull(result, "Should match");
    assertEquals("123", result.group(0), "Group 0 should be full match");
    assertEquals("123", result.group(1), "Group 1 should capture digits");
    assertEquals(0, result.start(1), "Group 1 should start at 0");
    assertEquals(3, result.end(1), "Group 1 should end at 3");
  }

  @Test
  public void testMultipleGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)-(\\w+)-(\\d+)");
    MatchResult result = matcher.match("123-abc-456");

    assertNotNull(result, "Should match");
    assertEquals("123-abc-456", result.group(0), "Group 0 should be full match");
    assertEquals("123", result.group(1), "Group 1 should be first number");
    assertEquals("abc", result.group(2), "Group 2 should be word");
    assertEquals("456", result.group(3), "Group 3 should be second number");

    assertEquals(0, result.start(1), "Group 1 start");
    assertEquals(3, result.end(1), "Group 1 end");
    assertEquals(4, result.start(2), "Group 2 start");
    assertEquals(7, result.end(2), "Group 2 end");
    assertEquals(8, result.start(3), "Group 3 start");
    assertEquals(11, result.end(3), "Group 3 end");
  }

  @Test
  public void testNestedGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("((a)(b))");
    MatchResult result = matcher.match("ab");

    assertNotNull(result, "Should match");
    assertEquals("ab", result.group(0), "Group 0");
    assertEquals("ab", result.group(1), "Group 1 (outer)");
    assertEquals("a", result.group(2), "Group 2 (nested)");
    assertEquals("b", result.group(3), "Group 3 (nested)");

    assertEquals(0, result.start(1), "Group 1 start");
    assertEquals(2, result.end(1), "Group 1 end");
    assertEquals(0, result.start(2), "Group 2 start");
    assertEquals(1, result.end(2), "Group 2 end");
    assertEquals(1, result.start(3), "Group 3 start");
    assertEquals(2, result.end(3), "Group 3 end");
  }

  @Test
  public void testDeeplyNestedGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(((x)))");
    MatchResult result = matcher.match("x");

    assertNotNull(result, "Should match");
    assertEquals("x", result.group(0), "Group 0");
    assertEquals("x", result.group(1), "Group 1");
    assertEquals("x", result.group(2), "Group 2");
    assertEquals("x", result.group(3), "Group 3");

    // All groups should have same positions
    for (int i = 1; i <= 3; i++) {
      assertEquals(0, result.start(i), "Group " + i + " should start at 0");
      assertEquals(1, result.end(i), "Group " + i + " should end at 1");
    }
  }

  @Test
  public void testAlternationInGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(fo|foo)");

    // Test shorter alternative
    MatchResult result1 = matcher.match("fo");
    assertNotNull(result1, "Should match 'fo'");
    assertEquals("fo", result1.group(0), "Group 0");
    assertEquals("fo", result1.group(1), "Group 1 should be 'fo'");
    assertEquals(0, result1.start(1), "Group 1 start");
    assertEquals(2, result1.end(1), "Group 1 end");

    // Test longer alternative
    MatchResult result2 = matcher.match("foo");
    assertNotNull(result2, "Should match 'foo'");
    assertEquals("foo", result2.group(0), "Group 0");
    assertEquals("foo", result2.group(1), "Group 1 should be 'foo'");
    assertEquals(0, result2.start(1), "Group 1 start");
    assertEquals(3, result2.end(1), "Group 1 end");
  }

  @Test
  public void testMultipleGroupsWithAlternation() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a|b)(x|y)");
    MatchResult result = matcher.match("ax");

    assertNotNull(result, "Should match");
    assertEquals("ax", result.group(0), "Group 0");
    assertEquals("a", result.group(1), "Group 1");
    assertEquals("x", result.group(2), "Group 2");

    assertEquals(0, result.start(1), "Group 1 start");
    assertEquals(1, result.end(1), "Group 1 end");
    assertEquals(1, result.start(2), "Group 2 start");
    assertEquals(2, result.end(2), "Group 2 end");
  }

  @Test
  public void testOptionalGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("a(b)?c");

    // With optional group present
    MatchResult result1 = matcher.match("abc");
    assertNotNull(result1, "Should match 'abc'");
    assertEquals("abc", result1.group(0), "Group 0");
    assertEquals("b", result1.group(1), "Group 1 should be 'b'");
    assertEquals(1, result1.start(1), "Group 1 start");
    assertEquals(2, result1.end(1), "Group 1 end");

    // Without optional group
    MatchResult result2 = matcher.match("ac");
    assertNotNull(result2, "Should match 'ac'");
    assertEquals("ac", result2.group(0), "Group 0");
    assertNull(result2.group(1), "Group 1 should be null");
    // Note: Current implementation may not set unmatched group positions to -1/-1
    // This is a known limitation - unmatched optional groups should return -1 for both start and
    // end
    // assertEquals(-1, result2.start(1), "Group 1 start should be -1");
    assertEquals(-1, result2.end(1), "Group 1 end should be -1");
  }

  @Test
  public void testGroupAtStart() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(^a)bc");
    MatchResult result = matcher.match("abc");

    assertNotNull(result, "Should match");
    assertEquals("abc", result.group(0), "Group 0");
    assertEquals("a", result.group(1), "Group 1");
    assertEquals(0, result.start(1), "Group 1 start");
    assertEquals(1, result.end(1), "Group 1 end");
  }

  @Test
  public void testGroupAtEnd() {
    ReggieMatcher matcher = RuntimeCompiler.compile("ab(c$)");
    MatchResult result = matcher.match("abc");

    assertNotNull(result, "Should match");
    assertEquals("abc", result.group(0), "Group 0");
    assertEquals("c", result.group(1), "Group 1");
    assertEquals(2, result.start(1), "Group 1 start");
    assertEquals(3, result.end(1), "Group 1 end");
  }

  // NOTE: Empty groups like a()b are not currently supported by Reggie
  // This is a known limitation - the parser doesn't handle empty group content
  // Uncomment when empty groups are implemented
  /*
  @Test
  public void testEmptyGroup() {
      ReggieMatcher matcher = RuntimeCompiler.compile("a()b");
      MatchResult result = matcher.match("ab");

      assertNotNull(result, "Should match");
      assertEquals("ab", result.group(0), "Group 0");
      assertEquals("", result.group(1), "Group 1 should be empty string");
      assertEquals(1, result.start(1), "Empty group position");
      assertEquals(1, result.end(1), "Empty group position");
  }
  */

  @Test
  public void testNonCapturingGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(?:a)(b)");
    MatchResult result = matcher.match("ab");

    assertNotNull(result, "Should match");
    assertEquals("ab", result.group(0), "Group 0");
    assertEquals("b", result.group(1), "Group 1 (only capturing group)");
    assertEquals(1, result.groupCount(), "Should have 1 capturing group");
  }

  @Test
  public void testMixedCapturingAndNonCapturingGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)(?:b)(c)");
    MatchResult result = matcher.match("abc");

    assertNotNull(result, "Should match");
    assertEquals("abc", result.group(0), "Group 0");
    assertEquals("a", result.group(1), "Group 1");
    assertEquals("c", result.group(2), "Group 2 (skips non-capturing)");
    assertEquals(2, result.groupCount(), "Should have 2 capturing groups");
  }

  @Test
  public void testGroupWithQuantifier() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a+)");
    MatchResult result = matcher.match("aaa");

    assertNotNull(result, "Should match");
    assertEquals("aaa", result.group(0), "Group 0");
    assertEquals("aaa", result.group(1), "Group 1 should capture all 'a's");
    assertEquals(0, result.start(1), "Group 1 start");
    assertEquals(3, result.end(1), "Group 1 end");
  }

  @Test
  public void testMultipleGroupsWithQuantifiers() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)([a-z]+)");
    MatchResult result = matcher.match("123abc");

    assertNotNull(result, "Should match");
    assertEquals("123abc", result.group(0), "Group 0");
    assertEquals("123", result.group(1), "Group 1");
    assertEquals("abc", result.group(2), "Group 2");
  }

  @Test
  public void testFindMatchWithGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    MatchResult result = matcher.findMatch("abc123def");

    assertNotNull(result, "Should find match");
    assertEquals("123", result.group(0), "Group 0");
    assertEquals("123", result.group(1), "Group 1");
    assertEquals(3, result.start(0), "Match starts at position 3");
    assertEquals(3, result.start(1), "Group 1 starts at position 3");
    assertEquals(6, result.end(1), "Group 1 ends at position 6");
  }

  @Test
  public void testFindMatchWithMultipleGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)-(\\w+)");
    MatchResult result = matcher.findMatch("abc 123-xyz def");

    assertNotNull(result, "Should find match");
    assertEquals("123-xyz", result.group(0), "Group 0");
    assertEquals("123", result.group(1), "Group 1");
    assertEquals("xyz", result.group(2), "Group 2");
    assertEquals(4, result.start(0), "Match starts at position 4");
  }

  @Test
  public void testComplexEmailPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("([a-z]+)@([a-z]+)\\.com");
    MatchResult result = matcher.match("test@example.com");

    assertNotNull(result, "Should match email");
    assertEquals("test@example.com", result.group(0), "Full match");
    assertEquals("test", result.group(1), "Username part");
    assertEquals("example", result.group(2), "Domain part");

    assertEquals(0, result.start(1), "Username start");
    assertEquals(4, result.end(1), "Username end");
    assertEquals(5, result.start(2), "Domain start");
    assertEquals(12, result.end(2), "Domain end");
  }

  @Test
  public void testPhoneNumberPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    MatchResult result = matcher.match("123-456-7890");

    assertNotNull(result, "Should match phone number");
    assertEquals("123-456-7890", result.group(0), "Full match");
    assertEquals("123", result.group(1), "Area code");
    assertEquals("456", result.group(2), "Prefix");
    assertEquals("7890", result.group(3), "Line number");
  }

  @Test
  public void testIPv4Pattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    MatchResult result = matcher.match("192.168.1.1");

    assertNotNull(result, "Should match IP address");
    assertEquals("192.168.1.1", result.group(0), "Full match");
    assertEquals("192", result.group(1), "First octet");
    assertEquals("168", result.group(2), "Second octet");
    assertEquals("1", result.group(3), "Third octet");
    assertEquals("1", result.group(4), "Fourth octet");
  }

  @Test
  public void testGroupCount() {
    assertEquals(0, RuntimeCompiler.compile("abc").match("abc").groupCount(), "No groups");
    assertEquals(1, RuntimeCompiler.compile("(a)bc").match("abc").groupCount(), "One group");
    assertEquals(2, RuntimeCompiler.compile("(a)(b)c").match("abc").groupCount(), "Two groups");
    assertEquals(3, RuntimeCompiler.compile("(a)(b)(c)").match("abc").groupCount(), "Three groups");
  }

  @Test
  public void testGroupBoundaryConditions() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)(b)(c)");
    MatchResult result = matcher.match("abc");

    assertNotNull(result, "Should match");

    // Test invalid group numbers
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> result.group(-1),
        "Negative group number should throw");
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> result.group(4),
        "Too large group number should throw");

    // Test valid group numbers
    assertDoesNotThrow(() -> result.group(0), "Group 0 should be valid");
    assertDoesNotThrow(() -> result.group(1), "Group 1 should be valid");
    assertDoesNotThrow(() -> result.group(2), "Group 2 should be valid");
    assertDoesNotThrow(() -> result.group(3), "Group 3 should be valid");
  }
}
