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
package com.datadoghq.reggie.benchmark;

/**
 * Hand-written specialized matcher for a single pattern: {@code
 * (?s)(?m)^(?:\s*(?:sudo|doas)\s+)?\b\S+\b\s*(.*)} (the {@code IastRegexpBenchmark} COMMAND
 * pattern). Not a generator output — this is a throwaway prototype to measure the performance
 * ceiling of per-pattern generated code (direct char comparisons, no job-stack, no CharSet
 * indirection) before committing to building a real bytecode generator for BitState-class patterns.
 */
final class CommandPatternPrototype {

  private CommandPatternPrototype() {}

  /**
   * Returns the span of capture group 1 packed as {@code (start << 32) | end}, or {@code -1} if no
   * match is found anywhere in the input.
   */
  static long findMatch(CharSequence input) {
    int n = input.length();
    int lineStart = 0;
    while (lineStart <= n) {
      long r = tryMatchAt(input, lineStart, n);
      if (r != -1L) {
        return r;
      }
      int nl = indexOfNewline(input, lineStart, n);
      if (nl < 0) {
        break;
      }
      lineStart = nl + 1;
    }
    return -1L;
  }

  private static int indexOfNewline(CharSequence input, int from, int n) {
    for (int i = from; i < n; i++) {
      if (input.charAt(i) == '\n') {
        return i;
      }
    }
    return -1;
  }

  private static long tryMatchAt(CharSequence input, int start, int n) {
    // Greedy optional group first: \s*(?:sudo|doas)\s+
    int q = skipSpaces(input, start, n);
    int afterKeyword = matchKeyword(input, q, n);
    if (afterKeyword >= 0) {
      int r = skipSpaces(input, afterKeyword, n);
      if (r > afterKeyword) {
        long res = matchMandatory(input, r, n);
        if (res != -1L) {
          return res;
        }
      }
    }
    // Backtrack: optional group not taken.
    return matchMandatory(input, start, n);
  }

  private static int skipSpaces(CharSequence input, int p, int n) {
    while (p < n && isSpace(input.charAt(p))) {
      p++;
    }
    return p;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
  }

  private static int matchKeyword(CharSequence input, int p, int n) {
    if (p + 4 <= n
        && input.charAt(p) == 's'
        && input.charAt(p + 1) == 'u'
        && input.charAt(p + 2) == 'd'
        && input.charAt(p + 3) == 'o') {
      return p + 4;
    }
    if (p + 4 <= n
        && input.charAt(p) == 'd'
        && input.charAt(p + 1) == 'o'
        && input.charAt(p + 2) == 'a'
        && input.charAt(p + 3) == 's') {
      return p + 4;
    }
    return -1;
  }

  // Matches \b\S+\b\s*(.*) starting at p. The trailing \b after \S+ is implied: a non-space
  // char followed by a space or end-of-input is always a word boundary, so it needs no check.
  private static long matchMandatory(CharSequence input, int p, int n) {
    if (p >= n || !isWordBoundary(input, p, n) || isSpace(input.charAt(p))) {
      return -1L;
    }
    int q = p;
    while (q < n && !isSpace(input.charAt(q))) {
      q++;
    }
    int r = skipSpaces(input, q, n);
    return ((long) r << 32) | (n & 0xFFFFFFFFL);
  }

  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static boolean isWordBoundary(CharSequence input, int p, int n) {
    boolean before = p > 0 && isWordChar(input.charAt(p - 1));
    boolean after = p < n && isWordChar(input.charAt(p));
    return before != after;
  }
}
