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

/** Tests for StringView's different access modes: charAt, copy, and zero-copy. */
class StringViewStrategyTest {

  @Test
  void testCharAtMode() {
    String input = "Hello World";
    StringView sv = StringView.ofWithCharAt(input);

    assertNotNull(sv);
    assertEquals(input.length(), sv.length());
    assertEquals('H', sv.charAt(0));
    assertEquals('W', sv.charAt(6));
    assertEquals('d', sv.charAt(10));
    assertTrue(sv.isCharAtMode());
  }

  @Test
  void testCopyModeLatin1() {
    String input = "Hello123";
    StringView sv = StringView.ofWithCopy(input);

    assertNotNull(sv);
    assertEquals(input.length(), sv.length());
    assertEquals('H', sv.charAt(0));
    assertEquals('1', sv.charAt(5));
    assertEquals('3', sv.charAt(7));
    assertTrue(sv.isLatin1());
    assertFalse(sv.isCharAtMode());
    assertNotNull(sv.getBytes());
  }

  @Test
  void testCopyModeUTF16() {
    String input = "Hello\u4E2D\u6587"; // Contains Chinese characters
    StringView sv = StringView.ofWithCopy(input);

    assertNotNull(sv);
    assertEquals(input.length(), sv.length());
    assertEquals('H', sv.charAt(0));
    assertEquals('\u4E2D', sv.charAt(5));
    assertEquals('\u6587', sv.charAt(6));
    assertTrue(sv.isUTF16());
    assertFalse(sv.isCharAtMode());
  }

  @Test
  void testDefaultModeWithZeroCopy() {
    String input = "TestString";
    StringView sv = StringView.of(input);

    assertNotNull(sv);
    assertEquals(input.length(), sv.length());
    assertEquals('T', sv.charAt(0));
    assertEquals('g', sv.charAt(9));

    // Mode depends on whether --add-opens is available
    // Just verify basic functionality works
    if (StringView.isZeroCopyAvailable()) {
      assertFalse(sv.isCharAtMode()); // Should be zero-copy or copy mode
    }
  }

  @Test
  void testSlicingCharAtMode() {
    String input = "HelloWorld";
    StringView sv = StringView.ofWithCharAt(input);
    StringView sliced = sv.slice(5, 10);

    assertEquals(5, sliced.length());
    assertEquals('W', sliced.charAt(0));
    assertEquals('d', sliced.charAt(4));
    assertEquals("World", sliced.toString());
  }

  @Test
  void testSlicingCopyMode() {
    String input = "HelloWorld";
    StringView sv = StringView.ofWithCopy(input);
    StringView sliced = sv.slice(0, 5);

    assertEquals(5, sliced.length());
    assertEquals('H', sliced.charAt(0));
    assertEquals('o', sliced.charAt(4));
    assertEquals("Hello", sliced.toString());
  }

  @Test
  void testFindFirstHexDigitCharAtMode() {
    String input = "abc123def";
    StringView sv = StringView.ofWithCharAt(input);

    int pos = sv.findFirstHexDigit(0);
    assertTrue(pos >= 0); // Should find 'a', 'b', 'c', '1', '2', or '3'

    // Find from middle
    pos = sv.findFirstHexDigit(3);
    assertEquals(3, pos); // Should find '1'
  }

  @Test
  void testFindFirstHexDigitCopyMode() {
    String input = "xyz123";
    StringView sv = StringView.ofWithCopy(input);

    // Will use SWAR if available, otherwise scalar
    int pos = sv.findFirstHexDigit(0);
    assertEquals(3, pos); // First hex digit is '1' at position 3
  }

  @Test
  void testNullInput() {
    assertNull(StringView.of(null));
    assertNull(StringView.ofWithCharAt(null));
    assertNull(StringView.ofWithCopy(null));
  }

  @Test
  void testBoundsChecking() {
    StringView sv = StringView.ofWithCharAt("test");

    assertThrows(IndexOutOfBoundsException.class, () -> sv.charAt(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> sv.charAt(4));
    assertThrows(IndexOutOfBoundsException.class, () -> sv.slice(-1, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> sv.slice(0, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> sv.slice(3, 2));
  }

  @Test
  void testToString() {
    String input = "TestString";

    StringView charAtView = StringView.ofWithCharAt(input);
    assertEquals(input, charAtView.toString());

    StringView copyView = StringView.ofWithCopy(input);
    assertEquals(input, copyView.toString());

    // Test sliced toString
    StringView sliced = charAtView.slice(0, 4);
    assertEquals("Test", sliced.toString());
  }

  @Test
  void testEmptyString() {
    String input = "";
    StringView sv = StringView.ofWithCharAt(input);

    assertNotNull(sv);
    assertEquals(0, sv.length());
  }
}
