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

public class InlineFlagCaseTest {

  @Test
  void globalCaseInsensitive_simplePattern() {
    ReggieMatcher m = Reggie.compile("(?i)ab");
    assertNotNull(m.findMatch("AB"), "AB should match (?i)ab");
    assertNotNull(m.findMatch("aB"), "aB should match (?i)ab");
    assertNotNull(m.findMatch("ab"), "ab should match (?i)ab");
  }

  @Test
  void globalCaseInsensitive_charClass() {
    ReggieMatcher m = Reggie.compile("(?i)[b-c]");
    assertNotNull(m.findMatch("B"), "B should match (?i)[b-c]");
    assertNotNull(m.findMatch("C"), "C should match (?i)[b-c]");
  }

  @Test
  void mixedScopedFlags_fullPcreCase() {
    // From PCRE test suite — all 6 cases from pcre-capturing-groups.txt lines 281-286
    ReggieMatcher m1 = Reggie.compile("^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)");
    // branch 1 literal "ab"
    var r1 = m1.findMatch("ab");
    assertNotNull(r1, "ab should match ^(ab|...)");
    assertEquals("ab", r1.group(1), "group 1 should be 'ab'");
    // branch 3 literal "xy"
    var r2 = m1.findMatch("xy");
    assertNotNull(r2, "xy should match ^(...|xy|...)");
    assertEquals("xy", r2.group(1), "group 1 should be 'xy'");
    // branch 4 literal "z" (only first char of "zebra")
    var r3 = m1.findMatch("zebra");
    assertNotNull(r3, "zebra should match ^(...|z)");
    assertEquals("z", r3.group(1), "group 1 should be 'z'");

    ReggieMatcher m2 = Reggie.compile("(?i)^(ab|a(?i)[b-c](?m-i)d|x(?i)y|z)");
    // case-insensitive branch 1: "aBd" — branch 1 (?i)ab matches "aB" first (NFA first-alt wins)
    var r4 = m2.findMatch("aBd");
    assertNotNull(r4, "aBd should match (?i)^(ab|...)");
    assertEquals("aB", r4.group(1), "group 1 should be 'aB' (branch 1 wins)");
    // case-insensitive branch 3: "xY"
    var r5 = m2.findMatch("xY");
    assertNotNull(r5, "xY should match (?i)^(...|xy|...)");
    assertEquals("xY", r5.group(1), "group 1 should be 'xY'");
    // case-insensitive branch 4: "Zambesi" — only 'Z'
    var r6 = m2.findMatch("Zambesi");
    assertNotNull(r6, "Zambesi should match (?i)^(...|z)");
    assertEquals("Z", r6.group(1), "group 1 should be 'Z'");
  }
}
