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

import java.util.Arrays;

/**
 * Counts capturing groups in a regex pattern string. Shared by the runtime and annotation-processor
 * compilation paths so both build the NFA with the same group count.
 */
public final class GroupCounter {
  private GroupCounter() {}

  /**
   * Returns the number of capturing groups in {@code pattern}. Parentheses inside character classes
   * (with their boundaries found by {@link PatternLexer#skipClass}), {@code \Q...\E} quotes and
   * {@code (?x)} comments, escaped parentheses, and the {@code (?...)} forms for non-capturing
   * groups, lookarounds, atomic groups, comments, branch resets, conditionals (with their condition
   * parenthesis), subroutine calls and inline modifiers are not counted; named groups are. Inside a
   * branch reset {@code (?|...)} each top-level alternative restarts at the count before the group,
   * and the group ends at the largest count any alternative reached, as RegexParser numbers it.
   */
  public static int count(String pattern) {
    int count = 0;
    boolean escaped = false;
    PatternLexer.CommentMode comments = new PatternLexer.CommentMode();
    int depth = 0; // open groups
    // Open branch resets, innermost last: depth inside the group, count before it, max reached
    int[] reset = null;
    int resets = 0;
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
      } else if (c == '[') {
        i = PatternLexer.skipClass(pattern, i, comments.on()) - 1;
      } else if (c == '#' && comments.on()) {
        i = PatternLexer.skipComment(pattern, i) - 1;
      } else if (c == '|') {
        if (resets > 0 && reset[3 * resets - 3] == depth) {
          int r = 3 * resets - 3;
          reset[r + 2] = Math.max(reset[r + 2], count);
          count = reset[r + 1];
        }
      } else if (c == ')') {
        comments.close();
        if (resets > 0 && reset[3 * resets - 3] == depth) {
          resets--;
          count = Math.max(reset[3 * resets + 2], count);
        }
        if (depth > 0) {
          depth--;
        }
      } else if (c == '(' && i + 1 < pattern.length()) {
        int flagsEnd = comments.open(pattern, i);
        if (flagsEnd != i) {
          i = flagsEnd;
          continue; // Inline modifier (?i) etc.
        }
        depth++;
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
              if (reset == null) {
                reset = new int[12];
              } else if (3 * resets == reset.length) {
                reset = Arrays.copyOf(reset, 2 * reset.length);
              }
              reset[3 * resets] = depth;
              reset[3 * resets + 1] = count;
              reset[3 * resets + 2] = count;
              resets++;
              i += 2; // the '|' after '?' does not separate alternatives
              continue; // Branch reset (?|...)
            }
            if (next == '(') {
              // Conditional (?(...)...): a lookaround condition is a group of its own; any other
              // condition (group number, name, R) is not a group, so skip to its ')'
              if (i + 3 < pattern.length() && pattern.charAt(i + 3) != '?') {
                int close = pattern.indexOf(')', i + 3);
                if (close < 0) {
                  return count;
                }
                i = close;
              }
              continue;
            }
            if (next == '&' || next == 'R' || next == '+' || (next >= '0' && next <= '9')) {
              continue; // Subroutine call (?&name), (?R), (?+1), (?1)
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
