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
 * Extended tests for GREEDY_BACKTRACK strategy: covers QUANTIFIED_CHAR_CLASS,
 * WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS, CHAR_CLASS, ANCHORED suffix types and various greedyMinCount
 * / charset combinations.
 */
class GreedyBacktrackExtendedTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── QUANTIFIED_CHAR_CLASS suffix: (.*)(\\d+) ─────────────────────────────

  @Test
  void quantifiedCharClassSuffixFind() {
    ReggieMatcher m = Reggie.compile("(.*)([0-9]+)");
    assertTrue(m.find("abc123"));
    assertTrue(m.find("123"));
    assertTrue(m.find("x1"));
    assertFalse(m.find("abc"));
  }

  @Test
  void quantifiedCharClassSuffixMatches() {
    ReggieMatcher m = Reggie.compile("(.*)([0-9]+)");
    assertTrue(m.matches("abc123"));
    assertTrue(m.matches("42"));
    assertFalse(m.matches("abc"));
  }

  @Test
  void quantifiedCharClassSuffixFindFrom() {
    ReggieMatcher m = Reggie.compile("(.*)([0-9]+)");
    int pos = m.findFrom("xxabc123yy", 0);
    assertTrue(pos >= 0);
    assertEquals(-1, m.findFrom("abcdef", 0));
  }

  @Test
  void quantifiedCharClassSuffixMatchResult() {
    ReggieMatcher m = Reggie.compile("(.*)([0-9]+)");
    MatchResult r = m.findMatch("prefix123");
    assertNotNull(r);
    assertEquals(2, r.groupCount());
  }

  @Test
  void quantifiedPlusMinCountOne() {
    // (.+)(\d+) — greedyMinCount=1
    ReggieMatcher m = Reggie.compile("(.+)([0-9]+)");
    assertTrue(m.find("ab12"));
    assertFalse(m.find("1")); // need at least 1 char before digit
  }

  @Test
  void quantifiedStarSuffixMinZero() {
    // (.*)(\\d*) — greedyMinCount=0 for suffix
    ReggieMatcher m = Reggie.compile("(.*)(\\d*)");
    assertTrue(m.find("abc123"));
    assertTrue(m.find("abc")); // suffix can be empty
  }

  @Test
  void quantifiedAlphaSuffix() {
    ReggieMatcher m = Reggie.compile("(.*)([a-z]+)");
    assertTrue(m.find("123abc"));
    assertTrue(m.find("abc"));
    assertFalse(m.find("123"));
  }

  // ── WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS suffix: (.*)\b(\\d+) ──────────────

  @Test
  void wordBoundaryQuantifiedSuffixFind() {
    ReggieMatcher m = Reggie.compile("(.*)\\b([0-9]+)");
    assertTrue(m.find("foo 123"));
    assertTrue(m.find("abc 42 xyz"));
    assertFalse(m.find("abc"));
  }

  @Test
  void wordBoundaryQuantifiedSuffixMatches() {
    ReggieMatcher m = Reggie.compile("(.*)\\b([0-9]+)");
    assertTrue(m.matches("text 99"));
    assertFalse(m.matches("text"));
  }

  @Test
  void wordBoundaryQuantifiedMatchResult() {
    ReggieMatcher m = Reggie.compile("(.*)\\b([0-9]+)");
    MatchResult r = m.findMatch("prefix 42");
    assertNotNull(r);
    assertEquals(2, r.groupCount());
    assertEquals("42", r.group(2));
  }

  @Test
  void wordBoundaryStarSuffix() {
    ReggieMatcher m = Reggie.compile("(.*)\\b([0-9]*)");
    assertTrue(m.find("foo 123"));
  }

  // ── Prefixed patterns ────────────────────────────────────────────────────

  @Test
  void prefixedGreedyBacktrack() {
    ReggieMatcher m = Reggie.compile("start(.*)end");
    assertTrue(m.find("start middle end"));
    assertTrue(m.find("startend"));
    assertFalse(m.find("no match"));
  }

  @Test
  void prefixedWithDigitSuffix() {
    ReggieMatcher m = Reggie.compile("v(.*)-(\\d+)");
    assertTrue(m.find("v1.2.3-42"));
    assertTrue(m.find("vrelease-1"));
    assertFalse(m.find("v1.2.3"));
  }

  // ── matchesBounded paths ─────────────────────────────────────────────────

  @Test
  void greedyBacktrackMatchesBounded() {
    ReggieMatcher m = Reggie.compile("(.*)bar");
    assertTrue(m.matchesBounded("prefixbarmore", 0, 9));
    assertFalse(m.matchesBounded("prefixbarmore", 0, 6));
  }

  // ── findBoundsFrom ───────────────────────────────────────────────────────

  @Test
  void greedyBacktrackFindBoundsFrom() {
    ReggieMatcher m = Reggie.compile("(.*)bar");
    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom("foobar", 0, bounds));
    assertEquals("foobar", "foobar".substring(bounds[0], bounds[1]));
  }

  // ── Long suffix (3+ chars, LITERAL) ──────────────────────────────────────

  @Test
  void longLiteralSuffix() {
    ReggieMatcher m = Reggie.compile("(.*)hello");
    assertTrue(m.find("say hello"));
    assertTrue(m.find("hello"));
    assertFalse(m.find("say bye"));
  }

  @Test
  void longLiteralSuffixMatches() {
    ReggieMatcher m = Reggie.compile("(.*)hello");
    assertTrue(m.matches("sayhello"));
    assertTrue(m.matches("hello"));
    assertFalse(m.matches("hellx"));
  }

  // ── greedy digit prefix ───────────────────────────────────────────────────

  @Test
  void greedyDigitsOnly() {
    ReggieMatcher m = Reggie.compile("(\\d*)end");
    assertTrue(m.find("123end"));
    assertTrue(m.find("end"));
    assertTrue(m.find("abcend")); // \d*="" at boundary before "end"
  }
}
