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
import org.junit.jupiter.api.Test;

/**
 * Tests for VARIABLE_CAPTURE_BACKREF strategy. Patterns like (.*)\d+\1 where the backreference has
 * variable-length capture.
 */
class VariableCaptureBackrefTest {

  @Test
  void testGreedyAnyWithDigitSeparator() {
    // (.*)(\d+)\1 - capture any, digit separator, backref
    // Example: "abc123abc" -> group1="abc", group2="123", backref="abc"
    ReggieMatcher m = Reggie.compile("(.*)\\d+\\1");

    assertTrue(m.matches("abc123abc"), "(.*)\\d+\\1 should match 'abc123abc'");
    assertTrue(m.matches("x1x"), "Should match 'x1x'");
    assertTrue(m.matches("test99test"), "Should match 'test99test'");
    assertFalse(m.matches("abc123def"), "Should not match 'abc123def' (backref mismatch)");
    assertFalse(m.matches("abc"), "Should not match 'abc' (no separator)");
  }

  @Test
  void testGreedyAnyWithLiteralSeparator() {
    // (.*)=\1 - capture any, literal '=' separator, backref
    ReggieMatcher m = Reggie.compile("(.*)=\\1");

    assertTrue(m.matches("abc=abc"), "(.*)=\\1 should match 'abc=abc'");
    assertTrue(m.matches("x=x"), "Should match 'x=x'");
    assertFalse(m.matches("abc=def"), "Should not match 'abc=def'");
    assertFalse(m.matches("abc"), "Should not match 'abc' (no separator)");
  }

  @Test
  void testGreedyPlusWithSeparator() {
    // (.+):\1 - capture at least 1 char, ':' separator, backref
    ReggieMatcher m = Reggie.compile("(.+):\\1");

    assertTrue(m.matches("abc:abc"), "(.+):\\1 should match 'abc:abc'");
    assertTrue(m.matches("x:x"), "Should match 'x:x'");
    assertFalse(m.matches(":"), "Should not match ':' (empty capture not allowed with .+)");
    assertFalse(m.matches("abc:def"), "Should not match 'abc:def'");
  }

  @Test
  void testCharClassCaptureWithSeparator() {
    // (a+)-\1 - capture 'a'+, '-' separator, backref
    ReggieMatcher m = Reggie.compile("(a+)-\\1");

    assertTrue(m.matches("aaa-aaa"), "(a+)-\\1 should match 'aaa-aaa'");
    assertTrue(m.matches("a-a"), "Should match 'a-a'");
    assertTrue(m.matches("aaaa-aaaa"), "Should match 'aaaa-aaaa'");
    assertFalse(m.matches("aaa-aa"), "Should not match 'aaa-aa' (length mismatch)");
    assertFalse(m.matches("aaa-aab"), "Should not match 'aaa-aab' (content mismatch)");
  }

  @Test
  void testNoSeparatorDirectBackref() {
    // (.*)\1 - capture any, direct backref (no separator)
    ReggieMatcher m = Reggie.compile("(.*)\\1");

    assertTrue(m.matches("abcabc"), "(.*)\\1 should match 'abcabc'");
    assertTrue(m.matches("xx"), "Should match 'xx'");
    assertTrue(m.matches(""), "Should match '' (empty capture, empty backref)");
    assertFalse(m.matches("abcdef"), "Should not match 'abcdef' (not doubled)");
    assertFalse(m.matches("abc"), "Should not match 'abc' (odd length)");
  }

  @Test
  void testFind() {
    // Find pattern in longer string
    ReggieMatcher m = Reggie.compile("(.+)=\\1");

    assertTrue(m.find("prefix:abc=abc:suffix"), "Should find 'abc=abc' in string");
    assertTrue(m.find("x=x"), "Should find 'x=x'");
    assertFalse(m.find("abc"), "Should not find in 'abc'");
  }

  @Test
  void testFindFrom() {
    ReggieMatcher m = Reggie.compile("(.+)=\\1");

    int pos = m.findFrom("xxx:abc=abc:yyy", 0);
    assertTrue(pos >= 0, "Should find match");
    assertEquals(-1, m.findFrom("no match here", 0), "Should return -1 for no match");
  }

  @Test
  void testBacktrackingBehavior() {
    // This pattern requires backtracking: (.*)(\d+)\1
    // For "abc123abc", must try:
    //   group1="abc123ab" -> backref "abc123ab" not found -> backtrack
    //   group1="abc123a" -> backref "abc123a" not found -> backtrack
    //   ... continue until ...
    //   group1="abc" -> separator "123" -> backref "abc" matches
    ReggieMatcher m = Reggie.compile("(.*)\\d+\\1");

    assertTrue(m.matches("abc123abc"), "Backtracking should find correct split");
    assertTrue(m.matches("a1a"), "Simple case");
    assertTrue(m.matches("12"), "Edge case: empty capture, digit, empty backref");
  }

  @Test
  void testLongerSeparator() {
    // (.*):::\1 - three colons as separator
    ReggieMatcher m = Reggie.compile("(.*):::\\1");

    assertTrue(m.matches("abc:::abc"), "Should match with ::: separator");
    assertTrue(m.matches("x:::x"), "Should match 'x:::x'");
    assertFalse(m.matches("abc::abc"), "Should not match with :: (wrong separator)");
  }
}
