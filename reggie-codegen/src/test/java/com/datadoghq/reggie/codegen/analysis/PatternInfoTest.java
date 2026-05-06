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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for PatternInfo data classes: QuantifiedGroupInfo and
 * NestedQuantifiedGroupsInfo. Covers branches in toString(), structuralHashCode(), and helper
 * methods.
 */
class PatternInfoTest {

  // ── QuantifiedGroupInfo ─────────────────────────────────────────────────────

  private static QuantifiedGroupInfo makeBasic(
      String literal, CharSet charSet, boolean isAlternation) {
    return new QuantifiedGroupInfo(
        1, null, 1, Integer.MAX_VALUE, charSet, literal, isAlternation, null);
  }

  @Test
  void quantifiedGroupInfoIsUnbounded() {
    QuantifiedGroupInfo unbounded = makeBasic(null, CharSet.DIGIT, false);
    assertTrue(unbounded.isUnbounded());

    QuantifiedGroupInfo bounded = new QuantifiedGroupInfo(1, null, 1, 3, CharSet.DIGIT, null, false, null);
    assertFalse(bounded.isUnbounded());
  }

  @Test
  void quantifiedGroupInfoIsSingleCharLiteral() {
    QuantifiedGroupInfo single = makeBasic("a", null, false);
    assertTrue(single.isSingleCharLiteral());

    QuantifiedGroupInfo multi = makeBasic("abc", null, false);
    assertFalse(multi.isSingleCharLiteral());

    QuantifiedGroupInfo noLiteral = makeBasic(null, CharSet.DIGIT, false);
    assertFalse(noLiteral.isSingleCharLiteral());
  }

  @Test
  void quantifiedGroupInfoIsCharClass() {
    QuantifiedGroupInfo charClass = makeBasic(null, CharSet.DIGIT, false);
    assertTrue(charClass.isCharClass());

    QuantifiedGroupInfo alternation = makeBasic(null, CharSet.DIGIT, true);
    assertFalse(alternation.isCharClass());

    QuantifiedGroupInfo noCharSet = makeBasic("a", null, false);
    assertFalse(noCharSet.isCharClass());
  }

  @Test
  void quantifiedGroupInfoToStringLiteralNonNegated() {
    QuantifiedGroupInfo info = makeBasic("x", null, false);
    String s = info.toString();
    assertTrue(s.contains("literal='x'"));
    assertFalse(s.contains("charset"));
    assertFalse(s.contains("alternation"));
  }

