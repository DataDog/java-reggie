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
import org.junit.jupiter.api.Test;

/** Tests for greedy backtracking patterns like (.*)bar and (.*)(\d+). */
class GreedyBacktrackTest {

  @Test
  void testSimpleGreedyWithLiteralSuffix() {
    ReggieMatcher rm = Reggie.compile("(.*)bar");

    // Should match "foobar" with group 1 = "foo"
    assertTrue(rm.matches("foobar"));
    MatchResult mr = rm.match("foobar");
    assertNotNull(mr);
    assertEquals("foo", mr.group(1));

    // Should match "bar" with group 1 = ""
    assertTrue(rm.matches("bar"));
    mr = rm.match("bar");
    assertNotNull(mr);
    assertEquals("", mr.group(1));

    // Should not match "foo"
    assertFalse(rm.matches("foo"));
  }

  @Test
  void testGreedyWithPrefixAndLiteralSuffix() {
    ReggieMatcher rm = Reggie.compile("foo(.*)bar");

    // Should match "fooXXXbar" with group 1 = "XXX"
    assertTrue(rm.matches("fooXXXbar"));
    MatchResult mr = rm.match("fooXXXbar");
    assertNotNull(mr);
    assertEquals("XXX", mr.group(1));

    // Should match "foobar" with group 1 = ""
    assertTrue(rm.matches("foobar"));
    mr = rm.match("foobar");
    assertNotNull(mr);
    assertEquals("", mr.group(1));

    // Should not match "foo"
    assertFalse(rm.matches("foo"));

    // Should not match "bar"
    assertFalse(rm.matches("bar"));
  }

  @Test
  void testGreedyWithMinCount() {
    ReggieMatcher rm = Reggie.compile("(.+)bar");

    // Should match "foobar" with group 1 = "foo"
    assertTrue(rm.matches("foobar"));
    MatchResult mr = rm.match("foobar");
    assertNotNull(mr);
    assertEquals("foo", mr.group(1));

    // Should NOT match "bar" (greedy requires at least 1 char)
    assertFalse(rm.matches("bar"));
  }

  @Test
  void testGreedyFollowedByCharClass() {
    ReggieMatcher rm = Reggie.compile("(.*)(\\d+)");

    // Should match "abc123" with group 1 = "abc12", group 2 = "3"
    // (greedy .* takes as much as possible, leaving minimum for \d+)
    assertTrue(rm.matches("abc123"));
    MatchResult mr = rm.match("abc123");
    assertNotNull(mr);
    // The greedy backtracking should give "3" to group 2 (minimum 1 digit)
    // and "abc12" to group 1
    assertEquals("abc12", mr.group(1));
    assertEquals("3", mr.group(2));
  }

  @Test
  void testGreedyWithMultipleLiteralSuffixes() {
    ReggieMatcher rm = Reggie.compile("(.*)foobar");

    assertTrue(rm.matches("xxxfoobar"));
    MatchResult mr = rm.match("xxxfoobar");
    assertNotNull(mr);
    assertEquals("xxx", mr.group(1));

    assertTrue(rm.matches("foobar"));
    mr = rm.match("foobar");
    assertNotNull(mr);
    assertEquals("", mr.group(1));
  }

  @Test
  void testFindWithGreedyBacktrack() {
    ReggieMatcher rm = Reggie.compile("(.*)bar");

    // Find in middle of string
    assertTrue(rm.find("xxx foobar yyy"));
    MatchResult mr = rm.findMatch("xxx foobar yyy");
    assertNotNull(mr);
    assertEquals("xxx foobar", mr.group(0));
    assertEquals("xxx foo", mr.group(1));
  }

  @Test
  void testFindWithGreedyQuantifiedCharClass() {
    ReggieMatcher rm = Reggie.compile("(.*)(\\d+)");

    // Find in full string
    assertTrue(rm.find("I have 2 numbers: 53147"));
    MatchResult mr = rm.findMatch("I have 2 numbers: 53147");
    assertNotNull(mr);
    assertEquals("I have 2 numbers: 53147", mr.group(0));
    // Greedy backtracking: group 1 gets maximum, group 2 gets minimum (1 digit)
    assertEquals("I have 2 numbers: 5314", mr.group(1));
    assertEquals("7", mr.group(2));

    // Find in middle of string
    assertTrue(rm.find("prefix abc123 suffix"));
    mr = rm.findMatch("prefix abc123 suffix");
    assertNotNull(mr);
    assertEquals("prefix abc123", mr.group(0));
    assertEquals("prefix abc12", mr.group(1));
    assertEquals("3", mr.group(2));
  }
}
