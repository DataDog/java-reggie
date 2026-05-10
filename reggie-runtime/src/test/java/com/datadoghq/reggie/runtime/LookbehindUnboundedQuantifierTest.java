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
import com.datadoghq.reggie.ReggieMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance tests for issue #29: unbounded quantifier after positive lookbehind. */
class LookbehindUnboundedQuantifierTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void positiveLookbehindPlusQuantifierMatchesDigitPrefixed() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
    assertTrue(m.find("3abc"), "(?<=\\d)[a-z]+ must find a match in '3abc'");
  }

  @Test
  void positiveLookbehindPlusQuantifierNoMatchWithoutDigit() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
    assertFalse(m.find("abc"), "(?<=\\d)[a-z]+ must NOT match 'abc'");
  }

  @Test
  void positiveLookbehindBoundedQuantifierUnaffected() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]{1,4}");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("abc"));
  }

  @Test
  void positiveLookbehindPlusQuantifierNotFallingBack() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
    assertFalse(m instanceof JavaRegexFallbackMatcher);
  }

  @Test
  void positiveLookbehindStarQuantifierMatches() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]*");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("abc"));
  }

  @Test
  void positiveLookbehindOpenEndedRangeQuantifierMatches() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]{2,}");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("3a"));
    assertFalse(m.find("abc"));
  }

  @Test
  void positiveLookbehindStarQuantifierNotFallingBack() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]*");
    assertFalse(m instanceof JavaRegexFallbackMatcher);
  }

  @Test
  void positiveLookbehindOpenEndedRangeQuantifierNotFallingBack() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]{1,}");
    assertFalse(m instanceof JavaRegexFallbackMatcher);
  }

  @Test
  void positiveLookbehindPlusQuantifierExactBoundary() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
    assertFalse(m.find("3 abc"));
    assertTrue(m.find("x3yz"));
  }

  @Test
  void fallbackDetectorDoesNotFlagLookbehindUnboundedQuantifier() {
    ReggieMatcher mPlus = Reggie.compile("(?<=\\d)[a-z]+");
    ReggieMatcher mStar = Reggie.compile("(?<=\\d)[a-z]*");
    ReggieMatcher mRange = Reggie.compile("(?<=\\d)[a-z]{3,}");
    assertFalse(mPlus instanceof JavaRegexFallbackMatcher);
    assertFalse(mStar instanceof JavaRegexFallbackMatcher);
    assertFalse(mRange instanceof JavaRegexFallbackMatcher);
  }
}
