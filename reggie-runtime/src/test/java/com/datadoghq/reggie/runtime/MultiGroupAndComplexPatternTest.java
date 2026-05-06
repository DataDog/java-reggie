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
 * Patterns exercising SPECIALIZED_MULTI_GROUP_GREEDY, SPECIALIZED_FIXED_SEQUENCE,
 * SPECIALIZED_BOUNDED_QUANTIFIERS, GREEDY_BACKTRACK, and DFA generator code paths.
 */
class MultiGroupAndComplexPatternTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // SPECIALIZED_MULTI_GROUP_GREEDY patterns

  @Test
  void emailLikePattern() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.com");
    assertTrue(m.matches("user@host.com"));
    assertFalse(m.matches("user@host.org"));
    assertFalse(m.matches("@host.com"));
  }

  @Test
  void emailLikeFind() {
    ReggieMatcher m = Reggie.compile("([a-z]+)@([a-z]+)\\.com");
    assertTrue(m.find("contact user@host.com please"));
    assertFalse(m.find("no email here"));
  }

  @Test
  void phoneLikePattern() {
    ReggieMatcher m = Reggie.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    assertTrue(m.matches("555-123-4567"));
    assertFalse(m.matches("55-123-4567"));
    assertFalse(m.matches("555-123-456"));
  }

  @Test
  void phoneLikeFind() {
    ReggieMatcher m = Reggie.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    assertTrue(m.find("call 555-123-4567 now"));
    assertFalse(m.find("no phone here"));
  }

  @Test
  void domainPattern() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\.(\\w+)\\.com");
    assertTrue(m.matches("foo.bar.com"));
    assertFalse(m.matches("foo.bar.org"));
    assertFalse(m.matches("foo.com"));
  }

  @Test
  void twoWordGroups() {
    ReggieMatcher m = Reggie.compile("(\\w+) (\\w+)");
    assertTrue(m.matches("hello world"));
    assertFalse(m.matches("hello"));
    assertFalse(m.matches(""));
  }

  @Test
  void twoWordGroupsFind() {
    ReggieMatcher m = Reggie.compile("(\\w+) (\\w+)");
    assertTrue(m.find("one two three"));
    assertFalse(m.find(""));
  }

  @Test
  void threeGroupsPattern() {
    ReggieMatcher m = Reggie.compile("(\\w+)/(\\w+)/(\\w+)");
    assertTrue(m.matches("a/b/c"));
    assertTrue(m.matches("foo/bar/baz"));
    assertFalse(m.matches("a/b"));
  }

  // SPECIALIZED_FIXED_SEQUENCE patterns

  @Test
  void datePattern() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    assertTrue(m.matches("2026-05-06"));
    assertFalse(m.matches("26-05-06"));
    assertFalse(m.matches("2026-5-6"));
  }

  @Test
  void timePattern() {
    ReggieMatcher m = Reggie.compile("\\d{2}:\\d{2}:\\d{2}");
    assertTrue(m.matches("12:34:56"));
    assertFalse(m.matches("2:34:56"));
  }

  @Test
  void hexColorPattern() {
    ReggieMatcher m = Reggie.compile("#[0-9a-fA-F]{6}");
    assertTrue(m.matches("#FF0000"));
    assertTrue(m.matches("#abcdef"));
    assertFalse(m.matches("#FF000"));
    assertFalse(m.matches("FF0000"));
  }

  @Test
  void fixedSequenceFind() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    assertTrue(m.find("date: 2026-05-06 end"));
    assertFalse(m.find("no date here"));
  }

  // SPECIALIZED_BOUNDED_QUANTIFIERS patterns

  @Test
  void ipv4Pattern() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.matches("192.168.1.1"));
    assertTrue(m.matches("1.2.3.4"));
    assertFalse(m.matches("192.168.1"));
    assertFalse(m.matches("192.168.1.1.1"));
  }

  @Test
  void ipv4Find() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.find("host 192.168.1.1 port"));
    assertFalse(m.find("no ip here"));
  }

  @Test
  void flexibleWordLength() {
    ReggieMatcher m = Reggie.compile("[a-z]{3,8}");
    assertTrue(m.matches("abc"));
    assertTrue(m.matches("abcdefgh"));
    assertFalse(m.matches("ab"));
    assertFalse(m.matches("abcdefghi"));
  }

  // GREEDY_BACKTRACK patterns

  @Test
  void greedyBacktrackLiteralSuffix() {
    ReggieMatcher m = Reggie.compile("(.*)bar");
    assertTrue(m.matches("foobar"));
    assertTrue(m.matches("bar"));
    assertFalse(m.matches("foo"));
    assertFalse(m.matches(""));
  }

  @Test
  void greedyBacktrackFind() {
    ReggieMatcher m = Reggie.compile("(.*)bar");
    assertTrue(m.find("the bar is here"));
    assertTrue(m.find("barista"));
    assertFalse(m.find("no match"));
  }

  @Test
  void greedyBacktrackSuffix() {
    ReggieMatcher m = Reggie.compile("(.*)(\\d+)");
    assertTrue(m.matches("abc123"));
    assertTrue(m.matches("123"));
    assertFalse(m.matches("abc"));
  }

  // DFA patterns with more states

  @Test
  void isoTimestampPattern() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    assertTrue(m.matches("2026-05-06T12:34:56"));
    assertFalse(m.matches("2026-05-06 12:34:56"));
    assertFalse(m.matches("2026-05-06T12:34"));
  }

  @Test
  void logLevelPattern() {
    ReggieMatcher m = Reggie.compile("\\[\\d{4}-\\d{2}-\\d{2}\\] \\w+");
    assertTrue(m.matches("[2026-05-06] INFO"));
    assertFalse(m.matches("[2026-05-06] "));
  }

  @Test
  void versionPattern() {
    ReggieMatcher m = Reggie.compile("\\d+\\.\\d+\\.\\d+");
    assertTrue(m.matches("1.2.3"));
    assertTrue(m.matches("10.20.300"));
    assertFalse(m.matches("1.2"));
    assertFalse(m.matches("1.2.3.4"));
  }

  @Test
  void versionFind() {
    ReggieMatcher m = Reggie.compile("\\d+\\.\\d+\\.\\d+");
    assertTrue(m.find("version 1.2.3 released"));
    assertFalse(m.find("no version here"));
  }

  // matchesBounded for multi-group patterns

  @Test
  void multiGroupMatchesBounded() {
    ReggieMatcher m = Reggie.compile("(\\w+)@(\\w+)\\.com");
    assertTrue(m.matchesBounded("prefix user@host.com suffix", 7, 20));
    assertFalse(m.matchesBounded("prefix user@host.org suffix", 7, 20));
  }

  @Test
  void findBoundsForComplexPattern() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom("server 10.0.0.1 port", 0, bounds));
    assertEquals(7, bounds[0]);
  }
}
