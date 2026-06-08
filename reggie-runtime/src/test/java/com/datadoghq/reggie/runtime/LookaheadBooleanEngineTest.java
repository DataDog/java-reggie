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
 * Test that lookahead patterns (HYBRID_DFA_LOOKAHEAD, SPECIALIZED_MULTIPLE_LOOKAHEADS,
 * SPECIALIZED_LITERAL_LOOKAHEADS) work correctly.
 *
 * <p>{@code matches()} requires the pattern to consume the entire input; a pattern like {@code
 * a(?=b)} consumes only the 'a' character, so it cannot fully match a string longer than "a", and
 * even "a" fails because the lookahead requires a 'b' to follow.
 *
 * <p>{@code find()} / {@code findFrom()} look for the pattern anywhere in the input.
 */
public class LookaheadBooleanEngineTest {

  @Test
  void matchesWholeStringOnly() {
    // a(?=b) consumes only 'a'; lookahead is zero-width.
    // matches() requires full-string consumption — no string of length > 1 can satisfy it,
    // and even length-1 "a" fails because the lookahead requires 'b' to follow.
    ReggieMatcher m = Reggie.compile("a(?=b)");
    assertFalse(m.matches("ab")); // 'b' not consumed — full match fails
    assertFalse(m.matches("abc")); // 'b','c' not consumed — full match fails
    assertFalse(m.matches("xab")); // starts with 'x', NFA never accepts
    assertFalse(m.matches("a")); // lookahead requires 'b' to follow, nothing does
  }

  @Test
  void findLocatesMatch() {
    // find() looks for substring matches — a(?=b) matches 'a' when followed by 'b'.
    ReggieMatcher m = Reggie.compile("a(?=b)");
    assertTrue(m.find("xaby")); // 'a' at pos 1 is followed by 'b'
    assertFalse(m.find("xacy")); // 'a' at pos 1 is followed by 'c', not 'b'
  }

  @Test
  void findFromStartsAtOffset() {
    ReggieMatcher m = Reggie.compile("a(?=b)");
    assertEquals(1, m.findFrom("xab", 0)); // match starts at pos 1
    assertEquals(-1, m.findFrom("xab", 2)); // start after match position
  }

  @Test
  void negativeLookaheadMatches() {
    // a(?!b) consumes only 'a'; full-string match requires the string to BE exactly
    // the consumed portion with no trailing characters.
    ReggieMatcher m = Reggie.compile("a(?!b)");
    assertFalse(m.matches("ac")); // 'c' is not consumed — full match fails
    assertFalse(m.matches("ab")); // negative lookahead fails because 'b' follows 'a'
    assertFalse(m.matches("acc")); // 'c','c' not consumed — full match fails
    // "a" at end of string: negative lookahead (?!b) succeeds (nothing follows), pattern
    // matches/consumes 'a', and that equals the whole string.
    assertTrue(m.matches("a")); // only 'a' — consumed fully, lookahead passes
  }

  @Test
  void negativeLookaheadFind() {
    ReggieMatcher m = Reggie.compile("a(?!b)");
    assertTrue(m.find("xac")); // 'a' at pos 1 not followed by 'b'
    assertFalse(m.find("xab")); // every 'a' is followed by 'b'
  }

  @Test
  void multipleLookaheads() {
    // SPECIALIZED_MULTIPLE_LOOKAHEADS: (?=a)(?=.)a — two lookaheads then 'a'
    // consumes only 'a', so matches() fails on strings longer than 1 char.
    ReggieMatcher m = Reggie.compile("(?=a)(?=.)a");
    assertTrue(m.matches("a")); // full string is 'a' — consumed exactly
    assertFalse(m.matches("ab")); // 'b' not consumed
  }
}
