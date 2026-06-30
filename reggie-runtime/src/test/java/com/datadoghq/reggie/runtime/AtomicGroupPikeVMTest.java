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
 * PikeVM-level backtracking-prevention semantics for atomic groups and possessive quantifiers.
 *
 * <p>Atomic groups ({@code (?>X)}) and possessive quantifiers ({@code X++}, {@code X*+}, etc.)
 * prevent backtracking into the matched portion. These patterns are routed to the PikeVM engine,
 * which enforces the commit semantics via atomic-group tracking.
 */
public class AtomicGroupPikeVMTest {

  // -------------------------------------------------------------------------
  // Atomic group backtracking prevention
  // -------------------------------------------------------------------------

  @Test
  void testAtomicGroupPreventsBacktrack_starThenLiteral() {
    // (?>a*)a: atomic group greedily consumes all 'a's; cannot backtrack to let trailing 'a' match.
    assertFalse(Reggie.compile("(?>a*)a").matches("a"));
    assertFalse(Reggie.compile("(?>a*)a").matches("aa"));
    assertFalse(Reggie.compile("(?>a*)a").matches(""));
  }

  @Test
  void testAtomicGroupPreventsBacktrack_plusThenLiteral() {
    // (?>a+)a: atomic group greedily consumes all 'a's; trailing 'a' has nothing to match.
    assertFalse(Reggie.compile("(?>a+)a").matches("a"));
    assertFalse(Reggie.compile("(?>a+)a").matches("aa"));
  }

  @Test
  void testAtomicGroupAllowsMatchWhenSuffixNotConsumed() {
    // (?>a*)b: atomic group commits on 'a's; 'b' can still follow.
    assertTrue(Reggie.compile("(?>a*)b").matches("b"));
    assertTrue(Reggie.compile("(?>a*)b").matches("ab"));
    assertTrue(Reggie.compile("(?>a*)b").matches("aaab"));
  }

  @Test
  void testAtomicGroupContrastWithNonAtomic() {
    // Non-atomic (a*)a can backtrack and succeed; atomic (?>a*)a cannot.
    assertTrue(Reggie.compile("(a*)a").matches("a"));
    assertFalse(Reggie.compile("(?>a*)a").matches("a"));
  }

  // -------------------------------------------------------------------------
  // Possessive quantifier backtracking prevention
  // -------------------------------------------------------------------------

  @Test
  void testPossessivePlusPreventsBacktrack() {
    // a++a: possessive '+' grabs all 'a's; trailing 'a' cannot match.
    assertFalse(Reggie.compile("a++a").matches("aa"));
    assertFalse(Reggie.compile("a++a").matches("aaa"));
  }

  @Test
  void testPossessiveStarPreventsBacktrack() {
    // a*+a: possessive '*' grabs all 'a's; trailing 'a' cannot match.
    assertFalse(Reggie.compile("a*+a").matches("a"));
    assertFalse(Reggie.compile("a*+a").matches("aa"));
  }

  @Test
  void testPossessiveQuestionPreventsBacktrack() {
    // a?+a: possessive '?' grabs the one 'a'; trailing 'a' has nothing to match.
    assertFalse(Reggie.compile("a?+a").matches("a"));
  }

  @Test
  void testPossessiveAllowsMatchWhenSuffixNotConsumed() {
    // a*+b: possessive commits on 'a's; 'b' can still follow.
    assertTrue(Reggie.compile("a*+b").matches("b"));
    assertTrue(Reggie.compile("a*+b").matches("ab"));
    assertTrue(Reggie.compile("a*+b").matches("aaab"));
  }
}
