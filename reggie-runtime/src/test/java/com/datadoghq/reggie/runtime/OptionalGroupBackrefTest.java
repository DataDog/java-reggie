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
 * Tests for OPTIONAL_GROUP_BACKREF strategy. Patterns like (a)?\1 where backreference refers to
 * optional group.
 *
 * <p>PCRE Semantics: - If optional group matched: backref must match captured content - If optional
 * group didn't match: backref matches empty string
 */
class OptionalGroupBackrefTest {

  @Test
  void testSimpleOptionalBackref() {
    // (a)?\1 - optional 'a', then backref
    // Matches: "" (group not matched, backref matches empty)
    // Matches: "aa" (group matched 'a', backref matches 'a')
    // Does NOT match: "a" (group matched 'a', backref expects 'a' but only empty remains)
    ReggieMatcher m = Reggie.compile("(a)?\\1");

    assertTrue(m.matches(""), "(a)?\\1 should match '' (empty - group not matched, backref empty)");
    assertTrue(m.matches("aa"), "(a)?\\1 should match 'aa' (group='a', backref='a')");
    assertFalse(m.matches("a"), "Should not match 'a' (group='a' but no room for backref)");
    assertFalse(m.matches("ab"), "Should not match 'ab'");
    assertFalse(m.matches("aaa"), "Should not match 'aaa' (extra char)");
  }

  @Test
  void testOptionalBackrefWithDifferentChar() {
    // (x)?\1
    ReggieMatcher m = Reggie.compile("(x)?\\1");

    assertTrue(m.matches(""), "Should match empty string");
    assertTrue(m.matches("xx"), "Should match 'xx'");
    assertFalse(m.matches("x"), "Should not match 'x'");
    assertFalse(m.matches("xy"), "Should not match 'xy'");
  }

  @Test
  void testMultipleOptionalGroups() {
    // (a)?(b)?\1\2 - two optional groups with backrefs
    // Pattern matches sequentially: try (a)?, then (b)?, then \1, then \2
    // Matches: "" (neither matched, both backrefs match empty)
    // Matches: "aa" (group1='a', group2=unmatched, \1='a', \2=empty)
    // Matches: "bb" (group1=unmatched, group2='b', \1=empty, \2='b')
    // Matches: "abab" (group1='a', group2='b', \1='a', \2='b')
    // Does NOT match: "aabb" (group1='a' at 0, group2 fails at 1 since 'a'!='b',
    //                         \1='a' at 1, \2=empty, pos=2 != len=4)
    ReggieMatcher m = Reggie.compile("(a)?(b)?\\1\\2");

    assertTrue(m.matches(""), "Should match '' (neither group matched)");
    assertTrue(m.matches("aa"), "Should match 'aa' (first matched, second not)");
    assertTrue(m.matches("bb"), "Should match 'bb' (first not, second matched)");
    assertFalse(m.matches("aabb"), "Should NOT match 'aabb' (group2 fails at pos 1)");
    assertTrue(m.matches("abab"), "Should match 'abab' (both matched sequentially)");
    assertFalse(m.matches("a"), "Should not match 'a'");
    assertFalse(m.matches("ab"), "Should not match 'ab'");
    assertFalse(m.matches("abc"), "Should not match 'abc'");
  }

  @Test
  void testOptionalGroupWithAnchors() {
    // ^(a)?\1$ - anchored
    ReggieMatcher m = Reggie.compile("^(a)?\\1$");

    assertTrue(m.matches(""), "Should match ''");
    assertTrue(m.matches("aa"), "Should match 'aa'");
    assertFalse(m.matches("a"), "Should not match 'a'");
    assertFalse(m.matches("aaa"), "Should not match 'aaa'");
  }

  @Test
  void testFind() {
    // Find pattern in longer string
    ReggieMatcher m = Reggie.compile("(a)?\\1");

    assertTrue(m.find("xaay"), "Should find 'aa' in 'xaay'");
    assertTrue(m.find("xy"), "Should find '' in 'xy' (empty match)");
    assertTrue(m.find("aa"), "Should find in 'aa'");
  }

  @Test
  void testFindFrom() {
    ReggieMatcher m = Reggie.compile("(a)?\\1");

    // Note: This pattern can match empty string, so it will find at position 0
    int pos = m.findFrom("xaay", 0);
    assertTrue(pos >= 0, "Should find a match");
  }

  @Test
  void testBackrefOrderMatters() {
    // The pattern (a)?(b)?\2\1 has backrefs in different order
    // Pattern matches: try (a)?, then (b)?, then \2, then \1
    // This test verifies correct group-to-backref mapping
    ReggieMatcher m = Reggie.compile("(a)?(b)?\\2\\1");

    assertTrue(m.matches(""), "Should match '' (neither group matched)");
    // "ba" does NOT match: (a)? fails at 'b', (b)? matches 'b' at pos 0,
    // then \2 needs 'b' at pos 1 but input[1]='a' → FAIL
    assertFalse(m.matches("ba"), "Should NOT match 'ba' (backref \\2 needs 'b' but finds 'a')");
    // "bb" matches: (a)? fails, (b)? matches 'b', \2='b', \1=empty
    assertTrue(m.matches("bb"), "Should match 'bb' (group2='b', group1 unmatched)");
    // "abba" matches: (a)? matches 'a', (b)? matches 'b', \2='b', \1='a'
    assertTrue(m.matches("abba"), "Should match 'abba'");
  }
}
