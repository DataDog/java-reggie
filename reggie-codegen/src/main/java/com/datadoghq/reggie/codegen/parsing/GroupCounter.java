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

/**
 * Counts capturing groups in a regex pattern string. Shared by the runtime and annotation-processor
 * compilation paths so both build the NFA with the same group count.
 */
public final class GroupCounter {
  private GroupCounter() {}

  /**
   * Returns the number of capturing groups in {@code pattern}. Parentheses inside character classes
   * and {@code \Q...\E} quotes, escaped parentheses, and the {@code (?...)} forms for non-capturing
   * groups, lookarounds, atomic groups, comments, branch resets, conditionals and inline modifiers
   * are not counted; named groups are.
   */
  public static int count(String pattern) {
    int count = 0;
    boolean escaped = false;
    boolean inClass = false; // inside a [...] character class: '(' is a literal, never a group
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\' && i + 1 < pattern.length() && pattern.charAt(i + 1) == 'Q') {
        i += 2;
        while (i + 1 < pattern.length()
            && !(pattern.charAt(i) == '\\' && pattern.charAt(i + 1) == 'E')) {
          i++;
        }
        if (i + 1 < pattern.length()) {
          i++;
        }
        continue;
      }
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (inClass) {
        // A ']' closes the class (a ']' as the very first class character is a literal
        // per java.util.regex; for group counting that distinction cannot change the count,
        // so treating any unescaped ']' as the closer is safe).
        if (c == ']') {
          inClass = false;
        }
      } else if (c == '[') {
        inClass = true;
      } else if (c == '(' && i + 1 < pattern.length()) {
        // Check if it's a capturing group
        // Named groups like (?<name>...) and (?'name'...) ARE capturing groups
        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
          // Check what follows the '?'
          if (i + 2 < pattern.length()) {
            char next = pattern.charAt(i + 2);
            if (next == ':') {
              continue; // Non-capturing (?:...)
            }
            if (next == '=' || next == '!') {
              continue; // Lookahead (?=...) or (?!...)
            }
            if (next == '<' && i + 3 < pattern.length()) {
              char afterLt = pattern.charAt(i + 3);
              if (afterLt == '=' || afterLt == '!') {
                continue; // Lookbehind (?<=...) or (?<!...)
              }
              // (?<name>...) is a named capturing group, count it
            }
            if (next == '>') {
              continue; // Atomic group (?>...)
            }
            if (next == '#') {
              continue; // Comment (?#...)
            }
            if (next == '|') {
              continue; // Branch reset (?|...)
            }
            if (next == '(') {
              continue; // Conditional (?(...)...)
            }
            // Check for inline modifiers like (?i), (?m), (?s), (?x), (?-i), etc.
            if (next == '-'
                || next == 'i'
                || next == 'm'
                || next == 's'
                || next == 'x'
                || next == 'u'
                || next == 'U'
                || next == 'd') {
              continue; // Inline modifier (?i...) etc.
            }
          }
        }
        count++;
      }
    }
    return count;
  }
}
