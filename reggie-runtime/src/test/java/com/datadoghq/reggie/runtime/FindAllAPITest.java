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

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for findAll API. Uses RuntimeCompiler to test actual Reggie-generated matchers. */
public class FindAllAPITest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testFindAllMultipleMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    List<MatchResult> matches = matcher.findAll("test and test and test");

    assertEquals(3, matches.size(), "Should find 3 matches");

    assertEquals(0, matches.get(0).start());
    assertEquals(4, matches.get(0).end());

    assertEquals(9, matches.get(1).start());
    assertEquals(13, matches.get(1).end());

    assertEquals(18, matches.get(2).start());
    assertEquals(22, matches.get(2).end());
  }

  @Test
  public void testFindAllNoMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    List<MatchResult> matches = matcher.findAll("nothing here");

    assertTrue(matches.isEmpty(), "Should find no matches");
  }

  @Test
  public void testFindAllSingleMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    List<MatchResult> matches = matcher.findAll("only test once");

    assertEquals(1, matches.size(), "Should find 1 match");
    assertEquals(5, matches.get(0).start());
    assertEquals(9, matches.get(0).end());
  }

  @Test
  public void testFindAllAdjacentMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("a");
    List<MatchResult> matches = matcher.findAll("aaa");

    assertEquals(3, matches.size(), "Should find 3 adjacent matches");

    assertEquals(0, matches.get(0).start());
    assertEquals(1, matches.get(1).start());
    assertEquals(2, matches.get(2).start());
  }

  @Test
  public void testFindAllMatchResultContent() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    List<MatchResult> matches = matcher.findAll("prefix test suffix");

    assertEquals(1, matches.size());
    MatchResult match = matches.get(0);

    assertEquals("test", match.group(), "Group 0 should contain matched text");
    assertEquals(0, match.groupCount(), "Should report correct group count");
  }

  @Test
  public void testFindAllWithOverlappingPattern() {
    // Pattern that could overlap (but greedy matching won't)
    ReggieMatcher matcher = RuntimeCompiler.compile("aa");
    List<MatchResult> matches = matcher.findAll("aaaa");

    // Non-overlapping: should match at 0-2 and 2-4
    assertEquals(2, matches.size(), "Should find non-overlapping matches");
    assertEquals(0, matches.get(0).start());
    assertEquals(2, matches.get(1).start());
  }

  @Test
  public void testFindAllEmptyInput() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    List<MatchResult> matches = matcher.findAll("");

    assertTrue(matches.isEmpty(), "Should find no matches in empty string");
  }

  @Test
  public void testFindAllDigitSequences() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
    List<MatchResult> matches = matcher.findAll("abc123def456ghi789");

    assertEquals(3, matches.size(), "Should find 3 digit sequences");
    assertEquals("123", matches.get(0).group());
    assertEquals("456", matches.get(1).group());
    assertEquals("789", matches.get(2).group());
  }

  @Test
  public void testFindAllWords() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\w+");
    List<MatchResult> matches = matcher.findAll("hello world foo");

    assertEquals(3, matches.size(), "Should find 3 words");
    assertEquals("hello", matches.get(0).group());
    assertEquals("world", matches.get(1).group());
    assertEquals("foo", matches.get(2).group());
  }
}
