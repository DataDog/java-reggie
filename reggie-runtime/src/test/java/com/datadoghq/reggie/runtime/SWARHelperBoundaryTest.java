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

import org.junit.jupiter.api.Test;

/**
 * Boundary and scalar-fallback tests for SWARHelper. Covers fromIndex >= len early returns, UTF-16
 * strings that force the scalar path, no-match cases, and the count methods.
 */
class SWARHelperBoundaryTest {

  // ── findNextHexPosition ────────────────────────────────────────────────────

  @Test
  void hexPositionFromIndexAtLen() {
    assertEquals(-1, SWARHelper.findNextHexPosition("abc", 3, 3));
  }

  @Test
  void hexPositionFromIndexBeyondLen() {
    assertEquals(-1, SWARHelper.findNextHexPosition("abc", 5, 3));
  }

  @Test
  void hexPositionNoHexInString() {
    assertEquals(-1, SWARHelper.findNextHexPosition("xyz", 0, 3));
  }

  @Test
  void hexPositionFoundAtStart() {
    assertEquals(0, SWARHelper.findNextHexPosition("1abc", 0, 4));
  }

  @Test
  void hexPositionFoundMidString() {
    assertEquals(2, SWARHelper.findNextHexPosition("zz3q", 0, 4));
  }

  @Test
  void hexPositionUtf16StringFallsBack() {
    // Characters above 0xFF force UTF-16 internal representation → scalar fallback
    String utf16 = "\u0100\u0200f";
    assertEquals(2, SWARHelper.findNextHexPosition(utf16, 0, utf16.length()));
  }

  @Test
  void hexPositionUtf16NoMatch() {
    String utf16 = "\u0100\u0200\u0300";
    assertEquals(-1, SWARHelper.findNextHexPosition(utf16, 0, utf16.length()));
  }

  // ── findNextInRange ────────────────────────────────────────────────────────

  @Test
  void inRangeFromIndexAtLen() {
    assertEquals(-1, SWARHelper.findNextInRange("abc", 3, 3, 'a', 'z'));
  }

  @Test
  void inRangeNoMatchInString() {
    assertEquals(-1, SWARHelper.findNextInRange("123", 0, 3, 'a', 'z'));
  }

  @Test
  void inRangeFoundAtPosition() {
    assertEquals(2, SWARHelper.findNextInRange("22b4", 0, 4, 'a', 'z'));
  }

  @Test
  void inRangeUtf16Fallback() {
    String utf16 = "\u0100b";
    assertEquals(1, SWARHelper.findNextInRange(utf16, 0, utf16.length(), 'a', 'z'));
  }

  @Test
  void inRangeUtf16NoMatch() {
    String utf16 = "\u0100\u0200";
    assertEquals(-1, SWARHelper.findNextInRange(utf16, 0, utf16.length(), 'a', 'z'));
  }

  // ── findNextByte ───────────────────────────────────────────────────────────

  @Test
  void byteFromIndexAtLen() {
    assertEquals(-1, SWARHelper.findNextByte("abc", 3, 3, 'a'));
  }

  @Test
  void byteNotFound() {
    assertEquals(-1, SWARHelper.findNextByte("bbb", 0, 3, 'a'));
  }

  @Test
  void byteFoundMidString() {
    assertEquals(2, SWARHelper.findNextByte("bba", 0, 3, 'a'));
  }

  @Test
  void byteUtf16Fallback() {
    String utf16 = "\u0100a";
    assertEquals(1, SWARHelper.findNextByte(utf16, 0, utf16.length(), 'a'));
  }

  @Test
  void byteUtf16NoMatch() {
    String utf16 = "\u0100\u0200";
    assertEquals(-1, SWARHelper.findNextByte(utf16, 0, utf16.length(), 'a'));
  }

  // ── findNextNotInRange ─────────────────────────────────────────────────────

  @Test
  void notInRangeFromIndexAtLen() {
    assertEquals(-1, SWARHelper.findNextNotInRange("abc", 3, 3, 'a', 'z'));
  }

  @Test
  void notInRangeAllInRange() {
    assertEquals(-1, SWARHelper.findNextNotInRange("abc", 0, 3, 'a', 'z'));
  }

  @Test
  void notInRangeFoundAtStart() {
    assertEquals(0, SWARHelper.findNextNotInRange("1ab", 0, 3, 'a', 'z'));
  }

  @Test
  void notInRangeFoundMidString() {
    assertEquals(2, SWARHelper.findNextNotInRange("aa1", 0, 3, 'a', 'z'));
  }

  @Test
  void notInRangeUtf16Fallback() {
    // UTF-16 forces scalar path
    String utf16 = "\u0100\u0200z";
    assertEquals(0, SWARHelper.findNextNotInRange(utf16, 0, utf16.length(), 'a', 'z'));
  }

  // ── findNextAlpha ──────────────────────────────────────────────────────────

  @Test
  void alphaFromIndexAtLen() {
    assertEquals(-1, SWARHelper.findNextAlpha("abc", 3, 3));
  }

  @Test
  void alphaNoAlphaInString() {
    assertEquals(-1, SWARHelper.findNextAlpha("123!@", 0, 5));
  }

  @Test
  void alphaFoundLowercase() {
    assertEquals(2, SWARHelper.findNextAlpha("11a", 0, 3));
  }

  @Test
  void alphaFoundUppercase() {
    assertEquals(0, SWARHelper.findNextAlpha("A12", 0, 3));
  }

