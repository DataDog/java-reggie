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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * View over a String with optimized access patterns.
 *
 * <p>Supports three modes: 1. Zero-copy (best): Direct access to String internals via MethodHandles
 * (requires --add-opens) 2. Copy-based: Copies byte array, enables SWAR operations (good fallback)
 * 3. charAt-based: Delegates to String.charAt() (minimal overhead for short strings)
 *
 * <p>Mode selection is pattern-dependent: - charAt mode: Early-bailout patterns (^abc), short
 * expected scan depth - Copy mode: SWAR-friendly patterns ([0-9a-fA-F]+), high scan depth -
 * Zero-copy mode: When available (requires --add-opens java.base/java.lang=ALL-UNNAMED)
 *
 * <p>Feature can be disabled via system property: reggie.stringview.enabled=false
 */
public final class StringView {
  private static final MethodHandle STRING_VALUE_GETTER;
  private static final MethodHandle STRING_CODER_GETTER;
  private static final boolean ZERO_COPY_AVAILABLE;
  private static final byte LATIN1 = 0;
  private static final byte UTF16 = 1;

  /** Access mode for this StringView instance */
  private enum Mode {
    ZERO_COPY, // Direct access to String internals
    COPIED, // Copied byte array
    CHAR_AT // Delegates to String.charAt()
  }

  static {
    boolean zeroCopyAvailable = false;
    MethodHandle valueGetter = null;
    MethodHandle coderGetter = null;

    if (Boolean.parseBoolean(System.getProperty("reggie.stringview.enabled", "true"))) {
      try {
        MethodHandles.Lookup lookup =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());

        // Access String.value (byte[] since Java 9)
        valueGetter = lookup.findGetter(String.class, "value", byte[].class);

        // Access String.coder (byte: LATIN1=0, UTF16=1)
        coderGetter = lookup.findGetter(String.class, "coder", byte.class);

        zeroCopyAvailable = true;
      } catch (Exception e) {
        // MethodHandles failed - will use copy-based or charAt fallback
        // This is expected when --add-opens is not specified
      }
    }

