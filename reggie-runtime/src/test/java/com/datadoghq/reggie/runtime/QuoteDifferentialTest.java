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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code \Q..\E} quoting against {@link Pattern}: {@code matches()} and the first {@code find()}
 * span on every probe input.
 */
class QuoteDifferentialTest {

  private static final String[] INPUTS = {
    "", "a", "aa", "ab", "abb", "abab", "b", "c", "-", "12", "1", "a.b", "axb", " a", "A", "AB",
    "(a)", "a\\b", "x\\Ey", "xay",
  };

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\Qab\\E+",
        "a\\Q\\E+",
        "a+\\Q\\E?",
        "[\\Qa\\E-c]",
        "\\Q12\\E",
        "\\Q1\\E+",
        "\\Qa.b\\E",
        "\\Q(a)\\E",
        "(?i)\\Qab\\E",
        "(?x)\\Q a\\E",
        "\\Qa\\b\\E",
        "x\\Q\\\\E",
        "\\Qa",
        "x\\Qa\\E*y",
      })
  void equalsJdk(String pattern) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    for (String input : INPUTS) {
      String row = pattern + " on " + input;
      assertEquals(jdk.matcher(input).matches(), reggie.matches(input), "matches " + row);
      Matcher j = jdk.matcher(input);
      String expected = j.find() ? "[" + j.start() + "," + j.end() + ")" : "-";
      MatchResult r = reggie.findMatch(input);
      assertEquals(expected, r == null ? "-" : "[" + r.start() + "," + r.end() + ")", row);
    }
  }
}
