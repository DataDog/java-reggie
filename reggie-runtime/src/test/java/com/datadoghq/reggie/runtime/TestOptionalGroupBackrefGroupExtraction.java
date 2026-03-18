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

/** Tests for group extraction in optional group backreference patterns. */
public class TestOptionalGroupBackrefGroupExtraction {

  @Test
  void testGroupExtractionWithNonEmptyMatch() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1{2}b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1{2}b, input: 'aaab'");
    MatchResult r = m.match("aaab");
    assertNotNull(r, "Should match 'aaab'");
    assertEquals("aaab", r.group(0), "Full match should be 'aaab'");
    assertEquals("a", r.group(1), "Group 1 should be 'a'");
  }

  @Test
  void testGroupExtractionWithEmptyMatch() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1{2}b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1{2}b, input: 'b'");
    MatchResult r = m.match("b");
    assertNotNull(r, "Should match 'b'");
    assertEquals("b", r.group(0), "Full match should be 'b'");
    assertEquals("", r.group(1), "Group 1 should be empty string");
  }

  @Test
  void testGroupExtractionWithPlusQuantifier() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a|)\\1+b");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1+b, input: 'aab'");
    MatchResult r1 = m.match("aab");
    assertNotNull(r1, "Should match 'aab'");
    assertEquals("aab", r1.group(0), "Full match should be 'aab'");
    assertEquals("a", r1.group(1), "Group 1 should be 'a'");

    System.out.println("[DEBUG] Pattern: ^(a|)\\1+b, input: 'b'");
    MatchResult r2 = m.match("b");
    assertNotNull(r2, "Should match 'b'");
    assertEquals("b", r2.group(0), "Full match should be 'b'");
    assertEquals("", r2.group(1), "Group 1 should be empty string");
  }
}
