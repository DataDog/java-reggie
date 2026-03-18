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

import java.lang.reflect.Field;
import java.nio.ByteOrder;
import sun.misc.Unsafe;

/**
 * SWAR (SIMD Within A Register) utilities for fast string scanning. Uses 64-bit operations to
 * process 8 bytes simultaneously.
 *
 * <p>Only effective for Latin-1 (single-byte) strings. UTF-16 strings fall back to scalar
 * operations.
 */
public final class SWARUtils {
  private static final Unsafe UNSAFE;
  private static final long BYTE_ARRAY_BASE_OFFSET;
  private static final boolean IS_LITTLE_ENDIAN =
      ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  // SWAR magic constants for zero-byte detection
  private static final long SWAR_0x01 = 0x0101010101010101L;
  private static final long SWAR_0x80 = 0x8080808080808080L;

  // Feature flag: can be disabled via system property
  private static final boolean SWAR_ENABLED =
      Boolean.parseBoolean(System.getProperty("reggie.swar.enabled", "true"));

  static {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      UNSAFE = (Unsafe) f.get(null);
      BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    } catch (Exception e) {
      throw new ExceptionInInitializerError("Failed to initialize Unsafe: " + e);
    }
  }

  private SWARUtils() {
    // Utility class
  }

  /** Check if SWAR optimizations are enabled and available. */
  public static boolean isEnabled() {
    return SWAR_ENABLED;
  }

  /** Load 8 bytes from byte array as a long value. Requires: offset + 8 <= array.length */
  static long getLong(byte[] array, int offset) {
    return UNSAFE.getLong(array, BYTE_ARRAY_BASE_OFFSET + offset);
  }

  /**
   * Find first occurrence of a specific byte in a byte array using SWAR.
   *
   * @param bytes byte array to search
   * @param start starting offset
   * @param end ending offset (exclusive)
   * @param target byte value to find
   * @return index of first occurrence, or -1 if not found
   */
  public static int findFirstByte(byte[] bytes, int start, int end, byte target) {
    if (!SWAR_ENABLED || end - start < 8) {
      return findFirstByteScalar(bytes, start, end, target);
    }

    int pos = start;
    long targetBroadcast = SWAR_0x01 * (target & 0xFF);

    // Process 8 bytes at a time
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      // XOR: matching bytes become 0x00
      long xor = chunk ^ targetBroadcast;

      // Zero-byte detection: (x - 0x01) & ~x & 0x80
      long hasZero = (xor - SWAR_0x01) & ~xor & SWAR_0x80;

      if (hasZero != 0) {
        // Found a match - locate exact byte position
        return findExactBytePosition(bytes, pos, pos + 8, target);
      }

      pos += 8;
    }

    // Handle remaining 0-7 bytes
    return findFirstByteScalar(bytes, pos, end, target);
  }

  /** Scalar fallback for finding first byte. */
  private static int findFirstByteScalar(byte[] bytes, int start, int end, byte target) {
    for (int i = start; i < end; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return -1;
  }

  /** Find exact position of matching byte in range (used after SWAR detection). */
  private static int findExactBytePosition(byte[] bytes, int start, int end, byte target) {
    for (int i = start; i < end; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Find first hex digit ([0-9a-fA-F]) in byte array using SWAR. Optimized for UUID pattern
   * no-match scanning.
   *
   * @param bytes byte array to search (Latin-1 encoded)
   * @param start starting offset
   * @param end ending offset (exclusive)
   * @return index of first hex digit, or -1 if not found
   */
  public static int findFirstHexDigit(byte[] bytes, int start, int end) {
    if (!SWAR_ENABLED || end - start < 8) {
      return findFirstHexDigitScalar(bytes, start, end);
    }

    int pos = start;

    // Process 8 bytes at a time
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      if (containsHexDigit(chunk)) {
        // Found at least one hex digit - locate exact position
        return findFirstHexDigitScalar(bytes, pos, Math.min(pos + 8, end));
      }

      pos += 8;
    }

    // Handle remaining 0-7 bytes
    return findFirstHexDigitScalar(bytes, pos, end);
  }

  /**
   * Check if an 8-byte chunk contains at least one hex digit. Uses SWAR to check all 8 bytes in
   * parallel.
   */
  private static boolean containsHexDigit(long chunk) {
    // Check for digits 0-9 (0x30-0x39)
    long digitsLow = chunk - 0x3030303030303030L; // Subtract '0'
    long digitsHigh = chunk - 0x3A3A3A3A3A3A3A3AL; // Subtract '9' + 1
    long digits = (~digitsLow & digitsHigh) & SWAR_0x80;

    if (digits != 0) {
      return true;
    }

    // Check for lowercase a-f (0x61-0x66)
    long lowerLow = chunk - 0x6161616161616161L; // Subtract 'a'
    long lowerHigh = chunk - 0x6767676767676767L; // Subtract 'f' + 1
    long lower = (~lowerLow & lowerHigh) & SWAR_0x80;

    if (lower != 0) {
      return true;
    }

    // Check for uppercase A-F (0x41-0x46)
    long upperLow = chunk - 0x4141414141414141L; // Subtract 'A'
    long upperHigh = chunk - 0x4747474747474747L; // Subtract 'F' + 1
    long upper = (~upperLow & upperHigh) & SWAR_0x80;

    return upper != 0;
  }

  /** Scalar fallback for finding first hex digit. */
  private static int findFirstHexDigitScalar(byte[] bytes, int start, int end) {
    for (int i = start; i < end; i++) {
      byte b = bytes[i];
      if (isHexDigit(b)) {
        return i;
      }
    }
    return -1;
  }

  /** Check if a byte is a hex digit. */
  private static boolean isHexDigit(byte b) {
    return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
  }

  /**
   * Check if all bytes in a chunk are within a specific range [low, high].
   *
   * @param chunk 8 bytes packed in a long
   * @param low lower bound (inclusive)
   * @param high upper bound (inclusive)
   * @return true if all 8 bytes are in range
   */
  public static boolean allBytesInRange(long chunk, int low, int high) {
    // Check: byte >= low  =>  (byte - low) has high bit clear
    long aboveLow = chunk - (SWAR_0x01 * low);

    // Check: byte <= high  =>  (high - byte) has high bit clear
    long belowHigh = (SWAR_0x01 * high) - chunk;

    // Byte is in range when BOTH high bits are clear (neither underflow occurred)
    // aboveLow has high bit SET when byte < low, belowHigh has high bit SET when byte > high
    // All 8 bytes are in range when all high bits of inverted OR are set
    long bothValid = ~(aboveLow | belowHigh) & SWAR_0x80;

    return bothValid == SWAR_0x80;
  }

  /**
   * Find first byte in range [low, high] using SWAR. Useful for single-range character classes like
   * [0-9], [a-z], [A-Z].
   *
   * @param bytes byte array to search
   * @param start starting offset
   * @param end ending offset (exclusive)
   * @param low lower bound (inclusive)
   * @param high upper bound (inclusive)
   * @return index of first byte in range, or -1 if not found
   */
  public static int findFirstInRange(byte[] bytes, int start, int end, char low, char high) {
    if (!SWAR_ENABLED || end - start < 8) {
      return findFirstInRangeScalar(bytes, start, end, low, high);
    }

    int pos = start;
    long lowBroadcast = SWAR_0x01 * (low & 0xFF);
    long highBroadcast = SWAR_0x01 * (high & 0xFF);

    // Process 8 bytes at a time
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      // Check: byte >= low  =>  (byte - low) has high bit clear
      long aboveLow = chunk - lowBroadcast;

      // Check: byte <= high  =>  (high - byte) has high bit clear
      long belowHigh = highBroadcast - chunk;

      // Byte is in range when BOTH high bits are clear (neither underflow occurred)
      // aboveLow has high bit SET when byte < low, belowHigh has high bit SET when byte > high
      // So invert the OR to find bytes where NEITHER condition has a set high bit
      long inRange = ~(aboveLow | belowHigh) & SWAR_0x80;

      if (inRange != 0) {
        // Found at least one byte in range - locate exact position
        return findFirstInRangeScalar(bytes, pos, Math.min(pos + 8, end), low, high);
      }

      pos += 8;
    }

    // Handle remaining 0-7 bytes
    return findFirstInRangeScalar(bytes, pos, end, low, high);
  }

  /** Scalar fallback for finding first byte in range. */
  private static int findFirstInRangeScalar(byte[] bytes, int start, int end, char low, char high) {
    for (int i = start; i < end; i++) {
      int b = bytes[i] & 0xFF;
      if (b >= low && b <= high) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Find first occurrence of any of the target bytes using SWAR. Supports up to 4 target bytes.
   * Useful for finding stopping characters like [.,;:].
   *
   * @param bytes byte array to search
   * @param start starting offset
   * @param end ending offset (exclusive)
   * @param target1 first target byte
   * @param target2 second target byte
   * @param target3 third target byte
   * @param target4 fourth target byte
   * @return index of first occurrence of any target, or -1 if not found
   */
  public static int findFirstOf(
      byte[] bytes, int start, int end, byte target1, byte target2, byte target3, byte target4) {
    if (!SWAR_ENABLED || end - start < 8) {
      return findFirstOfScalar(bytes, start, end, target1, target2, target3, target4);
    }

    int pos = start;
    long broadcast1 = SWAR_0x01 * (target1 & 0xFF);
    long broadcast2 = SWAR_0x01 * (target2 & 0xFF);
    long broadcast3 = SWAR_0x01 * (target3 & 0xFF);
    long broadcast4 = SWAR_0x01 * (target4 & 0xFF);

    // Process 8 bytes at a time
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      // Check each target using XOR + zero-byte detection
      long match = 0;

      // Target 1
      long xor1 = chunk ^ broadcast1;
      match |= (xor1 - SWAR_0x01) & ~xor1 & SWAR_0x80;

      // Target 2
      long xor2 = chunk ^ broadcast2;
      match |= (xor2 - SWAR_0x01) & ~xor2 & SWAR_0x80;

      // Target 3
      long xor3 = chunk ^ broadcast3;
      match |= (xor3 - SWAR_0x01) & ~xor3 & SWAR_0x80;

      // Target 4
      long xor4 = chunk ^ broadcast4;
      match |= (xor4 - SWAR_0x01) & ~xor4 & SWAR_0x80;

      if (match != 0) {
        // Found a match - locate exact position
        return findFirstOfScalar(
            bytes, pos, Math.min(pos + 8, end), target1, target2, target3, target4);
      }

      pos += 8;
    }

    // Handle remaining 0-7 bytes
    return findFirstOfScalar(bytes, pos, end, target1, target2, target3, target4);
  }

  /** Scalar fallback for finding first of multiple targets. */
  private static int findFirstOfScalar(
      byte[] bytes, int start, int end, byte target1, byte target2, byte target3, byte target4) {
    for (int i = start; i < end; i++) {
      byte b = bytes[i];
      if (b == target1 || b == target2 || b == target3 || b == target4) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Find first byte NOT in range [low, high] using SWAR. Useful for negated character classes like
   * [^0-9], [^a-z].
   *
   * @param bytes byte array to search
   * @param start starting offset
   * @param end ending offset (exclusive)
   * @param low lower bound (inclusive)
   * @param high upper bound (inclusive)
   * @return index of first byte not in range, or -1 if not found
   */
  public static int findFirstNotInRange(byte[] bytes, int start, int end, char low, char high) {
    if (!SWAR_ENABLED || end - start < 8) {
      return findFirstNotInRangeScalar(bytes, start, end, low, high);
    }

    int pos = start;
    long lowBroadcast = SWAR_0x01 * (low & 0xFF);
    long highBroadcast = SWAR_0x01 * (high & 0xFF);

    // Process 8 bytes at a time
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      // Check: byte >= low  =>  (byte - low) has high bit clear
      long aboveLow = chunk - lowBroadcast;

      // Check: byte <= high  =>  (high - byte) has high bit clear
      long belowHigh = highBroadcast - chunk;

      // Both conditions must hold for in-range: high bit SET means byte is in range
      long inRange = aboveLow & belowHigh & SWAR_0x80;

      // NOT in range: invert and check for any byte not matching
      long notInRange = ~inRange & SWAR_0x80;

      if (notInRange != 0) {
        // Found at least one byte not in range - locate exact position
        return findFirstNotInRangeScalar(bytes, pos, Math.min(pos + 8, end), low, high);
      }

      pos += 8;
    }

    // Handle remaining 0-7 bytes
    return findFirstNotInRangeScalar(bytes, pos, end, low, high);
  }

  /** Scalar fallback for finding first byte not in range. */
  private static int findFirstNotInRangeScalar(
      byte[] bytes, int start, int end, char low, char high) {
    for (int i = start; i < end; i++) {
      int b = bytes[i] & 0xFF;
      if (b < low || b > high) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Find first byte in any of multiple ranges using SWAR. Useful for multi-range character classes
   * like [a-zA-Z], [0-9a-zA-Z_].
   *
   * @param bytes byte array to search
   * @param start starting offset
   * @param end ending offset (exclusive)
   * @param ranges array of [low, high] pairs (must be even length)
   * @return index of first byte in any range, or -1 if not found
   */
  public static int findFirstInRanges(byte[] bytes, int start, int end, char[] ranges) {
    if (ranges.length == 0 || ranges.length % 2 != 0) {
      throw new IllegalArgumentException("Ranges must be non-empty and contain [low, high] pairs");
    }

    if (!SWAR_ENABLED || end - start < 8) {
      return findFirstInRangesScalar(bytes, start, end, ranges);
    }

    int pos = start;
    int rangeCount = ranges.length / 2;

    // Broadcast all range bounds
    long[] lowBroadcasts = new long[rangeCount];
    long[] highBroadcasts = new long[rangeCount];
    for (int i = 0; i < rangeCount; i++) {
      lowBroadcasts[i] = SWAR_0x01 * (ranges[i * 2] & 0xFF);
      highBroadcasts[i] = SWAR_0x01 * (ranges[i * 2 + 1] & 0xFF);
    }

    // Process 8 bytes at a time
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      long matchAny = 0;

      // Check each range and OR the results
      for (int i = 0; i < rangeCount; i++) {
        long aboveLow = chunk - lowBroadcasts[i];
        long belowHigh = highBroadcasts[i] - chunk;
        // Byte is in range when BOTH high bits are clear
        long inThisRange = ~(aboveLow | belowHigh) & SWAR_0x80;
        matchAny |= inThisRange;
      }

      if (matchAny != 0) {
        // Found at least one byte in any range - locate exact position
        return findFirstInRangesScalar(bytes, pos, Math.min(pos + 8, end), ranges);
      }

      pos += 8;
    }

    // Handle remaining 0-7 bytes
    return findFirstInRangesScalar(bytes, pos, end, ranges);
  }

  /** Scalar fallback for finding first byte in multiple ranges. */
  private static int findFirstInRangesScalar(byte[] bytes, int start, int end, char[] ranges) {
    for (int i = start; i < end; i++) {
      int b = bytes[i] & 0xFF;

      // Check if byte is in any range
      for (int r = 0; r < ranges.length; r += 2) {
        if (b >= ranges[r] && b <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }
}
