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

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Linear specialized matcher for the canonical logs-backend access-log Grok expansion. */
final class AccessLogGrokMatcher extends ReggieMatcher {
  private final int groupCount;
  private final boolean combined;
  private final int[] grokGroups;
  private final int[] scratchStarts;
  private final int[] scratchEnds;

  AccessLogGrokMatcher(
      String pattern, int groupCount, Map<String, Integer> nameToIndex, boolean combined) {
    super(pattern);
    this.groupCount = groupCount;
    this.combined = combined;
    this.nameToIndex = Map.copyOf(nameToIndex);
    this.grokGroups = new int[16];
    Arrays.fill(grokGroups, -1);
    for (int i = 0; i < grokGroups.length; i++) {
      Integer group = nameToIndex.get("grok" + i);
      if (group != null) {
        grokGroups[i] = group;
      }
    }
    this.scratchStarts = new int[groupCount + 1];
    this.scratchEnds = new int[groupCount + 1];
  }

  @Override
  public boolean matches(String input) {
    return matchInto(input, scratchStarts, scratchEnds);
  }

  @Override
  public boolean find(String input) {
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    Objects.requireNonNull(input, "input");
    if (start < 0 || start > input.length()) {
      return -1;
    }
    for (int i = start; i <= input.length(); i++) {
      if (matchesAt(input, i, scratchStarts, scratchEnds, false)) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public MatchResult match(String input) {
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    return matchInto(input, starts, ends)
        ? new MatchResultImpl(input, starts, ends, groupCount, nameToIndex)
        : null;
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    Objects.requireNonNull(input, "input");
    if (start < 0 || end < start || end > input.length()) {
      return false;
    }
    return matches(input.subSequence(start, end).toString());
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    Objects.requireNonNull(input, "input");
    if (start < 0 || end < start || end > input.length()) {
      return null;
    }
    return match(input.subSequence(start, end).toString());
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int pos = findFrom(input, start);
    if (pos < 0) {
      return null;
    }
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    if (!matchesAt(input, pos, starts, ends, false)) {
      return null;
    }
    return new MatchResultImpl(input, starts, ends, groupCount, nameToIndex);
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(groupStarts, "groupStarts");
    Objects.requireNonNull(groupEnds, "groupEnds");
    if (groupStarts.length <= groupCount || groupEnds.length <= groupCount) {
      throw new IndexOutOfBoundsException("group arrays too small for " + groupCount + " groups");
    }
    if (!matchesAt(input, 0, scratchStarts, scratchEnds, true)) {
      return false;
    }
    System.arraycopy(scratchStarts, 0, groupStarts, 0, groupCount + 1);
    System.arraycopy(scratchEnds, 0, groupEnds, 0, groupCount + 1);
    return true;
  }

  private boolean matchesAt(String input, int offset, int[] starts, int[] ends, boolean fullMatch) {
    Arrays.fill(starts, -1);
    Arrays.fill(ends, -1);
    starts[0] = offset;

    int pos = offset;
    pos = captureNonSpace(input, pos, grokGroups[0], starts, ends);
    if (pos < 0 || !isIpOrHost(input, starts[grokGroups[0]], ends[grokGroups[0]])) return false;
    if ((pos = expect(input, pos, ' ')) < 0) return false;

    pos = captureNonSpace(input, pos, grokGroups[1], starts, ends);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureNonSpace(input, pos, grokGroups[2], starts, ends);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;

    if ((pos = expect(input, pos, '[')) < 0) return false;
    pos = captureUntil(input, pos, ']', grokGroups[3], starts, ends);
    if (pos < 0 || (pos = expect(input, pos, ']')) < 0) return false;
    pos = skipWhitespace(input, pos);
    if (pos < 0 || (pos = expect(input, pos, '"')) < 0) return false;

    int methodStart = pos;
    int methodEnd = scanWord(input, pos);
    if (methodEnd > methodStart && methodEnd < input.length() && input.charAt(methodEnd) == ' ') {
      set(starts, ends, grokGroups[4], methodStart, methodEnd);
      pos = methodEnd + 1;
    }

    int urlStart = pos;
    while (pos < input.length() && input.charAt(pos) != ' ' && input.charAt(pos) != '"') {
      pos++;
    }
    if (pos == urlStart) return false;
    set(starts, ends, grokGroups[5], urlStart, pos);

    if (startsWith(input, pos, " HTTP/")) {
      pos += 6;
      int versionStart = pos;
      while (pos < input.length() && (isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
        pos++;
      }
      if (pos == versionStart || !containsDot(input, versionStart, pos)) return false;
      set(starts, ends, grokGroups[6], versionStart, pos);
    }
    if ((pos = expect(input, pos, '"')) < 0) return false;
    if ((pos = expect(input, pos, ' ')) < 0) return false;

    pos = captureSignedDigits(input, pos, grokGroups[7], starts, ends);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    if (pos < input.length() && input.charAt(pos) == '-') {
      pos++;
    } else {
      pos = captureSignedDigits(input, pos, grokGroups[8], starts, ends);
      if (pos < 0) return false;
    }

    if (!combined) {
      if (fullMatch && pos != input.length()) return false;
      ends[0] = pos;
      return true;
    }

    if ((pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureQuotedUntil(input, pos, '"', grokGroups[9], starts, ends, true);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureQuotedUntil(input, pos, '"', grokGroups[10], starts, ends, false);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureQuotedUntil(input, pos, '"', grokGroups[11], starts, ends, false);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureQuotedUntil(input, pos, '"', grokGroups[12], starts, ends, false);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureNumber(input, pos, grokGroups[13], starts, ends);
    if (pos < 0 || (pos = expect(input, pos, ' ')) < 0) return false;
    pos = captureNumber(input, pos, grokGroups[14], starts, ends);
    if (pos < 0) return false;

    int loggerOpen = findLoggerBracket(input, pos);
    if (loggerOpen < 0) return false;
    int loggerStart = loggerOpen + 1;
    int loggerEnd = scanWord(input, loggerStart);
    if (loggerEnd == loggerStart || loggerEnd >= input.length() || input.charAt(loggerEnd) != ']') {
      return false;
    }
    set(starts, ends, grokGroups[15], loggerStart, loggerEnd);
    pos = loggerEnd + 1;
    if (pos >= input.length() || !Character.isWhitespace(input.charAt(pos))) return false;
    if (fullMatch) {
      ends[0] = input.length();
    } else {
      ends[0] = input.length();
    }
    return true;
  }

  private static int captureNonSpace(String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < input.length() && !Character.isWhitespace(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureUntil(
      String input, int pos, char delimiter, int group, int[] starts, int[] ends) {
    int start = pos;
    int end = input.indexOf(delimiter, pos);
    if (end < 0) return -1;
    set(starts, ends, group, start, end);
    return end;
  }

  private static int captureQuotedUntil(
      String input,
      int pos,
      char delimiter,
      int group,
      int[] starts,
      int[] ends,
      boolean nonSpace) {
    if ((pos = expect(input, pos, '"')) < 0) return -1;
    int start = pos;
    int end = input.indexOf(delimiter, pos);
    if (end < 0) return -1;
    if (nonSpace) {
      for (int i = start; i < end; i++) {
        if (Character.isWhitespace(input.charAt(i))) return -1;
      }
    }
    set(starts, ends, group, start, end);
    return end + 1;
  }

  private static int captureSignedDigits(
      String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
    int digitStart = pos;
    while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
    if (pos == digitStart) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureNumber(String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
    int before = pos;
    while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
    if (pos < input.length() && input.charAt(pos) == '.') {
      pos++;
      while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
    }
    if (pos == before || (pos == before + 1 && input.charAt(before) == '.')) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int skipWhitespace(String input, int pos) {
    int start = pos;
    while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    return pos == start ? -1 : pos;
  }

  private static int expect(String input, int pos, char expected) {
    return pos < input.length() && input.charAt(pos) == expected ? pos + 1 : -1;
  }

  private static boolean startsWith(String input, int pos, String prefix) {
    return input.regionMatches(pos, prefix, 0, prefix.length());
  }

  private static boolean containsDot(String input, int start, int end) {
    for (int i = start; i < end; i++) if (input.charAt(i) == '.') return true;
    return false;
  }

  private static int scanWord(String input, int pos) {
    while (pos < input.length()) {
      char ch = input.charAt(pos);
      if (!isWord(ch)) break;
      pos++;
    }
    return pos;
  }

  private static int findLoggerBracket(String input, int pos) {
    int search = pos;
    while (search < input.length()) {
      int open = input.indexOf('[', search);
      if (open < 0) return -1;
      int close = input.indexOf(']', open + 1);
      if (close < 0) return -1;
      if (close + 1 < input.length() && Character.isWhitespace(input.charAt(close + 1))) {
        int wordEnd = scanWord(input, open + 1);
        if (wordEnd == close && wordEnd > open + 1) return open;
      }
      search = open + 1;
    }
    return -1;
  }

  private static void set(int[] starts, int[] ends, int group, int start, int end) {
    if (group > 0) {
      starts[group] = start;
      ends[group] = end;
    }
  }

  private static boolean isIpOrHost(String input, int start, int end) {
    if (start < 0 || end <= start) return false;
    boolean hasHostChar = false;
    for (int i = start; i < end; i++) {
      char ch = input.charAt(i);
      if (isAsciiAlphaNum(ch) || ch == '-' || ch == '_' || ch == '.' || ch == ':' || ch == '%') {
        hasHostChar = true;
      } else {
        return false;
      }
    }
    return hasHostChar;
  }

  private static boolean isDigit(char ch) {
    return ch >= '0' && ch <= '9';
  }

  private static boolean isAsciiAlphaNum(char ch) {
    return isDigit(ch) || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
  }

  private static boolean isWord(char ch) {
    return isAsciiAlphaNum(ch) || ch == '_';
  }
}
