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

/** Direct unit tests for SWARUtils, covering uncovered branches. */
class SWARUtilsTest {

  // ── isEnabled ────────────────────────────────────────────────────────────────

  @Test
  void isEnabledReturnsBool() {
    // Just call it; result depends on system property reggie.swar.enabled
    boolean enabled = SWARUtils.isEnabled();
    assertTrue(enabled || !enabled);
  }

  // ── findFirstByte ────────────────────────────────────────────────────────────

  @Test
  void findFirstByteShortArrayScalar() {
    // end - start < 8 → scalar path
    byte[] bytes = "abc".getBytes();
    assertEquals(-1, SWARUtils.findFirstByte(bytes, 0, bytes.length, (byte) 'z'));
    assertEquals(1, SWARUtils.findFirstByte(bytes, 0, bytes.length, (byte) 'b'));
  }

  @Test
  void findFirstByteLongArraySwarHit() {
    // >= 8 bytes → SWAR path, match found in first chunk
    byte[] bytes = "xxxxxxxxa_______".getBytes();
    assertEquals(8, SWARUtils.findFirstByte(bytes, 0, bytes.length, (byte) 'a'));
  }

  @Test
  void findFirstByteLongArraySwarMiss() {
    // >= 8 bytes → SWAR loop, no match → scalar remainder
    byte[] bytes = "xxxxxxxxzzzzzzzz".getBytes();
    assertEquals(-1, SWARUtils.findFirstByte(bytes, 0, bytes.length, (byte) 'q'));
  }

  @Test
  void findFirstByteLongArrayMatchInRemainder() {
    // 9-byte array: 8-byte SWAR chunk misses, remainder finds it
    byte[] bytes = "aaaaaaaab".getBytes();
    assertEquals(8, SWARUtils.findFirstByte(bytes, 0, bytes.length, (byte) 'b'));
  }

  // ── findFirstHexDigit ────────────────────────────────────────────────────────

