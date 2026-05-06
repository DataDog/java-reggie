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
 * Functional tests for the LiteralAlternationTrieGenerator code path. Triggered when a pattern is a
 * pure alternation of 5+ literal strings (e.g. foo|bar|baz|qux|quux).
 */
class LiteralAlternationTest {

  // 5-keyword pattern — minimum to trigger SPECIALIZED_LITERAL_ALTERNATION
  private static final String FIVE_KW = "foo|bar|baz|qux|quux";
  // 8 keywords of mixed lengths
  private static final String MIXED_LEN = "select|insert|update|delete|create|drop|alter|truncate";
  // Keywords that share a common prefix (exercises trie branching)
  private static final String PREFIX_SHARED = "pre|prefix|prefer|prevent|prepare|present|preview";

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── matches() ─────────────────────────────────────────────────────────────

  @Test
  void matchesExactKeyword() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertTrue(m.matches("foo"));
    assertTrue(m.matches("bar"));
    assertTrue(m.matches("baz"));
    assertTrue(m.matches("qux"));
    assertTrue(m.matches("quux"));
  }

  @Test
  void matchesRejectsNonKeyword() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertFalse(m.matches("fooo"));
    assertFalse(m.matches("fo"));
    assertFalse(m.matches(""));
    assertFalse(m.matches("other"));
  }

  @Test
  void matchesRejectsEmptyString() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertFalse(m.matches(""));
  }

  @Test
  void matchesMixedLengthKeywords() {
    ReggieMatcher m = Reggie.compile(MIXED_LEN);
    assertTrue(m.matches("select"));
    assertTrue(m.matches("truncate"));
    assertFalse(m.matches("sel"));
    assertFalse(m.matches("truncated"));
  }

  @Test
  void matchesPrefixSharedKeywords() {
    ReggieMatcher m = Reggie.compile(PREFIX_SHARED);
    assertTrue(m.matches("pre"));
    assertTrue(m.matches("prefix"));
    assertTrue(m.matches("preview"));
    assertFalse(m.matches("pref")); // not a keyword
    assertFalse(m.matches("previeww"));
  }

  @Test
  void matchesCaseSensitive() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertFalse(m.matches("FOO"));
    assertFalse(m.matches("Foo"));
    assertFalse(m.matches("BAR"));
  }

  // ── find() ────────────────────────────────────────────────────────────────

  @Test
  void findKeywordAtStart() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertTrue(m.find("foo and more"));
  }

  @Test
  void findKeywordInMiddle() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertTrue(m.find("the bar is here"));
  }

  @Test
  void findKeywordAtEnd() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertTrue(m.find("end with quux"));
  }

  @Test
  void findNoKeywordInString() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertFalse(m.find("nothing relevant here"));
  }

  @Test
  void findInEmptyString() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertFalse(m.find(""));
  }

  @Test
  void findExactMatch() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertTrue(m.find("foo"));
  }

  @Test
  void findWithPrefixSharedPattern() {
    ReggieMatcher m = Reggie.compile(PREFIX_SHARED);
    assertTrue(m.find("I prefer Java"));
    assertTrue(m.find("show preview now"));
    assertFalse(m.find("no match here"));
  }

  // ── findFrom() ────────────────────────────────────────────────────────────

  @Test
  void findFromSkipsPrefixMatch() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    // "foo" at index 0, "bar" at index 4
    assertEquals(0, m.findFrom("foo bar", 0));
    assertEquals(4, m.findFrom("foo bar", 1)); // skip past "foo", find "bar"
  }

  @Test
  void findFromBeyondEnd() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertEquals(-1, m.findFrom("foo", 10));
  }

  @Test
  void findFromAtExactPosition() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertEquals(4, m.findFrom("xxx bar", 4));
  }

  @Test
  void findFromNoMatch() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertEquals(-1, m.findFrom("nothing here", 0));
  }

  // ── matchesBounded() ──────────────────────────────────────────────────────

  @Test
  void matchesBoundedExactSubstring() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertTrue(m.matchesBounded("prefoo", 3, 6)); // "foo"
    assertTrue(m.matchesBounded("xbarx", 1, 4)); // "bar"
  }

  @Test
  void matchesBoundedPartialKeyword() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    assertFalse(m.matchesBounded("foooo", 0, 4)); // "fooo" not a keyword
  }

  // ── Large alternation (stress trie branching) ─────────────────────────────

  @Test
  void largeAlternationMatches() {
    // 10 keywords to stress-test the trie branching logic
    ReggieMatcher m =
        Reggie.compile("alpha|bravo|charlie|delta|echo|foxtrot|golf|hotel|india|juliet");
    assertTrue(m.matches("alpha"));
    assertTrue(m.matches("juliet"));
    assertTrue(m.matches("foxtrot"));
    assertFalse(m.matches("kilo"));
  }

  @Test
  void largeAlternationFind() {
    ReggieMatcher m =
        Reggie.compile("alpha|bravo|charlie|delta|echo|foxtrot|golf|hotel|india|juliet");
    assertTrue(m.find("the echo was loud"));
    assertTrue(m.find("golf is a sport"));
    assertFalse(m.find("no match here"));
  }

  // ── Single-char vs multi-char keywords ────────────────────────────────────

  @Test
  void keywordsOfVaryingLengths() {
    // min=1, max=5 — exercises length-switch path in generated code
    ReggieMatcher m = Reggie.compile("a|bb|ccc|dddd|eeeee|fffff");
    assertTrue(m.matches("a"));
    assertTrue(m.matches("bb"));
    assertTrue(m.matches("eeeee"));
    assertFalse(m.matches("b"));
    assertFalse(m.matches("eeeeee"));
  }

  @Test
  void findBoundsFromReturnsPosition() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom("prefix foo suffix", 0, bounds));
    assertEquals(7, bounds[0]);
    assertEquals(10, bounds[1]);
  }

  @Test
  void findBoundsFromNoMatch() {
    ReggieMatcher m = Reggie.compile(FIVE_KW);
    int[] bounds = new int[2];
    assertFalse(m.findBoundsFrom("nothing", 0, bounds));
  }
}
