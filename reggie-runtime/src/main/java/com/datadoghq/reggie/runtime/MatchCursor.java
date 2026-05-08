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

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Stateful streaming cursor for incremental match-and-replace, similar to {@link
 * java.util.regex.Matcher#appendReplacement}/{@link java.util.regex.Matcher#appendTail}.
 *
 * <p><b>Not thread-safe.</b> A single cursor instance must be used by only one thread at a time.
 * Additionally, a single {@link ReggieMatcher} instance must not be used concurrently by multiple
 * cursors — its NFA state is mutable and unsynchronized. If concurrent use is required, compile a
 * separate {@code ReggieMatcher} per thread.
 *
 * <p>Obtain instances via {@link ReggieMatcher#cursor(String)}.
 */
public final class MatchCursor implements Iterator<MatchResult>, AutoCloseable {

  private static final Map<String, Integer> EMPTY_NAME_INDEX = Collections.emptyMap();

  private final ReggieMatcher matcher;
  private final Map<String, Integer> nameIndex;

  private String input;
  private int searchPos; // where the next findMatchFrom() call starts
  private int appendPos; // where the next appendReplacement/appendTail append starts
  private MatchResult lastMatch;
  private boolean tailAppended;
  // buffer for Iterator.hasNext() lookahead
  private MatchResult peeked;
  private boolean peekedValid;

  MatchCursor(ReggieMatcher matcher, String input) {
    this.matcher = matcher;
    this.input = input;
    this.nameIndex = parseNamedGroups(matcher.pattern());
  }

  /**
   * Advances the cursor and returns the next match, or {@code null} when exhausted or after
   * appendTail.
   *
   * <p>Design: returns {@code null} rather than {@link java.util.Optional} to stay allocation-free
   * on the hot path. Callers should use {@code != null} to check for a match.
   *
   * <p>Side effect: sets the active match for a subsequent {@link #appendReplacement} call. {@link
   * #hasNext()} calls this method internally for lookahead; {@link #appendReplacement} clears the
   * lookahead buffer, so mixing {@code hasNext()}/{@code next()} with {@code appendReplacement()}
   * is supported and behaves correctly.
   */
  public MatchResult findNext() {
    if (tailAppended || input == null) {
      return null;
    }
    if (searchPos > input.length()) {
      return null;
    }
    MatchResult m = matcher.findMatchFrom(input, searchPos);
    if (m == null) {
      return null;
    }
    if (!nameIndex.isEmpty()) {
      m = withNameIndex(m);
    }
    // advance searchPos past this match (zero-width guard)
    searchPos = m.end() > m.start() ? m.end() : m.end() + 1;
    lastMatch = m;
    return m;
  }

  /**
   * Appends gap text and expanded replacement for the last match to {@code sb}.
   *
   * @return this, for fluent chaining
   * @throws NullPointerException if {@code sb} or {@code replacement} is null
   * @throws IllegalStateException if no active match or tail already appended
   */
  public MatchCursor appendReplacement(StringBuilder sb, String replacement) {
    Objects.requireNonNull(sb, "sb");
    Objects.requireNonNull(replacement, "replacement");
    if (lastMatch == null) {
      throw new IllegalStateException("No active match; call findNext() first");
    }
    if (tailAppended) {
      throw new IllegalStateException("appendTail() already called");
    }
    sb.append(input, appendPos, lastMatch.start());
    expandReplacement(sb, replacement, lastMatch);
    appendPos = lastMatch.end();
    lastMatch = null;
    // Clear lookahead buffer so hasNext() re-advances after appendReplacement.
    peeked = null;
    peekedValid = false;
    return this;
  }

  /** Appends remaining input after the last append position and marks tail as appended. */
  public StringBuilder appendTail(StringBuilder sb) {
    Objects.requireNonNull(sb, "sb");
    if (tailAppended) {
      throw new IllegalStateException("appendTail() already called");
    }
    if (input != null) {
      sb.append(input, appendPos, input.length());
    }
    tailAppended = true;
    return sb;
  }

  /** Resets this cursor to a new input string; safe to call at any point. */
  public void reset(String newInput) {
    this.input = newInput;
    this.searchPos = 0;
    this.appendPos = 0;
    this.lastMatch = null;
    this.tailAppended = false;
    this.peeked = null;
    this.peekedValid = false;
  }

  @Override
  public void close() {
    input = null;
    searchPos = 0;
    appendPos = 0;
    lastMatch = null;
    tailAppended = false;
    peeked = null;
    peekedValid = false;
  }

  @Override
  public boolean hasNext() {
    if (!peekedValid) {
      MatchResult next = findNext();
      if (next != null) {
        peeked = next;
        peekedValid = true;
      }
    }
    return peekedValid;
  }

  @Override
  public MatchResult next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    MatchResult result = peeked;
    peeked = null;
    peekedValid = false;
    return result;
  }

  // Wraps a MatchResult to support named group lookup via nameIndex.
  private MatchResult withNameIndex(MatchResult m) {
    int gc = m.groupCount();
    int[] starts = new int[gc + 1];
    int[] ends = new int[gc + 1];
    starts[0] = m.start();
    ends[0] = m.end();
    for (int i = 1; i <= gc; i++) {
      starts[i] = m.start(i);
      ends[i] = m.end(i);
    }
    return new MatchResultImpl(input, starts, ends, gc, nameIndex);
  }

  // Expands $0, $1, $2, ${name} backreferences from replacement into sb.
  // Follows java.util.regex.Matcher semantics: throws IndexOutOfBoundsException for
  // out-of-range numeric groups, and IllegalArgumentException for unknown named groups.
  private void expandReplacement(StringBuilder sb, String replacement, MatchResult m) {
    int len = replacement.length();
    for (int i = 0; i < len; i++) {
      char c = replacement.charAt(i);
      if (c != '$' || i + 1 >= len) {
        sb.append(c);
        continue;
      }
      char next = replacement.charAt(i + 1);
      if (next >= '0' && next <= '9') {
        int groupNum = next - '0';
        i++;
        // consume additional digits
        while (i + 1 < len
            && replacement.charAt(i + 1) >= '0'
            && replacement.charAt(i + 1) <= '9') {
          groupNum = groupNum * 10 + (replacement.charAt(i + 1) - '0');
          i++;
        }
        if (groupNum > m.groupCount()) {
          throw new IndexOutOfBoundsException("No group " + groupNum);
        }
        String val = m.group(groupNum);
        if (val != null) sb.append(val);
      } else if (next == '{') {
        int end = replacement.indexOf('}', i + 2);
        if (end < 0) {
          sb.append(c);
          continue;
        }
        String name = replacement.substring(i + 2, end);
        i = end;
        // look up by name index directly; throws IAE for unknown names (JDK behaviour)
        Integer groupIdx = nameIndex.get(name);
        if (groupIdx == null) {
          throw new IllegalArgumentException("No group with name <" + name + ">");
        }
        String val = m.group(groupIdx);
        if (val != null) sb.append(val);
      } else if (next == '$') {
        sb.append('$');
        i++;
      } else {
        sb.append(c);
      }
    }
  }

  // Parses (?<name>...) and (?'name'...) in the pattern, returning name→group-index map.
  // Returns EMPTY_NAME_INDEX when the pattern has no named groups.
  private static Map<String, Integer> parseNamedGroups(String pattern) {
    Map<String, Integer> map = new HashMap<>();
    int groupIndex = 0;
    int len = pattern.length();
    for (int i = 0; i < len; i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++; // skip escaped char
        continue;
      }
      if (c == '[') {
        // skip character class content
        i++;
        // ']' as first char (or after '^') is literal — skip it before entering the loop
        if (i < len && pattern.charAt(i) == '^') i++;
        if (i < len && pattern.charAt(i) == ']') i++;
        while (i < len && pattern.charAt(i) != ']') {
          if (pattern.charAt(i) == '\\') {
            i++; // skip escaped char
            if (i < len) i++; // advance past the escaped char itself
          } else {
            i++;
          }
        }
        continue;
      }
      if (c == '(') {
        if (i + 1 < len && pattern.charAt(i + 1) == '?') {
          if (i + 2 < len) {
            char delim = pattern.charAt(i + 2);
            if (delim == '<' || delim == '\'') {
              char close = delim == '<' ? '>' : '\'';
              int nameEnd = pattern.indexOf(close, i + 3);
              if (nameEnd > i + 3) {
                // check it's not a lookbehind (?<= or (?<!
                char firstNameChar = pattern.charAt(i + 3);
                if (firstNameChar != '=' && firstNameChar != '!') {
                  groupIndex++;
                  String name = pattern.substring(i + 3, nameEnd);
                  map.put(name, groupIndex);
                  i = nameEnd; // advance past name
                  continue;
                }
              }
            }
            // (?:, (?=, (?!, (?>, (?#, (?P) — non-capturing, don't increment groupIndex
          }
          // non-capturing group: do not increment groupIndex
        } else {
          // plain capturing group
          groupIndex++;
        }
      }
    }
    return map.isEmpty() ? EMPTY_NAME_INDEX : map;
  }
}
