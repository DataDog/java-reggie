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
 * Possessive quantifiers (X*+, X++, X?+, X{n,m}+): semantically equivalent to greedy for DFA-based
 * engines since there is no backtracking. The parser must accept them without throwing.
 */
public class PossessiveQuantifierTest {

  @Test
  void testStarPossessive() {
    // a*+ matches same inputs as a*
    assertTrue(Reggie.compile("a*+").matches(""));
    assertTrue(Reggie.compile("a*+").matches("aaa"));
    assertFalse(Reggie.compile("a*+").matches("b"));
  }

  @Test
  void testPlusPossessive() {
    // a++ matches same inputs as a+
    assertFalse(Reggie.compile("a++").matches(""));
    assertTrue(Reggie.compile("a++").matches("a"));
    assertTrue(Reggie.compile("a++").matches("aaa"));
  }

  @Test
  void testQuestionPossessive() {
    // a?+ matches same inputs as a?
    assertTrue(Reggie.compile("a?+").matches(""));
    assertTrue(Reggie.compile("a?+").matches("a"));
    assertFalse(Reggie.compile("a?+").matches("aa"));
  }

  @Test
  void testCountedPossessive() {
    // a{2,4}+ matches same inputs as a{2,4}
    assertTrue(Reggie.compile("a{2,4}+").matches("aa"));
    assertTrue(Reggie.compile("a{2,4}+").matches("aaa"));
    assertTrue(Reggie.compile("a{2,4}+").matches("aaaa"));
    assertFalse(Reggie.compile("a{2,4}+").matches("a"));
    assertFalse(Reggie.compile("a{2,4}+").matches("aaaaa"));
  }

  @Test
  void testPossessiveInContext() {
    // (a*+)b: possessive quantifier inside a group followed by literal
    assertTrue(Reggie.compile("(a*+)b").matches("aaab"));
    assertFalse(Reggie.compile("(a*+)b").matches("aaac"));
  }
}
