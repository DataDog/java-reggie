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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Character class syntax against {@link Pattern}: leading {@code ]}, nested classes, {@code &&}
 * intersection, (?x) whitespace and comments in classes, and (?i) folding. Each class is checked on
 * every character up to U+024F and a few beyond.
 */
class ClassDifferentialTest {

  private static final char[] EXTRA = {'\u0300', '\u2028', '\u212A', '\u3000', '\uFFFF'};

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]a]",
        "[^]a]",
        "(?x)[ ]a]",
        "[a[bc]]",
        "[a[^bc]]",
        "[a-z&&[^aeiou]]",
        "[^a-z&&[aeiou]]",
        "[&&a]",
        "[a&&]",
        "[a-c&&]",
        "[a-[b]]",
        "[a-]",
        "[-a]",
        "[a&b]",
        "[a&&b&&c]",
        "[a-c&&b-d&&c]",
        "[a&&[b]&c]",
        "[a-c[x-z]&&[b-y]]",
        "[[a-c]&&b]",
        "[\\w&&[^\\d]]",
        "[\\u0100-\\u0200&&[^\\u0150]]",
        "[^]",
        "(?i)[a-z&&[^A]]",
        "(?i)[^a]",
        "(?i)[\\W]",
        "(?i)[k[^K]]",
        "(?x)[a b]",
        "(?x)[a - c]",
        "(?x)[a& b]",
        "(?x)[a&& b]",
        "(?x)[a#x\n]",
        "(?x)[ ^a]",
        "(?x)[a\\ ]",
      })
  void classEqualsJdk(String pattern) {
    if (pattern.equals("[^]")) {
      assertThrows(PatternSyntaxException.class, () -> Pattern.compile(pattern));
      assertThrows(PatternSyntaxException.class, () -> Reggie.compile(pattern));
      return;
    }
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    for (int c = 0; c < 0x250 + EXTRA.length; c++) {
      String input = String.valueOf(c < 0x250 ? (char) c : EXTRA[c - 0x250]);
      assertEquals(
          jdk.matcher(input).matches(),
          reggie.matches(input),
          pattern + " on U+" + String.format("%04X", (int) input.charAt(0)));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\S@[]/]",
        "@[]/]",
        "x[a&&b]|y",
        "(?:x[a&&b]|y)+z",
        "(x[a&&b]|y)\\1",
        "x[a&&b]*y",
        "[]a]+b",
        "(?x) a [ ] ] b # comment",
      })
  void findEqualsJdk(String pattern) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    for (String input :
        new String[] {"", "a@]", "a@/", "@]", " @]", "x", "y", "yy", "yz", "xy", "]]ab", "a]b"}) {
      String row = pattern + " on " + input;
      assertEquals(jdk.matcher(input).matches(), reggie.matches(input), "matches " + row);
      Matcher j = jdk.matcher(input);
      String expected = j.find() ? "[" + j.start() + "," + j.end() + ")" : "-";
      MatchResult r = reggie.findMatch(input);
      assertEquals(expected, r == null ? "-" : "[" + r.start() + "," + r.end() + ")", row);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "[[]",
        "[&&]",
        "[[x]a&&]",
        "(?x)[#x\n]",
        "(?x)[a- ]",
        "[a-",
        "[z-a]",
        "[a-\\]]",
      })
  void syntaxErrorAsInJdk(String pattern) {
    assertThrows(PatternSyntaxException.class, () -> Pattern.compile(pattern));
    assertThrows(PatternSyntaxException.class, () -> Reggie.compile(pattern));
  }
}
