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

/** Simplified tests for recursive patterns to isolate issues. */
public class TestRecursiveSimple {

  @Test
  void testOptionalGroupQuantified() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|)*b");

    System.out.println("[DEBUG] Pattern: (a|)*b");
    assertTrue(m.matches("b"), "Should match 'b'");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("aab"), "Should match 'aab'");
  }

  @Test
  void testSubroutineCallToOptionalGroup() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|)(?1)b");

    System.out.println("[DEBUG] Pattern: (a|)(?1)b");
    assertTrue(m.matches("b"), "Should match 'b' (group captures empty, (?1) matches empty)");
    assertTrue(m.matches("ab"), "Should match 'ab' (group captures empty, (?1) matches 'a')");
    assertTrue(m.matches("aab"), "Should match 'aab' (group captures 'a', (?1) matches 'a')");
  }

  @Test
  void testSubroutineCallToSimpleGroup() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a)(?1)b");

    System.out.println("[DEBUG] Pattern: (a)(?1)b");
    assertTrue(m.matches("aab"), "Should match 'aab'");
  }

  @Test
  void testSubroutineCallToGroupWithAlternation() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|b)(?1)c");

    System.out.println("[DEBUG] Pattern: (a|b)(?1)c");
    assertTrue(m.matches("aac"), "Should match 'aac'");
    assertTrue(m.matches("abc"), "Should match 'abc'");
    assertTrue(m.matches("bac"), "Should match 'bac'");
    assertTrue(m.matches("bbc"), "Should match 'bbc'");
  }

  @Test
  void testRecursiveCallSimple() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("a(?R)?b");

    System.out.println("[DEBUG] Pattern: a(?R)?b");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("aabb"), "Should match 'aabb'");
    assertTrue(m.matches("aaabbb"), "Should match 'aaabbb'");
  }
}
