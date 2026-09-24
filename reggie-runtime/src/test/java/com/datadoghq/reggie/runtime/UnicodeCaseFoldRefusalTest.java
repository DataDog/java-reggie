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
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.Test;

/**
 * Review regression: case-insensitive matching under (?u)/(?U) must refuse. The JDK folds the
 * special Unicode cases there ((?iu)k matches the Kelvin sign U+212A, (?iu)s matches U+017F);
 * reggie's simple Unicode folding does not, so accepting the combination diverged exactly where the
 * JDK matched (measured: (?iu)k and (?iU)k returned no-match on U+212A while the JDK matched).
 */
class UnicodeCaseFoldRefusalTest {

  @Test
  void unicodeCaseWithInsensitivityIsRefused() {
    for (String pattern : new String[] {"(?iu)k", "(?ui)k", "(?iU)k", "(?iu)[a-z]+", "(?i-u)k"}) {
      if (pattern.equals("(?i-u)k")) {
        // '-u' disables u: not a unicode-case fold, must keep compiling
        assertEquals(true, Reggie.compile(pattern).find("K"));
        continue;
      }
      assertThrows(UnsupportedPatternException.class, () -> Reggie.compile(pattern), pattern);
    }
  }

  @Test
  void fallbackKeepsJdkParityOnSpecialFolds() {
    ReggieMatcher m = com.datadoghq.reggie.Reggie.compileAllowingFallback("(?iu)k");
    String kelvin = "\u212A";
    assertEquals(java.util.regex.Pattern.compile("(?iu)k").matcher(kelvin).find(), m.find(kelvin));
    assertEquals(
        java.util.regex.Pattern.compile("(?iu)s").matcher("\u017F").find(),
        com.datadoghq.reggie.Reggie.compileAllowingFallback("(?iu)s").find("\u017F"));
  }

  @Test
  void plainCaseInsensitiveAndNonCaseUnicodeFlagsUnaffected() {
    assertEquals(true, Reggie.compile("(?i)k").find("K"));
    assertEquals(true, Reggie.compile("(?u)k").find("k")); // u without i has no fold effect
    assertEquals(true, Reggie.compile("(?U)\\w+").find("a"));
    // plain (?i) does not fold the Kelvin sign (simple fold) — same as the JDK without u
    assertEquals(false, Reggie.compile("(?i)k").find("\u212A"));
  }
}
