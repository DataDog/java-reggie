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
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JDK escapes {@code \e \a \cX \h \H \v \V \R \X}, inside and outside classes, against {@link
 * Pattern}: {@code matches()} and the first {@code find()} span on every probe input.
 */
class EscapeDifferentialTest {

  private static final String[] INPUTS = {
    "", "a", "A", "e", "R", "X", "h", "v", "c", "-", " ", "\t", "\n", "\u000B", "\f", "\r", "\r\n",
    "\n\r", "\u001B", "\u0007", "\u0001", "\u00A0", "\u0085", "\u1680", "\u180E", "\u2000",
    "\u200A", "\u200B", "\u2028", "\u2029", "\u202F", "\u205F", "\u3000", "x\r\ny",
  };

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\e",
        "\\a",
        "\\cA",
        "\\c!",
        "(?i)\\c!",
        "\\c@",
        "\\h",
        "\\H",
        "\\v",
        "\\V",
        "\\h+",
        "\\R",
        "\\R+",
        "\\R\\n",
        "x\\Ry",
        "[\\e]",
        "[\\a]",
        "[\\cA]",
        "[\\h]",
        "[\\H]",
        "[\\v]",
        "[\\V]",
        "[^\\h]",
        "[^\\v]",
        "[\\v-\\r]",
        "[\\t-\\v]",
        "[\\v-]",
        "[\\h-a]",
        "[\\d-a]",
        "[\\a-\\e]",
      })
  void equalsJdk(String pattern) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    for (String input : INPUTS) {
      String row = pattern + " on " + escape(input);
      assertEquals(jdk.matcher(input).matches(), reggie.matches(input), "matches " + row);
      Matcher j = jdk.matcher(input);
      String expected = j.find() ? "[" + j.start() + "," + j.end() + ")" : "-";
      MatchResult r = reggie.findMatch(input);
      assertEquals(expected, r == null ? "-" : "[" + r.start() + "," + r.end() + ")", row);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"[\\R]", "[\\X]", "[a-\\h]", "[a-\\H]", "[a-\\V]", "[a-\\d]", "\\c"})
  void syntaxErrorAsInJdk(String pattern) {
    assertThrows(PatternSyntaxException.class, () -> Pattern.compile(pattern));
    assertThrows(PatternSyntaxException.class, () -> Reggie.compile(pattern));
  }

  @Test
  void graphemeClusterGoesToFallback() {
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("a\\X"));
    ReggieMatcher r = Reggie.compileAllowingFallback("a\\X");
    assertTrue(r.isJdkFallback());
    for (String input : new String[] {"ab", "ae\u0301", "a", "aXb"}) {
      assertEquals(Pattern.compile("a\\X").matcher(input).matches(), r.matches(input), input);
    }
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      sb.append(c < 0x20 || c > 0x7E ? String.format("\\u%04X", (int) c) : String.valueOf(c));
    }
    return sb.toString();
  }
}
