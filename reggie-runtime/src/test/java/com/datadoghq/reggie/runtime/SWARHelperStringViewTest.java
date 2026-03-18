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
 * Tests for StringView overloads in SWARHelper. Verifies that StringView-accepting methods produce
 * identical results to String-accepting methods.
 */
public class SWARHelperStringViewTest {

  @Test
  public void testFindNextHexPositionWithView() {
    String input = "xyz123ghi456"; // x,y,z,g,h,i are NOT hex digits
    StringView view = StringView.of(input);

    assertEquals(3, SWARHelper.findNextHexPosition(view, 0, input.length()));
    assertEquals(4, SWARHelper.findNextHexPosition(view, 4, input.length()));
    assertEquals(9, SWARHelper.findNextHexPosition(view, 7, input.length()));
    assertEquals(-1, SWARHelper.findNextHexPosition(view, 12, input.length()));
  }

  @Test
  public void testFindNextHexPositionWithNullView() {
    assertEquals(-1, SWARHelper.findNextHexPosition((StringView) null, 0, 10));
  }

  @Test
  public void testFindNextHexPositionConsistency() {
    String input = "test123abc0x45 DEAD beef";
    StringView view = StringView.of(input);

    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextHexPosition(input, i, input.length());
      int resultView = SWARHelper.findNextHexPosition(view, i, input.length());
      assertEquals(resultString, resultView, "Mismatch at index " + i);
    }
  }

  @Test
  public void testFindNextInRangeWithView() {
    // Use longer string to ensure SWAR is triggered (needs >=8 bytes)
    String input = "xyzpqrstu123vwxyz";
    StringView view = StringView.of(input);

    // Find digits [0-9] - first digit '1' is at index 9
    assertEquals(9, SWARHelper.findNextInRange(view, 0, input.length(), '0', '9'));
    assertEquals(10, SWARHelper.findNextInRange(view, 10, input.length(), '0', '9'));
    assertEquals(11, SWARHelper.findNextInRange(view, 11, input.length(), '0', '9'));
    assertEquals(-1, SWARHelper.findNextInRange(view, 12, input.length(), '0', '9'));

    // Find lowercase letters [a-z]
    assertEquals(0, SWARHelper.findNextInRange(view, 0, input.length(), 'a', 'z'));
    assertEquals(13, SWARHelper.findNextInRange(view, 13, input.length(), 'a', 'z'));
  }

  @Test
  public void testFindNextInRangeWithNullView() {
    assertEquals(-1, SWARHelper.findNextInRange((StringView) null, 0, 10, 'a', 'z'));
  }

  @Test
  public void testFindNextInRangeConsistency() {
    String input = "Hello123World456";
    StringView view = StringView.of(input);

    // Test digits
    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextInRange(input, i, input.length(), '0', '9');
      int resultView = SWARHelper.findNextInRange(view, i, input.length(), '0', '9');
      assertEquals(resultString, resultView, "Digit search mismatch at index " + i);
    }

    // Test uppercase letters
    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextInRange(input, i, input.length(), 'A', 'Z');
      int resultView = SWARHelper.findNextInRange(view, i, input.length(), 'A', 'Z');
      assertEquals(resultString, resultView, "Uppercase search mismatch at index " + i);
    }
  }

  @Test
  public void testFindNextByteWithView() {
    String input = "find.the.dots.here";
    StringView view = StringView.of(input);

    assertEquals(4, SWARHelper.findNextByte(view, 0, input.length(), '.'));
    assertEquals(8, SWARHelper.findNextByte(view, 5, input.length(), '.'));
    assertEquals(13, SWARHelper.findNextByte(view, 9, input.length(), '.'));
    assertEquals(-1, SWARHelper.findNextByte(view, 14, input.length(), '.'));
  }

  @Test
  public void testFindNextByteWithNullView() {
    assertEquals(-1, SWARHelper.findNextByte((StringView) null, 0, 10, 'x'));
  }

  @Test
  public void testFindNextByteConsistency() {
    String input = "test@example.com";
    StringView view = StringView.of(input);

    // Test finding '@'
    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextByte(input, i, input.length(), '@');
      int resultView = SWARHelper.findNextByte(view, i, input.length(), '@');
      assertEquals(resultString, resultView, "@ search mismatch at index " + i);
    }

    // Test finding '.'
    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextByte(input, i, input.length(), '.');
      int resultView = SWARHelper.findNextByte(view, i, input.length(), '.');
      assertEquals(resultString, resultView, ". search mismatch at index " + i);
    }
  }

  @Test
  public void testFindNextNotInRangeWithView() {
    String input = "123abc456xyz";
    StringView view = StringView.of(input);

    // Find first non-digit
    assertEquals(3, SWARHelper.findNextNotInRange(view, 0, input.length(), '0', '9'));
    assertEquals(3, SWARHelper.findNextNotInRange(view, 1, input.length(), '0', '9'));
    assertEquals(9, SWARHelper.findNextNotInRange(view, 6, input.length(), '0', '9'));

    // Find first non-lowercase
    assertEquals(0, SWARHelper.findNextNotInRange(view, 0, input.length(), 'a', 'z'));
    assertEquals(6, SWARHelper.findNextNotInRange(view, 3, input.length(), 'a', 'z'));
  }

  @Test
  public void testFindNextNotInRangeWithNullView() {
    assertEquals(-1, SWARHelper.findNextNotInRange((StringView) null, 0, 10, 'a', 'z'));
  }

  @Test
  public void testFindNextNotInRangeConsistency() {
    String input = "abc123DEF456";
    StringView view = StringView.of(input);

    // Test finding non-digits
    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextNotInRange(input, i, input.length(), '0', '9');
      int resultView = SWARHelper.findNextNotInRange(view, i, input.length(), '0', '9');
      assertEquals(resultString, resultView, "Non-digit search mismatch at index " + i);
    }

    // Test finding non-lowercase
    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextNotInRange(input, i, input.length(), 'a', 'z');
      int resultView = SWARHelper.findNextNotInRange(view, i, input.length(), 'a', 'z');
      assertEquals(resultString, resultView, "Non-lowercase search mismatch at index " + i);
    }
  }

  @Test
  public void testFindNextAlphaWithView() {
    String input = "12345678xyz90PQRST"; // Longer string for SWAR
    StringView view = StringView.of(input);

    assertEquals(8, SWARHelper.findNextAlpha(view, 0, input.length()));
    assertEquals(9, SWARHelper.findNextAlpha(view, 9, input.length()));
    assertEquals(13, SWARHelper.findNextAlpha(view, 12, input.length()));
    assertEquals(-1, SWARHelper.findNextAlpha(view, 18, input.length()));
  }

  @Test
  public void testFindNextAlphaWithNullView() {
    assertEquals(-1, SWARHelper.findNextAlpha((StringView) null, 0, 10));
  }

  @Test
  public void testFindNextAlphaConsistency() {
    String input = "test123TEST456mixed";
    StringView view = StringView.of(input);

    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextAlpha(input, i, input.length());
      int resultView = SWARHelper.findNextAlpha(view, i, input.length());
      assertEquals(resultString, resultView, "Alpha search mismatch at index " + i);
    }
  }

  @Test
  public void testFindNextAlphaNumWithView() {
    String input = "!!!@@@###xyz123...pqr"; // Longer string for SWAR
    StringView view = StringView.of(input);

    assertEquals(9, SWARHelper.findNextAlphaNum(view, 0, input.length()));
    assertEquals(10, SWARHelper.findNextAlphaNum(view, 10, input.length()));
    assertEquals(18, SWARHelper.findNextAlphaNum(view, 16, input.length()));
    assertEquals(-1, SWARHelper.findNextAlphaNum(view, 21, input.length()));
  }

  @Test
  public void testFindNextAlphaNumWithNullView() {
    assertEquals(-1, SWARHelper.findNextAlphaNum((StringView) null, 0, 10));
  }

  @Test
  public void testFindNextAlphaNumConsistency() {
    String input = "test@123#TEST!456$mixed";
    StringView view = StringView.of(input);

    for (int i = 0; i < input.length(); i++) {
      int resultString = SWARHelper.findNextAlphaNum(input, i, input.length());
      int resultView = SWARHelper.findNextAlphaNum(view, i, input.length());
      assertEquals(resultString, resultView, "AlphaNum search mismatch at index " + i);
    }
  }

  @Test
  public void testBoundaryConditions() {
    String input = "test";
    StringView view = StringView.of(input);

    // Test at end of string
    assertEquals(-1, SWARHelper.findNextHexPosition(view, 4, 4));
    assertEquals(-1, SWARHelper.findNextInRange(view, 4, 4, 'a', 'z'));
    assertEquals(-1, SWARHelper.findNextByte(view, 4, 4, 'x'));
    assertEquals(-1, SWARHelper.findNextNotInRange(view, 4, 4, '0', '9'));
    assertEquals(-1, SWARHelper.findNextAlpha(view, 4, 4));
    assertEquals(-1, SWARHelper.findNextAlphaNum(view, 4, 4));

    // Test fromIndex >= len
    assertEquals(-1, SWARHelper.findNextHexPosition(view, 5, 4));
    assertEquals(-1, SWARHelper.findNextInRange(view, 5, 4, 'a', 'z'));
    assertEquals(-1, SWARHelper.findNextByte(view, 5, 4, 'x'));
    assertEquals(-1, SWARHelper.findNextNotInRange(view, 5, 4, '0', '9'));
    assertEquals(-1, SWARHelper.findNextAlpha(view, 5, 4));
    assertEquals(-1, SWARHelper.findNextAlphaNum(view, 5, 4));
  }

  @Test
  public void testEmptyString() {
    String input = "";
    StringView view = StringView.of(input);

    assertEquals(-1, SWARHelper.findNextHexPosition(view, 0, 0));
    assertEquals(-1, SWARHelper.findNextInRange(view, 0, 0, 'a', 'z'));
    assertEquals(-1, SWARHelper.findNextByte(view, 0, 0, 'x'));
    assertEquals(-1, SWARHelper.findNextNotInRange(view, 0, 0, '0', '9'));
    assertEquals(-1, SWARHelper.findNextAlpha(view, 0, 0));
    assertEquals(-1, SWARHelper.findNextAlphaNum(view, 0, 0));
  }

  @Test
  public void testLongString() {
    // Create a long string to test SWAR chunk processing
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      sb.append("a");
    }
    sb.append("1"); // Add a digit at position 1000
    String input = sb.toString();
    StringView view = StringView.of(input);

    assertEquals(1000, SWARHelper.findNextInRange(view, 0, input.length(), '0', '9'));
    assertEquals(1000, SWARHelper.findNextInRange(input, 0, input.length(), '0', '9'));
  }
}
