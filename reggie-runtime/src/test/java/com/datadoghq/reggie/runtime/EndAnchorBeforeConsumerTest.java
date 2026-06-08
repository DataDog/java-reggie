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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for Wave 4B: removing the end-anchor-before-consumer fallback.
 *
 * <p>In JDK, {@code $} without MULTILINE matches at end of input OR before a terminal {@code \n}.
 * So {@code $\n} matches "foo\n" because {@code $} succeeds (before terminal \n), then {@code \n}
 * consumes the newline.
 *
 * <p>After the fix, Reggie must handle these patterns natively instead of delegating to JDK.
 */
class EndAnchorBeforeConsumerTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /** Asserts that Reggie produces the same find result (found/not-found, start, end) as JDK. */
  private static void assertSameAsJdk(String pattern, String input) {
    Pattern jdkPattern = Pattern.compile(pattern);
    Matcher jdkMatcher = jdkPattern.matcher(input);
    boolean jdkFound = jdkMatcher.find();

    ReggieMatcher reggieMatcher = Reggie.compile(pattern);
    MatchResult reggieResult = reggieMatcher.findMatch(input);
    boolean reggieFound = reggieResult != null;

    assertEquals(
        jdkFound,
        reggieFound,
        "find() mismatch for pattern=\""
            + pattern
            + "\" input=\""
            + input.replace("\n", "\\n")
            + "\""
            + " jdk="
            + (jdkFound ? "[" + jdkMatcher.start() + "," + jdkMatcher.end() + ")" : "null")
            + " reggie="
            + (reggieFound ? "[" + reggieResult.start() + "," + reggieResult.end() + ")" : "null")
            + " strategy="
            + reggieMatcher.getClass().getSimpleName());

    if (jdkFound) {
      assertEquals(
          jdkMatcher.start(),
          reggieResult.start(),
          "start() mismatch for pattern=\""
              + pattern
              + "\" input=\""
              + input.replace("\n", "\\n")
              + "\"");
      assertEquals(
          jdkMatcher.end(),
          reggieResult.end(),
          "end() mismatch for pattern=\""
              + pattern
              + "\" input=\""
              + input.replace("\n", "\\n")
              + "\"");
    }
  }

  /** {@code $\n} must be handled natively — no JDK fallback. */
  @Test
  void dollarNewline_usesNativeEngine() {
    ReggieMatcher m = Reggie.compile("$\\n");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "$\\n should NOT use JDK fallback but got: " + m.getClass().getSimpleName());
  }

  /** {@code \Z\n} must be handled natively — no JDK fallback. */
  @Test
  void stringEndNewline_usesNativeEngine() {
    ReggieMatcher m = Reggie.compile("\\Z\\n");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "\\Z\\n should NOT use JDK fallback but got: " + m.getClass().getSimpleName());
  }

  /** {@code $\n} must match "foo\n" — JDK matches here because $ succeeds before terminal \n. */
  @Test
  void dollarNewline_matchesFooNewline() {
    assertSameAsJdk("$\\n", "foo\n");
  }

  /** {@code $\n} must not match plain "foo" (no trailing newline). */
  @Test
  void dollarNewline_doesNotMatchFoo() {
    assertSameAsJdk("$\\n", "foo");
  }

  /** {@code $\n} must not match "foo\nbar" (newline is not terminal). */
  @Test
  void dollarNewline_doesNotMatchFooNewlineBar() {
    assertSameAsJdk("$\\n", "foo\nbar");
  }

  /** {@code $\n} with input just "\n". */
  @Test
  void dollarNewline_matchesSingleNewline() {
    assertSameAsJdk("$\\n", "\n");
  }

  /** {@code $\n} with empty input. */
  @Test
  void dollarNewline_doesNotMatchEmpty() {
    assertSameAsJdk("$\\n", "");
  }

  /** {@code \Z\n} must match "foo\n". */
  @Test
  void stringEndNewline_matchesFooNewline() {
    assertSameAsJdk("\\Z\\n", "foo\n");
  }

  /** {@code \Z\n} must not match plain "foo". */
  @Test
  void stringEndNewline_doesNotMatchFoo() {
    assertSameAsJdk("\\Z\\n", "foo");
  }

  /** {@code \Z\n} must not match "foo\nbar". */
  @Test
  void stringEndNewline_doesNotMatchFooNewlineBar() {
    assertSameAsJdk("\\Z\\n", "foo\nbar");
  }

  /** {@code \Z\n} with input just "\n". */
  @Test
  void stringEndNewline_matchesSingleNewline() {
    assertSameAsJdk("\\Z\\n", "\n");
  }

  /** {@code \Z\n} with empty input. */
  @Test
  void stringEndNewline_doesNotMatchEmpty() {
    assertSameAsJdk("\\Z\\n", "");
  }
}
