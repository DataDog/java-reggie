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
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for case-insensitive backreferences. */
class CaseInsensitiveBackrefTest {

  @BeforeEach
  void clearCache() {
    Reggie.clearCache();
  }

  @Test
  void caseInsensitiveSimpleBackref_matchesUpperLower() {
    // JDK: Pattern.compile("(?i)(ab)\\1").matcher("abAB").matches() == true
    String pat = "(?i)(ab)\\1";
    assertTrue(jdk(pat, "abAB"), "JDK precondition: (?i)(ab)\\1 must match 'abAB'");
    assertTrue(Reggie.compile(pat).matches("abAB"), "(?i)(ab)\\1 must match 'abAB'");
    assertTrue(Reggie.compile(pat).matches("ABab"), "(?i)(ab)\\1 must match 'ABab'");
    assertTrue(Reggie.compile(pat).matches("ABAB"), "(?i)(ab)\\1 must match 'ABAB'");
    assertFalse(Reggie.compile(pat).matches("abcd"), "(?i)(ab)\\1 must not match 'abcd'");
  }

  @Test
  void caseInsensitiveBackref_singleChar() {
    // (?i)(x)\1 should match "xX" and "Xx"
    String pat = "(?i)(x)\\1";
    assertTrue(jdk(pat, "xX"), "JDK precondition");
    assertTrue(Reggie.compile(pat).matches("xX"), "(?i)(x)\\1 must match 'xX'");
    assertTrue(Reggie.compile(pat).matches("Xx"), "(?i)(x)\\1 must match 'Xx'");
    assertFalse(Reggie.compile(pat).matches("xy"), "(?i)(x)\\1 must not match 'xy'");
  }

  private static boolean jdk(String pattern, String input) {
    return Pattern.compile(pattern).matcher(input).matches();
  }
}
