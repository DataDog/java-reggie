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
 * Tests for GREEDY_BACKTRACK strategy with star (*) quantifier in suffix group.
 *
 * <p>The star quantifier allows matching zero characters, so the greedy group should be able to
 * consume the entire input when the suffix matches nothing.
 */
class TestGreedyBacktrackStarSuffix {

  @BeforeEach
  void setUp() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testStarSuffixCanMatchEmpty() {
    // Pattern: (.*)(\d*) - suffix can match 0 digits
    ReggieMatcher m = Reggie.compile("(.*)(\\d*)");

    // Input has digits at the end, but * allows empty match
    // Greedy (.*) should take everything, (\d*) should match empty
    MatchResult result = m.findMatch("I have 2 numbers: 53147");

    assertNotNull(result, "Should match");
    assertEquals(
        "I have 2 numbers: 53147", result.group(1), "Group 1 should capture entire string");
    assertEquals("", result.group(2), "Group 2 should be empty (star allows 0 matches)");
  }

  @Test
  void testPlusSuffixRequiresOne() {
    // Pattern: (.*)(\d+) - suffix requires at least 1 digit
    ReggieMatcher m = Reggie.compile("(.*)(\\d+)");

    MatchResult result = m.findMatch("I have 2 numbers: 53147");

    assertNotNull(result, "Should match");
    // The greedy group should leave at least one digit for the suffix
    assertTrue(result.group(2).length() >= 1, "Group 2 should have at least 1 digit");
  }

  @Test
  void testStarSuffixWithDigitsAtEnd() {
    // When input has digits at end, * allows greedy group to take all
    ReggieMatcher m = Reggie.compile("(.*)(\\d*)");

    // With *, greedy should take everything since * allows zero matches
    MatchResult result = m.findMatch("test123");

    assertNotNull(result, "Should match");
    assertEquals(
        "test123", result.group(1), "Group 1 should capture entire string (star allows zero)");
    assertEquals("", result.group(2), "Group 2 should be empty");
  }
}
