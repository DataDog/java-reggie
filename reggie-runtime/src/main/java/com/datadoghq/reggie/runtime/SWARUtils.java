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
  private static final long SWAR_0x7F = 0x7F7F7F7F7F7F7F7FL;

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
   * Carry-safe per-lane range mask: a word whose high bit is set in each lane whose byte value lies
   * in [low, high] (unsigned, both inclusive). The naive formulation ({@code chunk + (0x80 - low)}
   * / {@code high + (0x80 - chunk)}) overflows or borrows across lane boundaries for bytes >= 0x80
   * (Latin-1), silently corrupting neighboring lanes and both missing and hallucinating range
   * members. This formulation folds every byte to 7 bits first ({@code chunk & SWAR_0x7F}), which
   * keeps both comparisons inside [0x01, 0xFF] so no carry can ever leave a lane, then restores the
   * high bit with an explicit ASCII mask: a folded byte matches the low half only when the original
   * byte is < 0x80 and the high half (range portion above 0x7F, re-based by -0x80) only when it is
   * >= 0x80.
   */
  private static long rangeMask(long chunk, int low, int high) {
    long x7 = chunk & SWAR_0x7F;
    long m = 0;
    if (high < 0x80) {
      // Entire range below 0x80: match only high-bit-clear lanes.
      m = halfRangeMask(x7, low, high) & ~chunk;
    } else if (low >= 0x80) {
      // Entire range above 0x80: re-base to [low-0x80, high-0x80], match only high-bit-set lanes.
      m = halfRangeMask(x7, low - 0x80, high - 0x80) & chunk;
    } else {
      // Range straddles 0x80: both halves.
      m = (halfRangeMask(x7, low, 0x7F) & ~chunk) | (halfRangeMask(x7, 0, high - 0x80) & chunk);
    }
    return m & SWAR_0x80;
  }

  /**
   * Per-lane mask over 7-bit folded bytes: high bit set iff {@code lo <= (byte & 0x7F) <= hi} with
   * lo, hi in [0, 0x7F]. Both additions stay within [0x01, 0xFF], so they are carry- and
   * borrow-free by construction.
   */
  private static long halfRangeMask(long x7, int lo, int hi) {
    long aboveLow = x7 + (SWAR_0x80 - SWAR_0x01 * lo);
    long belowHigh = SWAR_0x01 * hi + (SWAR_0x80 - x7);
    return aboveLow & belowHigh & SWAR_0x80;
  }

  /**
   * Check if an 8-byte chunk contains at least one hex digit. Uses SWAR to check all 8 bytes in
   * parallel.
   */
  private static boolean containsHexDigit(long chunk) {
    // Digits 0-9, lowercase a-f, uppercase A-F - all three via the carry-safe range mask.
    // (The previous broadcast constants used the exclusive upper bounds 0x3A/0x67/0x47 with an
    // inclusive comparison, so ':', 'g' and 'G' read as hex digits; a false-positive first chunk
    // made findFirstHexDigit return -1 without ever scanning later chunks.)
    return rangeMask(chunk, '0', '9') != 0
        || rangeMask(chunk, 'a', 'f') != 0
        || rangeMask(chunk, 'A', 'F') != 0;
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
    // Carry-safe formulation (see rangeMask): the previous 0x80-offset additions crossed lane
    // boundaries for bytes >= 0x80, so a Latin-1 byte could corrupt its neighbor's lane.
    return rangeMask(chunk, low, high) == SWAR_0x80;
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

    // Process 8 bytes at a time (carry-safe range mask, see rangeMask)
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      long inRange = rangeMask(chunk, low & 0xFF, high & 0xFF);

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

    // Process 8 bytes at a time (carry-safe range mask, see rangeMask)
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      long notInRange = ~rangeMask(chunk, low & 0xFF, high & 0xFF) & SWAR_0x80;

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

    // Process 8 bytes at a time (carry-safe range masks, see rangeMask)
    while (pos + 8 <= end) {
      long chunk = getLong(bytes, pos);

      long matchAny = 0;

      // Check each range and OR the results
      for (int i = 0; i < rangeCount; i++) {
        matchAny |= rangeMask(chunk, ranges[i * 2] & 0xFF, ranges[i * 2 + 1] & 0xFF);
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
