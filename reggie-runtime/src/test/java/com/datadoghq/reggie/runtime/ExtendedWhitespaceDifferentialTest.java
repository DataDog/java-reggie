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
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code (?x)} comments end before any line terminator or NUL, only ASCII whitespace is skipped,
 * and whitespace-only alternatives are empty, as in the JDK. Rows compare {@code matches()}: a
 * boolean {@code find()} is true on both sides for the comment-terminator rows.
 */
class ExtendedWhitespaceDifferentialTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "(?x)a#c\\u000Db | ab",
        "(?x)a#c\\u0085b | a\\u0085b",
        "(?x)a#c\\u2028b | a\\u2028b",
        "(?x)a#c\\u0000b | a\\u0000b",
        "(?x)a\\u001Cb   | a\\u001Cb",
        "(?x)a\\u00A0b   | a\\u00A0b",
        "'(?x)a| '       | ''",
        "(?x)a|#c        | ''",
        "'(?x)(a| )b'    | b",
        "'(?x)a| |b'     | b",
        "(?ux)a b        | ab",
        "(?ux)[a b]      | ' '",
      })
  void matchesEqualsJdk(String pattern, String input) {
    String p = unescape(pattern);
    String in = unescape(input);
    assertEquals(Pattern.compile(p).matcher(in).matches(), Reggie.compile(p).matches(in), pattern);
  }

  /** Expands {@code \\uXXXX} so rows can carry control and non-ASCII characters. */
  private static String unescape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      if (s.startsWith("\\u", i)) {
        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
        i += 5;
      } else {
        sb.append(s.charAt(i));
      }
    }
    return sb.toString();
  }
}
