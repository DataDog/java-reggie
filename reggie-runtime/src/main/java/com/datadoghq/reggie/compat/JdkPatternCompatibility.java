/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.datadoghq.reggie.compat;

import com.datadoghq.reggie.ReggieFlags;

/**
 * Explicit adapter that maps supported JDK flag values onto Reggie flag values.
 *
 * <h2>CASE_INSENSITIVE</h2>
 *
 * The engines fold case differently for <em>non-ASCII pattern letters</em>: the JDK (without
 * {@code UNICODE_CASE}) folds only ASCII {@code a-z}/{@code A-Z}, while Reggie's {@code (?i)} also
 * folds Unicode letters ({@code "é"} becomes {@code [éÉ]}). Input-side non-ASCII characters do
 * <em>not</em> diverge: neither engine folds them (verified for {@code 'İ'} U+0130, {@code 'ı'}
 * U+0131, Kelvin-sign {@code 'K'} U+212A and long-s {@code 'ſ'} U+017F against literal and
 * character-class patterns).
 *
 * <p>Consequently:
 *
 * <ul>
 *   <li>{@link #toReggieFlags(String, int)} maps JDK CASE_INSENSITIVE when the pattern is pure
 *       ASCII (where both engines fold exactly the ASCII alphabet) and is the recommended
 *       migration entry point.
 *   <li>{@link #toReggieFlags(int)} — the pattern-unaware overload — cannot validate the pattern
 *       and keeps rejecting CASE_INSENSITIVE.
 *   <li>Non-ASCII patterns (or the {@code LITERAL} combination, whose interaction with quoted
 *       literals is not equivalent) are rejected with an actionable message; those sites either
 *       spell {@code "(?i)"} inline and accept Reggie's Unicode folding, or stay on {@code
 *       java.util.regex}.
 * </ul>
 */
public final class JdkPatternCompatibility {
  private JdkPatternCompatibility() {}

  /**
   * Converts supported JDK pattern flags to {@link ReggieFlags}, validating the pattern where the
   * flag semantics depend on it.
   *
   * @param pattern the regex pattern the flags will be applied to
   * @param jdkFlags JDK pattern flag bitmask
   * @return the equivalent {@link ReggieFlags} bitmask
   * @throws IllegalArgumentException if a flag cannot be mapped soundly for this pattern
   */
  public static int toReggieFlags(String pattern, int jdkFlags) {
    int remaining = jdkFlags;
    if ((remaining & java.util.regex.Pattern.CASE_INSENSITIVE) != 0) {
      boolean asciiOnly = pattern.chars().allMatch(c -> c < 0x80);
      boolean literal = (remaining & java.util.regex.Pattern.LITERAL) != 0;
      if (asciiOnly && !literal) {
        // For an ASCII pattern both engines fold exactly the ASCII alphabet, so the mapping is
        // sound (see class javadoc for the empirical basis).
        return toReggieFlags(remaining & ~java.util.regex.Pattern.CASE_INSENSITIVE)
            | ReggieFlags.CASE_INSENSITIVE;
      }
      throw new IllegalArgumentException(
          "JDK CASE_INSENSITIVE cannot be mapped for this pattern: Reggie's (?i) also folds "
              + "non-ASCII pattern letters while the JDK (without UNICODE_CASE) does not, and the "
              + "LITERAL combination is not equivalent. Spell \"(?i)\" inline in the pattern to "
              + "adopt Reggie's Unicode case folding, or keep the site on java.util.regex");
    }
    // JDK UNICODE_CASE is a no-op under Reggie: case folding is unconditionally Unicode here,
    // which is exactly equivalent for the pure-ASCII patterns the CASE_INSENSITIVE mapping covers.
    remaining &= ~java.util.regex.Pattern.UNICODE_CASE;
    return toReggieFlags(remaining);
  }

  /**
   * Converts supported JDK pattern flags to {@link ReggieFlags}. Pattern-unaware, so
   * CASE_INSENSITIVE is always rejected; use {@link #toReggieFlags(String, int)} instead.
   */
  public static int toReggieFlags(int jdkFlags) {
    int remaining = jdkFlags;
    int result = 0;
    if ((remaining & java.util.regex.Pattern.CASE_INSENSITIVE) != 0) {
      throw new IllegalArgumentException(
          "JDK CASE_INSENSITIVE cannot be mapped without seeing the pattern: Reggie's (?i) also "
              + "folds non-ASCII pattern letters while the JDK (without UNICODE_CASE) does not. "
              + "Use toReggieFlags(pattern, flags) — it maps CASE_INSENSITIVE for pure-ASCII "
              + "patterns — or spell \"(?i)\" inline in the pattern");
    }
    if ((remaining & java.util.regex.Pattern.UNICODE_CASE) != 0) {
      // No-op under Reggie (folding is unconditionally Unicode; equivalent for the ASCII
      // patterns the CASE_INSENSITIVE mapping covers).
      remaining &= ~java.util.regex.Pattern.UNICODE_CASE;
    }
    if ((remaining & java.util.regex.Pattern.UNICODE_CHARACTER_CLASS) != 0) {
      result |= ReggieFlags.UNICODE_CHARACTER_CLASS;
      remaining &= ~java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
    }
    if ((remaining & java.util.regex.Pattern.MULTILINE) != 0) {
      result |= ReggieFlags.MULTILINE;
      remaining &= ~java.util.regex.Pattern.MULTILINE;
    }
    if ((remaining & java.util.regex.Pattern.DOTALL) != 0) {
      result |= ReggieFlags.DOTALL;
      remaining &= ~java.util.regex.Pattern.DOTALL;
    }
    if ((remaining & java.util.regex.Pattern.LITERAL) != 0) {
      result |= ReggieFlags.LITERAL;
      remaining &= ~java.util.regex.Pattern.LITERAL;
    }
    if (remaining != 0) {
      throw new IllegalArgumentException(
          "Unsupported JDK regex flags: "
              + remaining
              + " (supported: MULTILINE, DOTALL, LITERAL, UNICODE_CHARACTER_CLASS;"
              + " CASE_INSENSITIVE maps via toReggieFlags(pattern, flags) for pure-ASCII"
              + " patterns)");
    }
    return result;
  }
}
