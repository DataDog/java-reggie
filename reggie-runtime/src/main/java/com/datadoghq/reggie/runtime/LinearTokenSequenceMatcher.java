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

import com.datadoghq.reggie.codegen.analysis.LinearTokenSequencePlan;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Generic runtime executor for deterministic linear-token-sequence plans. */
final class LinearTokenSequenceMatcher extends ReggieMatcher {
  private final LinearTokenSequencePlan plan;
  private final int groupCount;
  private final int[] scratchStarts;
  private final int[] scratchEnds;
  private final int[][] optionalScratchStarts;
  private final int[][] optionalScratchEnds;

  LinearTokenSequenceMatcher(
      String pattern,
      LinearTokenSequencePlan plan,
      int groupCount,
      Map<String, Integer> nameToIndex) {
    super(pattern);
    this.plan = plan;
    this.groupCount = groupCount;
    this.nameToIndex = Map.copyOf(nameToIndex);
    this.scratchStarts = new int[groupCount + 1];
    this.scratchEnds = new int[groupCount + 1];
    int optionalDepth = maxOptionalDepth(plan.ops());
    this.optionalScratchStarts = new int[optionalDepth][groupCount + 1];
    this.optionalScratchEnds = new int[optionalDepth][groupCount + 1];
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
    if (start < 0 || start > input.length()) return -1;
    for (int pos = start; pos <= input.length(); pos++) {
      if (matchesAt(input, pos, scratchStarts, scratchEnds, false)) return pos;
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
    return start >= 0
        && end >= start
        && end <= input.length()
        && matches(input.subSequence(start, end).toString());
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    Objects.requireNonNull(input, "input");
    if (start < 0 || end < start || end > input.length()) return null;
    return match(input.subSequence(start, end).toString());
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int pos = findFrom(input, start);
    if (pos < 0) return null;
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    return matchesAt(input, pos, starts, ends, false)
        ? new MatchResultImpl(input, starts, ends, groupCount, nameToIndex)
        : null;
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(groupStarts, "groupStarts");
    Objects.requireNonNull(groupEnds, "groupEnds");
    if (groupStarts.length <= groupCount || groupEnds.length <= groupCount) {
      throw new IndexOutOfBoundsException("group arrays too small for " + groupCount + " groups");
    }
    if (!matchesAt(input, 0, scratchStarts, scratchEnds, true)) return false;
    System.arraycopy(scratchStarts, 0, groupStarts, 0, groupCount + 1);
    System.arraycopy(scratchEnds, 0, groupEnds, 0, groupCount + 1);
    return true;
  }

  private boolean matchesAt(String input, int offset, int[] starts, int[] ends, boolean fullMatch) {
    Arrays.fill(starts, -1);
    Arrays.fill(ends, -1);
    starts[0] = offset;
    int pos = offset;
    for (int i = 0; i < plan.ops().size(); i++) {
      pos = apply(plan.ops().get(i), input, pos, starts, ends, i == plan.ops().size() - 1, 0);
      if (pos < 0) return false;
    }
    if (fullMatch && pos != input.length()) return false;
    ends[0] = fullMatch ? input.length() : pos;
    return true;
  }

  private int apply(
      LinearTokenSequencePlan.Op op,
      String input,
      int pos,
      int[] starts,
      int[] ends,
      boolean lastOp,
      int optionalDepth) {
    return switch (op.kind()) {
      case LITERAL -> startsWith(input, pos, op.literal()) ? pos + op.literal().length() : -1;
      case WHITESPACE_PLUS -> skipWhitespace(input, pos);
      case CAPTURE_NON_SPACE -> captureNonSpace(input, pos, op.groupNumber(), starts, ends);
      case CAPTURE_DIGITS -> captureDigits(input, pos, op.groupNumber(), starts, ends);
      case CAPTURE_SIGNED_INTEGER ->
          captureSignedInteger(input, pos, op.groupNumber(), starts, ends);
      case CAPTURE_SIGNED_INTEGER_OR_DASH ->
          captureSignedIntegerOrDash(input, pos, op.groupNumber(), starts, ends);
      case CAPTURE_DECIMAL_NUMBER ->
          captureDecimal(input, pos, op.groupNumber(), starts, ends, false);
      case CAPTURE_SIGNED_DECIMAL_NUMBER ->
          captureDecimal(input, pos, op.groupNumber(), starts, ends, true);
      case CAPTURE_WORD -> captureWord(input, pos, op.groupNumber(), starts, ends);
      case CAPTURE_UNTIL_DELIMITER ->
          captureUntil(input, pos, op.delimiter(), op.groupNumber(), starts, ends);
      case CAPTURE_QUOTED_UNTIL_DELIMITER ->
          captureQuotedUntil(input, pos, op.delimiter(), op.groupNumber(), starts, ends, false);
      case CAPTURE_QUOTED_NON_SPACE ->
          captureQuotedUntil(input, pos, op.delimiter(), op.groupNumber(), starts, ends, true);
      case CAPTURE_IP_OR_HOST -> captureIpOrHost(input, pos, op.groupNumber(), starts, ends);
      case CAPTURE_BRACKETED_WORD_AFTER_SKIP ->
          captureBracketedWordAfterSkip(input, pos, op.groupNumber(), starts, ends);
      case SKIP_ANY -> lastOp ? input.length() : -1;
      case ANCHOR -> pos;
      case OPTIONAL_SEQUENCE -> applyOptional(op, input, pos, starts, ends, optionalDepth);
    };
  }

  private static int captureNonSpace(String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < input.length() && !Character.isWhitespace(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureDigits(String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureSignedInteger(
      String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
    int digitStart = pos;
    while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
    if (pos == digitStart) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureSignedIntegerOrDash(
      String input, int pos, int group, int[] starts, int[] ends) {
    if (pos < input.length() && input.charAt(pos) == '-') return pos + 1;
    return captureSignedInteger(input, pos, group, starts, ends);
  }

  private static int captureDecimal(
      String input, int pos, int group, int[] starts, int[] ends, boolean signed) {
    int start = pos;
    if (signed && pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
      pos++;
    }
    int digitStart = pos;
    while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
    boolean sawLeadingDigits = pos > digitStart;
    if (pos < input.length() && input.charAt(pos) == '.') {
      pos++;
      int fractionStart = pos;
      while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
      if (!sawLeadingDigits && pos == fractionStart) return -1;
    } else if (!sawLeadingDigits) {
      return -1;
    }
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureWord(String input, int pos, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < input.length() && isWord(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureUntil(
      String input, int pos, char delimiter, int group, int[] starts, int[] ends) {
    int end = input.indexOf(delimiter, pos);
    if (end < 0) return -1;
    set(starts, ends, group, pos, end);
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
    if (pos >= input.length() || input.charAt(pos) != '"') return -1;
    int start = pos + 1;
    int end = input.indexOf(delimiter, start);
    if (end < 0) return -1;
    if (nonSpace) {
      for (int i = start; i < end; i++) {
        if (Character.isWhitespace(input.charAt(i))) return -1;
      }
    }
    set(starts, ends, group, start, end);
    return end + 1;
  }

  private static int captureIpOrHost(String input, int pos, int group, int[] starts, int[] ends) {
    int end = captureNonSpace(input, pos, group, starts, ends);
    return end >= 0 && isIpOrHost(input, pos, end) ? end : -1;
  }

  private static int captureBracketedWordAfterSkip(
      String input, int pos, int group, int[] starts, int[] ends) {
    int search = pos;
    while (search < input.length()) {
      int open = input.indexOf('[', search);
      if (open < 0) return -1;
      int close = input.indexOf(']', open + 1);
      if (close < 0) return -1;
      int wordEnd = open + 1;
      while (wordEnd < close && isWord(input.charAt(wordEnd))) wordEnd++;
      if (wordEnd == close
          && wordEnd > open + 1
          && close + 1 < input.length()
          && Character.isWhitespace(input.charAt(close + 1))) {
        set(starts, ends, group, open + 1, close);
        return input.length();
      }
      search = open + 1;
    }
    return -1;
  }

  private int applyOptional(
      LinearTokenSequencePlan.Op op,
      String input,
      int pos,
      int[] starts,
      int[] ends,
      int optionalDepth) {
    int[] savedStarts = optionalScratchStarts[optionalDepth];
    int[] savedEnds = optionalScratchEnds[optionalDepth];
    System.arraycopy(starts, 0, savedStarts, 0, starts.length);
    System.arraycopy(ends, 0, savedEnds, 0, ends.length);
    int next = pos;
    for (int i = 0; i < op.children().size(); i++) {
      next =
          apply(
              op.children().get(i),
              input,
              next,
              starts,
              ends,
              i == op.children().size() - 1,
              optionalDepth + 1);
      if (next < 0) {
        System.arraycopy(savedStarts, 0, starts, 0, starts.length);
        System.arraycopy(savedEnds, 0, ends, 0, ends.length);
        return pos;
      }
    }
    return next;
  }

  private static int maxOptionalDepth(Iterable<LinearTokenSequencePlan.Op> ops) {
    int max = 0;
    for (LinearTokenSequencePlan.Op op : ops) {
      int childDepth = maxOptionalDepth(op.children());
      max =
          Math.max(
              max,
              op.kind() == LinearTokenSequencePlan.OpKind.OPTIONAL_SEQUENCE
                  ? 1 + childDepth
                  : childDepth);
    }
    return max;
  }

  private static int skipWhitespace(String input, int pos) {
    int start = pos;
    while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    return pos == start ? -1 : pos;
  }

  private static boolean startsWith(String input, int pos, String prefix) {
    return pos >= 0 && pos + prefix.length() <= input.length() && input.startsWith(prefix, pos);
  }

  private static void set(int[] starts, int[] ends, int group, int start, int end) {
    if (group > 0) {
      starts[group] = start;
      ends[group] = end;
    }
  }

  private static boolean isIpOrHost(String input, int start, int end) {
    for (int i = start; i < end; i++) {
      char ch = input.charAt(i);
      if (!isAsciiAlphaNum(ch) && ch != '-' && ch != '_' && ch != '.' && ch != ':' && ch != '%') {
        return false;
      }
    }
    return end > start;
  }

  private static boolean isDigit(char ch) {
    return ch >= '0' && ch <= '9';
  }

  private static boolean isWord(char ch) {
    return isAsciiAlphaNum(ch) || ch == '_';
  }

  private static boolean isAsciiAlphaNum(char ch) {
    return isDigit(ch) || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
  }
}
