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

/**
 * Tests that scoped inline flags work correctly across multiple bytecode generation strategies
 * (DFA_UNROLLED, DFA_SWITCH).
 */
public class ScopedFlagStrategyTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void scopedCaseInsensitive_dfaUnrolled() {
    // Small pattern → DFA_UNROLLED
    ReggieMatcher m = Reggie.compile("a(?i:bc)d");
    assertNotNull(m.findMatch("abcd"), "abcd should match");
    assertNotNull(m.findMatch("aBCd"), "aBCd should match (scoped case-insensitive)");
    assertNull(m.findMatch("Abcd"), "Abcd should NOT match (a outside scope)");
    assertNull(m.findMatch("abcD"), "abcD should NOT match (d outside scope)");
  }

  @Test
  void scopedCaseInsensitive_dfaSwitch() {
    // Pattern that uses DFA_SWITCH strategy (moderate size)
    // (?i:[a-z]{1,5}abc[a-z]{1,5}) is DFA_SWITCH range
    ReggieMatcher m = Reggie.compile("(?i:[a-z]{1,5}abc[a-z]{1,5})def");
    assertNotNull(m.findMatch("xyzABCxyzdef"), "should match (ABC case-insensitive)");
    assertNotNull(m.findMatch("xyzabcxyzdef"), "should match (abc lowercase)");
    assertNull(m.findMatch("xyzABCxyzDEF"), "should NOT match (DEF outside scope)");
  }

  @Test
  void globalVsScoped_contrast() {
    // Global (?i) affects everything after it
    ReggieMatcher global = Reggie.compile("(?i)abc");
    assertNotNull(global.findMatch("ABC"), "global: ABC matches");

    // Scoped (?i:...) only affects the group
    ReggieMatcher scoped = Reggie.compile("(?i:abc)def");
    assertNotNull(scoped.findMatch("ABCdef"), "scoped: ABC+def matches");
    assertNull(scoped.findMatch("ABCDef"), "scoped: ABC+Def should not match");
  }
}
