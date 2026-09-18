/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the java.util.regex POSIX-style property classes.
 *
 * <p>History: {@code \p{Alnum}}, {@code \p{Alpha}}, {@code \p{ASCII}}, {@code \p{Cntrl}},
 * {@code \p{Lower}}, {@code \p{Upper}} and {@code \p{IsAlphabetic}} threw "Unsupported Unicode
 * property" — 7 hard-rejected consumer sites in logs-backend. The JDK forms are ASCII-only unless
 * {@code UNICODE_CHARACTER_CLASS} is set (which Reggie does not support), so they map onto the
 * existing ASCII CharSets; the {@code IsXxx} forms stay Unicode-aware.
 *
 * <p>Membership expectations are computed live against {@code java.util.regex} so the test cannot
 * drift from JDK semantics.
 */
class PosixPropertyClassRegressionTest {

  private static final String[] CLASSES = {
    "Alnum", "Alpha", "ASCII", "Blank", "Cntrl", "Digit", "Graph", "Lower",
    "Print", "Punct", "Space", "Upper", "XDigit", "IsLetter", "IsDigit"
  };

  /** Probe characters: controls, boundaries, letters/digits, DEL, Latin-1, Greek, CJK. */
  private static final int[] PROBES = {
    0, 1, 7, 8, 9, 10, 11, 12, 13, 31, 32, 33, 45, 47, 48, 57, 58, 64, 65, 70, 90, 91, 95, 96,
    97, 102, 127, 128, 160, 233, 255, 945, 940, 917, 19968, 65279, 8364
  };

  @Test
  void posixClassesMatchJdkMembership() {
    for (String cls : CLASSES) {
      String p = "\\p{" + cls + "}";
      var jp = Pattern.compile(p);
      var rm = Reggie.compile(p);
      for (int c : PROBES) {
        String s = String.valueOf((char) c);
        assertEquals(
            jp.matcher(s).matches(),
            rm.matches(s),
            "\\p{" + cls + "} membership diverged for U+" + Integer.toHexString(c));
      }
      // negated form
      var jpN = Pattern.compile("\\P{" + cls + "}");
      var rmN = Reggie.compile("\\P{" + cls + "}");
      for (int c : PROBES) {
        String s = String.valueOf((char) c);
        assertEquals(jpN.matcher(s).matches(), rmN.matches(s), "\\P{" + cls + "} diverged");
      }
    }
  }

  @Test
  void isAlphabeticMatchesJdk() {
    // The bare \p{IsAlphabetic} class compiles natively and must match JDK membership exactly.
    var rm = Reggie.compile("\\p{IsAlphabetic}");
    var jp = Pattern.compile("\\p{IsAlphabetic}");
    for (int c : PROBES) {
      String s = String.valueOf((char) c);
      assertEquals(jp.matcher(s).matches(), rm.matches(s), "IsAlphabetic diverged for U+"
              + Integer.toHexString(c));
    }
    // The '.*[\\p{IsAlphabetic}].*' shape exceeds the 64KB generated-method limit on strict
    // compile (graceful rejection); the fallback path must then be JDK-equivalent.
    assertThrows(
        UnsupportedPatternException.class,
        () -> Reggie.compile(".*[\\p{IsAlphabetic}].*"));
    var fallback = Reggie.compileAllowingFallback(".*[\\p{IsAlphabetic}].*");
    var jp2 = Pattern.compile(".*[\\p{IsAlphabetic}].*");
    assertEquals(jp2.matcher("ab\u00e9").matches(), fallback.matches("ab\u00e9"));
    assertEquals(jp2.matcher("123").matches(), fallback.matches("123"));
  }

  @Test
  void consumerShapesCompileNativelyAndMatchJdk() throws Exception {
    // Exact shapes from the logs-backend/profiling-backend sites that previously hard-failed.
    // Each row: pattern, input, and whether the whole input should match (computed from JDK).
    String[][] shapes = {
      {"[\\p{Cntrl}\\s]+", "a" + (char) 1 + "b c"},
      {"[^\\p{Alnum}.]+", "!!.."},
      {"^[^\\p{Alpha}]+", "123"},
      {"\\p{Lower}+", "abc"},
      {"[^\\p{ASCII}]+", "ab\u00e9"},
      {"[\\p{Alnum}-]+", "ab-c9"},
      {"[^\\p{IsAlphabetic}\\d./:-]+", "ab\u00e9 x"},
      {"\\p{XDigit}+", "0aF3"},
      {"(?<n>[0-9]+)\\s+abs\\s+(?<a>\\p{XDigit}+)", "42 abs 0F1"},
    };
    for (String[] shape : shapes) {
      String pattern = shape[0];
      String input = shape[1];
      var jdkMatches = Pattern.compile(pattern).matcher(input).matches();
      var jdkFind = Pattern.compile(pattern).matcher(input).find();
      var matcher = Reggie.compile(pattern); // must strict-compile natively
      assertEquals(jdkMatches, matcher.matches(input), "matches() diverged for " + pattern);
      assertEquals(jdkFind, matcher.find(input), "find() diverged for " + pattern);
    }
    // group extraction on a named-group consumer shape
    var r = Reggie.compile("(?<n>[0-9]+)\\s+abs\\s+(?<a>\\p{XDigit}+)").findMatch("42 abs 0F1");
    assertEquals("42", r.group("n"));
    assertEquals("0F1", r.group("a"));
  }
}
