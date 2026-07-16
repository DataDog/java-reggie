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
package com.datadoghq.reggie;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.compat.JdkPatternCompatibility;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReggieCompileFlagsTest {

  @Test
  void caseInsensitiveMatches() {
    assertMatches("abc", ReggieFlags.CASE_INSENSITIVE, "AbC", true);
    assertMatches("\\x61", ReggieFlags.CASE_INSENSITIVE, "A", true);
    assertMatches("\\a", ReggieFlags.CASE_INSENSITIVE, "A", true);
    assertMatches("abc", 0, "AbC", false);
  }

  @Test
  void multilineMatches() {
    assertFinds("^foo$", ReggieFlags.MULTILINE, "before\nfoo\nafter", true);
  }

  @Test
  void dotallMatches() {
    assertFinds("a.b", ReggieFlags.DOTALL, "a\nb", true);
    assertFinds("a.b", 0, "a\nb", false);
  }

  @Test
  void combinedFlagsMatch() {
    int flags = ReggieFlags.CASE_INSENSITIVE | ReggieFlags.MULTILINE | ReggieFlags.DOTALL;
    assertFinds("^a.b$", flags, "before\nA\nb\nafter", true);
  }

  @Test
  void literalQuotesMetacharactersAndEmbeddedQuoteTerminator() {
    int flags = ReggieFlags.LITERAL | ReggieFlags.CASE_INSENSITIVE;
    String source = "a.b(\\E)";

    assertMatches(source, flags, "A.B(\\e)", true);
    assertMatches(source, flags, "aXb(\\E)", false);
    assertMatches("(?i)a.b", ReggieFlags.LITERAL, "(?i)a.b", true);
  }

  @Test
  void cacheSeparatesFlaggedAndUnflaggedPatterns() {
    assertTrue(Reggie.compile("abc", ReggieFlags.CASE_INSENSITIVE).matches("ABC"));
    assertFalse(Reggie.compile("abc").matches("ABC"));
  }

  @Test
  void unsupportedReggieFlagsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> Reggie.compile("abc", 1));
    assertThrows(
        IllegalArgumentException.class, () -> Reggie.compile("abc", Pattern.CASE_INSENSITIVE));
  }

  @Test
  void compatibilityAdapterConvertsSupportedJdkFlags() {
    int jdkFlags = Pattern.MULTILINE | Pattern.DOTALL | Pattern.LITERAL;
    int reggieFlags = JdkPatternCompatibility.toReggieFlags(jdkFlags);
    assertTrue(reggieFlags == (ReggieFlags.MULTILINE | ReggieFlags.DOTALL | ReggieFlags.LITERAL));
    assertTrue(Reggie.compile("abc", reggieFlags).matches("abc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> JdkPatternCompatibility.toReggieFlags(Pattern.COMMENTS));
    assertThrows(
        IllegalArgumentException.class,
        () -> JdkPatternCompatibility.toReggieFlags(Pattern.CASE_INSENSITIVE));
  }

  @Test
  void flagsComposeWithEngineOptionsAndPreserveTheSourcePattern() {
    com.datadoghq.reggie.runtime.ReggieMatcher matcher =
        Reggie.compile("abc", ReggieFlags.CASE_INSENSITIVE, ReggieOptions.DEFAULT);
    assertTrue(matcher.matches("ABC"));
    assertTrue(matcher.pattern().equals("abc"));
  }

  private static void assertMatches(String pattern, int flags, String input, boolean expected) {
    assertTrue(Reggie.compile(pattern, flags).matches(input) == expected);
  }

  private static void assertFinds(String pattern, int flags, String input, boolean expected) {
    assertTrue(Reggie.compile(pattern, flags).find(input) == expected);
  }
}
