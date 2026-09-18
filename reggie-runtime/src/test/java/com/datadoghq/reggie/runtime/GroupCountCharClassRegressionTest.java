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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for group counting with parentheses inside character classes.
 *
 * <p>History: {@code RuntimeCompiler.countGroups} was a textual scan over the pattern that did not
 * track character classes, so every {@code (} inside a {@code [...]} counted as a capturing group.
 * {@code [()\s]} reported groupCount=1 (JDK: 0) and {@code ([\[\]{}()*+?.\\^$|])} reported 2 (JDK:
 * 1), inflating capture-slot arrays and misreporting group counts while matching itself stayed
 * correct. The scan now skips {@code [...]} class bodies (as it already did for {@code \Q...\E}).
 *
 * <p>Expectations are computed live against {@code java.util.regex} so the test cannot drift from
 * JDK semantics.
 */
class GroupCountCharClassRegressionTest {

  /**
   * Each row: pattern + an input that is guaranteed to match it (so a MatchResult exists to read
   * groupCount from).
   */
  private static final String[][] CASES = {
    {"[()\\s]", "foo bar"},
    {"[{}()\\[\\].+*?^$\\\\|]", "a.b|c"},
    {"([\\[\\]{}()*+?.\\\\^$|])", "a.b|c"},
    {"[(]x", "(x"},
    {"(a)[(]", "a("},
    {"(a)(b)[()]", "ab("},
    {"\\d+(x)", "12x"},
    {"([(]y)", "((y)"},
    {"[(a](b?)c", "ac"},
    {"(?>[(])(z)", "((z)"},
  };

  @Test
  void groupCountMatchesJdkWithParensInClasses() {
    for (String[] c : CASES) {
      String pattern = c[0];
      String input = c[1];
      int jdk = Pattern.compile(pattern).matcher(input).groupCount();
      MatchResult result = Reggie.compile(pattern).findMatch(input);
      assertNotNull(result, "expected a match for " + pattern + " on " + input);
      assertEquals(
          jdk,
          result.groupCount(),
          "groupCount mismatch for pattern " + pattern + " (paren inside a character class?)");
    }
  }

  @Test
  void groupExtractionUnchangedWithClassParens() {
    // Real group indices must still be correct when the pattern also contains parens in a class.
    var r = Reggie.compile("([\\[\\]{}()*+?.\\\\^$|])(\\w)").findMatch("a.b|c");
    assertEquals(".", r.group(1));
    assertEquals("b", r.group(2));
    var r2 = Reggie.compile("[(](x)").findMatch("((x)");
    assertEquals("x", r2.group(1));
    assertEquals("a", Reggie.compile("(a)[(]").findMatch("a(").group(1));
  }
}
