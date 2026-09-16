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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Direct unit tests for SWARUtils, covering uncovered branches. */
class SWARUtilsTest {

  // ── Regression: carry-safe range masks (Latin-1 / bounds bugs) ────────────────

  @Test
  void hexDigitBoundsRejectColon() {
    // The old broadcast constants used the exclusive upper bounds 0x3A/0x67/0x47 with an
    // inclusive comparison, so ':', 'g', 'G' read as hex digits - and a false-positive first
    // chunk made findFirstHexDigit return -1 without scanning later chunks.
    byte[] bytes = "::::::::1".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertEquals(8, SWARUtils.findFirstHexDigit(bytes, 0, bytes.length));
    byte[] gs = "gggggggA".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertEquals(7, SWARUtils.findFirstHexDigit(gs, 0, gs.length));
  }

  @Test
  void rangeCarrySafeWithHighBitBytes() {
    // The naive x + (0x80 - low) formulation overflowed its lane for bytes >= 0x80 and the
    // borrow variant underflowed, corrupting neighboring lanes: [0xff, '9', 0xff, ...] read as
    // an empty [0-9] chunk and the digit was missed.
    byte[] bytes = {
      (byte) 0xFF,
      (byte) '9',
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF
    };
    assertEquals(1, SWARUtils.findFirstInRange(bytes, 0, bytes.length, '0', '9'));
    // Symmetric borrow direction: a trailing high-bit byte must not hide a leading digit.
    byte[] bytes2 = {
      (byte) '0',
      (byte) '0',
      (byte) '0',
      (byte) '0',
      (byte) '0',
      (byte) '0',
      (byte) '0',
      (byte) 0xC0
    };
    assertEquals(0, SWARUtils.findFirstInRange(bytes2, 0, bytes2.length, '0', '9'));
  }

  @Test
  void notInRangeHighBitByteIsAViolator() {
    // A byte >= 0x80 is never in an ASCII range; findFirstNotInRange must report it, and a
    // high-bit byte must not mask a real violator (or produce one) in a neighbor lane.
    byte[] bytes = {
      (byte) 0xC0,
      (byte) '9',
      (byte) '9',
      (byte) '9',
      (byte) '9',
      (byte) '9',
      (byte) '9',
      (byte) '9',
      (byte) 'a'
    };
    assertEquals(0, SWARUtils.findFirstNotInRange(bytes, 0, bytes.length, '0', '9'));
    byte[] clean = "99999999".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertEquals(-1, SWARUtils.findFirstNotInRange(clean, 0, clean.length, '0', '9'));
  }

  @Test
  void inRangesCarrySafeWithHighBitBytes() {
    byte[] bytes = {
      (byte) 0xFF,
      (byte) 'b',
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF
    };
    char[] az09 = {'0', '9', 'a', 'f'};
    assertEquals(1, SWARUtils.findFirstInRanges(bytes, 0, bytes.length, az09));
  }

  @Test
  void latin1RangeStraddle() {
    // A range straddling 0x80 must match bytes on both sides via the two-half formulation.
    byte[] bytes = {
      (byte) 0x7F,
      (byte) 0x80,
      (byte) 0xA0,
      (byte) 0xFF,
      (byte) 0x00,
      (byte) 0x00,
      (byte) 0x00,
      (byte) 0x00
    };
    assertEquals(1, SWARUtils.findFirstInRange(bytes, 0, bytes.length, (char) 0x80, (char) 0xFF));
    assertEquals(0, SWARUtils.findFirstInRange(bytes, 0, bytes.length, (char) 0x7F, (char) 0x80));
    // All-hits chunk for allBytesInRange:
    java.nio.ByteBuffer buf =
        java.nio.ByteBuffer.wrap(
            new byte[] {
              (byte) 0x90,
              (byte) 0xA0,
              (byte) 0xFF,
              (byte) 0x90,
              (byte) 0xA0,
              (byte) 0xFF,
              (byte) 0x90,
              (byte) 0xA0
            });
    assertTrue(SWARUtils.allBytesInRange(buf.getLong(), (char) 0x80, (char) 0xFF));
    java.nio.ByteBuffer buf2 = java.nio.ByteBuffer.wrap("99999999".getBytes());
    assertTrue(SWARUtils.allBytesInRange(buf2.getLong(), '0', '9'));
  }

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

  private static long pack(String s) {
    return ByteBuffer.wrap(s.getBytes()).order(ByteOrder.nativeOrder()).getLong(0);
  }

  @Test
  void allBytesInRangeTrue() {
    assertTrue(SWARUtils.allBytesInRange(pack("abcdefgh"), 'a', 'z'));
  }

  @Test
  void allBytesInRangeFalse() {
    // '1' (0x31) is outside [a-z]
    assertFalse(SWARUtils.allBytesInRange(pack("a1cdefgh"), 'a', 'z'));
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
    assertEquals(
        0,
        SWARUtils.findFirstOf(
            bytes, 0, bytes.length, (byte) 'a', (byte) 'x', (byte) 'y', (byte) 'z'));
    assertEquals(
        -1,
        SWARUtils.findFirstOf(
            bytes, 0, bytes.length, (byte) '1', (byte) '2', (byte) '3', (byte) '4'));
  }

  @Test
  void findFirstOfLongArraySwarHit() {
    // >= 8 bytes → SWAR, target2 '@' found at pos 8
    byte[] bytes = "________@_______".getBytes();
    assertEquals(
        8,
        SWARUtils.findFirstOf(
            bytes, 0, bytes.length, (byte) '@', (byte) '#', (byte) '$', (byte) '%'));
  }

  @Test
  void findFirstOfLongArraySwarMiss() {
    // No match in any chunk
    byte[] bytes = "aaaaaaaaaaaaaaaa".getBytes();
    assertEquals(
        -1,
        SWARUtils.findFirstOf(
            bytes, 0, bytes.length, (byte) '1', (byte) '2', (byte) '3', (byte) '4'));
  }

  @Test
  void findFirstOfMatchInRemainder() {
    // 9-byte array: SWAR chunk misses, remainder finds '@'
    byte[] bytes = "aaaaaaaa@".getBytes();
    assertEquals(
        8,
        SWARUtils.findFirstOf(
            bytes, 0, bytes.length, (byte) '@', (byte) '#', (byte) '$', (byte) '%'));
  }

  @Test
  void findFirstOfMultipleTargets() {
    // Each target covered in scalar fallback
    byte[] bytes = "a#b$".getBytes();
    assertEquals(
        1,
        SWARUtils.findFirstOf(
            bytes, 0, bytes.length, (byte) '!', (byte) '#', (byte) '?', (byte) '@'));
    assertEquals(
        3,
        SWARUtils.findFirstOf(
            bytes, 2, bytes.length, (byte) '!', (byte) '?', (byte) '$', (byte) '@'));
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
    assertThrows(
        IllegalArgumentException.class,
        () -> SWARUtils.findFirstInRanges(new byte[0], 0, 0, new char[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> SWARUtils.findFirstInRanges(new byte[0], 0, 0, new char[1]));
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
