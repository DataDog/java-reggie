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
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for multiple backreferences to the same group (issue #27). Verifies that the
 * native NFA engine produces correct results without falling back to java.util.regex.
 */
class MultiBackrefCorrectnessTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // T-01: triple backref with word separator — the original false-positive bug
  @Test
  void tripleBackrefFalsePositiveFix() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1\\s+\\1");
    assertTrue(m.find("go go go"), "should match when all three tokens are identical");
    assertFalse(m.find("go go stop"), "should NOT match when third token differs");
  }

  // T-02: triple backref must not be routed to java.util.regex fallback
  @Test
  void tripleBackrefIsNotFallback() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1\\s+\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher, "native engine must handle multiple backrefs");
  }

  // T-03: single backref to group 1 in a two-group pattern with literal separator
  @Test
  void singleBackrefTwoGroupLiteralSeparator() {
    ReggieMatcher m = Reggie.compile("(\\w+)=(\\w+)=\\1");
    assertTrue(m.find("foo=bar=foo"), "group 1 repeats after the second =");
    assertFalse(m.find("foo=bar=baz"), "neither group repeats");
  }

  // T-04: double backref to same group, positional match
  @Test
  void doubleBackrefSameGroup() {
    ReggieMatcher m = Reggie.compile("(\\w+)-\\1-\\1");
    assertTrue(m.find("ab-ab-ab"), "three identical tokens separated by dashes");
    assertFalse(m.find("ab-ab-cd"), "third token differs");
    assertFalse(m.find("ab-cd-ab"), "second token differs");
  }

  // T-05: four repetitions of same group
  @Test
  void quadrupleBackref() {
    ReggieMatcher m = Reggie.compile("(\\w+) \\1 \\1 \\1");
    assertTrue(m.find("x x x x"), "four identical single-char tokens");
    assertFalse(m.find("x x x y"), "last token differs");
    assertFalse(m.find("x y x x"), "second token differs");
  }

  // T-06: two backrefs, each to a different group
  @Test
  void twoGroupsTwoBackrefs() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+(\\w+)\\s+\\1\\s+\\2");
    assertTrue(m.find("foo bar foo bar"), "each group matches its own backref");
    assertFalse(m.find("foo bar baz bar"), "group 1 backref fails");
    assertFalse(m.find("foo bar foo baz"), "group 2 backref fails");
  }

  // T-07: double backref with variable-length content (digits)
  @Test
  void doubleBackrefDigits() {
    ReggieMatcher m = Reggie.compile("([0-9]+) \\1 \\1");
    assertTrue(m.find("123 123 123"), "three equal digit sequences");
    assertFalse(m.find("123 123 456"), "third differs");
  }

  // T-08: anchored double backref with matches()
  @Test
  void doubleBackrefAnchored() {
    ReggieMatcher m = Reggie.compile("(\\w+)-\\1-\\1");
    assertTrue(m.matches("hi-hi-hi"), "full-string match");
    assertFalse(m.matches("hi-hi-ho"), "last token differs");
    assertFalse(m.matches("hi-ho-hi"), "middle token differs");
  }

  // T-09: double backref with mixed-case (case-sensitive)
  @Test
  void doubleBackrefCaseSensitive() {
    ReggieMatcher m = Reggie.compile("([a-z]+) \\1 \\1");
    assertTrue(m.find("abc abc abc"));
    assertFalse(m.find("abc abc ABC"), "uppercase mismatch");
  }

  // T-10: double backref embedded in longer string
  @Test
  void doubleBackrefEmbedded() {
    ReggieMatcher m = Reggie.compile("(\\w+) \\1 \\1");
    assertTrue(m.find("prefix word word word suffix"), "match found within larger input");
    assertFalse(m.find("prefix word word diff suffix"), "no triple repetition present");
  }

  // T-11: double backref with empty-capable group (.*)
  @Test
  void doubleBackrefEmptyCapable() {
    ReggieMatcher m = Reggie.compile("(\\w*) \\1 \\1", WITH_FALLBACK);
    assertTrue(m.find("  "), "empty group matches three times with spaces");
    assertTrue(m.find("x x x"), "non-empty group");
    assertFalse(m.find("x x y"), "third differs");
  }

  // T-12: parity with java.util.regex for triple-backref on edge inputs
  @Test
  void parityWithJavaRegex() {
    java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("(\\w+)\\s+\\1\\s+\\1");
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1\\s+\\1");

    String[] inputs = {"go go go", "go go stop", "a a a", "ab ab ab", "ab ab cd", "", "x"};
    for (String input : inputs) {
      boolean javaResult = javaPattern.matcher(input).find();
      boolean reggieResult = m.find(input);
      assertEquals(javaResult, reggieResult, "parity failure for input: '" + input + "'");
    }
  }
}