  @Test
  void alphaUtf16Fallback() {
    String utf16 = "\u0100A";
    assertEquals(1, SWARHelper.findNextAlpha(utf16, 0, utf16.length()));
  }

  @Test
  void alphaUtf16NoMatch() {
    String utf16 = "\u0100\u0200";
    assertEquals(-1, SWARHelper.findNextAlpha(utf16, 0, utf16.length()));
  }

  // ── findNextAlphaNum ───────────────────────────────────────────────────────

  @Test
  void alphaNumFromIndexAtLen() {
    assertEquals(-1, SWARHelper.findNextAlphaNum("abc", 3, 3));
  }

  @Test
  void alphaNumNoMatchInString() {
    assertEquals(-1, SWARHelper.findNextAlphaNum("!@#", 0, 3));
  }

  @Test
  void alphaNumFoundDigit() {
    assertEquals(2, SWARHelper.findNextAlphaNum("!!3", 0, 3));
  }

  @Test
  void alphaNumFoundLetter() {
    assertEquals(1, SWARHelper.findNextAlphaNum("!z", 0, 2));
  }

  @Test
  void alphaNumUtf16Fallback() {
    String utf16 = "\u0100\u02009";
    assertEquals(2, SWARHelper.findNextAlphaNum(utf16, 0, utf16.length()));
  }

  @Test
  void alphaNumUtf16NoMatch() {
    String utf16 = "\u0100\u0200";
    assertEquals(-1, SWARHelper.findNextAlphaNum(utf16, 0, utf16.length()));
  }

  // ── countMatchingSingleByte ────────────────────────────────────────────────

  @Test
  void countSingleByteZeroMatchesAtStart() {
    assertEquals(0, SWARHelper.countMatchingSingleByte("abc", 0, 3, 'x'));
  }

  @Test
  void countSingleByteAllMatch() {
    assertEquals(4, SWARHelper.countMatchingSingleByte("aaaa", 0, 4, 'a'));
  }

  @Test
  void countSingleByteStopsAtFirstMismatch() {
    assertEquals(3, SWARHelper.countMatchingSingleByte("aaab", 0, 4, 'a'));
  }

  @Test
  void countSingleByteFromMidString() {
    assertEquals(2, SWARHelper.countMatchingSingleByte("xaab", 1, 4, 'a'));
  }

  @Test
  void countSingleByteStartAtLen() {
    assertEquals(0, SWARHelper.countMatchingSingleByte("aaa", 3, 3, 'a'));
  }

  @Test
  void countSingleByteEmptyRange() {
    assertEquals(0, SWARHelper.countMatchingSingleByte("abc", 1, 1, 'b'));
  }

  // ── countMatchingRange ─────────────────────────────────────────────────────

  @Test
  void countRangeZeroMatchesAtStart() {
    assertEquals(0, SWARHelper.countMatchingRange("123", 0, 3, 'a', 'z'));
  }

  @Test
  void countRangeAllMatch() {
    assertEquals(3, SWARHelper.countMatchingRange("abc", 0, 3, 'a', 'z'));
  }

  @Test
  void countRangeStopsAtFirstOutOfRange() {
    assertEquals(2, SWARHelper.countMatchingRange("ab1c", 0, 4, 'a', 'z'));
  }

  @Test
  void countRangeFromMidString() {
    assertEquals(2, SWARHelper.countMatchingRange("1ab!", 1, 4, 'a', 'z'));
  }

  @Test
  void countRangeStartAtLen() {
    assertEquals(0, SWARHelper.countMatchingRange("abc", 3, 3, 'a', 'z'));
  }

  // ── StringView overloads — boundary guards ─────────────────────────────────

  @Test
  void hexViewFromIndexAtLen() {
    StringView view = StringView.of("abc");
    if (view == null) return; // skip if zero-copy unavailable
    assertEquals(-1, SWARHelper.findNextHexPosition(view, 3, 3));
  }

  @Test
  void hexViewNullView() {
    assertEquals(-1, SWARHelper.findNextHexPosition((StringView) null, 0, 3));
  }

  @Test
  void inRangeViewFromIndexAtLen() {
    StringView view = StringView.of("abc");
    if (view == null) return;
    assertEquals(-1, SWARHelper.findNextInRange(view, 3, 3, 'a', 'z'));
  }

  @Test
  void inRangeViewNullView() {
    assertEquals(-1, SWARHelper.findNextInRange((StringView) null, 0, 3, 'a', 'z'));
  }

  @Test
  void notInRangeViewFromIndexAtLen() {
    StringView view = StringView.of("abc");
    if (view == null) return;
    assertEquals(-1, SWARHelper.findNextNotInRange(view, 3, 3, 'a', 'z'));
  }

  @Test
  void notInRangeViewNullView() {
    assertEquals(-1, SWARHelper.findNextNotInRange((StringView) null, 0, 3, 'a', 'z'));
  }

  @Test
  void alphaViewFromIndexAtLen() {
    StringView view = StringView.of("abc");
    if (view == null) return;
    assertEquals(-1, SWARHelper.findNextAlpha(view, 3, 3));
  }

  @Test
  void alphaNumViewFromIndexAtLen() {
    StringView view = StringView.of("abc");
    if (view == null) return;
    assertEquals(-1, SWARHelper.findNextAlphaNum(view, 3, 3));
  }
}
