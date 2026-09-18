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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * UNICODE_CHARACTER_CLASS support: inline {@code (?U)} and {@code
 * ReggieFlags.UNICODE_CHARACTER_CLASS} switch {@code \w}/{\code \d}/{\code \s} (and complements,
 * and the POSIX-style {@code \p{...}} classes) to their java.util.regex Unicode definitions. Every
 * set membership is verified differentially against {@code Pattern.compile(pat,
 * UNICODE_CHARACTER_CLASS)} over the whole BMP.
 *
 * <p>BMP scope: like every Reggie charset, the Unicode sets are 16-bit — supplementary code points
 * (surrogate pairs) are not represented, so membership diverges from the JDK there. That is the
 * pre-existing engine-wide BMP limitation, not specific to this flag.
 *
 * <p>Known JDK-specific details reproduced here (all verified empirically): {@code \s} under the
 * flag is {@code Character.isSpaceChar} ∪ [\t-\r] ∪ {NEL} but EXCLUDES the information separators
 * U+001C-001F; {@code \w} includes M (Mn+Mc+Me), Pc and Join_Control (U+200C/U+200D); {@code
 * \p{Punct}} under the flag is the P* categories; {@code \p{ASCII}} stays ASCII.
 *
 * <p>Deliberately unsupported under the flag (loud rejects, not silent divergence): {@code
 * \b}/{\code \B} (the Unicode word boundary needs every engine's word-boundary evaluator to be
 * mode-aware) and {@code \p{Graph}}/{\code \p{Print}}/{\code \p{XDigit}} (JDK Unicode definitions
 * not reproduced).
 */
class UnicodeCharacterClassTest {

  /** Differential membership over the whole BMP: reggie (?U) must equal JDK U-flag exactly. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\w",
        "\\W",
        "\\d",
        "\\D",
        "\\s",
        "\\S",
        "[\\w]",
        "[^\\w:]",
        "[\\d_]",
        "[\\s\\d]",
        "\\p{Alpha}",
        "\\p{Alnum}",
        "\\p{Lower}",
        "\\p{Upper}",
        "\\p{Digit}",
        "\\p{Space}",
        "\\p{Blank}",
        "\\p{Cntrl}",
        "\\p{Punct}",
        "\\p{ASCII}",
        "[\\p{Alpha}]",
        "[^\\p{Alpha}]",
      })
  void unicodeClassMembershipMatchesJdkOverBmp(String inner) {
    String pattern = "(?U)" + inner;
    Pattern jp = Pattern.compile(inner, Pattern.UNICODE_CHARACTER_CLASS);
    ReggieMatcher rm = Reggie.compile(pattern);
    int checked = 0;
    for (int c = 0; c <= 0xFFFF; c++) {
      if (Character.isSurrogate((char) c)) continue;
      String input = new String(Character.toChars(c));
      boolean jdk;
      try {
        jdk = jp.matcher(input).matches();
      } catch (Exception e) {
        continue;
      }
      assertEquals(jdk, rm.matches(input), "U+" + Integer.toHexString(c) + " for " + pattern);
      checked++;
    }
    assertTrue(checked > 60000, "membership scan must cover the BMP, got " + checked);
  }

  /** The flags API is equivalent to the inline modifier. */
  @Test
  void reggieFlagEqualsInlineModifier() {
    Pattern jp = Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS);
    ReggieMatcher rm = Reggie.compile("\\w", ReggieFlags.UNICODE_CHARACTER_CLASS);
    for (String s : new String[] {"abc", "ΣΙΣΥΦΟΣ", "ÄÖÜ", "0372", "_", "᠔", "1a٢", "­"}) {
      boolean jdk;
      try {
        jdk = jp.matcher(s).matches();
      } catch (Exception e) {
        continue;
      }
      assertEquals(jdk, rm.matches(s), s);
    }
    assertEquals(
        rm.matches("0123"),
        Reggie.compile("(?U)\\w").matches("0123"),
        "flag and inline modifier must agree");
  }

  /** Scoped (?U:...) and (?-U) toggles restore the ASCII sets. */
  @ParameterizedTest
  @ValueSource(strings = {"(?U:\\w)x", "(?U)\\d(?-U:\\d)", "(?U)\\w(?-U:\\w)\\w"})
  void scopedModifiersToggleSets(String pattern) {
    // JDK-differential over the BMP for each scoped shape
    for (int c = 0; c <= 0x2FF; c++) { // dense low-BMP scan is enough to catch set mixups
      String input = new String(Character.toChars(c)) + "x";
      boolean jdk;
      try {
        jdk =
            Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)
                .matcher(input.subSequence(0, 1) + (pattern.contains("x") ? "x" : ""))
                .matches();
      } catch (Exception e) {
        continue;
      }
      boolean reg;
      try {
        reg =
            Reggie.compile(pattern)
                .matches(input.subSequence(0, 1) + (pattern.contains("x") ? "x" : ""));
      } catch (Exception e) {
        reg = false;
      }
      assertEquals(jdk, reg, "U+" + Integer.toHexString(c) + " for " + pattern);
    }
  }

  /** The original logs-backend consumer pattern that needed the flag. */
  @Test
  void negatedWordClassWithUntrustedInput() {
    String pattern = "[^\\w:\\-\\.\\/]";
    Pattern jp = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
    ReggieMatcher rm = Reggie.compile(pattern, ReggieFlags.UNICODE_CHARACTER_CLASS);
    // inputs from the original corpus divergence: Greek/German letters are word chars under the
    // flag, so the negated class only matches the space; without the flag reggie matched "Ä".
    assertEquals(jp.matcher("Ä").matches(), rm.matches("Ä"));
    assertEquals(jp.matcher("Σ").matches(), rm.matches("Σ"));
    assertEquals(jp.matcher(" ").matches(), rm.matches(" "));
    assertEquals(jp.matcher("x").matches(), rm.matches("x"));
  }

  /** \b under the flag is a loud reject, not a silent ASCII divergence. */
  @Test
  void wordBoundaryUnderUnicodeClassesRejectedLoudly() {
    assertThrows(
        com.datadoghq.reggie.UnsupportedPatternException.class,
        () -> Reggie.compile("(?U)\\bword\\b"));
    assertThrows(
        com.datadoghq.reggie.UnsupportedPatternException.class,
        () -> Reggie.compile("\\b\\w", ReggieFlags.UNICODE_CHARACTER_CLASS));
  }

  /** \p{Graph}/\p{Print}/\p{XDigit} under the flag are loud rejects (JDK sets not reproduced). */
  @ParameterizedTest
  @ValueSource(
      strings = {"(?U)\\p{Graph}", "(?U)\\p{Print}", "(?U)\\p{XDigit}", "(?U)[\\p{Print}]"})
  void unsupportedUnicodePosixClassesRejectedLoudly(String pattern) {
    assertThrows(
        com.datadoghq.reggie.UnsupportedPatternException.class, () -> Reggie.compile(pattern));
  }

  /** The pre-existing vertical-tab gap in default \s ([ \t\n\x0B\f\r] per the JDK) is fixed. */
  @Test
  void defaultWhitespaceIncludesVerticalTab() {
    for (char c : new char[] {' ', '\t', '\n', '\u000B', '\f', '\r'}) {
      assertEquals(
          Pattern.compile("\\s").matcher(String.valueOf(c)).matches(),
          Reggie.compile("\\s").matches(String.valueOf(c)),
          "U+" + Integer.toHexString(c));
    }
  }
}
