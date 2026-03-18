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

/** Tests for MatchResult API on fixed-length sequence patterns. */
public class FixedSequenceMatchResultTest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testMatchSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    MatchResult result = matcher.match("123-456-7890");

    assertNotNull(result, "Match should succeed");
    assertEquals(0, result.start(), "Match should start at 0");
    assertEquals(12, result.end(), "Match should end at 12");
    assertEquals("123-456-7890", result.group(), "Group 0 should be full match");
  }

  @Test
  public void testMatchFailure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    MatchResult result = matcher.match("abc-def-ghij");

    assertNull(result, "Match should fail for non-digits");
  }

  @Test
  public void testFindMatchInMiddle() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    MatchResult result = matcher.findMatch("Call me at 123-456-7890 tomorrow");

    assertNotNull(result, "Find should succeed");
    assertEquals(11, result.start(), "Match should start at 11");
    assertEquals(23, result.end(), "Match should end at 23");
    assertEquals("123-456-7890", result.group(), "Group 0 should be phone number");
  }

  @Test
  public void testFindMatchAtStart() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{4}-\\d{2}-\\d{2}");
    MatchResult result = matcher.findMatch("2025-12-26 is the date");

    assertNotNull(result, "Find should succeed");
    assertEquals(0, result.start(), "Match should start at 0");
    assertEquals(10, result.end(), "Match should end at 10");
    assertEquals("2025-12-26", result.group(), "Group 0 should be date");
  }

  @Test
  public void testFindMatchAtEnd() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    MatchResult result = matcher.findMatch("Phone: 555-123-4567");

    assertNotNull(result, "Find should succeed");
    assertEquals(7, result.start(), "Match should start at 7");
    assertEquals(19, result.end(), "Match should end at 19");
    assertEquals("555-123-4567", result.group(), "Group 0 should be phone");
  }

  @Test
  public void testFindMatchFromOffset() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}");
    MatchResult result = matcher.findMatchFrom("123 and 456 and 789", 5);

    assertNotNull(result, "Find should succeed from offset 5");
    assertEquals(8, result.start(), "Match should start at 8");
    assertEquals(11, result.end(), "Match should end at 11");
    assertEquals("456", result.group(), "Group 0 should be '456'");
  }

  @Test
  public void testFindMatchNotFound() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    MatchResult result = matcher.findMatch("No phone number here");

    assertNull(result, "Find should fail when pattern not found");
  }

  @Test
  public void testMatchBounded() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}");
    MatchResult result = matcher.matchBounded("abc123def", 3, 6);

    assertNotNull(result, "Bounded match should succeed");
    assertEquals(0, result.start(), "Match should start at 0 (relative to substring)");
    assertEquals(3, result.end(), "Match should end at 3 (relative to substring)");
    assertEquals("123", result.group(), "Group 0 should be '123'");
  }

  @Test
  public void testMatchesBounded() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");

    assertTrue(matcher.matchesBounded("xxx123-456-7890yyy", 3, 15), "Should match bounded region");
    assertFalse(
        matcher.matchesBounded("xxx123-456-7890yyy", 0, 12), "Should not match (starts with xxx)");
    assertFalse(
        matcher.matchesBounded("xxx123-456-7890yyy", 3, 12), "Should not match (too short)");
  }
}
