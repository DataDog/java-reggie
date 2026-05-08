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

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for named group extraction API: group(String), start(String), end(String).
 * Written from spec only — no implementation context.
 */
public class NamedGroupExtractionTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /** T-01: group(String) returns captured value for single named group */
  @Test
  void groupByName_singleNamedGroup_returnsCapture() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<year>\\d{4})");
    MatchResult r = m.match("2024");
    assertNotNull(r);
    assertEquals("2024", r.group("year"));
  }

  /** T-02: group(String) returns captured value for each of multiple named groups */
  @Test
  void groupByName_multipleNamedGroups_returnsEachCapture() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<host>[\\w.]+):(?<port>\\d+)");
    MatchResult r = m.match("example.com:8080");
    assertNotNull(r);
    assertEquals("example.com", r.group("host"));
    assertEquals("8080", r.group("port"));
  }

  /** T-03: group(String) result matches group(int) for same named group */
  @Test
  void groupByName_matchesGroupByIndex() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<year>\\d{4})-(?<month>\\d{2})");
    MatchResult r = m.match("2024-03");
    assertNotNull(r);
    assertEquals(r.group(1), r.group("year"));
    assertEquals(r.group(2), r.group("month"));
  }

  /** T-04: group(String) throws IllegalArgumentException for unknown group name */
  @Test
  void groupByName_unknownName_throwsIllegalArgumentException() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)");
    MatchResult r = m.match("hello");
    assertNotNull(r);
    assertThrows(IllegalArgumentException.class, () -> r.group("nonexistent"));
  }

  /** T-05: group(String) returns null for optional group absent */
  @Test
  void groupByName_optionalGroupAbsent_returnsNull() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<prefix>\\w+)-(?<suffix>\\w+)?");
    MatchResult r = m.match("hello-");
    assertNotNull(r);
    assertEquals("hello", r.group("prefix"));
    assertNull(r.group("suffix"));
  }

  /** T-06: start(String) returns correct start index */
  @Test
  void startByName_returnsCorrectIndex() {
    ReggieMatcher m = RuntimeCompiler.compile("xx(?<target>\\d+)yy");
    MatchResult r = m.match("xx42yy");
    assertNotNull(r);
    assertEquals(2, r.start("target"));
  }

  /** T-07: end(String) returns correct end index */
  @Test
  void endByName_returnsCorrectIndex() {
    ReggieMatcher m = RuntimeCompiler.compile("xx(?<target>\\d+)yy");
    MatchResult r = m.match("xx42yy");
    assertNotNull(r);
    assertEquals(4, r.end("target"));
  }

  /** T-08: start/end by name match start/end by int */
  @Test
  void startEndByName_matchIndexBasedCounterparts() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<a>\\w+)-(?<b>\\d+)");
    MatchResult r = m.match("foo-123");
    assertNotNull(r);
    assertEquals(r.start(1), r.start("a"));
    assertEquals(r.end(1), r.end("a"));
    assertEquals(r.start(2), r.start("b"));
    assertEquals(r.end(2), r.end("b"));
  }

  /** T-09: start(String) throws IllegalArgumentException for unknown name */
  @Test
  void startByName_unknownName_throwsIllegalArgumentException() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)");
    MatchResult r = m.match("hello");
    assertNotNull(r);
    assertThrows(IllegalArgumentException.class, () -> r.start("nonexistent"));
  }

  /** T-10: end(String) throws IllegalArgumentException for unknown name */
  @Test
  void endByName_unknownName_throwsIllegalArgumentException() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)");
    MatchResult r = m.match("hello");
    assertNotNull(r);
    assertThrows(IllegalArgumentException.class, () -> r.end("nonexistent"));
  }

  /** T-11: start(String) returns -1 when optional group absent */
  @Test
  void startByName_optionalGroupAbsent_returnsMinusOne() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<prefix>\\w+)-(?<suffix>\\w+)?");
    MatchResult r = m.match("hello-");
    assertNotNull(r);
    assertEquals(-1, r.start("suffix"));
  }

  /** T-12: end(String) returns -1 when optional group absent */
  @Test
  void endByName_optionalGroupAbsent_returnsMinusOne() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<prefix>\\w+)-(?<suffix>\\w+)?");
    MatchResult r = m.match("hello-");
    assertNotNull(r);
    assertEquals(-1, r.end("suffix"));
  }

  /** T-13: group(String) works via findMatch */
  @Test
  void groupByName_viaFindMatch_returnsCapture() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<num>\\d+)");
    MatchResult r = m.findMatch("prefix 42 suffix");
    assertNotNull(r);
    assertEquals("42", r.group("num"));
  }

  /** T-14: start/end by name correct via findMatch */
  @Test
  void startEndByName_viaFindMatch_correctIndices() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<num>\\d+)");
    MatchResult r = m.findMatch("prefix 42 suffix");
    assertNotNull(r);
    assertEquals(7, r.start("num"));
    assertEquals(9, r.end("num"));
  }

  /** T-15: group(String) works for single-quote syntax (?'name'...) */
  @Test
  void groupByName_singleQuoteSyntax_returnsCapture() {
    ReggieMatcher m = RuntimeCompiler.compile("(?'word'\\w+)");
    MatchResult r = m.match("hello");
    assertNotNull(r);
    assertEquals("hello", r.group("word"));
  }

  /** T-16: group(String) works for nested named groups */
  @Test
  void groupByName_nestedNamedGroups_returnsCorrectValues() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<outer>(?<inner>\\w+)-)");
    MatchResult r = m.match("hello-");
    assertNotNull(r);
    assertEquals("hello-", r.group("outer"));
    assertEquals("hello", r.group("inner"));
  }

  /** T-17: MatchResult interface declares group(String), start(String), end(String) */
  @Test
  void matchResultInterface_declaresThreeNamedGroupMethods() throws NoSuchMethodException {
    Method groupByName = MatchResult.class.getMethod("group", String.class);
    Method startByName = MatchResult.class.getMethod("start", String.class);
    Method endByName = MatchResult.class.getMethod("end", String.class);

    assertNotNull(groupByName);
    assertEquals(String.class, groupByName.getReturnType());
    assertNotNull(startByName);
    assertEquals(int.class, startByName.getReturnType());
    assertNotNull(endByName);
    assertEquals(int.class, endByName.getReturnType());
    // Verify the methods work, not just that they exist
    ReggieMatcher m = RuntimeCompiler.compile("(?<w>\\w+)");
    MatchResult r = m.match("test");
    assertNotNull(r);
    assertEquals("test", r.group("w"));
    assertEquals(0, r.start("w"));
    assertEquals(4, r.end("w"));
  }

  /** T-18: start/end by name return offset-corrected indices via findMatchFrom with offset > 0 */
  @Test
  void startEndByName_viaFindMatchFromWithOffset_returnsOffsetCorrectedIndices() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<num>\\d+)");
    MatchResult r = m.findMatchFrom("abc 42 def", 4);
    assertNotNull(r);
    assertEquals("42", r.group("num"));
    assertEquals(4, r.start("num"));
    assertEquals(6, r.end("num"));
  }

  /** T-19: group(String) works via matchBounded */
  @Test
  void groupByName_viaMatchBounded_returnsCapture() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<num>\\d+)");
    MatchResult r = m.matchBounded("xx42xx", 2, 4);
    assertNotNull(r);
    assertEquals("42", r.group("num"));
  }

  /** T-20: group(String) on numbered-only pattern throws IllegalArgumentException */
  @Test
  void groupByName_numberedOnlyPattern_throwsIllegalArgumentException() {
    ReggieMatcher m = RuntimeCompiler.compile("(\\d+)");
    MatchResult r = m.match("42");
    assertNotNull(r);
    assertThrows(IllegalArgumentException.class, () -> r.group("x"));
    assertThrows(IllegalArgumentException.class, () -> r.start("x"));
    assertThrows(IllegalArgumentException.class, () -> r.end("x"));
  }

  /** T-21: group(null)/start(null)/end(null) throw IllegalArgumentException */
  @Test
  void groupByName_nullName_throwsIllegalArgumentException() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)");
    MatchResult r = m.match("hello");
    assertNotNull(r);
    assertThrows(IllegalArgumentException.class, () -> r.group(null));
    assertThrows(IllegalArgumentException.class, () -> r.start(null));
    assertThrows(IllegalArgumentException.class, () -> r.end(null));
  }
}