    ZERO_COPY_AVAILABLE = zeroCopyAvailable;
    STRING_VALUE_GETTER = valueGetter;
    STRING_CODER_GETTER = coderGetter;
  }

  private final Mode mode;
  private final byte[] bytes; // null for CHAR_AT mode
  private final byte coder; // coder value
  private final int offset; // byte offset in array
  private final int length; // character length
  private final String source; // original string for CHAR_AT mode

  /**
   * Create a StringView from a String, preferring zero-copy if available. Falls back to copy-based
   * mode if zero-copy is unavailable. Returns null if str is null.
   */
  public static StringView of(String str) {
    if (str == null) {
      return null;
    }

    // Try zero-copy first
    if (ZERO_COPY_AVAILABLE) {
      try {
        byte[] bytes = (byte[]) STRING_VALUE_GETTER.invoke(str);
        byte coder = (byte) STRING_CODER_GETTER.invoke(str);
        return new StringView(Mode.ZERO_COPY, bytes, coder, 0, str.length(), null);
      } catch (Throwable e) {
        // Fall through to copy-based
      }
    }

    // Fallback to copy-based mode
    return ofWithCopy(str);
  }

  /**
   * Create a StringView using charAt() delegation. Best for patterns with early bailout and short
   * expected scan depth. Zero setup cost, but ~1-2ns overhead per character access.
   */
  public static StringView ofWithCharAt(String str) {
    if (str == null) {
      return null;
    }

    // Determine coder by checking if string is Latin-1 compatible
    byte coder = isLatin1String(str) ? LATIN1 : UTF16;
    return new StringView(Mode.CHAR_AT, null, coder, 0, str.length(), str);
  }

  /**
   * Create a StringView by copying the string's bytes. Best for patterns with SWAR opportunities or
   * high scan depth. O(n) setup cost, but enables fast bulk operations.
   */
  public static StringView ofWithCopy(String str) {
    if (str == null) {
      return null;
    }

    // Try to create Latin-1 copy first (most common and efficient)
    if (isLatin1String(str)) {
      byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
      return new StringView(Mode.COPIED, bytes, LATIN1, 0, str.length(), null);
    } else {
      // UTF-16 string - need to copy as UTF-16
      byte[] bytes = new byte[str.length() * 2];
      for (int i = 0; i < str.length(); i++) {
        char c = str.charAt(i);
        bytes[i * 2] = (byte) (c & 0xFF);
        bytes[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
      }
      return new StringView(Mode.COPIED, bytes, UTF16, 0, str.length(), null);
    }
  }

  /** Check if zero-copy mode is available (requires --add-opens). */
  public static boolean isZeroCopyAvailable() {
    return ZERO_COPY_AVAILABLE;
  }

  /** Check if a string can be represented as Latin-1 (all chars < 256). */
  private static boolean isLatin1String(String str) {
    for (int i = 0; i < str.length(); i++) {
      if (str.charAt(i) > 0xFF) {
        return false;
      }
    }
    return true;
  }

  // Private constructor
  private StringView(Mode mode, byte[] bytes, byte coder, int offset, int length, String source) {
    this.mode = mode;
    this.bytes = bytes;
    this.coder = coder;
    this.offset = offset;
    this.length = length;
    this.source = source;
  }

  /** Get character at index. Delegates to appropriate access method based on mode. */
  public char charAt(int index) {
    if (index < 0 || index >= length) {
      throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
    }

    if (mode == Mode.CHAR_AT) {
      // Delegate to String.charAt() - zero setup cost, small per-char overhead
      return source.charAt(index);
    }

    // Array-based access (ZERO_COPY or COPIED modes)
    if (coder == LATIN1) {
      // Latin-1: 1 byte per character
      return (char) (bytes[offset + index] & 0xFF);
    } else {
      // UTF-16: 2 bytes per character (little-endian)
      int byteIndex = (offset + index) << 1;
      return (char) (((bytes[byteIndex + 1] & 0xFF) << 8) | (bytes[byteIndex] & 0xFF));
    }
  }

  /** Get length of string view. */
  public int length() {
    return length;
  }

  /** Check if this string is Latin-1 encoded (single-byte). */
  public boolean isLatin1() {
    return coder == LATIN1;
  }

  /** Check if this string is UTF-16 encoded (two-byte). */
  public boolean isUTF16() {
    return coder == UTF16;
  }

  /**
   * Get direct access to underlying byte array. Only valid for array-based modes (ZERO_COPY,
   * COPIED). Returns null for CHAR_AT mode.
   *
   * <p>WARNING: Caller must check isLatin1() and mode before using.
   */
  public byte[] getBytes() {
    return bytes;
  }

  /** Get byte offset in the underlying array. Only meaningful for array-based modes. */
  public int getOffset() {
    return offset;
  }

  /** Get the access mode of this StringView. */
  public boolean isCharAtMode() {
    return mode == Mode.CHAR_AT;
  }

  /**
   * Create a slice of this StringView. Behavior depends on mode: - ZERO_COPY/COPIED: Adjusts offset
   * and length, shares byte array - CHAR_AT: Creates substring reference
   *
   * @param start starting index (inclusive)
   * @param end ending index (exclusive)
   * @return new StringView over the slice
   */
  public StringView slice(int start, int end) {
    if (start < 0 || end > length || start > end) {
      throw new IndexOutOfBoundsException(
          "Invalid slice range [" + start + ", " + end + ") for length " + length);
    }

    if (mode == Mode.CHAR_AT) {
      // For charAt mode, we need to track the substring bounds
      // but we don't want to create a new String object
      String sliced = source.substring(start, end);
      return new StringView(Mode.CHAR_AT, null, coder, 0, sliced.length(), sliced);
    }

    // Array-based modes: adjust offset
    if (coder == LATIN1) {
      return new StringView(mode, bytes, coder, offset + start, end - start, null);
    } else {
      // UTF-16: each char is 2 bytes
      return new StringView(mode, bytes, coder, offset + (start << 1), end - start, null);
    }
  }

  /**
   * Find first hex digit ([0-9a-fA-F]) in this StringView using SWAR. Only works for Latin-1
   * strings in array-based modes. Falls back to scalar search for CHAR_AT mode or non-Latin-1
   * strings.
   *
   * @param fromIndex starting index
   * @return index of first hex digit, or -1 if not found
   */
  public int findFirstHexDigit(int fromIndex) {
    if (fromIndex < 0 || fromIndex >= length) {
      return -1;
    }

    // SWAR only available for array-based Latin-1 strings
    if (mode == Mode.CHAR_AT || !isLatin1() || !SWARUtils.isEnabled()) {
      return findFirstHexDigitScalar(fromIndex);
    }

    int result = SWARUtils.findFirstHexDigit(bytes, offset + fromIndex, offset + length);

    return result >= 0 ? (result - offset) : -1;
  }

  /** Scalar fallback for finding first hex digit. */
  private int findFirstHexDigitScalar(int fromIndex) {
    for (int i = fromIndex; i < length; i++) {
      char c = charAt(i);
      if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Convert back to String. For CHAR_AT mode, returns the source string directly (zero-copy). For
   * array modes, creates a new String from the slice.
   */
  @Override
  public String toString() {
    if (mode == Mode.CHAR_AT) {
      return source;
    }

    if (coder == LATIN1) {
      return new String(bytes, offset, length, java.nio.charset.StandardCharsets.ISO_8859_1);
    } else {
      // UTF-16: need to handle carefully
      char[] chars = new char[length];
      for (int i = 0; i < length; i++) {
        chars[i] = charAt(i);
      }
      return new String(chars);
    }
  }
}
