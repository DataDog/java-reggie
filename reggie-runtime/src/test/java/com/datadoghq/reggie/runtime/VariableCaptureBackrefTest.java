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

/**
 * Tests for VARIABLE_CAPTURE_BACKREF patterns: variable-length capturing group followed by a
 * separator and backreference. Exercises VariableCaptureBackrefInfo, PatternAnalyzer variable
 * capture analysis, and the corresponding bytecode generator.
 */
class VariableCaptureBackrefTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // Literal separator

  @Test
  void literalSeparatorEquals() {
    ReggieMatcher m = Reggie.compile("(.+)=\\1");
    assertTrue(m.find("foo=foo"));
    assertFalse(m.find("foo=bar"));
  }

  @Test
  void literalSeparatorColon() {
    ReggieMatcher m = Reggie.compile("(.+):\\1");
    assertTrue(m.find("value:value"));
    assertFalse(m.find("a:b"));
  }

  @Test
  void literalSeparatorDash() {
    ReggieMatcher m = Reggie.compile("(.+)-\\1");
    assertTrue(m.find("foo-foo"));
    assertTrue(m.find("hello-hello"));
    assertFalse(m.find("foo-bar"));
  }

  @Test
  void greedilyCaptures() {
    ReggieMatcher m = Reggie.compile("(.*)=\\1");
    assertTrue(m.find("ab=ab"));
    assertTrue(m.find("=")); // empty group matches
    assertTrue(m.find("ab=cd")); // empty group at = position also matches
    assertFalse(m.find("no-equals")); // no = sign at all
  }

  // Digit separator

  @Test
  void digitSeparatorSingle() {
    ReggieMatcher m = Reggie.compile("(.*)\\d\\1");
    assertTrue(m.find("abc1abc"));
    // Consistent with java.util.regex: (.*) can capture empty string at the digit position,
    // so the pattern matches "1" alone (group1="", \d="1", \1="").
    assertTrue(m.find("abc1def"));
    assertFalse(m.find("abcdef")); // no digit, no match
  }

  @Test
  void digitSeparatorMultiple() {
    ReggieMatcher m = Reggie.compile("(.*)\\d+\\1");
    assertTrue(m.find("abc123abc"));
    assertTrue(m.find("xy99xy"));
    assertFalse(m.find("abcdef")); // no digits
  }

  // Matches (anchored)

  @Test
  void matchesWithSeparator() {
    ReggieMatcher m = Reggie.compile("(.+)-\\1");
    assertTrue(m.matches("hi-hi"));
    assertFalse(m.matches("hi-ho"));
    assertFalse(m.matches("hi-"));
  }

  @Test
  void matchesGreedyStar() {
    ReggieMatcher m = Reggie.compile("(.*)-\\1");
    assertTrue(m.matches("-"));
    assertTrue(m.matches("x-x"));
    assertFalse(m.matches("x-y"));
  }

  // Various content in capture group

  @Test
  void captureWithDigits() {
    ReggieMatcher m = Reggie.compile("([0-9]+)=\\1");
    assertTrue(m.find("123=123"));
    assertFalse(m.find("123=456"));
  }

  @Test
  void captureWithWordChars() {
    ReggieMatcher m = Reggie.compile("(\\w+)=\\1");
    assertTrue(m.find("hello=hello"));
    assertTrue(m.find("_var=_var"));
    assertFalse(m.find("hello=world"));
  }

  // Anchored patterns

  @Test
  void anchoredWithSeparator() {
    ReggieMatcher m = Reggie.compile("^(.+)=\\1$");
    assertTrue(m.matches("key=key"));
    assertFalse(m.matches("key=value"));
  }

  // find vs matches semantics

  @Test
  void findLocatesMatch() {
    ReggieMatcher m = Reggie.compile("(.+)=\\1");
    assertTrue(m.find("before foo=foo after"));
    assertFalse(m.find("no match here"));
  }

  @Test
  void findFromPosition() {
    ReggieMatcher m = Reggie.compile("(.+)-\\1");
    int pos = m.findFrom("xx ab-ab yy", 0);
    assertTrue(pos >= 3);
  }
}
