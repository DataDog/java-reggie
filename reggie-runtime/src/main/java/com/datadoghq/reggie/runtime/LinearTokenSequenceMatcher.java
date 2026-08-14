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
  private final int optionalDepth;

  LinearTokenSequenceMatcher(
      String pattern,
      LinearTokenSequencePlan plan,
      int groupCount,
      Map<String, Integer> nameToIndex) {
    super(pattern);
    this.plan = plan;
    this.groupCount = groupCount;
    this.nameToIndex = Map.copyOf(nameToIndex);
    this.optionalDepth = maxOptionalDepth(plan.ops());
  }

  @Override
  boolean embedsNameMap() {
    return true;
  }

  @Override
  public boolean matches(String input) {
    Objects.requireNonNull(input, "input");
    return matchesAt(input, 0, input.length(), newWorkspace(), true);
  }

  @Override
  public boolean find(String input) {
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    Objects.requireNonNull(input, "input");
    if (start < 0 || start > input.length()) return -1;
    MatchWorkspace workspace = newWorkspace();
    for (int pos = start; pos <= input.length(); pos++) {
      if (matchesAt(input, pos, input.length(), workspace, false)) return pos;
    }
    return -1;
  }

  @Override
  public MatchResult match(String input) {
    Objects.requireNonNull(input, "input");
    MatchWorkspace workspace = newWorkspace();
    if (!matchesAt(input, 0, input.length(), workspace, true)) return null;
    return toMatchResult(input, workspace);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    Objects.requireNonNull(input, "input");
    if (!isValidRegion(input, start, end)) return false;
    return matchesAt(input, start, end, newWorkspace(), true);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    Objects.requireNonNull(input, "input");
    if (!isValidRegion(input, start, end)) return null;
    MatchWorkspace workspace = newWorkspace();
    if (!matchesAt(input, start, end, workspace, true)) return null;
    return toMatchResult(input.toString(), workspace);
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    Objects.requireNonNull(input, "input");
    if (start < 0 || start > input.length()) return null;
    MatchWorkspace workspace = newWorkspace();
    for (int pos = start; pos <= input.length(); pos++) {
      if (!matchesAt(input, pos, input.length(), workspace, false)) {
        continue;
      }
      return toMatchResult(input, workspace);
    }
    return null;
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    return matchIntoBounded(input, 0, input.length(), groupStarts, groupEnds);
  }

  boolean matchIntoBounded(
      CharSequence input, int start, int end, int[] groupStarts, int[] groupEnds) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(groupStarts, "groupStarts");
    Objects.requireNonNull(groupEnds, "groupEnds");
    validateRegion(input, start, end);
    if (groupStarts.length <= groupCount || groupEnds.length <= groupCount) {
      throw new IndexOutOfBoundsException("group arrays too small for " + groupCount + " groups");
    }
    MatchWorkspace workspace = newWorkspace();
    if (!matchesAt(input, start, end, workspace, true)) return false;
    System.arraycopy(workspace.starts, 0, groupStarts, 0, groupCount + 1);
    System.arraycopy(workspace.ends, 0, groupEnds, 0, groupCount + 1);
    return true;
  }

  MatchWorkspace newMatchWorkspace() {
    return newWorkspace();
  }

  int groupCount() {
    return groupCount;
  }

  int groupIndex(String name) {
    Objects.requireNonNull(name, "name");
    Integer index = nameToIndex.get(name);
    if (index == null) {
      throw new IllegalArgumentException("unknown group name: " + name);
    }
    return index;
  }

  boolean matchIntoBounded(
      CharSequence input,
      int start,
      int end,
      int[] groupStarts,
      int[] groupEnds,
      MatchWorkspace workspace) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(groupStarts, "groupStarts");
    Objects.requireNonNull(groupEnds, "groupEnds");
    Objects.requireNonNull(workspace, "workspace");
    validateRegion(input, start, end);
    if (groupStarts.length <= groupCount || groupEnds.length <= groupCount) {
      throw new IndexOutOfBoundsException("group arrays too small for " + groupCount + " groups");
    }
    if (!matchesAt(input, start, end, workspace, true)) return false;
    System.arraycopy(workspace.starts, 0, groupStarts, 0, groupCount + 1);
    System.arraycopy(workspace.ends, 0, groupEnds, 0, groupCount + 1);
    return true;
  }

  private MatchResult toMatchResult(String input, MatchWorkspace workspace) {
    if (!nameToIndex.isEmpty()) {
      return new NamedMatchResultImpl(
          input, workspace.starts, workspace.ends, groupCount, nameToIndex);
    }
    return new MatchResultImpl(input, workspace.starts, workspace.ends, groupCount, nameToIndex);
  }

  private static void validateRegion(CharSequence input, int start, int end) {
    if (!isValidRegion(input, start, end)) {
      throw new IndexOutOfBoundsException("invalid region [" + start + ", " + end + ")");
    }
  }

  private static boolean isValidRegion(CharSequence input, int start, int end) {
    return start >= 0 && end >= start && end <= input.length();
  }

  private MatchWorkspace newWorkspace() {
    return new MatchWorkspace(groupCount, optionalDepth);
  }

  private boolean matchesAt(
      CharSequence input, int offset, int regionEnd, MatchWorkspace workspace, boolean fullMatch) {
    int[] starts = workspace.starts;
    int[] ends = workspace.ends;
    Arrays.fill(starts, -1);
    Arrays.fill(ends, -1);
    starts[0] = offset;
    int pos = offset;
    for (int i = 0; i < plan.ops().size(); i++) {
      LinearTokenSequencePlan.Op op = plan.ops().get(i);
      if (isTargetBeforeOptionalHttpVersion(plan.ops(), i)) {
        pos =
            captureTargetBeforeOptionalHttpVersion(
                op, plan.ops().get(i + 1), input, pos, regionEnd, starts, ends);
        if (pos < 0) return false;
        i++;
        continue;
      }
      pos =
          apply(op, input, pos, regionEnd, starts, ends, i == plan.ops().size() - 1, workspace, 0);
      if (pos < 0) return false;
    }
    if (fullMatch && pos != regionEnd) return false;
    ends[0] = fullMatch ? regionEnd : pos;
    return true;
  }

  private int apply(
      LinearTokenSequencePlan.Op op,
      CharSequence input,
      int pos,
      int regionEnd,
      int[] starts,
      int[] ends,
      boolean lastOp,
      MatchWorkspace workspace,
      int optionalDepth) {
    return switch (op.kind()) {
      case LITERAL ->
          startsWith(input, pos, regionEnd, op.literal()) ? pos + op.literal().length() : -1;
      case WHITESPACE_PLUS -> skipWhitespace(input, pos, regionEnd);
      case CAPTURE_NON_SPACE ->
          captureNonSpace(input, pos, regionEnd, op.groupNumber(), starts, ends);
      case CAPTURE_DIGITS -> captureDigits(input, pos, regionEnd, op.groupNumber(), starts, ends);
      case CAPTURE_SIGNED_INTEGER ->
          captureSignedInteger(input, pos, regionEnd, op.groupNumber(), starts, ends);
      case CAPTURE_SIGNED_INTEGER_OR_DASH ->
          captureSignedIntegerOrDash(input, pos, regionEnd, op.groupNumber(), starts, ends, true);
      case CAPTURE_SIGNED_INTEGER_OR_UNCAPTURED_DASH ->
          captureSignedIntegerOrDash(input, pos, regionEnd, op.groupNumber(), starts, ends, false);
      case CAPTURE_DECIMAL_NUMBER ->
          captureDecimal(input, pos, regionEnd, op.groupNumber(), starts, ends, false);
      case CAPTURE_SIGNED_DECIMAL_NUMBER ->
          captureDecimal(input, pos, regionEnd, op.groupNumber(), starts, ends, true);
      case CAPTURE_WORD -> captureWord(input, pos, regionEnd, op.groupNumber(), starts, ends);
      case CAPTURE_UNTIL_DELIMITER ->
          captureUntil(input, pos, regionEnd, op.delimiter(), op.groupNumber(), starts, ends);
      case CAPTURE_QUOTED_UNTIL_DELIMITER ->
          captureQuotedUntil(
              input, pos, regionEnd, op.delimiter(), op.groupNumber(), starts, ends, false);
      case CAPTURE_QUOTED_NON_SPACE ->
          captureQuotedUntil(
              input, pos, regionEnd, op.delimiter(), op.groupNumber(), starts, ends, true);
      case CAPTURE_IP_OR_HOST ->
          captureIpOrHost(input, pos, regionEnd, op.groupNumber(), starts, ends);
      case CAPTURE_BRACKETED_WORD_AFTER_SKIP ->
          captureBracketedWordAfterSkip(input, pos, regionEnd, op.groupNumber(), starts, ends);
      case SKIP_ANY -> lastOp ? consumeToEnd(input, pos, regionEnd) : -1;
      case SKIP_ANY_EXCEPT_NEWLINE ->
          lastOp ? consumeToEndExceptNewline(input, pos, regionEnd) : -1;
      case ANCHOR -> pos;
      case OPTIONAL_SEQUENCE ->
          applyOptional(op, input, pos, regionEnd, starts, ends, workspace, optionalDepth);
    };
  }

  private static int captureNonSpace(
      CharSequence input, int pos, int regionEnd, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < regionEnd && !isJdkWhitespace(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureDigits(
      CharSequence input, int pos, int regionEnd, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < regionEnd && isDigit(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureSignedInteger(
      CharSequence input, int pos, int regionEnd, int group, int[] starts, int[] ends) {
    int start = pos;
    if (pos < regionEnd && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
    int digitStart = pos;
    while (pos < regionEnd && isDigit(input.charAt(pos))) pos++;
    if (pos == digitStart) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureSignedIntegerOrDash(
      CharSequence input,
      int pos,
      int regionEnd,
      int group,
      int[] starts,
      int[] ends,
      boolean captureDash) {
    if (pos < regionEnd && input.charAt(pos) == '-') {
      if (captureDash) set(starts, ends, group, pos, pos + 1);
      return pos + 1;
    }
    return captureSignedInteger(input, pos, regionEnd, group, starts, ends);
  }

  private static int captureDecimal(
      CharSequence input,
      int pos,
      int regionEnd,
      int group,
      int[] starts,
      int[] ends,
      boolean signed) {
    int start = pos;
    if (signed && pos < regionEnd && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
      pos++;
    }
    int digitStart = pos;
    while (pos < regionEnd && isDigit(input.charAt(pos))) pos++;
    boolean sawLeadingDigits = pos > digitStart;
    if (pos < regionEnd && input.charAt(pos) == '.') {
      pos++;
      int fractionStart = pos;
      while (pos < regionEnd && isDigit(input.charAt(pos))) pos++;
      if (!sawLeadingDigits && pos == fractionStart) return -1;
    } else if (!sawLeadingDigits) {
      return -1;
    }
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureWord(
      CharSequence input, int pos, int regionEnd, int group, int[] starts, int[] ends) {
    int start = pos;
    while (pos < regionEnd && isWord(input.charAt(pos))) pos++;
    if (pos == start) return -1;
    set(starts, ends, group, start, pos);
    return pos;
  }

  private static int captureUntil(
      CharSequence input,
      int pos,
      int regionEnd,
      char delimiter,
      int group,
      int[] starts,
      int[] ends) {
    int end = findChar(input, pos, regionEnd, delimiter);
    if (end == regionEnd) return -1;
    set(starts, ends, group, pos, end);
    return end;
  }

  private static int captureQuotedUntil(
      CharSequence input,
      int pos,
      int regionEnd,
      char delimiter,
      int group,
      int[] starts,
      int[] ends,
      boolean nonSpace) {
    if (pos >= regionEnd || input.charAt(pos) != '"') return -1;
    int start = pos + 1;
    int end = findChar(input, start, regionEnd, delimiter);
    if (end == regionEnd) return -1;
    if (nonSpace) {
      for (int i = start; i < end; i++) {
        if (isJdkWhitespace(input.charAt(i))) return -1;
      }
    }
    set(starts, ends, group, start, end);
    return end + 1;
  }

  private static int captureIpOrHost(
      CharSequence input, int pos, int regionEnd, int group, int[] starts, int[] ends) {
    int end = captureNonSpace(input, pos, regionEnd, group, starts, ends);
    return end >= 0 && isIpOrHost(input, pos, end) ? end : -1;
  }

  private static int captureBracketedWordAfterSkip(
      CharSequence input, int pos, int regionEnd, int group, int[] starts, int[] ends) {
    int lastStart = -1;
    int lastEnd = -1;
    int open = -1;
    int wordEnd = -1;
    for (int index = pos; index < regionEnd; index++) {
      char ch = input.charAt(index);
      if (ch == '[' && index > pos && input.charAt(index - 1) == ' ') {
        open = index;
        wordEnd = index + 1;
        continue;
      }
      if (open < 0) {
        continue;
      }
      if (ch == ']') {
        if (wordEnd == index
            && wordEnd > open + 1
            && index + 1 < regionEnd
            && input.charAt(index + 1) == ' ') {
          lastStart = open + 1;
          lastEnd = index;
        }
        open = -1;
        wordEnd = -1;
      } else if (wordEnd == index && isWord(ch)) {
        wordEnd++;
      } else {
        wordEnd = -1;
      }
    }
    if (lastStart < 0) return -1;
    set(starts, ends, group, lastStart, lastEnd);
    return regionEnd;
  }

  private static boolean isTargetBeforeOptionalHttpVersion(
      java.util.List<LinearTokenSequencePlan.Op> ops, int index) {
    if (index + 2 >= ops.size()) return false;
    LinearTokenSequencePlan.Op target = ops.get(index);
    LinearTokenSequencePlan.Op optional = ops.get(index + 1);
    LinearTokenSequencePlan.Op quote = ops.get(index + 2);
    if (target.kind() != LinearTokenSequencePlan.OpKind.CAPTURE_NON_SPACE
        || optional.kind() != LinearTokenSequencePlan.OpKind.OPTIONAL_SEQUENCE
        || quote.kind() != LinearTokenSequencePlan.OpKind.LITERAL
        || !quote.literal().startsWith("\"")) return false;
    if (optional.children().size() != 2) return false;
    LinearTokenSequencePlan.Op prefix = optional.children().get(0);
    LinearTokenSequencePlan.Op version = optional.children().get(1);
    return prefix.kind() == LinearTokenSequencePlan.OpKind.LITERAL
        && " HTTP/".equals(prefix.literal())
        && version.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_DECIMAL_NUMBER;
  }

  private static int captureTargetBeforeOptionalHttpVersion(
      LinearTokenSequencePlan.Op target,
      LinearTokenSequencePlan.Op optionalHttpVersion,
      CharSequence input,
      int pos,
      int regionEnd,
      int[] starts,
      int[] ends) {
    int quote = findChar(input, pos, regionEnd, '"');
    if (quote == regionEnd || quote == pos) return -1;
    int marker = findLastLiteral(input, pos, quote, " HTTP/");
    if (marker >= pos && isNonSpace(input, pos, marker)) {
      LinearTokenSequencePlan.Op version = optionalHttpVersion.children().get(1);
      int versionStart = marker + " HTTP/".length();
      int versionEnd = scanDecimal(input, versionStart, quote, false);
      if (versionEnd == quote) {
        set(starts, ends, target.groupNumber(), pos, marker);
        set(starts, ends, version.groupNumber(), versionStart, quote);
        return quote;
      }
    }
    if (!isNonSpace(input, pos, quote)) return -1;
    set(starts, ends, target.groupNumber(), pos, quote);
    return quote;
  }

  private int applyOptional(
      LinearTokenSequencePlan.Op op,
      CharSequence input,
      int pos,
      int regionEnd,
      int[] starts,
      int[] ends,
      MatchWorkspace workspace,
      int optionalDepth) {
    int[] savedStarts = workspace.optionalStarts[optionalDepth];
    int[] savedEnds = workspace.optionalEnds[optionalDepth];
    System.arraycopy(starts, 0, savedStarts, 0, starts.length);
    System.arraycopy(ends, 0, savedEnds, 0, ends.length);
    int next = pos;
    for (int i = 0; i < op.children().size(); i++) {
      next =
          apply(
              op.children().get(i),
              input,
              next,
              regionEnd,
              starts,
              ends,
              i == op.children().size() - 1,
              workspace,
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

  static final class MatchWorkspace {
    final int[] starts;
    final int[] ends;
    final int[][] optionalStarts;
    final int[][] optionalEnds;

    MatchWorkspace(int groupCount, int optionalDepth) {
      starts = new int[groupCount + 1];
      ends = new int[groupCount + 1];
      optionalStarts = new int[optionalDepth][groupCount + 1];
      optionalEnds = new int[optionalDepth][groupCount + 1];
    }
  }

  private static int skipWhitespace(CharSequence input, int pos, int regionEnd) {
    int start = pos;
    while (pos < regionEnd && isJdkWhitespace(input.charAt(pos))) pos++;
    return pos == start ? -1 : pos;
  }

  private static boolean startsWith(CharSequence input, int pos, int regionEnd, String prefix) {
    if (pos < 0 || pos + prefix.length() > regionEnd) return false;
    for (int i = 0; i < prefix.length(); i++) {
      if (input.charAt(pos + i) != prefix.charAt(i)) return false;
    }
    return true;
  }

  private static int findChar(CharSequence input, int pos, int regionEnd, char target) {
    for (int i = pos; i < regionEnd; i++) {
      if (input.charAt(i) == target) return i;
    }
    return regionEnd;
  }

  private static int findLastLiteral(CharSequence input, int start, int end, String literal) {
    int last = -1;
    for (int pos = start; pos + literal.length() <= end; pos++) {
      if (startsWith(input, pos, end, literal)) last = pos;
    }
    return last;
  }

  private static int consumeToEnd(CharSequence input, int pos, int regionEnd) {
    while (pos < regionEnd) {
      input.charAt(pos++);
    }
    return regionEnd;
  }

  private static int consumeToEndExceptNewline(CharSequence input, int pos, int regionEnd) {
    while (pos < regionEnd) {
      if (input.charAt(pos++) == '\n') return -1;
    }
    return regionEnd;
  }

  private static void set(int[] starts, int[] ends, int group, int start, int end) {
    if (group > 0) {
      starts[group] = start;
      ends[group] = end;
    }
  }

  private static boolean isIpOrHost(CharSequence input, int start, int end) {
    for (int i = start; i < end; i++) {
      char ch = input.charAt(i);
      if (!isAsciiAlphaNum(ch) && ch != '-' && ch != '_' && ch != '.' && ch != ':' && ch != '%') {
        return false;
      }
    }
    return end > start;
  }

  private static boolean isNonSpace(CharSequence input, int start, int end) {
    if (end <= start) return false;
    for (int i = start; i < end; i++) {
      if (isJdkWhitespace(input.charAt(i))) return false;
    }
    return true;
  }

  private static int scanDecimal(CharSequence input, int pos, int limit, boolean signed) {
    if (signed && pos < limit && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
      pos++;
    }
    int digitStart = pos;
    while (pos < limit && isDigit(input.charAt(pos))) pos++;
    boolean sawLeadingDigits = pos > digitStart;
    if (pos < limit && input.charAt(pos) == '.') {
      pos++;
      int fractionStart = pos;
      while (pos < limit && isDigit(input.charAt(pos))) pos++;
      if (!sawLeadingDigits && pos == fractionStart) return -1;
    } else if (!sawLeadingDigits) {
      return -1;
    }
    return pos;
  }

  private static boolean isJdkWhitespace(char ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\f' || ch == '\r';
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
