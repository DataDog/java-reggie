/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.datadoghq.reggie.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import org.junit.jupiter.api.Test;

/**
 * Pins the JDK-flag compatibility contract: CASE_INSENSITIVE maps for pure-ASCII patterns (where
 * both engines fold exactly the ASCII alphabet) and is rejected otherwise, because Reggie's
 * {@code (?i)} also folds non-ASCII pattern letters while the JDK (without UNICODE_CASE) does
 * not. Input-side non-ASCII characters do not diverge (verified for İ/ı/K/ſ).
 */
class JdkPatternCompatibilityTest {

  @Test
  void mapsMultilineDotallLiteral() {
    assertEquals(
        ReggieFlags.MULTILINE | ReggieFlags.DOTALL,
        JdkPatternCompatibility.toReggieFlags(
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.DOTALL));
    assertEquals(
        ReggieFlags.LITERAL,
        JdkPatternCompatibility.toReggieFlags(java.util.regex.Pattern.LITERAL));
    assertEquals(ReggieFlags.NONE, JdkPatternCompatibility.toReggieFlags(0));
  }

  @Test
  void caseInsensitiveMapsForAsciiPatterns() {
    assertEquals(
        ReggieFlags.CASE_INSENSITIVE,
        JdkPatternCompatibility.toReggieFlags(
            "abc", java.util.regex.Pattern.CASE_INSENSITIVE));
    assertEquals(
        ReggieFlags.CASE_INSENSITIVE | ReggieFlags.MULTILINE,
        JdkPatternCompatibility.toReggieFlags(
            "^a$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE));
    // mapped flags compile and behave like the JDK flag for ASCII input
    int flags =
        assertDoesNotThrow(
            () ->
                JdkPatternCompatibility.toReggieFlags(
                    "abc", java.util.regex.Pattern.CASE_INSENSITIVE));
    var rm = Reggie.compile("abc", flags);
    for (String s : new String[] {"abc", "ABC", "aBc"}) {
      assertEquals(
          java.util.regex.Pattern.compile("abc", java.util.regex.Pattern.CASE_INSENSITIVE)
              .matcher(s)
              .matches(),
          rm.matches(s));
    }
  }

  @Test
  void caseInsensitiveRejectedForNonAsciiPatterns() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                JdkPatternCompatibility.toReggieFlags(
                    "\u00e9", java.util.regex.Pattern.CASE_INSENSITIVE));
    assertTrue(
        ex.getMessage().contains("(?i)"),
        "error must point consumers at the inline-modifier migration path");
    // and for the pattern-unaware overload
    assertThrows(
        IllegalArgumentException.class,
        () -> JdkPatternCompatibility.toReggieFlags(java.util.regex.Pattern.CASE_INSENSITIVE));
  }

  @Test
  void caseInsensitiveRejectedForLiteralCombination() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JdkPatternCompatibility.toReggieFlags(
                "abc",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.LITERAL));
  }

  @Test
  void foldsAgreeForAsciiPatternsOnAdversarialInputs() {
    // The empirical basis for the ASCII-pattern mapping: identical results including non-ASCII
    // input probes that lower-case to ASCII letters (İ U+0130, ı U+0131, K U+212A, ſ U+017F).
    String[][] cases = {
      {"i", "\u0130"}, {"i", "\u0131"}, {"k", "\u212A"}, {"s", "\u017F"},
      {"[a-z]+", "\u212A"}, {"[A-Z]", "a"}, {"gr([a-z])y", "grEy"},
    };
    for (String[] c : cases) {
      String pattern = c[0];
      String input = c[1];
      boolean jdk =
          java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
              .matcher(input)
              .matches();
      boolean reggie = Reggie.compile("(?i)" + pattern).matches(input);
      assertEquals(
          jdk,
          reggie,
          "ASCII pattern " + pattern + " diverged on input U+"
              + Integer.toHexString(input.charAt(0)));
    }
  }

  @Test
  void nonAsciiPatternLetterFoldingDivergenceIsReal() {
    // The documented reason non-ASCII patterns are rejected: Reggie folds é→É, the JDK flag does
    // not. If either side flips, revisit the compat decision.
    assertEquals(false,
        java.util.regex.Pattern.compile("\u00e9", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher("\u00c9")
            .matches());
    assertEquals(true, Reggie.compile("(?i)\u00e9").matches("\u00c9"));
  }
}