  @Test
  void quantifiedGroupInfoToStringCharsetNegated() {
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        1, null, 0, Integer.MAX_VALUE, CharSet.LOWER, null, false, null,
        false, null, null, null, null, false, 1, 1, true);
    String s = info.toString();
    assertTrue(s.contains("charset(negated)"));
  }

  @Test
  void quantifiedGroupInfoToStringCharsetNotNegated() {
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        1, null, 0, Integer.MAX_VALUE, CharSet.LOWER, null, false, null,
        false, null, null, null, null, false, 1, 1, false);
    String s = info.toString();
    assertTrue(s.contains("charset"));
    assertFalse(s.contains("negated"));
  }

  @Test
  void quantifiedGroupInfoToStringAlternationWithNegated() {
    CharSet[] altSets = {CharSet.DIGIT, CharSet.LOWER};
    boolean[] negated = {false, true};
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        1, null, 1, Integer.MAX_VALUE, null, null, true, altSets,
        false, null, null, negated, null, false, 1, 1, false);
    String s = info.toString();
    assertTrue(s.contains("alternation"));
    assertTrue(s.contains("negated=[false,true]"));
  }

  @Test
  void quantifiedGroupInfoToStringAlternationNullNegated() {
    CharSet[] altSets = {CharSet.DIGIT};
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        1, null, 1, Integer.MAX_VALUE, null, null, true, altSets,
        false, null, null, null, null, false, 1, 1, false);
    String s = info.toString();
    assertTrue(s.contains("alternation"));
    assertFalse(s.contains("negated="));
  }

  @Test
  void quantifiedGroupInfoToStringNestedQuantifier() {
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        1, null, 0, Integer.MAX_VALUE, CharSet.LOWER, null, false, null,
        false, null, null, null, null, true, 1, Integer.MAX_VALUE, false);
    String s = info.toString();
    assertTrue(s.contains("nested{"));
  }

  @Test
  void quantifiedGroupInfoStructuralHashAllPaths() {
    // alternationCharSets with null and non-null entries, alternationNegated
    CharSet[] altSets = {CharSet.DIGIT, null};
    boolean[] negated = {true, false};
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        2, null, 1, 5, CharSet.LOWER, "a", true, altSets,
        true, new int[]{1, 2}, new int[]{3, 4}, negated, null,
        true, 1, Integer.MAX_VALUE, true);
    int h = info.structuralHashCode();
    assertNotEquals(0, h);
  }

  @Test
  void quantifiedGroupInfoStructuralHashNullSets() {
    // No charSet, no alternation, no nested
    QuantifiedGroupInfo info = new QuantifiedGroupInfo(
        1, null, 1, 3, null, null, false, null);
    int h = info.structuralHashCode();
    assertNotEquals(0, h);
  }

  // ── NestedQuantifiedGroupsInfo ───────────────────────────────────────────────

  private static NestedQuantifiedGroupsInfo.QuantifierLevel level(
      int min, int max, int group, CharSet cs, String lit) {
    return new NestedQuantifiedGroupsInfo.QuantifierLevel(min, max, group, null, cs, lit);
  }

  @Test
  void nestedQuantifiedGroupsInfoIsUnbounded() {
    var lvl = level(1, Integer.MAX_VALUE, 1, null, null);
    assertTrue(lvl.isUnbounded());
    var lvl2 = level(1, 3, 1, null, null);
    assertFalse(lvl2.isUnbounded());
  }

  @Test
  void nestedQuantifiedGroupsInfoGetOuterAndInnerEmpty() {
    NestedQuantifiedGroupsInfo info = new NestedQuantifiedGroupsInfo(
        List.of(), List.of(), List.of(), false, false, 0);
    assertNull(info.getOuterLevel());
    assertNull(info.getInnerLevel());
    assertEquals(0, info.getNestingDepth());
  }

  @Test
  void nestedQuantifiedGroupsInfoGetOuterAndInnerNonEmpty() {
    var lvl1 = level(0, Integer.MAX_VALUE, 1, CharSet.DIGIT, null);
    var lvl2 = level(1, Integer.MAX_VALUE, -1, CharSet.LOWER, null);
    NestedQuantifiedGroupsInfo info = new NestedQuantifiedGroupsInfo(
        List.of(lvl1, lvl2), List.of(), List.of(), false, false, 1);
    assertSame(lvl1, info.getOuterLevel());
    assertSame(lvl2, info.getInnerLevel());
    assertEquals(2, info.getNestingDepth());
  }

  @Test
  void nestedQuantifiedGroupsInfoToStringAllBranches() {
    var lvl1 = level(0, Integer.MAX_VALUE, 1, CharSet.DIGIT, null);
    var lvl2 = level(1, 5, -1, null, "abc");
    RegexNode dummyNode = new CharClassNode(CharSet.DIGIT, false);
    // prefix non-empty, suffix non-empty, anchors set
    NestedQuantifiedGroupsInfo info = new NestedQuantifiedGroupsInfo(
        List.of(lvl1, lvl2),
        List.of(dummyNode),
        List.of(dummyNode),
        true, true, 2);
    String s = info.toString();
    assertTrue(s.contains("depth=2"));
    assertTrue(s.contains("^"));
    assertTrue(s.contains("$"));
    assertTrue(s.contains("prefix=1"));
    assertTrue(s.contains("suffix=1"));
    // First level has group >= 0 → "group1"
    assertTrue(s.contains("group1"));
    // Separator ", " between two levels
    assertTrue(s.contains(", "));
  }

  @Test
  void nestedQuantifiedGroupsInfoToStringNoBranchesNoAnchors() {
    var lvl = level(1, Integer.MAX_VALUE, -1, CharSet.LOWER, null);
    NestedQuantifiedGroupsInfo info = new NestedQuantifiedGroupsInfo(
        List.of(lvl), List.of(), List.of(), false, false, 0);
    String s = info.toString();
    assertFalse(s.contains("^"));
    assertFalse(s.contains("$"));
    assertFalse(s.contains("prefix="));
    assertFalse(s.contains("suffix="));
    // group -1 → no "group" appended
    assertFalse(s.contains("group"));
  }

  @Test
  void nestedQuantifiedGroupsInfoStructuralHashAllPaths() {
    var lvl1 = level(1, Integer.MAX_VALUE, 1, CharSet.DIGIT, null);
    var lvl2 = level(0, 3, -1, null, "x");
    RegexNode dummyNode = new CharClassNode(CharSet.DIGIT, false);
    NestedQuantifiedGroupsInfo info = new NestedQuantifiedGroupsInfo(
        List.of(lvl1, lvl2), List.of(dummyNode), List.of(dummyNode), true, true, 2);
    int h = info.structuralHashCode();
    assertNotEquals(0, h);
  }

  @Test
  void nestedQuantifiedGroupsInfoStructuralHashBoundedNoCharSetNoLiteral() {
    var lvl = level(0, 5, -1, null, null);
    NestedQuantifiedGroupsInfo info = new NestedQuantifiedGroupsInfo(
        List.of(lvl), List.of(), List.of(), false, false, 0);
    int h = info.structuralHashCode();
    assertNotEquals(0, h);
  }
}
