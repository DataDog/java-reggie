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
package com.datadoghq.reggie.codegen.parsing;

import java.util.BitSet;

/**
 * Lexical helpers for the scanners that walk a raw pattern string without parsing it ({@link
 * GroupCounter}, the parser's group pre-count, the runtime's inline-modifier check). They find the
 * same class and comment boundaries as {@link RegexParser}, which follows java.util.regex.
 */
public final class PatternLexer {
  private PatternLexer() {}

  /**
   * Returns the index after the {@code ]} that closes the class opening at {@code i}, or the
   * pattern length when the class is unclosed. As in java.util.regex, a {@code ]} is a literal
   * while the class has no content yet ({@code []]}, {@code [^]]}), a nested {@code [..]} is
   * skipped as a whole, and {@code \Q..\E} quotes are skipped. With {@code comments}, whitespace
   * and {@code #} comments are not content.
   */
  public static int skipClass(String p, int i, boolean comments) {
    int len = p.length();
    int j = i + 1;
    if (j < len && p.charAt(j) == '^') {
      j++;
    }
    boolean content = false;
    while (j < len) {
      char c = p.charAt(j);
      if (comments && isSpace(c)) {
        j++;
      } else if (comments && c == '#') {
        j = skipComment(p, j);
      } else if (c == '[') {
        j = skipClass(p, j, comments);
        content = true;
      } else if (c == ']') {
        if (content) {
          return j + 1;
        }
        j++;
        content = true;
      } else if (c == '\\' && j + 1 < len && p.charAt(j + 1) == 'Q') {
        int end = p.indexOf("\\E", j + 2);
        int stop = end < 0 ? len : end;
        content |= stop > j + 2;
        j = end < 0 ? len : end + 2;
      } else if (c == '\\') {
        j += 2;
        content = true;
      } else {
        j++;
        content = true;
      }
    }
    return len;
  }

  /**
   * Returns the index of the character that ends the {@code #} comment at {@code i} (a line
   * terminator or NUL, not consumed), or the pattern length.
   */
  public static int skipComment(String p, int i) {
    int j = i + 1;
    while (j < p.length() && !endsComment(p.charAt(j))) {
      j++;
    }
    return j;
  }

  /**
   * Tracks whether {@code (?x)} comment mode is on while a scanner walks the pattern. As in
   * java.util.regex, a group restores the mode it started with when it closes, {@code (?x)} applies
   * to the rest of its enclosing group, and {@code (?x:..)} to its own group.
   */
  public static final class CommentMode {
    private final BitSet saved = new BitSet();
    private int depth;
    private boolean on;

    /** Returns whether comment mode is on. */
    public boolean on() {
      return on;
    }

    /**
     * Opens the group at the {@code (} at {@code i}. Returns {@code i}, or, for a flags-only {@code
     * (?flags)}, the index of its {@code )}, which then does not close a group.
     */
    public int open(String p, int i) {
      boolean next = on;
      if (i + 1 < p.length() && p.charAt(i + 1) == '?') {
        boolean set = true;
        for (int j = i + 2; j < p.length(); j++) {
          char c = p.charAt(j);
          if (c == ')' && j > i + 2) {
            on = next;
            return j;
          }
          if (c == ':') {
            break;
          }
          if (c == '-') {
            set = false;
          } else if (c == 'x') {
            next = set;
          } else if (!Character.isLetter(c)) {
            next = on;
            break;
          }
        }
      }
      saved.set(depth++, on);
      on = next;
      return i;
    }

    /** Closes the innermost open group; a {@code )} without an open group is ignored. */
    public void close() {
      if (depth > 0) {
        on = saved.get(--depth);
      }
    }
  }

  /** The characters {@code (?x)} skips: space and {@code \t \n \x0B \f \r}. */
  static boolean isSpace(char c) {
    return c == ' ' || (c >= '\t' && c <= '\r');
  }

  /** The characters that end a {@code (?x)} comment. */
  static boolean endsComment(char c) {
    return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029' || c == '\0';
  }
}