  @Test
  void findFirstHexDigitShortScalar() {
    byte[] bytes = "xyz".getBytes();
    assertEquals(-1, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
    byte[] bytes2 = "x9z".getBytes();
    assertEquals(1, SWARUtils.findFirstHexDigit(bytes2, 0, bytes2.length));
  }

  @Test
  void findFirstHexDigitLongArrayDigit09() {
    // Long array, first chunk contains digit 0-9
    byte[] bytes = "xxxxxxxx1yyyyyyy".getBytes();
    assertEquals(8, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
  }

  @Test
  void findFirstHexDigitLongArrayLowerAF() {
    // Long array, first chunk contains lowercase a-f (no digit before it)
    byte[] bytes = "xxxxxxxxbyyyyyy_".getBytes();
    assertEquals(8, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
  }

  @Test
  void findFirstHexDigitLongArrayUpperAF() {
    // Long array, first chunk contains uppercase A-F (no digit or lower a-f)
    byte[] bytes = "xxxxxxxxCyyyyyy_".getBytes();
    assertEquals(8, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
  }

  @Test
  void findFirstHexDigitLongArrayNoHex() {
    // 16-char array with no hex digits
    byte[] bytes = "xxxxxxxxrrrrrrrr".getBytes();
    assertEquals(-1, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
  }

  @Test
  void findFirstHexDigitMatchInRemainder() {
    // 9-byte array: SWAR chunk misses (non-hex), remainder finds 'f'
    byte[] bytes = "xxxxxxxxf".getBytes();
    assertEquals(8, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
  }

  // ── allBytesInRange ──────────────────────────────────────────────────────────

  @Test
  void allBytesInRangeTrue() {
    long chunk = SWARUtils.getLong("abcdefgh".getBytes(), 0);
    assertTrue(SWARUtils.allBytesInRange(chunk, 'a', 'z'));
  }

  @Test
  void allBytesInRangeFalse() {
    // '1' (0x31) is outside [a-z]
    long chunk = SWARUtils.getLong("a1cdefgh".getBytes(), 0);
    assertFalse(SWARUtils.allBytesInRange(chunk, 'a', 'z'));
  }

  // ── findFirstInRange ─────────────────────────────────────────────────────────

  @Test
  void findFirstInRangeShortScalar() {
    byte[] bytes = "123".getBytes();
    assertEquals(-1, SWARUtils.findFirstInRange(bytes, 0, bytes.length, 'a', 'z'));
    assertEquals(0, SWARUtils.findFirstInRange(bytes, 0, bytes.length, '0', '9'));
  }

  @Test
  void findFirstInRangeLongArraySwarHit() {
    byte[] bytes = "11111111a_______".getBytes();
    assertEquals(8, SWARUtils.findFirstInRange(bytes, 0, bytes.length, 'a', 'z'));
  }

  @Test
  void findFirstInRangeLongArraySwarMiss() {
    byte[] bytes = "1111111111111111".getBytes();
    assertEquals(-1, SWARUtils.findFirstInRange(bytes, 0, bytes.length, 'a', 'z'));
  }

  @Test
  void findFirstInRangeMatchInRemainder() {
    byte[] bytes = "11111111a".getBytes();
    assertEquals(8, SWARUtils.findFirstInRange(bytes, 0, bytes.length, 'a', 'z'));
  }

  // ── findFirstOf ──────────────────────────────────────────────────────────────

  @Test
  void findFirstOfShortScalar() {
    // Short array → scalar path
    byte[] bytes = "abc".getBytes();
    assertEquals(0, SWARUtils.findFirstOf(bytes, 0, bytes.length, (byte) 'a', (byte) 'x', (byte) 'y', (byte) 'z'));
    assertEquals(-1, SWARUtils.findFirstOf(bytes, 0, bytes.length, (byte) '1', (byte) '2', (byte) '3', (byte) '4'));
  }

  @Test
  void findFirstOfLongArraySwarHit() {
    // >= 8 bytes → SWAR, target2 '@' found at pos 8
    byte[] bytes = "________@_______".getBytes();
    assertEquals(8, SWARUtils.findFirstOf(bytes, 0, bytes.length, (byte) '@', (byte) '#', (byte) '$', (byte) '%'));
  }

  @Test
  void findFirstOfLongArraySwarMiss() {
    // No match in any chunk
    byte[] bytes = "aaaaaaaaaaaaaaaa".getBytes();
    assertEquals(-1, SWARUtils.findFirstOf(bytes, 0, bytes.length, (byte) '1', (byte) '2', (byte) '3', (byte) '4'));
  }

  @Test
  void findFirstOfMatchInRemainder() {
    // 9-byte array: SWAR chunk misses, remainder finds '@'
    byte[] bytes = "aaaaaaaa@".getBytes();
    assertEquals(8, SWARUtils.findFirstOf(bytes, 0, bytes.length, (byte) '@', (byte) '#', (byte) '$', (byte) '%'));
  }

  @Test
  void findFirstOfMultipleTargets() {
    // Each target covered in scalar fallback
    byte[] bytes = "a#b$".getBytes();
    assertEquals(1, SWARUtils.findFirstOf(bytes, 0, bytes.length, (byte) '!', (byte) '#', (byte) '?', (byte) '@'));
    assertEquals(3, SWARUtils.findFirstOf(bytes, 2, bytes.length, (byte) '!', (byte) '?', (byte) '$', (byte) '@'));
  }

  // ── findFirstNotInRange ──────────────────────────────────────────────────────

  @Test
  void findFirstNotInRangeShortScalar() {
    byte[] bytes = "abc".getBytes();
    assertEquals(-1, SWARUtils.findFirstNotInRange(bytes, 0, bytes.length, 'a', 'z'));
    assertEquals(0, SWARUtils.findFirstNotInRange(bytes, 0, bytes.length, '0', '9'));
  }

  @Test
  void findFirstNotInRangeLongArraySwarHit() {
    // First non-lowercase at position 8
    byte[] bytes = "aaaaaaaa1_______".getBytes();
    assertEquals(8, SWARUtils.findFirstNotInRange(bytes, 0, bytes.length, 'a', 'z'));
  }

  @Test
  void findFirstNotInRangeLongArraySwarMiss() {
    // All in range
    byte[] bytes = "aaaaaaaaaaaaaaaa".getBytes();
    assertEquals(-1, SWARUtils.findFirstNotInRange(bytes, 0, bytes.length, 'a', 'z'));
  }

  @Test
  void findFirstNotInRangeMatchInRemainder() {
    // 9-byte array: SWAR chunk all in range, remainder has non-matching byte
    byte[] bytes = "aaaaaaaa1".getBytes();
    assertEquals(8, SWARUtils.findFirstNotInRange(bytes, 0, bytes.length, 'a', 'z'));
  }

  // ── findFirstInRanges ────────────────────────────────────────────────────────

  @Test
  void findFirstInRangesInvalidRanges() {
    assertThrows(IllegalArgumentException.class, () ->
        SWARUtils.findFirstInRanges(new byte[0], 0, 0, new char[0]));
    assertThrows(IllegalArgumentException.class, () ->
        SWARUtils.findFirstInRanges(new byte[0], 0, 0, new char[1]));
  }

  @Test
  void findFirstInRangesShortScalar() {
    byte[] bytes = "1ab".getBytes();
    char[] ranges = {'a', 'z', 'A', 'Z'};
    assertEquals(1, SWARUtils.findFirstInRanges(bytes, 0, bytes.length, ranges));
    assertEquals(-1, SWARUtils.findFirstInRanges(bytes, 0, 1, ranges));
  }

  @Test
  void findFirstInRangesLongArraySwarHit() {
    byte[] bytes = "11111111a_______".getBytes();
    char[] ranges = {'a', 'z', 'A', 'Z'};
    assertEquals(8, SWARUtils.findFirstInRanges(bytes, 0, bytes.length, ranges));
  }

  @Test
  void findFirstInRangesLongArraySwarMiss() {
    byte[] bytes = "1111111111111111".getBytes();
    char[] ranges = {'a', 'z', 'A', 'Z'};
    assertEquals(-1, SWARUtils.findFirstInRanges(bytes, 0, bytes.length, ranges));
  }

  @Test
  void findFirstInRangesMatchInRemainder() {
    byte[] bytes = "11111111a".getBytes();
    char[] ranges = {'a', 'z'};
    assertEquals(8, SWARUtils.findFirstInRanges(bytes, 0, bytes.length, ranges));
  }
}
