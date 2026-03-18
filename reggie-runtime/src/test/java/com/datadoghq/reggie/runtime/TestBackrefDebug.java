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

public class TestBackrefDebug {

  @Test
  void testSimpleQuantifiedBackref() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a)\\1");

    System.out.println("[DEBUG] Pattern: (a)\\1");
    System.out.println("Strategy: " + System.getProperty("reggie.debug.trace"));
    assertTrue(m.matches("aa"), "Should match 'aa'");
  }

  @Test
  void testQuantifiedBackrefWithBraces() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a)\\1{2}");

    System.out.println("[DEBUG] Pattern: (a)\\1{2}");
    assertTrue(m.matches("aaa"), "Should match 'aaa' (a + a + a)");
  }

  @Test
  void testOptionalGroupWithBackref() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|)\\1");

    System.out.println("[DEBUG] Pattern: (a|)\\1");
    assertTrue(m.matches("aa"), "Should match 'aa'");
    assertTrue(m.matches(""), "Should match '' (empty)");
  }

  @Test
  void testOptionalGroupWithQuantifiedBackref() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|)\\1{2}b");

    System.out.println("[DEBUG] Pattern: (a|)\\1{2}b");
    assertTrue(
        m.matches("b"), "Should match 'b' (group captured empty, \\1{2} matches empty twice)");
    assertTrue(m.matches("aaab"), "Should match 'aaab' (group='a', \\1{2}='aa', then 'b')");
  }
}
