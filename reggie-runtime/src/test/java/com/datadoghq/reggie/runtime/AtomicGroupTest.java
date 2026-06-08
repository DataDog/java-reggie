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
 * Atomic groups (?>X): DFA-based engines have no backtracking, so atomic groups have the same
 * language semantics as non-capturing groups. The parser already handles (?>...) at line 294-298 of
 * RegexParser.java.
 */
public class AtomicGroupTest {

  @Test
  void testAtomicGroupBasic() {
    assertTrue(Reggie.compile("(?>abc)").matches("abc"));
    assertFalse(Reggie.compile("(?>abc)").matches("ab"));
    assertFalse(Reggie.compile("(?>abc)").matches("abcd"));
  }

  @Test
  void testAtomicGroupWithQuantifier() {
    // (?>a+)b: in a DFA engine, equivalent to a+b
    assertTrue(Reggie.compile("(?>a+)b").matches("aab"));
    assertTrue(Reggie.compile("(?>a+)b").matches("ab"));
    assertFalse(Reggie.compile("(?>a+)b").matches("b"));
  }

  @Test
  void testAtomicGroupFind() {
    // (?>a*)b: find in "ab"
    assertNotNull(Reggie.compile("(?>a*)b").findMatch("ab"));
    assertNotNull(Reggie.compile("(?>a*)b").findMatch("b"));
    assertNull(Reggie.compile("(?>a*)b").findMatch("aac"));
  }

  @Test
  void testAtomicGroupNested() {
    // Nested atomic group
    assertTrue(Reggie.compile("(?>(?>[abc]+))").matches("abc"));
    assertFalse(Reggie.compile("(?>(?>[abc]+))").matches("xyz"));
  }
}
