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
 * <p>These tests verify that Reggie produces the same results as java.util.regex, and that after
 * the fallback condition is removed, they are handled natively (not via JDK fallback).
 */
class AnchorInQuantifierTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ---- Helper: get JDK result for a find operation ----
  private static boolean jdkFind(String pattern, String input) {
    return Pattern.compile(pattern).matcher(input).find();
  }

  private static boolean jdkMatches(String pattern, String input) {
    return Pattern.compile(pattern).matcher(input).matches();
  }

  // ---- Step 1: confirm native handling (fallback removed) ----

  @Test
  void dollarTwoHandledNatively() {
    // ${2} must now be handled natively — the anchor-in-quantifier fallback has been removed
    ReggieMatcher m = Reggie.compile("${2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "${2} must be handled natively (anchor-in-quantifier fallback removed)");
  }

  // ---- Step 2: semantic correctness tests — these verify Reggie matches JDK ----
  // These tests should FAIL before the fix (because currently they just check JDK fallback status)
  // and PASS after the fix (native engine with correct semantics).

  @Test
  void dollarTwoMatchesNativelyAfterFix() {
    // After removing the fallback, ${2} must be handled natively (not via JDK)
    ReggieMatcher m = Reggie.compile("${2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "${2} should be handled natively after the anchor-in-quantifier fallback is removed");
    // Semantically: ${2} ≡ $ — matches end of input (or before final newline in multiline)
    assertTrue(m.matches(""), "${2} must match empty string ($ at position 0 ≡ end)");
    assertTrue(m.find("hello"), "${2} must find a match at end of 'hello'");
    assertEquals(jdkMatches("${2}", ""), m.matches(""), "must match JDK for empty string");
    assertEquals(jdkFind("${2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
  }

  @Test
  void dollarZeroToTwo_nativeAfterFix() {
    // ${0,2}: zero repetitions always succeed → matches everywhere
    ReggieMatcher m = Reggie.compile("${0,2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "${0,2} should be handled natively after the fix");
    assertEquals(jdkFind("${0,2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkFind("${0,2}", ""), m.find(""), "must match JDK for empty string");
    assertEquals(jdkMatches("${0,2}", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void dollarPlus_nativeAfterFix() {
    // $+: one or more repetitions of $ — semantically same as $
    ReggieMatcher m = Reggie.compile("$+");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "$+ should be handled natively after the fix");
    assertEquals(jdkFind("$+", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("$+", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void dollarStar_nativeAfterFix() {
    // $*: zero or more repetitions of $ — semantically matches everywhere
    ReggieMatcher m = Reggie.compile("$*");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "$* should be handled natively after the fix");
    assertEquals(jdkFind("$*", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("$*", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void dollarQuestion_nativeAfterFix() {
    // $?: zero or one repetition of $ — semantically matches everywhere
    ReggieMatcher m = Reggie.compile("$?");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "$? should be handled natively after the fix");
    assertEquals(jdkFind("$?", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("$?", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void caretTwo_nativeAfterFix() {
    // ^{2}: ^ repeated twice — semantically same as ^
    ReggieMatcher m = Reggie.compile("^{2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "^{2} should be handled natively after the fix");
    assertEquals(jdkFind("^{2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("^{2}", ""), m.matches(""), "must match JDK matches() for empty");
  }

  @Test
  void stringEndTwo_nativeAfterFix() {
    // \\z{2}: \z repeated twice — semantically same as \z
    ReggieMatcher m = Reggie.compile("\\z{2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "\\z{2} should be handled natively after the fix");
    assertEquals(jdkFind("\\z{2}", "hello"), m.find("hello"), "must match JDK for 'hello'");
    assertEquals(jdkMatches("\\z{2}", ""), m.matches(""), "must match JDK matches() for empty");
  }

  // ---- Step 3: combined anchor-in-quantifier with real content ----

  @Test
  void anchorInQuantifierWithSurroundingContent_nativeAfterFix() {
    // Pattern: hello${2} — "hello" followed by $$ (end assertion twice)
    ReggieMatcher m = Reggie.compile("hello${2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "hello${2} should be handled natively after the fix");
    assertEquals(
        jdkMatches("hello${2}", "hello"), m.matches("hello"), "must match JDK for 'hello'");
    assertEquals(
        jdkMatches("hello${2}", "hello world"),
        m.matches("hello world"),
        "must match JDK for 'hello world'");
  }
}
