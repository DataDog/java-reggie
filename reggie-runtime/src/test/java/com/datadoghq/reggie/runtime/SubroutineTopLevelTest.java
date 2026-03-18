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

/** Test if the issue is with top-level groups. */
class SubroutineTopLevelTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testNonCapturingGroupWithLiteral() {
    ReggieMatcher m = Reggie.compile("(?:a)");
    assertTrue(m.matches("a"));
  }

  @Test
  void testAlternationWithoutGroup() {
    // No group wrapper - just the alternation
    ReggieMatcher m = Reggie.compile("a|b");
    assertTrue(m.matches("a"));
    assertTrue(m.matches("b"));
  }

  @Test
  void testAlternationWithSubroutineWithoutGroup() {
    // Pattern without the non-capturing group: [^()]|(?R)
    // BUT you can't have this at top level in PCRE - (?R) must be inside something
    // So let's test with a prefix: x[^()]|(?R)
    ReggieMatcher m = Reggie.compile("x|(?R)");
    assertTrue(m.matches("x"), "Should match 'x' via first alternative");
  }

  @Test
  void testCharClassOrSubroutineInGroup() {
    // Test [^()] OR (?R) inside a capturing group
    ReggieMatcher m = Reggie.compile("([^()]|(?R))");
    assertTrue(m.matches("a"), "Capturing group with alternation");
  }
}
