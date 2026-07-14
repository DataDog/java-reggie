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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #107: {@code \B} (non-word-boundary) used to be silently parsed as the
 * literal character {@code 'B'} by {@code RegexParser.parseEscape}'s {@code default} branch.
 */
class NonWordBoundaryTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void notParsedAsLiteralB() {
    // If \B were still the literal 'B', "\\Bfoo" would require a literal "Bfoo" substring
    // starting at index 0; the real non-word-boundary semantics match "foo" at [1,4) instead.
    expectFindMatch("\\Bfoo", "Bfoo", 1, 4);
  }

  @Test
  void basicNonWordBoundarySemantics() {
    expectFindMatch("\\Bfoo", "xfoo", 1, 4);
    expectFindNone("\\Bfoo", "foo");
    expectFindNone("\\Bfoo", " foo");
  }

  @Test
  void trailingNonWordBoundary() {
    expectFindMatch("foo\\B", "foox", 0, 3);
    expectFindNone("foo\\B", "foo");
    expectFindNone("foo\\B", "foo ");
  }

  @Test
  void midWordAndEmptyInput() {
    expectFindMatch("\\B", "ab", 1, 1);
    expectFindMatch("\\B", "", 0, 0);
    expectFindNone("\\B", "a");
  }

  @Test
  void combinedWithWordBoundaryAlternation() {
    String[] inputs = {"", "a", "ab", "a b", " a ", "a1b2", "  ", "foo bar", "foobar"};
    for (String input : inputs) {
      assertFindFamilyMatchesJdk("\\b\\w+\\B", input);
      assertFindFamilyMatchesJdk("\\B\\w+\\b", input);
      assertFindFamilyMatchesJdk("(a\\B|b\\b)+", input);
    }
  }

  @Test
  void quantifiedAndAnchoredShapes() {
    String[] inputs = {"", "x", "xx", "xxx", "1x1", " x ", "x\n", "\nx"};
    for (String input : inputs) {
      assertFindFamilyMatchesJdk("\\Bx+", input);
      assertFindFamilyMatchesJdk("x+\\B", input);
      assertFindFamilyMatchesJdk("^\\Bx", input);
      assertFindFamilyMatchesJdk("x\\B$", input);
      assertFindFamilyMatchesJdk("(?m)^x\\B", input);
    }
  }

  private static void assertFindFamilyMatchesJdk(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatched = jm.find();

    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    MatchResult mr = m.findMatch(input);

    if (!jdkMatched) {
      assertNull(
          mr, () -> "Reggie found a match for /" + regex + "/ on \"" + input + "\", JDK did not");
      return;
    }
    assertTrue(
        mr != null && mr.start() == jm.start() && mr.end() == jm.end(),
        () ->
            "Reggie/JDK diverge for /"
                + regex
                + "/ on \""
                + input
                + "\": reggie="
                + mr
                + " jdk=["
                + jm.start()
                + ","
                + jm.end()
                + ")");
  }

  private static void expectFindMatch(String regex, String input, int start, int end) {
    Pattern jdk = Pattern.compile(regex);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatched = jm.find();
    if (!(jdkMatched && jm.start() == start && jm.end() == end)) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK did not match pattern '"
              + regex
              + "' on '"
              + input
              + "' as ["
              + start
              + ","
              + end
              + ")");
    }
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    MatchResult mr = m.findMatch(input);
    assertTrue(mr != null, () -> "Reggie find('" + input + "') for /" + regex + "/ should match");
    assertEquals(start, mr.start());
    assertEquals(end, mr.end());
  }

  private static void expectFindNone(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    if (jdk.matcher(input).find()) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK matched pattern '" + regex + "' on '" + input + "'");
    }
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    MatchResult mr = m.findMatch(input);
    assertNull(mr, () -> "Reggie find('" + input + "') for /" + regex + "/ should not match");
  }
}
