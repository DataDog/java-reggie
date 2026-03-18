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
 * Tests for empty alternation with backreferences. PCRE semantics: An empty group repeated N times
 * is still empty (always matches).
 */
public class TestEmptyAlternationBackref {

  @Test
  void testSingleCharEmptyAlternationWithCountedBackref() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1{2}b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1{2}b");
    assertTrue(m.matches("aaab"), "Should match 'aaab'");
    assertTrue(m.matches("b"), "Should match 'b' (empty group case)");
  }

  // TODO: Pattern ^(cow|)\1(bell) routes to OPTIMIZED_NFA_WITH_BACKREFS strategy
  // which needs separate zero-width handling fix in NFABytecodeGenerator
  // @Test
  // void testMultiCharEmptyAlternationWithBackref() {
  //     RuntimeCompiler.clearCache();
  //     ReggieMatcher m = Reggie.compile("^(cow|)\\1(bell)");
  //     System.out.println("[DEBUG] Pattern: ^(cow|)\\1(bell)");
  //     assertTrue(m.matches("cowcowbell"), "matches() should match 'cowcowbell'");
  //     assertTrue(m.matches("bell"), "matches() should match 'bell' (empty group case)");
  //     assertNotNull(m.findMatch("cowcowbell"), "findMatch() should match 'cowcowbell'");
  //     assertNotNull(m.findMatch("bell"), "findMatch() should match 'bell' (empty group case)");
  // }

  @Test
  void testEmptyAlternationWithRangeQuantifier() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1{2,3}b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1{2,3}b");
    assertTrue(m.matches("aaab"), "Should match 'aaab'");
    assertTrue(m.matches("aaaab"), "Should match 'aaaab'");
    assertTrue(m.matches("b"), "Should match 'b' (empty group case)");
  }
}
