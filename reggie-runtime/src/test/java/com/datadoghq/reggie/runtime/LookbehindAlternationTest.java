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
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Acceptance tests for REQ-DataDog-java-reggie-30: lookbehind alternation correctness. */
public class LookbehindAlternationTest {

  // --- Positive lookbehind alternation (?<=a|b)c ---

  @Test
  @Tag("acceptance")
  public void positiveLookbehindAlternation_firstAlternativeMatches() {
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertTrue(m.find("ac"), "'ac': 'c' preceded by 'a' must match");
  }

  @Test
  @Tag("acceptance")
  public void positiveLookbehindAlternation_secondAlternativeMatches() {
    // Core reproduction from the bug report: only first alternative was evaluated
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertTrue(
        m.find("bc"), "'bc': 'c' preceded by 'b' must match — was incorrectly false before fix");
  }

  @Test
  @Tag("acceptance")
  public void positiveLookbehindAlternation_noMatchWhenPrecursorAbsent() {
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertFalse(m.find("xc"), "'xc': 'c' not preceded by 'a' or 'b' must not match");
    assertFalse(m.find("xca"), "'xca': 'c' at index 1 is preceded by 'x', not 'a' or 'b'");
  }

  @Test
  @Tag("acceptance")
  public void positiveLookbehindAlternation_fullReproductionCase() {
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertTrue(m.find("ac"), "ac  => true  (first alternative)");
    assertTrue(m.find("bc"), "bc  => true  (second alternative, was false before fix)");
    assertFalse(m.find("xc"), "xc  => false (no matching predecessor)");
  }

  // --- Negative lookbehind alternation (?<!a|b)c ---

  @Test
  @Tag("acceptance")
  public void negativeLookbehindAlternation_firstAlternativeSuppressesMatch() {
    ReggieMatcher m = Reggie.compile("(?<!a|b)c");
    assertFalse(m.find("ac"), "'ac': 'c' preceded by 'a' must NOT match negative lookbehind");
  }

  @Test
  @Tag("acceptance")
  public void negativeLookbehindAlternation_secondAlternativeSuppressesMatch() {
    ReggieMatcher m = Reggie.compile("(?<!a|b)c");
    assertFalse(m.find("bc"), "'bc': 'c' preceded by 'b' must NOT match negative lookbehind");
  }

  @Test
  @Tag("acceptance")
  public void negativeLookbehindAlternation_matchWhenPrecursorAbsent() {
    ReggieMatcher m = Reggie.compile("(?<!a|b)c");
    assertTrue(m.find("xc"), "'xc': 'c' not preceded by 'a' or 'b' must match negative lookbehind");
  }

  @Test
  @Tag("acceptance")
  public void negativeLookbehindAlternation_fullCoverage() {
    ReggieMatcher m = Reggie.compile("(?<!a|b)c");
    assertFalse(m.find("ac"), "ac  => false (first alternative blocks)");
    assertFalse(m.find("bc"), "bc  => false (second alternative blocks)");
    assertTrue(m.find("xc"), "xc  => true  (no alternative matches, assertion passes)");
  }

  // --- Multi-character equal-width lookbehind alternation (?<=ab|cd)x ---

  @Test
  @Tag("acceptance")
  public void multiCharPositiveLookbehind_firstAlternativeMatches() {
    ReggieMatcher m = Reggie.compile("(?<=ab|cd)x");
    assertTrue(m.find("abx"), "'abx': 'x' preceded by 'ab' must match");
  }

  @Test
  @Tag("acceptance")
  public void multiCharPositiveLookbehind_secondAlternativeMatches() {
    ReggieMatcher m = Reggie.compile("(?<=ab|cd)x");
    assertTrue(m.find("cdx"), "'cdx': 'x' preceded by 'cd' must match");
  }

  @Test
  @Tag("acceptance")
  public void multiCharPositiveLookbehind_noMatchForWrongPrefix() {
    ReggieMatcher m = Reggie.compile("(?<=ab|cd)x");
    assertFalse(m.find("efx"), "'efx': 'x' not preceded by 'ab' or 'cd' must not match");
    assertFalse(m.find("acx"), "'acx': partial overlap must not match");
    assertFalse(m.find("xbx"), "'xbx': 'x' preceded by 'xb', not 'ab' or 'cd'");
  }

  @Test
  @Tag("acceptance")
  public void multiCharPositiveLookbehind_bothAlternativesInString() {
    ReggieMatcher m = Reggie.compile("(?<=ab|cd)x");
    List<MatchResult> matches = m.findAll("abx cdx");
    assertEquals(2, matches.size(), "Both 'abx' and 'cdx' occurrences must be found");
  }

  @Test
  @Tag("acceptance")
  public void multiCharNegativeLookbehind_bothAlternativesSuppress() {
    ReggieMatcher m = Reggie.compile("(?<!ab|cd)x");
    assertFalse(m.find("abx"), "'abx': 'x' preceded by 'ab' must not match negative lookbehind");
    assertFalse(m.find("cdx"), "'cdx': 'x' preceded by 'cd' must not match negative lookbehind");
    assertTrue(m.find("efx"), "'efx': 'x' not preceded by 'ab' or 'cd' must match");
  }

  // --- Native engine usage assertions ---

  @Test
  @Tag("acceptance")
  public void positiveLookbehindAlternation_usesNativeEngine() {
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "(?<=a|b)c must be handled by the native Reggie engine, not java.util.regex fallback");
  }

  @Test
  @Tag("acceptance")
  public void negativeLookbehindAlternation_usesNativeEngine() {
    ReggieMatcher m = Reggie.compile("(?<!a|b)c");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "(?<!a|b)c must be handled by the native Reggie engine, not java.util.regex fallback");
  }

  @Test
  @Tag("acceptance")
  public void multiCharLookbehindAlternation_usesNativeEngine() {
    ReggieMatcher m = Reggie.compile("(?<=ab|cd)x");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "(?<=ab|cd)x must be handled by the native Reggie engine, not java.util.regex fallback");
  }
}
