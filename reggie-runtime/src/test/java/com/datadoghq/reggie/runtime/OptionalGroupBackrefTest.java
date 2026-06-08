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
 * Tests for OPTIONAL_GROUP_BACKREF strategy. Patterns like (a)?\1 where backreference refers to
 * optional group.
 *
 * <p>Java semantics (verified against JDK): - If optional group matched: backref must match
 * captured content - If optional group did NOT participate (was skipped): backref FAILS to match
 */
class OptionalGroupBackrefTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testSimpleOptionalBackref() {
    // (a)?\1 - optional 'a', then backref
    // Does NOT match: "" (group didn't participate — \1 fails per Java semantics)
    // Matches: "aa" (group matched 'a', backref matches 'a')
    // Does NOT match: "a" (group matched 'a', backref expects 'a' but only empty remains)
    ReggieMatcher m = Reggie.compile("(a)?\\1");

    assertFalse(m.matches(""), "(a)?\\1 should NOT match '' — unmatched group makes \\1 fail");
    assertTrue(m.matches("aa"), "(a)?\\1 should match 'aa' (group='a', backref='a')");
    assertFalse(m.matches("a"), "Should not match 'a' (group='a' but no room for backref)");
    assertFalse(m.matches("ab"), "Should not match 'ab'");
    assertFalse(m.matches("aaa"), "Should not match 'aaa' (extra char)");
  }

  @Test
  void testOptionalBackrefWithDifferentChar() {
    // (x)?\1
    ReggieMatcher m = Reggie.compile("(x)?\\1");

    assertFalse(m.matches(""), "Should NOT match empty string — unmatched group makes \\1 fail");
    assertTrue(m.matches("xx"), "Should match 'xx'");
    assertFalse(m.matches("x"), "Should not match 'x'");
    assertFalse(m.matches("xy"), "Should not match 'xy'");
  }

  @Test
  void testMultipleOptionalGroups() {
    // (a)?(b)?\1\2 - two optional groups with backrefs
    // Java semantics: when an optional group didn't participate, \N to it FAILS.
    // Matches: "abab" (group1='a', group2='b', \1='a', \2='b')
    // Does NOT match "" (neither group participated → \1 and \2 fail)
    // Does NOT match "aa" (group2 unmatched → \2 fails)
    // Does NOT match "bb" (group1 unmatched → \1 fails)
    // Does NOT match "aabb"
    ReggieMatcher m = Reggie.compile("(a)?(b)?\\1\\2");

    assertFalse(m.matches(""), "Should NOT match '' (unmatched groups make backrefs fail)");
    assertFalse(m.matches("aa"), "Should NOT match 'aa' (group2 unmatched → \\2 fails)");
    assertFalse(m.matches("bb"), "Should NOT match 'bb' (group1 unmatched → \\1 fails)");
    assertFalse(m.matches("aabb"), "Should NOT match 'aabb'");
    assertTrue(m.matches("abab"), "Should match 'abab' (both matched sequentially)");
    assertFalse(m.matches("a"), "Should not match 'a'");
    assertFalse(m.matches("ab"), "Should not match 'ab'");
    assertFalse(m.matches("abc"), "Should not match 'abc'");
  }

  @Test
  void testOptionalGroupWithAnchors() {
    // ^(a)?\1$ - anchored
    ReggieMatcher m = Reggie.compile("^(a)?\\1$");

    assertFalse(m.matches(""), "Should NOT match '' — unmatched group makes \\1 fail");
    assertTrue(m.matches("aa"), "Should match 'aa'");
    assertFalse(m.matches("a"), "Should not match 'a'");
    assertFalse(m.matches("aaa"), "Should not match 'aaa'");
  }

  @Test
  void testFind() {
    // Find pattern in longer string
    ReggieMatcher m = Reggie.compile("(a)?\\1");

    assertTrue(m.find("xaay"), "Should find 'aa' in 'xaay'");
    assertFalse(m.find("xy"), "Should NOT find in 'xy' — no 'a' so group never matches");
    assertTrue(m.find("aa"), "Should find in 'aa'");
  }

  @Test
  void testFindFrom() {
    ReggieMatcher m = Reggie.compile("(a)?\\1");

    // Note: match at pos=1 ("aa" in "xaay")
    int pos = m.findFrom("xaay", 0);
    assertTrue(pos >= 0, "Should find a match");
  }

  @Test
  void testBackrefOrderMatters() {
    // The pattern (a)?(b)?\2\1 has backrefs in different order
    // Java semantics: when an optional group didn't participate, \N to it FAILS.
    // Matches: "abba" (group1='a', group2='b', \2='b', \1='a')
    // Does NOT match "" (neither group participated → backrefs fail)
    // Does NOT match "bb" (group1 unmatched → \1 fails)
    ReggieMatcher m = Reggie.compile("(a)?(b)?\\2\\1");

    assertFalse(m.matches(""), "Should NOT match '' (unmatched groups make backrefs fail)");
    assertFalse(m.matches("ba"), "Should NOT match 'ba' (backref \\2 needs 'b' but finds 'a')");
    assertFalse(m.matches("bb"), "Should NOT match 'bb' (group1 unmatched → \\1 fails)");
    assertTrue(m.matches("abba"), "Should match 'abba'");
  }

  @Test
  void nonParticipatingGroupBackrefFails() {
    // Java semantics: when (a)? skips, \1 fails
    ReggieMatcher m = Reggie.compile("(a)?\\1");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(a)?\\1");
    assertEquals(jdk.matcher("").matches(), m.matches(""), "empty string");
    assertEquals(jdk.matcher("aa").matches(), m.matches("aa"), "'aa'");
    assertEquals(jdk.matcher("a").matches(), m.matches("a"), "single 'a'");
  }

  @Test
  void nonNullableOptionalGroupIsNative() {
    ReggieMatcher m = Reggie.compile("(a)?\\1");
    assertFalse(m instanceof JavaRegexFallbackMatcher, "(a)?\\1 must be native");
  }
}
