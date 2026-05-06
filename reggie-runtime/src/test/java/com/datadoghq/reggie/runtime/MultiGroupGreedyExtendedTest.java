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
 * Extended tests for SPECIALIZED_MULTI_GROUP_GREEDY: exercises segment types (variable group, fixed
 * group, literal), negated charsets, bounded quantifiers, and find/match/bounded APIs.
 */
class MultiGroupGreedyExtendedTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Email-like: ([a-z]+)@([a-z]+)\.([a-z]+) ──────────────────────────────

  @Test
  void threeGroupEmailMatches() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
    assertTrue(m.matches("user@host.com"));
    assertFalse(m.matches("user@host"));
    assertFalse(m.matches("@host.com"));
  }

  @Test
  void threeGroupEmailFind() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
    assertTrue(m.find("send to user@host.com now"));
    assertFalse(m.find("no email here"));
  }

  @Test
  void threeGroupEmailFindFrom() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
    assertTrue(m.findFrom("user@host.com", 0) >= 0);
    assertEquals(-1, m.findFrom("user@host.com", 14));
  }

  @Test
  void threeGroupEmailMatchResult() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
    MatchResult r = m.findMatch("user@host.com");
    assertNotNull(r);
    assertEquals(3, r.groupCount());
    assertEquals("user", r.group(1));
    assertEquals("host", r.group(2));
    assertEquals("com", r.group(3));
  }

  // ── Negated charsets: ([^@]+)@([^.]+)\.([^.]+) ───────────────────────────

  @Test
  void negatedCharsetGroups() {
    ReggieMatcher m = Reggie.compile("([^@]+)@([^.]+)\\.([^.]+)");
    assertTrue(m.matches("user@host.com"));
    assertFalse(m.matches("@host.com"));
  }

  @Test
  void negatedCharsetFind() {
    ReggieMatcher m = Reggie.compile("([^@]+)@([^.]+)\\.([^.]+)");
    assertTrue(m.find("email: user@host.com !"));
    assertFalse(m.find("no email"));
  }

  // ── Mixed fixed/variable: (\d{3})-(\d{3})-(\d{4}) ────────────────────────

  @Test
  void phonePatternMatches() {
    ReggieMatcher m = Reggie.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    assertTrue(m.matches("555-123-4567"));
    assertFalse(m.matches("55-123-4567"));
    assertFalse(m.matches("555-123-456"));
    assertFalse(m.matches("555-123-45678"));
  }

  @Test
  void phonePatternFind() {
    ReggieMatcher m = Reggie.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    assertTrue(m.find("call 555-867-5309 now"));
    assertFalse(m.find("no number"));
  }

  @Test
  void phonePatternMatchResult() {
    ReggieMatcher m = Reggie.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    MatchResult r = m.findMatch("555-867-5309");
    assertNotNull(r);
    assertEquals("555", r.group(1));
    assertEquals("867", r.group(2));
    assertEquals("5309", r.group(3));
  }

  // ── Two groups: (\w+) (\w+) ───────────────────────────────────────────────

  @Test
  void twoGroupSpaceMatches() {
    ReggieMatcher m = Reggie.compile("(\\w+) (\\w+)");
    assertTrue(m.matches("hello world"));
    assertFalse(m.matches("hello"));
    assertFalse(m.matches("hello world extra"));
  }

  @Test
  void twoGroupSpaceFind() {
    ReggieMatcher m = Reggie.compile("(\\w+) (\\w+)");
    assertTrue(m.find("the quick brown fox"));
    assertFalse(m.find("oneword"));
  }

  @Test
  void twoGroupMatchesBounded() {
    ReggieMatcher m = Reggie.compile("(\\w+) (\\w+)");
    assertTrue(m.matchesBounded("--hello world--", 2, 13));
    assertFalse(m.matchesBounded("--hello world--", 2, 7));
  }

  @Test
  void twoGroupFindBoundsFrom() {
    ReggieMatcher m = Reggie.compile("(\\w+) (\\w+)");
    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom("go hello world go", 3, bounds));
    assertEquals(3, bounds[0]);
  }

  // ── Path-like: (\w+)/(\w+)/(\w+) ──────────────────────────────────────────

  @Test
  void pathPatternMatches() {
    ReggieMatcher m = Reggie.compile("(\\w+)/(\\w+)/(\\w+)");
    assertTrue(m.matches("a/b/c"));
    assertTrue(m.matches("foo/bar/baz"));
    assertFalse(m.matches("foo/bar"));
    assertFalse(m.matches("foo/bar/baz/qux"));
  }

  @Test
  void pathPatternFind() {
    ReggieMatcher m = Reggie.compile("(\\w+)/(\\w+)/(\\w+)");
    assertTrue(m.find("path: foo/bar/baz !"));
    assertFalse(m.find("no path here"));
  }

  // ── matchBounded returns MatchResult ──────────────────────────────────────

  @Test
  void matchBoundedResult() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
    MatchResult r = m.matchBounded("user@host.com", 0, 13);
    assertNotNull(r);
    assertEquals("user", r.group(1));
    assertNull(m.matchBounded("user@host.com", 0, 8));
  }

  // ── Non-matching short input ──────────────────────────────────────────────

  @Test
  void shortInputNoMatch() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
    assertFalse(m.find("a@b"));
    assertFalse(m.find(""));
    assertTrue(m.matches("a@b.c")); // group1=a, @, group2=b, ., group3=c
  }

  // ── findMatchFrom ─────────────────────────────────────────────────────────

  @Test
  void findMatchFromOffset() {
    ReggieMatcher m = Reggie.compile("(\\w+)@(\\w+)\\.(\\w+)");
    MatchResult r = m.findMatchFrom("first second@host.com", 6);
    assertNotNull(r);
    assertEquals("second", r.group(1));
  }
}
