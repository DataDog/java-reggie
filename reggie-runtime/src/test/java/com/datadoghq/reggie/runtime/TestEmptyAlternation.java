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

/** Tests for empty alternations in patterns. */
public class TestEmptyAlternation {

  @Test
  void testEmptyAlternationAtEnd() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("a|");

    System.out.println("[DEBUG] Pattern: a|");
    assertTrue(m.matches("a"), "Should match 'a'");
    assertTrue(m.matches(""), "Should match empty string");
  }

  @Test
  void testEmptyAlternationInGroup() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|)b");

    System.out.println("[DEBUG] Pattern: (a|)b");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("b"), "Should match 'b'");
  }

  @Test
  void testMultiCharEmptyAlternation() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(abc|)def");

    System.out.println("[DEBUG] Pattern: (abc|)def");
    assertTrue(m.matches("abcdef"), "Should match 'abcdef'");
    assertTrue(m.matches("def"), "Should match 'def'");
  }
}
