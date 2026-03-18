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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Test string anchors \A, \Z, \z. */
public class StringAnchorsTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testStringStart() {
    // \A matches only at the start of the string, not affected by multiline mode
    ReggieMatcher m = Reggie.compile("\\Aabc.*");

    assertTrue(m.matches("abc"));
    assertTrue(m.matches("abcdef"));
    assertFalse(m.matches("xabc"));
    assertFalse(m.matches("\nabc"));
  }

  @Test
  public void testStringStartWithMultiline() {
    // \A should NOT be affected by multiline mode
    ReggieMatcher m = Reggie.compile("(?m)\\Aabc.*");

    assertTrue(m.matches("abc"));
    assertFalse(m.matches("\nabc")); // \A doesn't match after newline, even in multiline
  }

  @Test
  public void testStringEndAbsolute() {
    // \z matches only at the absolute end of string
    ReggieMatcher m = Reggie.compile(".*abc\\z");

    assertTrue(m.matches("abc"));
    assertTrue(m.matches("xyzabc"));
    assertFalse(m.matches("abcx"));
    assertFalse(m.matches("abc\n")); // \z doesn't match before newline
  }

  @Test
  public void testStringEnd() {
    // \Z matches at end of string OR before final newline
    ReggieMatcher m = Reggie.compile(".*abc\\Z");

    assertTrue(m.matches("abc"));
    assertTrue(m.matches("xyzabc"));
    assertTrue(m.matches("abc\n")); // \Z matches before final newline
    assertFalse(m.matches("abcx"));
    assertFalse(m.matches("abc\nx")); // \Z doesn't match before non-final newline
  }

  @Test
  public void testStringEndVsAbsoluteEnd() {
    String input1 = "abc";
    String input2 = "abc\n";

    // Both should match "abc" without newline
    ReggieMatcher mZ = Reggie.compile("abc\\Z");
    ReggieMatcher mz = Reggie.compile("abc\\z");
    assertTrue(mZ.matches(input1));
    assertTrue(mz.matches(input1));

    // \Z matches "abc\n", but \z doesn't
    assertTrue(mZ.matches(input2), "\\Z should match before final newline");
    assertFalse(mz.matches(input2), "\\z should not match before newline");
  }

  @Test
  public void testCompareWithRegularAnchors() {
    String multiline = "line1\nline2";

    // ^ vs \A in multiline mode
    ReggieMatcher caret = Reggie.compile("(?m)^line2");
    ReggieMatcher stringStart = Reggie.compile("(?m)\\Aline2");

    assertTrue(caret.find(multiline), "^ should match after newline in multiline mode");
    assertFalse(stringStart.find(multiline), "\\A should NOT match after newline");

    // $ vs \z in multiline mode
    ReggieMatcher dollar = Reggie.compile("(?m)line1$");
    ReggieMatcher stringEndAbs = Reggie.compile("(?m)line1\\z");

    assertTrue(dollar.find(multiline), "$ should match before newline in multiline mode");
    assertFalse(stringEndAbs.find(multiline), "\\z should NOT match before newline");
  }
}
