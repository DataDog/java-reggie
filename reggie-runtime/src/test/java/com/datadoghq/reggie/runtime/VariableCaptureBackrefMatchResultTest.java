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
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the rich MatchResult API in VARIABLE_CAPTURE_BACKREF strategy. Verifies that match(),
 * findMatch(), findMatchFrom(), matchesBounded(), and matchBounded() are fully implemented and
 * return correct group spans without falling back to java.util.regex.
 */
class VariableCaptureBackrefMatchResultTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ---- strategy routing assertion ----

  @Test
  void strategyIsVariableCaptureBackref() throws Exception {
    PatternAnalyzer.MatchingStrategy strategy = StrategyCorrectnessMetaTest.routeOf("(.*)=\\1");
    assertEquals(PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF, strategy);
  }

  @Test
  void strategyIsNative() throws Exception {
    PatternAnalyzer.MatchingStrategy strategy = StrategyCorrectnessMetaTest.routeOf("(.*)=\\1");
    assertEquals(
        StrategyJdkClassifier.StrategyJdkClass.NATIVE,
        StrategyJdkClassifier.classifyJdkDependency(strategy));
  }

  // ---- match() ----

  @Test
  void matchReturnsResultForLiteralSeparator() {
    MatchResult r = Reggie.compile("(.*)=\\1").match("abc=abc");
    assertNotNull(r);
    assertEquals("abc=abc", r.group(0));
    assertEquals("abc", r.group(1));
  }

  @Test
  void matchReturnsNullOnMismatch() {
    assertNull(Reggie.compile("(.*)=\\1").match("abc=xyz"));
  }

  @Test
  void matchPlusSeparatorColon() {
    MatchResult r = Reggie.compile("(.+):\\1").match("hello:hello");
    assertNotNull(r);
    assertEquals("hello:hello", r.group(0));
    assertEquals("hello", r.group(1));
  }

  @Test
  void matchEmptyGroupStar() {
    // (.*) can capture empty string: "=" matches with group(1)=""
    MatchResult r = Reggie.compile("(.*)=\\1").match("=");
    assertNotNull(r);
    assertEquals("=", r.group(0));
    assertEquals("", r.group(1));
  }

  @Test
  void matchReturnsNullForNull() {
    assertNull(Reggie.compile("(.*)=\\1").match((String) null));
  }

  // ---- matchesBounded() ----

  @Test
  void matchesBoundedMatchesSubregion() {
    // "xabc=abcx" with bounds [1,8] covers "abc=abc"
    assertTrue(Reggie.compile("(.*)=\\1").matchesBounded("xabc=abcx", 1, 8));
  }

  @Test
  void matchesBoundedRejectsMismatch() {
    assertFalse(Reggie.compile("(.*)=\\1").matchesBounded("xabc=xyzx", 1, 8));
  }

  // ---- matchBounded() ----

  @Test
  void matchBoundedReturnsAdjustedSpans() {
    MatchResult r = Reggie.compile("(.*)=\\1").matchBounded("xabc=abcx", 1, 8);
    assertNotNull(r);
    // group(0) should be "abc=abc" — the full match within the bounded region, relative to full
    // input
    assertEquals("abc=abc", r.group(0));
    assertEquals("abc", r.group(1));
  }

  @Test
  void matchBoundedReturnsNullOnMismatch() {
    assertNull(Reggie.compile("(.*)=\\1").matchBounded("xabc=xyzx", 1, 8));
  }

  // ---- findMatch() ----

  @Test
  void findMatchLocatesFirstMatch() {
    // (.+) requires non-empty — so the first match in "x=abc=abc" from start is "abc=abc"
    // (start=2, group(1)="abc")
    MatchResult r = Reggie.compile("(.+)=\\1").findMatch("x=abc=abc");
    assertNotNull(r);
    assertEquals("abc", r.group(1));
  }

  @Test
  void findMatchReturnsNullWhenNoMatch() {
    assertNull(Reggie.compile("(.+)=\\1").findMatch("abc=xyz"));
  }

  @Test
  void findMatchPlusSeparator() {
    MatchResult r = Reggie.compile("(.+):\\1").findMatch("prefix hello:hello suffix");
    assertNotNull(r);
    assertEquals("hello", r.group(1));
  }

  // ---- findMatchFrom() ----

  @Test
  void findMatchFromStartsAtOffset() {
    // "ab=ab foo=foo" — start at 6 (past "ab=ab ") should find "foo=foo"
    MatchResult r = Reggie.compile("(.+)=\\1").findMatchFrom("ab=ab foo=foo", 6);
    assertNotNull(r);
    assertEquals("foo", r.group(1));
  }

  @Test
  void findMatchFromReturnsNullWhenNoMatchAfterOffset() {
    assertNull(Reggie.compile("(.+)=\\1").findMatchFrom("abc=abc xyz", 4));
  }

  @Test
  void findMatchFromZeroSameAsFindMatch() {
    ReggieMatcher m = Reggie.compile("(.+)-\\1");
    MatchResult r1 = m.findMatch("foo-foo");
    MatchResult r2 = m.findMatchFrom("foo-foo", 0);
    assertNotNull(r1);
    assertNotNull(r2);
    assertEquals(r1.group(0), r2.group(0));
    assertEquals(r1.group(1), r2.group(1));
  }

  // ---- digit separator ----

  @Test
  void matchWithDigitSeparator() {
    MatchResult r = Reggie.compile("(.*)\\d+\\1").match("abc123abc");
    assertNotNull(r);
    assertEquals("abc123abc", r.group(0));
    assertEquals("abc", r.group(1));
  }

  @Test
  void findMatchWithDigitSeparator() {
    MatchResult r = Reggie.compile("(.*)\\d+\\1").findMatch("x abc123abc y");
    assertNotNull(r);
    assertEquals("abc", r.group(1));
  }
}
