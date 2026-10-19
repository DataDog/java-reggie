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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

/**
 * Recursive-descent patterns with choice points run on {@link BackrefBacktrackMatcher}, including
 * atomic groups whose bodies hold calls, conditionals, recursion and counted loops. java.util.regex
 * parses none of these patterns; the expectations are worked out by hand.
 */
class RdAtomicBacktrackTest {

  @Test
  void alternationIsRetriedWhenTheRestFails() {
    ReggieMatcher m = onBacktracker("(x)?(a|ab)c(?(1)y)(?>q)?");
    assertTrue(m.matches("abc"));
    MatchResult r = m.findMatch("abc");
    assertNotNull(r);
    assertEquals(0, r.start());
    assertEquals(3, r.end());
    assertEquals(0, r.start(2));
    assertEquals(2, r.end(2));
  }

  @Test
  void recursionInsidePossessiveLoop() {
    ReggieMatcher m = Reggie.compile("^(\\((?:[^()]|(?1))*+\\))$");
    assertTrue(m.matches("(a(b)c)"));
    assertTrue(m.matches("((()))"));
    assertFalse(m.matches("(()"));
    assertFalse(m.matches("(a))"));
  }

  /** The inner call re-enters group 1 at the same position, which fails that path. */
  @Test
  void leftRecursionThroughPossessiveFailsThatPath() {
    ReggieMatcher m = onBacktracker("((?1))?+c");
    assertTrue(m.matches("c"));
    MatchResult r = m.findMatch("xc");
    assertNotNull(r);
    assertEquals(1, r.start());
    assertEquals(2, r.end());
    assertEquals(-1, r.start(1));
  }

  @Test
  void conditionalInsideAtomic() {
    ReggieMatcher m = onBacktracker("(a)?(?>(?(1)b|c))d?d");
    assertTrue(m.matches("abd"));
    assertTrue(m.matches("abdd"));
    assertTrue(m.matches("cd"));
    assertFalse(m.matches("ad"));
    assertFalse(m.matches("acd"));
    MatchResult r = m.findMatch("acd");
    assertNotNull(r);
    assertEquals(1, r.start());
    assertEquals(3, r.end());
  }

  /** The atomic call commits to the callee's first match: "ababc" needs (?1) = "ab" at 2. */
  @Test
  void callInsideAtomicIsNotRetried() {
    ReggieMatcher m = onBacktracker("(a|ab)(?>(?1))c");
    assertTrue(m.matches("aac"));
    assertTrue(m.matches("abac"));
    assertFalse(m.matches("aabc"));
    assertFalse(m.matches("ababc"));
    assertTrue(onBacktracker("(a|ab)(?1)c").matches("ababc"));
  }

  /** The counted loop is lowered to markers inside the possessive's atomic body. */
  @Test
  void possessiveCountedLoop() {
    ReggieMatcher m = onBacktracker("(?|(a)|b)(a|ab)x{0,10000}+(x|y)");
    assertTrue(m.matches("aaxxxy"));
    assertTrue(m.matches("babxy"));
    assertFalse(m.matches("aaxxx"));
    assertTrue(m.matches("aa" + "x".repeat(10000) + "x"));
  }

  /** Group 1 is built only for the call: (?:...){0} never runs it in the main flow. */
  @Test
  void callOfAGroupQuantifiedZeroTimes() {
    ReggieMatcher m = onBacktracker("(?:(a)){0}(?1)b?b");
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("abb"));
    assertFalse(m.matches("b"));
    assertFalse(m.matches("aab"));
    MatchResult r = m.findMatch("ab");
    assertNotNull(r);
    assertEquals(-1, r.start(1));
  }

  private static ReggieMatcher onBacktracker(String pattern) {
    ReggieMatcher m = Reggie.compile(pattern);
    assertEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m), pattern);
    return m;
  }
}
