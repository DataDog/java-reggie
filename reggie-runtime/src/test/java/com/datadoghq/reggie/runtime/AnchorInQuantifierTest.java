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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for anchor-in-quantifier patterns (e.g. ${2}, $+, $*, $?, ^{2}, \z{2}).
 *
 * <p>Anchors are zero-width assertions. Repeating them is semantically redundant:
 *
 * <ul>
 *   <li>${n} for n ≥ 1 is equivalent to $ (asserting end-of-line at the same position n times)
 *   <li>${0,n}, $*, $?: always vacuously true (zero repetitions always succeed)
 * </ul>
 *
 * <p>These tests verify that Reggie produces the same results as java.util.regex. Currently these
 * patterns are routed to JDK fallback via the hasAnchorInQuantifier guard in
 * FallbackPatternDetector.
 */
class AnchorInQuantifierTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static boolean jdkFind(String pattern, String input) {
    return Pattern.compile(pattern).matcher(input).find();
  }

  private static boolean jdkMatches(String pattern, String input) {
    return Pattern.compile(pattern).matcher(input).matches();
  }

  @Test
  void dollarTwoHandledNatively() {
    // ${2} is routed to JDK fallback via the hasAnchorInQuantifier guard
    ReggieMatcher m = Reggie.compile("${2}");
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "${2} must be routed to JDK fallback (anchor-in-quantifier guard active)");
  }

  @Test
  void dollarTwoMatchesNativelyAfterFix() {
    // ${2} is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("${2}");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "${2} must be routed to JDK fallback");
    assertEquals(jdkMatches("${2}", ""), m.matches(""), "must match JDK for empty string");
    assertEquals(jdkFind("${2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
  }

  @Test
  void dollarZeroToTwo_nativeAfterFix() {
    // ${0,2} is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("${0,2}");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "${0,2} must be routed to JDK fallback");
    assertEquals(jdkFind("${0,2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkFind("${0,2}", ""), m.find(""), "must match JDK for empty string");
    assertEquals(jdkMatches("${0,2}", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void dollarPlus_nativeAfterFix() {
    // $+ is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("$+");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "$+ must be routed to JDK fallback");
    assertEquals(jdkFind("$+", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("$+", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void dollarStar_nativeAfterFix() {
    // $* is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("$*");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "$* must be routed to JDK fallback");
    assertEquals(jdkFind("$*", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("$*", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void dollarQuestion_nativeAfterFix() {
    // $? is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("$?");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "$? must be routed to JDK fallback");
    assertEquals(jdkFind("$?", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("$?", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void caretTwo_nativeAfterFix() {
    // ^{2} is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("^{2}");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "^{2} must be routed to JDK fallback");
    assertEquals(jdkFind("^{2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("^{2}", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void stringEndTwo_nativeAfterFix() {
    // \z{2} is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("\\z{2}");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "\\z{2} must be routed to JDK fallback");
    assertEquals(jdkFind("\\z{2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("\\z{2}", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void anchorInQuantifierWithSurroundingContent_nativeAfterFix() {
    // hello${2} is routed to JDK fallback — verify it agrees with JDK
    ReggieMatcher m = Reggie.compile("hello${2}");
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "hello${2} must be routed to JDK fallback (anchor-in-quantifier guard active)");
    assertEquals(
        jdkMatches("hello${2}", "hello"), m.matches("hello"), "must match JDK for 'hello'");
    assertEquals(
        jdkMatches("hello${2}", "hello world"),
        m.matches("hello world"),
        "must match JDK for 'hello world'");
  }
}
