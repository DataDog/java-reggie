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
import com.datadoghq.reggie.ReggieMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the RECURSIVE_DESCENT structural-cache collision. Two patterns with
 * identical AST shape but different literal characters must compile to distinct classes and produce
 * correct match results.
 */
class RecursiveDescentCacheCollisionTest {

  @BeforeEach
  void clearCache() {
    Reggie.clearCache();
  }

  @Test
  void subroutinePatternsDifferingInLiteralDoNotCollide() {
    // Both patterns route to RECURSIVE_DESCENT. Before the fix, the second compile returned the
    // class for "(abc)(?1)" because ASTStructuralHashVisitor dropped node.ch.
    ReggieMatcher abcMatcher = Reggie.compile("(abc)(?1)");
    ReggieMatcher xyzMatcher = Reggie.compile("(xyz)(?1)");

    assertTrue(abcMatcher.matches("abcabc"), "(abc)(?1) must match 'abcabc'");
    assertFalse(abcMatcher.matches("xyzxyz"), "(abc)(?1) must not match 'xyzxyz'");

    assertTrue(xyzMatcher.matches("xyzxyz"), "(xyz)(?1) must match 'xyzxyz'");
    assertFalse(xyzMatcher.matches("abcabc"), "(xyz)(?1) must not match 'abcabc'");
  }

  @Test
  void conditionalPatternsDifferingInLiteralDoNotCollide() {
    ReggieMatcher thenB = Reggie.compile("(a)(?(1)b|c)");
    ReggieMatcher thenX = Reggie.compile("(a)(?(1)x|y)");

    assertTrue(thenB.matches("ab"), "(a)(?(1)b|c) must match 'ab'");
    assertFalse(thenB.matches("ax"), "(a)(?(1)b|c) must not match 'ax'");

    assertTrue(thenX.matches("ax"), "(a)(?(1)x|y) must match 'ax'");
    assertFalse(thenX.matches("ab"), "(a)(?(1)x|y) must not match 'ab'");
  }

  @Test
  void conditionalPatternsDifferingInCharClassDoNotCollide() {
    ReggieMatcher digits = Reggie.compile("(a)(?(1)[0-9])");
    ReggieMatcher lower = Reggie.compile("(a)(?(1)[a-z])");

    assertTrue(digits.matches("a5"), "(a)(?(1)[0-9]) must match 'a5'");
    assertFalse(digits.matches("ab"), "(a)(?(1)[0-9]) must not match 'ab'");

    assertTrue(lower.matches("ab"), "(a)(?(1)[a-z]) must match 'ab'");
    assertFalse(lower.matches("a5"), "(a)(?(1)[a-z]) must not match 'a5'");
  }
}
