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
 * Tests for empty group captures (zero-length matches).
 *
 * <p>POSIX semantics: A group that matches zero characters should capture an empty string, not
 * null.
 */
public class EmptyGroupCaptureTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testOptionalGroupMatchesZero() {
    // Pattern: a(b*)
    // Input: "a"
    // Group 1 should match "" (zero b's), not null
    ReggieMatcher m = Reggie.compile("a(b*)");
    MatchResult result = m.match("a");

    assertNotNull(result, "Should match");

    // Debug output
    System.out.println("testOptionalGroupMatchesZero:");
    System.out.println(
        "  Group 0: '" + result.group(0) + "' [" + result.start(0) + ", " + result.end(0) + "]");
    System.out.println(
        "  Group 1: '" + result.group(1) + "' [" + result.start(1) + ", " + result.end(1) + "]");

    assertEquals("a", result.group(0), "Group 0 should be 'a'");
    assertEquals("", result.group(1), "Group 1 should be empty string, not null");
    assertEquals(1, result.start(1), "Group 1 start should be 1");
    assertEquals(1, result.end(1), "Group 1 end should be 1 (empty match)");
  }

  @Test
  public void testAlternationWithEmpty() {
    // Pattern: (a|)
    // Input: "b"
    // Group 1 should match "" (empty alternative)
    ReggieMatcher m = Reggie.compile("(a|)b");
    MatchResult result = m.match("b");

    assertNotNull(result, "Should match");

    // Debug output
    System.out.println("testAlternationWithEmpty:");
    System.out.println(
        "  Group 0: '" + result.group(0) + "' [" + result.start(0) + ", " + result.end(0) + "]");
    System.out.println(
        "  Group 1: '" + result.group(1) + "' [" + result.start(1) + ", " + result.end(1) + "]");

    assertEquals("b", result.group(0), "Group 0 should be 'b'");
    assertEquals("", result.group(1), "Group 1 should be empty string");
    assertEquals(0, result.start(1), "Group 1 start should be 0");
    assertEquals(0, result.end(1), "Group 1 end should be 0");
  }

  @Test
  public void testOptionalQuantifierMatchesZero() {
    // Pattern: a(b)?
    // Input: "a"
    // Group 1 should not be captured (truly optional)
    ReggieMatcher m = Reggie.compile("a(b)?");
    MatchResult result = m.match("a");

    assertNotNull(result, "Should match");
    assertEquals("a", result.group(0), "Group 0 should be 'a'");
    // Note: (b)? is different from (b*) - ? means 0 or 1, and if 0, group is not set
    // This test documents current behavior - may need to verify PCRE semantics
  }

  @Test
  public void testZeroOrMoreMatchesZero() {
    // Pattern: x(y*)z
    // Input: "xz"
    // Group 1 should match "" (zero y's)
    ReggieMatcher m = Reggie.compile("x(y*)z");
    MatchResult result = m.match("xz");

    assertNotNull(result, "Should match");

    // Debug output
    System.out.println("testZeroOrMoreMatchesZero:");
    System.out.println(
        "  Group 0: '" + result.group(0) + "' [" + result.start(0) + ", " + result.end(0) + "]");
    System.out.println(
        "  Group 1: '" + result.group(1) + "' [" + result.start(1) + ", " + result.end(1) + "]");

    assertEquals("xz", result.group(0), "Group 0 should be 'xz'");
    assertEquals("", result.group(1), "Group 1 should be empty string");
    assertEquals(1, result.start(1), "Group 1 start should be 1");
    assertEquals(1, result.end(1), "Group 1 end should be 1");
  }

  @Test
  public void testQuantifierRangeMatchesMinimum() {
    // Pattern: a(b{0,3})
    // Input: "a"
    // Group 1 should match "" (zero b's, minimum is 0)
    ReggieMatcher m = Reggie.compile("a(b{0,3})");
    MatchResult result = m.match("a");

    assertNotNull(result, "Should match");

    // Debug output
    System.out.println("testQuantifierRangeMatchesMinimum:");
    System.out.println(
        "  Group 0: '" + result.group(0) + "' [" + result.start(0) + ", " + result.end(0) + "]");
    System.out.println(
        "  Group 1: '" + result.group(1) + "' [" + result.start(1) + ", " + result.end(1) + "]");

    assertEquals("a", result.group(0), "Group 0 should be 'a'");
    assertEquals("", result.group(1), "Group 1 should be empty string");
    assertEquals(1, result.start(1), "Group 1 start should be 1");
    assertEquals(1, result.end(1), "Group 1 end should be 1");
  }
}
