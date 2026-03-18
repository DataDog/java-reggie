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
 * Tests for MatchResult API methods (match, findMatch, findMatchFrom). Uses RuntimeCompiler to test
 * actual Reggie-generated matchers.
 */
public class MatchResultAPITest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testMatchSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.match("test");

    assertNotNull(result, "Match should succeed");
    assertEquals(0, result.start(), "Match should start at 0");
    assertEquals(4, result.end(), "Match should end at 4");
    assertEquals("test", result.group(), "Group 0 should be 'test'");
    assertEquals(0, result.groupCount(), "Should have 0 capturing groups");
  }

  @Test
  public void testMatchFailure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.match("other");

    assertNull(result, "Match should fail");
  }

  @Test
  public void testFindMatchSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.findMatch("prefix test suffix");

    assertNotNull(result, "Find should succeed");
    assertEquals(7, result.start(), "Match should start at 7");
    assertEquals(11, result.end(), "Match should end at 11");
    assertEquals("test", result.group(), "Group 0 should be 'test'");
  }

  @Test
  public void testFindMatchFailure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.findMatch("nothing here");

    assertNull(result, "Find should fail");
  }

  @Test
  public void testFindMatchFromSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.findMatchFrom("test test test", 5);

    assertNotNull(result, "Find from offset should succeed");
    assertEquals(5, result.start(), "Match should start at 5");
    assertEquals(9, result.end(), "Match should end at 9");
  }

  @Test
  public void testFindMatchFromFailure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.findMatchFrom("test nothing", 5);

    assertNull(result, "Find from offset should fail");
  }

  @Test
  public void testMatchResultGroupAccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.match("test");

    assertNotNull(result);
    assertEquals("test", result.group(0), "Group 0 access via method");
    assertEquals(0, result.start(0), "Start of group 0");
    assertEquals(4, result.end(0), "End of group 0");
  }

  @Test
  public void testMatchResultInvalidGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    MatchResult result = matcher.match("test");

    assertNotNull(result);
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> result.group(1),
        "Should throw for invalid group index");
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> result.group(-1),
        "Should throw for negative group index");
  }

  @Test
  public void testMatchWithDigitPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
    MatchResult result = matcher.match("12345");

    assertNotNull(result, "Match should succeed for digits");
    assertEquals("12345", result.group(), "Should match entire input");
  }

  @Test
  public void testFindDigitPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
    MatchResult result = matcher.findMatch("abc123def");

    assertNotNull(result, "Find should succeed");
    assertEquals(3, result.start(), "Match should start at 3");
    assertEquals(6, result.end(), "Match should end at 6");
    assertEquals("123", result.group(), "Should match digits");
  }

  @Test
  public void testMatchWithWordPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\w+");
    MatchResult result = matcher.match("hello_world123");

    assertNotNull(result, "Match should succeed");
    assertEquals("hello_world123", result.group(), "Should match word chars");
  }

  @Test
  public void testCapturingGroupSingleGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    MatchResult result = matcher.match("12345");

    assertNotNull(result, "Match should succeed");
    assertEquals(1, result.groupCount(), "Should have 1 capturing group");
    assertEquals("12345", result.group(0), "Group 0 should be entire match");
    assertEquals("12345", result.group(1), "Group 1 should capture digits");
    assertEquals(0, result.start(1), "Group 1 should start at 0");
    assertEquals(5, result.end(1), "Group 1 should end at 5");
  }

  @Test
  public void testCapturingGroupMultipleGroups() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    MatchResult result = matcher.match("123-456-7890");

    assertNotNull(result, "Match should succeed");
    assertEquals(3, result.groupCount(), "Should have 3 capturing groups");
    assertEquals("123-456-7890", result.group(0), "Group 0 should be entire match");
    assertEquals("123", result.group(1), "Group 1 should be area code");
    assertEquals("456", result.group(2), "Group 2 should be exchange");
    assertEquals("7890", result.group(3), "Group 3 should be subscriber");
  }

  @Test
  public void testCapturingGroupFindMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    MatchResult result = matcher.findMatch("prefix 123 suffix");

    assertNotNull(result, "Find should succeed");
    assertEquals(1, result.groupCount(), "Should have 1 capturing group");
    assertEquals("123", result.group(0), "Group 0 should be entire match");
    assertEquals("123", result.group(1), "Group 1 should capture digits");
    assertEquals(7, result.start(1), "Group 1 should start at 7");
    assertEquals(10, result.end(1), "Group 1 should end at 10");
  }
}
