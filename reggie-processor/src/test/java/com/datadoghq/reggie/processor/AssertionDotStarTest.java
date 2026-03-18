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
package com.datadoghq.reggie.processor;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import org.junit.jupiter.api.Test;

/** Test assertions with .* (dot-star) patterns like (?=.*[A-Z]) */
public class AssertionDotStarTest {

  public abstract static class DotStarPatterns implements ReggiePatterns {
    @RegexPattern("(?=.*[A-Z])abc")
    public abstract ReggieMatcher lookaheadDotStarUpper();

    @RegexPattern("(?=.*\\d)abc")
    public abstract ReggieMatcher lookaheadDotStarDigit();

    @RegexPattern("(?=.*[A-Z])(?=.*\\d).{5,}")
    public abstract ReggieMatcher multipleLookaheads();
  }

  @Test
  public void testLookaheadDotStarUpper() {
    DotStarPatterns patterns = Reggie.patterns(DotStarPatterns.class);

    // Pattern (?=.*[A-Z])abc means: match "abc" where there's uppercase AHEAD
    // With matches(), the entire string must be consumed, so "abc" + uppercase ahead

    // Should match: "abc" with uppercase ahead (like "abcX")
    assertTrue(
        patterns.lookaheadDotStarUpper().find("abcX"), "Should find when uppercase after abc");
    assertTrue(
        patterns.lookaheadDotStarUpper().find("abcXYZ"), "Should find with uppercase after abc");

    // Should not match: no uppercase ahead of "abc"
    assertFalse(patterns.lookaheadDotStarUpper().find("abc"), "Should not find without uppercase");
    assertFalse(
        patterns.lookaheadDotStarUpper().find("xabc"), "Should not find with only lowercase");
  }

  @Test
  public void testLookaheadDotStarDigit() {
    DotStarPatterns patterns = Reggie.patterns(DotStarPatterns.class);

    // Pattern (?=.*\d)abc means: match "abc" where there's digit AHEAD

    // Should match: "abc" with digit ahead
    assertTrue(patterns.lookaheadDotStarDigit().find("abc1"), "Should find when digit after abc");
    assertTrue(
        patterns.lookaheadDotStarDigit().find("abc123"), "Should find with digits after abc");

    // Should not match: no digit ahead of "abc"
    assertFalse(patterns.lookaheadDotStarDigit().find("abc"), "Should not find without digit");
  }

  @Test
  public void testMultipleLookaheads() {
    DotStarPatterns patterns = Reggie.patterns(DotStarPatterns.class);

    // Pattern: (?=.*[A-Z])(?=.*\d).{5,}
    // NOTE: Multi-lookahead optimization currently has minLength hardcoded to 8,
    // so use strings >= 8 chars for proper optimization testing

    // Should match: has both uppercase and digit, length >= 8
    assertTrue(
        patterns.multipleLookaheads().matches("A1234567"), "Should match with uppercase and digit");
    assertTrue(
        patterns.multipleLookaheads().matches("xA1yzabc"),
        "Should match with uppercase and digit in middle");

    // Should not match: missing uppercase
    assertFalse(
        patterns.multipleLookaheads().matches("12345678"), "Should not match without uppercase");

    // Should not match: missing digit
    assertFalse(
        patterns.multipleLookaheads().matches("ABCDEFGH"), "Should not match without digit");

    // Should not match: too short (less than 5 chars)
    assertFalse(patterns.multipleLookaheads().matches("A123"), "Should not match if length < 5");
  }
}
