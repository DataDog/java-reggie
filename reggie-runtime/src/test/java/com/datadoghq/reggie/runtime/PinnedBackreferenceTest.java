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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for PINNED_BACKREFERENCE strategy: backreferences whose group boundary is provably
 * unambiguous because the group's content charset is disjoint from what follows it (and, when
 * present, from the separator between the group and the backreference site). Differential against
 * {@code java.util.regex} for the same shapes covered by {@code detectPinnedBackreference}
 * (word-boundary repeated word, literal separator, charset separator, no-separator).
 */
class PinnedBackreferenceTest {

  // ── \b(\w+)\s+\1\b : repeated word, whitespace separator ─────────────────

  @Test
  void repeatedWord_matches() {
    ReggieMatcher m = Reggie.compile("\\b(\\w+)\\s+\\1\\b");
    assertTrue(m.matches("hello hello"));
    assertFalse(m.matches("hello world"));
  }

  @Test
  void repeatedWord_find() {
    ReggieMatcher m = Reggie.compile("\\b(\\w+)\\s+\\1\\b");
    assertTrue(m.find("hello hello world"));
    assertFalse(m.find("hello world foo"));
  }

  @Test
  void repeatedWord_findAll() {
    ReggieMatcher m = Reggie.compile("\\b(\\w+)\\s+\\1\\b");
    Pattern jp = Pattern.compile("\\b(\\w+)\\s+\\1\\b");
    String input = "hello hello world foo foo bar";
    java.util.List<MatchResult> reggieMatches = m.findAll(input);
    java.util.regex.Matcher jm = jp.matcher(input);
    int i = 0;
    while (jm.find()) {
      assertTrue(i < reggieMatches.size(), "reggie should find as many matches as JDK");
      assertEquals(jm.start(), reggieMatches.get(i).start());
      assertEquals(jm.end(), reggieMatches.get(i).end());
      assertEquals(jm.group(), reggieMatches.get(i).group(0));
      i++;
    }
    assertEquals(i, reggieMatches.size(), "reggie should not find more matches than JDK");
  }

  // ── (\w+):\1 : literal separator ──────────────────────────────────────────

  @Test
  void literalSeparator_matches() {
    ReggieMatcher m = Reggie.compile("(\\w+):\\1");
    assertTrue(m.matches("hello:hello"));
    assertFalse(m.matches("hello:world"));
  }

  @Test
  void literalSeparator_find() {
    ReggieMatcher m = Reggie.compile("(\\w+):\\1");
    assertTrue(m.find("xx hello:hello yy"));
    assertFalse(m.find("hello:world"));
  }

  // ── ([a-z]+)\d+\1 : charset separator ─────────────────────────────────────

  @Test
  void charsetSeparator_matches() {
    ReggieMatcher m = Reggie.compile("([a-z]+)\\d+\\1");
    assertTrue(m.matches("ab12ab"));
    assertFalse(m.matches("ab12cd"));
  }

  @Test
  void charsetSeparator_find() {
    ReggieMatcher m = Reggie.compile("([a-z]+)\\d+\\1");
    assertTrue(m.find("xx ab12ab yy"));
    assertFalse(m.find("ab12cd"));
  }

  // ── (\w+)\b\1 : zero-width (empty) separator ──────────────────────────────
  // A word-boundary can never occur between two word characters, so this pattern is
  // structurally unsatisfiable - JDK agrees it never matches; reggie must agree too.

  @Test
  void emptySeparator_matches() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\b\\1");
    assertFalse(m.matches("abcabc"));
    assertFalse(m.matches("abcxyz"));
  }

  @Test
  void emptySeparator_find() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\b\\1");
    assertFalse(m.find("xx abcabc yy"));
    assertFalse(m.find("abcxyz"));
  }

  // ── Boundary: backref site immediately at end of string ──────────────────

  @Test
  void backrefAtEndOfString_matches() {
    ReggieMatcher m = Reggie.compile("(\\w+):\\1");
    Pattern jp = Pattern.compile("(\\w+):\\1");
    assertEquals(jp.matcher("hello:hello").matches(), m.matches("hello:hello"));
    assertEquals(jp.matcher("hello:hell").matches(), m.matches("hello:hell"));
  }

  @Test
  void backrefAtEndOfString_findMatch() {
    ReggieMatcher m = Reggie.compile("(\\w+):\\1");
    MatchResult result = m.findMatch("xx hello:hello");
    assertNotNull(result);
    assertEquals(3, result.start());
    assertEquals(14, result.end());
    assertEquals("hello:hello", result.group(0));
  }

  // ── Boundary: empty separator ─────────────────────────────────────────────

  @Test
  void emptySeparator_boundary() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\b\\1");
    Pattern jp = Pattern.compile("(\\w+)\\b\\1");
    for (String in : new String[] {"", "a", "aa", "abab", "abba", "xabcabcy"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  // ── Boundary: minimum group length 1 ──────────────────────────────────────

  @Test
  void minimumGroupLength_one() {
    ReggieMatcher m = Reggie.compile("(\\w+):\\1");
    Pattern jp = Pattern.compile("(\\w+):\\1");
    for (String in : new String[] {"a:a", "a:b", "1:1", "1:2"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
    }
  }

  // ── Differential fuzz-lite across all positive-case patterns ─────────────

  @Test
  void differential_repeatedWord() {
    assertDifferentialMatchesAndFind(
        "\\b(\\w+)\\s+\\1\\b",
        new String[] {
          "hello hello", "hello world", "the the cat", "go go go", "a a", "a b", "", " ", "x  x"
        });
  }

  @Test
  void differential_literalSeparator() {
    assertDifferentialMatchesAndFind(
        "(\\w+):\\1", new String[] {"hello:hello", "hello:world", "a:a", "", "x:x:x", "abc:ab"});
  }

  @Test
  void differential_charsetSeparator() {
    assertDifferentialMatchesAndFind(
        "([a-z]+)\\d+\\1", new String[] {"ab12ab", "ab12cd", "z9z", "a1a", "a1b", ""});
  }

  @Test
  void differential_emptySeparator() {
    // (\w+)\b\1 is structurally unsatisfiable (a word-boundary can't occur between two word
    // characters) - both JDK and reggie must agree it never matches, on every input.
    assertDifferentialMatchesAndFind(
        "(\\w+)\\b\\1", new String[] {"abcabc", "abcxyz", "aa", "a", "", "xabcabcy"});
  }

  private static void assertDifferentialMatchesAndFind(String pattern, String[] inputs) {
    ReggieMatcher m = Reggie.compile(pattern);
    Pattern jp = Pattern.compile(pattern);
    for (String in : inputs) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged for '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged for '" + in + "'");
    }
  }
}
