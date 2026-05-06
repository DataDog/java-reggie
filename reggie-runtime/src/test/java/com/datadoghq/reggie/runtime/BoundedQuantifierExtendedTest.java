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
 * Extended tests for SPECIALIZED_BOUNDED_QUANTIFIERS: exercises IPv4, dates, times, hex colors,
 * optional quantifiers, multi-element sequences, and all API methods.
 */
class BoundedQuantifierExtendedTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── IPv4 address ──────────────────────────────────────────────────────────

  @Test
  void ipv4Matches() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.matches("192.168.1.1"));
    assertTrue(m.matches("0.0.0.0"));
    assertTrue(m.matches("255.255.255.255"));
    assertFalse(m.matches("1234.1.1.1"));
    assertFalse(m.matches("1.1.1"));
  }

  @Test
  void ipv4Find() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.find("host: 10.0.0.1 port: 80"));
    assertFalse(m.find("no ip here"));
  }

  @Test
  void ipv4FindFrom() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.findFrom("ip=10.0.0.1", 3) >= 0);
    assertEquals(-1, m.findFrom("10.0.0.1", 8));
  }

  @Test
  void ipv4MatchResult() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    MatchResult r = m.findMatch("10.0.0.1");
    assertNotNull(r);
    assertEquals("10.0.0.1", r.group());
  }

  @Test
  void ipv4TooShort() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertFalse(m.matches("1.1"));
    assertFalse(m.find("1.1"));
  }

  // ── Date: \d{4}-\d{2}-\d{2} ──────────────────────────────────────────────

  @Test
  void datePatternMatches() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    assertTrue(m.matches("2026-05-06"));
    assertFalse(m.matches("26-05-06"));
    assertFalse(m.matches("2026-5-06"));
    assertFalse(m.matches("2026-05-6"));
  }

  @Test
  void datePatternFind() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    assertTrue(m.find("Created: 2026-05-06T12:00:00"));
    assertFalse(m.find("no date"));
  }

  @Test
  void datePatternFindBoundsFrom() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom("on 2026-05-06 at", 3, bounds));
    assertEquals(3, bounds[0]);
    assertEquals(13, bounds[1]);
  }

  // ── Time: \d{2}:\d{2}:\d{2} ──────────────────────────────────────────────

  @Test
  void timePatternMatches() {
    ReggieMatcher m = Reggie.compile("\\d{2}:\\d{2}:\\d{2}");
    assertTrue(m.matches("12:34:56"));
    assertFalse(m.matches("1:34:56"));
    assertFalse(m.matches("12:3:56"));
  }

  @Test
  void timePatternFind() {
    ReggieMatcher m = Reggie.compile("\\d{2}:\\d{2}:\\d{2}");
    assertTrue(m.find("at 12:34:56 UTC"));
    assertFalse(m.find("no time"));
  }

  @Test
  void timeMatchesBounded() {
    ReggieMatcher m = Reggie.compile("\\d{2}:\\d{2}:\\d{2}");
    assertTrue(m.matchesBounded("start 12:34:56 end", 6, 14));
    assertFalse(m.matchesBounded("start 12:34:56 end", 6, 11));
  }

  // ── Hex color: #[0-9a-fA-F]{6} ────────────────────────────────────────────

  @Test
  void hexColorMatches() {
    ReggieMatcher m = Reggie.compile("#[0-9a-fA-F]{6}");
    assertTrue(m.matches("#FF0000"));
    assertTrue(m.matches("#abc123"));
    assertTrue(m.matches("#000000"));
    assertFalse(m.matches("#FF000")); // 5 hex chars
    assertFalse(m.matches("#GG0000")); // G not hex
    assertFalse(m.matches("FF0000")); // no #
  }

  @Test
  void hexColorFind() {
    ReggieMatcher m = Reggie.compile("#[0-9a-fA-F]{6}");
    assertTrue(m.find("color: #FF0000;"));
    assertTrue(m.find("background: #abc123 !important"));
    assertFalse(m.find("no color"));
  }

  @Test
  void hexColorMatchResult() {
    ReggieMatcher m = Reggie.compile("#[0-9a-fA-F]{6}");
    MatchResult r = m.findMatch("color: #FF0000;");
    assertNotNull(r);
    assertEquals("#FF0000", r.group());
  }

  // ── Optional quantifier min=0: [a-z]{0,3} ────────────────────────────────

  @Test
  void optionalQuantifierMin0() {
    ReggieMatcher m = Reggie.compile("[a-z]{0,3}");
    assertTrue(m.matches(""));
    assertTrue(m.matches("a"));
    assertTrue(m.matches("abc"));
    assertFalse(m.matches("abcd")); // 4 chars > max
    assertFalse(m.matches("ABC")); // wrong case
  }

  @Test
  void optionalQuantifierFind() {
    ReggieMatcher m = Reggie.compile("\\d{4}[a-z]{0,2}");
    assertTrue(m.find("1234ab"));
    assertFalse(m.find("abc"));
  }

  // ── Version: \d+\.\d+\.\d+ ────────────────────────────────────────────────

  @Test
  void versionMatches() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.matches("1.2.3"));
    assertTrue(m.matches("10.20.30"));
    assertFalse(m.matches("1.2"));
    assertFalse(m.matches("1.2.3.4"));
  }

  @Test
  void versionFind() {
    ReggieMatcher m = Reggie.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.find("version 1.2.3 released"));
    assertFalse(m.find("no version"));
  }

  // ── Mixed literal prefix: v\d{1,3}\.\d{1,3} ─────────────────────────────

  @Test
  void mixedLiteralPrefixMatches() {
    ReggieMatcher m = Reggie.compile("v\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.matches("v1.0"));
    assertTrue(m.matches("v10.20"));
    assertFalse(m.matches("1.0"));
    assertFalse(m.matches("v.0"));
  }

  @Test
  void mixedLiteralPrefixFind() {
    ReggieMatcher m = Reggie.compile("v\\d{1,3}\\.\\d{1,3}");
    assertTrue(m.find("release v1.0 notes"));
    assertFalse(m.find("no version"));
  }

  // ── matchBounded returning MatchResult ────────────────────────────────────

  @Test
  void matchBoundedResult() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    MatchResult r = m.matchBounded("2026-05-06", 0, 10);
    assertNotNull(r);
    assertEquals("2026-05-06", r.group());
    assertNull(m.matchBounded("2026-05-06", 0, 7));
  }

  // ── Exact quantifier (no range) ───────────────────────────────────────────

  @Test
  void exactQuantifier() {
    ReggieMatcher m = Reggie.compile("\\d{4}");
    assertTrue(m.matches("1234"));
    assertFalse(m.matches("123"));
    assertFalse(m.matches("12345"));
  }

  @Test
  void exactQuantifierFind() {
    ReggieMatcher m = Reggie.compile("\\d{4}");
    assertTrue(m.find("year 2026"));
    assertFalse(m.find("year 26"));
  }

  // ── findMatchFrom ─────────────────────────────────────────────────────────

  @Test
  void findMatchFromOffset() {
    ReggieMatcher m = Reggie.compile("\\d{4}-\\d{2}-\\d{2}");
    MatchResult r = m.findMatchFrom("events: 2025-01-01 and 2026-05-06", 19);
    assertNotNull(r);
    assertEquals("2026-05-06", r.group());
  }
}
