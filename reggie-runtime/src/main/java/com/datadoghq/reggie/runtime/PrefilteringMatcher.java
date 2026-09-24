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

import java.util.Map;

/**
 * Required-literal rejection wrapper: rejects inputs that lack the pattern's required literal
 * before delegating to the engine matcher.
 *
 * <p>Used by {@code RuntimeCompiler} for engine-class matchers (PikeVM, BitState, hybrid,
 * counted-loop) whose scanning loops live in Java code rather than generated {@code findFrom}
 * bytecode. Generated matchers embed the same rejection check directly in their bytecode.
 *
 * <p>Soundness: the required literal is a language-level fact — every string the pattern can match
 * contains it. A match reported at or after {@code start} must therefore contain the literal within
 * {@code [start, length)}, which is exactly what {@code String.indexOf(literal, start) < 0}
 * falsifies. Applies to anchored, backreference and lookahead patterns alike.
 */
final class PrefilteringMatcher extends ReggieMatcher {

  private final ReggieMatcher delegate;
  private final String requiredLiteral;
  // ASCII-only case-insensitive scan (Java (?i) without (?u) folds ASCII only). Set for facts
  // extracted from a globally-(?i) pattern with all cased chars in the ASCII range.
  private final boolean asciiCaseInsensitive;

  private PrefilteringMatcher(
      ReggieMatcher delegate, String requiredLiteral, boolean asciiCaseInsensitive) {
    super(delegate.pattern());
    this.delegate = delegate;
    this.requiredLiteral = requiredLiteral;
    this.asciiCaseInsensitive = asciiCaseInsensitive;
    this.nameToIndex = delegate.nameToIndex;
  }

  /**
   * Wraps {@code matcher} with the literal rejection check, or returns it unchanged when no usable
   * required literal exists. 1-char facts are usable: absence still falsifies every match.
   */
  static ReggieMatcher wrap(
      ReggieMatcher matcher, String requiredLiteral, boolean asciiCaseInsensitive) {
    if (matcher == null || requiredLiteral == null || requiredLiteral.isEmpty()) {
      return matcher;
    }
    return new PrefilteringMatcher(matcher, requiredLiteral, asciiCaseInsensitive);
  }

  /** Delegates to {@code input.indexOf(fact, from)}, ASCII case-insensitively when required. */
  private int factIndexOf(String input, int from) {
    if (!asciiCaseInsensitive) {
      // 1-char facts use the char overload: its intrinsic scans with full-width SIMD, while the
      // String overload's first-char scan is ~5.7x slower on x86 (22KB no-match input,
      // workspace-jb).
      if (requiredLiteral.length() == 1) {
        return input.indexOf(requiredLiteral.charAt(0), from);
      }
      return input.indexOf(requiredLiteral, from);
    }
    if (requiredLiteral.length() == 1) {
      return indexOfAsciiIgnoreCase1(input, requiredLiteral.charAt(0), from);
    }
    return indexOfAsciiIgnoreCase(input, requiredLiteral, from);
  }

  /**
   * ASCII-only case-insensitive presence scan for a 1-char fact: two exact {@code indexOf(int)}
   * scans (the cased char and its ASCII case pair — extraction validated the fact char is ASCII),
   * taking the earlier hit. Still ~3x faster than the per-char loop. Non-letter fact chars have no
   * case pair, so the scan is exact.
   */
  private static int indexOfAsciiIgnoreCase1(String input, char fact, int from) {
    int first = input.indexOf(fact, from);
    char pair = toUpperAscii(fact) == fact ? toLowerAscii(fact) : toUpperAscii(fact);
    if (pair == fact) {
      return first; // no case fold (non-letter)
    }
    int second = input.indexOf(pair, from);
    if (first < 0) {
      return second;
    }
    return second < 0 ? first : Math.min(first, second);
  }

  /**
   * Allocation-free ASCII-only case-insensitive {@code indexOf}. Cased fact chars are validated
   * ASCII at extraction time; input chars outside a-z/A-Z compare by exact equality only (Java
   * {@code (?i)} without {@code (?u)} has the same semantics, so the scan is exact, not merely
   * conservative).
   */
  private static int indexOfAsciiIgnoreCase(String input, String fact, int from) {
    int n = input.length();
    int m = fact.length();
    if (m == 0) {
      return Math.max(from, 0);
    }
    int start = Math.max(from, 0);
    if (m > n) {
      return -1;
    }
    char f0 = fact.charAt(0);
    for (int i = start; i <= n - m; i++) {
      char c = input.charAt(i);
      if (c != f0 && toUpperAscii(c) != toUpperAscii(f0)) {
        continue;
      }
      int j = 1;
      while (j < m) {
        char a = input.charAt(i + j);
        char b = fact.charAt(j);
        if (a != b && toUpperAscii(a) != toUpperAscii(b)) {
          break;
        }
        j++;
      }
      if (j == m) {
        return i;
      }
    }
    return -1;
  }

  private static char toUpperAscii(char c) {
    return (c >= 'a' && c <= 'z') ? (char) (c - 32) : c;
  }

  private static char toLowerAscii(char c) {
    return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
  }

  @Override
  public boolean isJdkFallback() {
    return delegate.isJdkFallback();
  }

  /** The wrapped engine matcher (test-visible unwrap). */
  ReggieMatcher delegate() {
    return delegate;
  }

  @Override
  protected void setNameToIndex(Map<String, Integer> map) {
    super.setNameToIndex(map);
    delegate.setNameToIndex(map);
  }

  @Override
  public boolean matches(String input) {
    // null handling stays with the delegate
    if (input != null && factIndexOf(input, 0) < 0) {
      return false;
    }
    return delegate.matches(input);
  }

  @Override
  public boolean find(String input) {
    if (input != null && factIndexOf(input, 0) < 0) {
      return false;
    }
    return delegate.find(input);
  }

  @Override
  public int findFrom(String input, int start) {
    // indexOf clamps negative start and returns -1 past the end — same contract as findFrom.
    if (input != null && factIndexOf(input, start) < 0) {
      return -1;
    }
    return delegate.findFrom(input, start);
  }

  @Override
  public MatchResult match(String input) {
    return delegate.match(input);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return delegate.matchesBounded(input, start, end);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    return delegate.matchBounded(input, start, end);
  }

  @Override
  public MatchResult findMatch(String input) {
    if (input != null && factIndexOf(input, 0) < 0) {
      return null;
    }
    return delegate.findMatch(input);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    if (input != null && factIndexOf(input, start) < 0) {
      return null;
    }
    return delegate.findMatchFrom(input, start);
  }

  @Override
  public boolean findBoundsFrom(String input, int start, int[] bounds) {
    return delegate.findBoundsFrom(input, start, bounds);
  }
}
