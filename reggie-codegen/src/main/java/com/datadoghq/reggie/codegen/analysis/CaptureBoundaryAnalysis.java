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
package com.datadoghq.reggie.codegen.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capture-layout and boundary-determinism checks shared by the native full-capture and named-only
 * linear-token-sequence admission paths.
 */
public final class CaptureBoundaryAnalysis {
  private CaptureBoundaryAnalysis() {}

  /** Returns whether every required group index has exactly one capturing plan operation. */
  public static boolean hasExactCaptureLayout(
      LinearTokenSequencePlan plan, Set<Integer> requiredIndexes) {
    if (!plan.coversCaptureIndexes(requiredIndexes)) return false;
    Map<Integer, Integer> occurrences = new HashMap<>();
    countCaptureOperations(plan.ops(), occurrences);
    if (occurrences.size() != requiredIndexes.size()) return false;
    for (int index : requiredIndexes) {
      if (occurrences.getOrDefault(index, 0) != 1) return false;
    }
    return true;
  }

  private static void countCaptureOperations(
      List<LinearTokenSequencePlan.Op> ops, Map<Integer, Integer> occurrences) {
    for (LinearTokenSequencePlan.Op op : ops) {
      if (op.groupNumber() > 0) occurrences.merge(op.groupNumber(), 1, Integer::sum);
      countCaptureOperations(op.children(), occurrences);
    }
  }

  /** Declines categorizer transforms that do not retain a direct source-group boundary witness. */
  public static boolean hasOnlyDirectCaptureOps(LinearTokenSequencePlan plan) {
    return hasOnlyDirectCaptureOps(plan.ops());
  }

  private static boolean hasOnlyDirectCaptureOps(List<LinearTokenSequencePlan.Op> ops) {
    for (LinearTokenSequencePlan.Op op : ops) {
      if (op.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_UNTIL_DELIMITER
          || op.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_QUOTED_UNTIL_DELIMITER
          || op.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_QUOTED_NON_SPACE
          || op.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_BRACKETED_WORD_AFTER_SKIP
          || op.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_IP_OR_HOST) {
        return false;
      }
      if (!hasOnlyDirectCaptureOps(op.children())) return false;
    }
    return true;
  }

  /**
   * Returns whether every variable-width plan operation is followed by an operation that proves its
   * match boundary, so the executor never needs to backtrack to find it.
   *
   * @param httpVersionLiteral the literal the executor matches for the optional HTTP-version
   *     capture shape; supplied by the caller so this codegen-side analysis never depends on the
   *     runtime matcher's constant.
   */
  public static boolean hasDeterministicCaptureBoundaries(
      LinearTokenSequencePlan plan, String httpVersionLiteral) {
    return hasDeterministicCaptureBoundaries(plan.ops(), httpVersionLiteral);
  }

  private static boolean hasDeterministicCaptureBoundaries(
      List<LinearTokenSequencePlan.Op> ops, String httpVersionLiteral) {
    for (int index = 0; index < ops.size(); index++) {
      LinearTokenSequencePlan.Op op = ops.get(index);
      if (op.kind() == LinearTokenSequencePlan.OpKind.OPTIONAL_SEQUENCE) {
        LinearTokenSequencePlan.Op successor = index + 1 < ops.size() ? ops.get(index + 1) : null;
        if (!isProvenOptionalHttpVersion(ops, index, httpVersionLiteral)
            || !hasDeterministicCaptureBoundaries(op.children(), successor, httpVersionLiteral))
          return false;
        continue;
      }
      if (!op.children().isEmpty()
          && !hasDeterministicCaptureBoundaries(op.children(), httpVersionLiteral)) return false;
      if (!isVariableWidth(op) || index == ops.size() - 1) continue;
      LinearTokenSequencePlan.Op next = ops.get(index + 1);
      if (next.groupNumber() > 0) return false;
      if (next.kind() == LinearTokenSequencePlan.OpKind.OPTIONAL_SEQUENCE) {
        if (next.children().isEmpty()
            || next.children().get(0).kind() != LinearTokenSequencePlan.OpKind.LITERAL
            || index + 2 == ops.size()
            || !isBoundary(ops.get(index + 2))
            || !isSafeLiteralBoundary(op, next.children().get(0).literal())) return false;
      } else if (!isSafeBoundary(op, next)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isProvenOptionalHttpVersion(
      List<LinearTokenSequencePlan.Op> ops, int optionalIndex, String httpVersionLiteral) {
    if (optionalIndex + 1 >= ops.size()) return false;
    LinearTokenSequencePlan.Op optional = ops.get(optionalIndex);
    LinearTokenSequencePlan.Op successor = ops.get(optionalIndex + 1);
    if (successor.kind() != LinearTokenSequencePlan.OpKind.LITERAL
        || !successor.literal().startsWith("\"")
        || optional.children().size() != 2) return false;
    LinearTokenSequencePlan.Op prefix = optional.children().get(0);
    LinearTokenSequencePlan.Op version = optional.children().get(1);
    return prefix.kind() == LinearTokenSequencePlan.OpKind.LITERAL
        && httpVersionLiteral.equals(prefix.literal())
        && version.kind() == LinearTokenSequencePlan.OpKind.CAPTURE_DECIMAL_NUMBER;
  }

  private static boolean hasDeterministicCaptureBoundaries(
      List<LinearTokenSequencePlan.Op> ops,
      LinearTokenSequencePlan.Op successor,
      String httpVersionLiteral) {
    if (!hasDeterministicCaptureBoundaries(ops, httpVersionLiteral)) return false;
    if (successor == null || ops.isEmpty()) return true;
    LinearTokenSequencePlan.Op last = ops.get(ops.size() - 1);
    return !isVariableWidth(last) || isSafeBoundary(last, successor);
  }

  private static boolean isVariableWidth(LinearTokenSequencePlan.Op op) {
    return switch (op.kind()) {
      case CAPTURE_NON_SPACE,
          CAPTURE_DIGITS,
          CAPTURE_SIGNED_INTEGER,
          CAPTURE_DECIMAL_NUMBER,
          CAPTURE_SIGNED_DECIMAL_NUMBER,
          CAPTURE_WORD,
          CAPTURE_UNTIL_DELIMITER,
          CAPTURE_QUOTED_UNTIL_DELIMITER,
          CAPTURE_QUOTED_NON_SPACE,
          CAPTURE_IP_OR_HOST,
          CAPTURE_SIGNED_INTEGER_OR_DASH,
          CAPTURE_SIGNED_INTEGER_OR_UNCAPTURED_DASH,
          CAPTURE_BRACKETED_WORD_AFTER_SKIP,
          WHITESPACE_PLUS,
          SKIP_ANY ->
          true;
      default -> false;
    };
  }

  private static boolean isSafeBoundary(
      LinearTokenSequencePlan.Op variable, LinearTokenSequencePlan.Op next) {
    if (next.kind() == LinearTokenSequencePlan.OpKind.WHITESPACE_PLUS) {
      return consumesNonWhitespace(variable);
    }
    return next.kind() == LinearTokenSequencePlan.OpKind.LITERAL
        && isSafeLiteralBoundary(variable, next.literal());
  }

  private static boolean isSafeLiteralBoundary(
      LinearTokenSequencePlan.Op variable, String literal) {
    if (literal == null || literal.isEmpty()) return false;
    char boundary = literal.charAt(0);
    return switch (variable.kind()) {
      case CAPTURE_NON_SPACE, CAPTURE_IP_OR_HOST, CAPTURE_QUOTED_NON_SPACE ->
          isWhitespace(boundary);
      case CAPTURE_DIGITS,
          CAPTURE_SIGNED_INTEGER,
          CAPTURE_SIGNED_INTEGER_OR_DASH,
          CAPTURE_SIGNED_INTEGER_OR_UNCAPTURED_DASH ->
          !isDigit(boundary);
      case CAPTURE_DECIMAL_NUMBER, CAPTURE_SIGNED_DECIMAL_NUMBER ->
          !isDigit(boundary) && boundary != '.';
      case CAPTURE_WORD -> !isWord(boundary);
      case WHITESPACE_PLUS -> !isWhitespace(boundary);
      case CAPTURE_UNTIL_DELIMITER -> boundary == variable.delimiter();
      case CAPTURE_QUOTED_UNTIL_DELIMITER, CAPTURE_BRACKETED_WORD_AFTER_SKIP -> true;
      default -> false;
    };
  }

  private static boolean consumesNonWhitespace(LinearTokenSequencePlan.Op op) {
    return switch (op.kind()) {
      case CAPTURE_NON_SPACE,
          CAPTURE_DIGITS,
          CAPTURE_SIGNED_INTEGER,
          CAPTURE_DECIMAL_NUMBER,
          CAPTURE_SIGNED_DECIMAL_NUMBER,
          CAPTURE_WORD,
          CAPTURE_IP_OR_HOST,
          CAPTURE_SIGNED_INTEGER_OR_DASH,
          CAPTURE_SIGNED_INTEGER_OR_UNCAPTURED_DASH,
          CAPTURE_QUOTED_NON_SPACE ->
          true;
      default -> false;
    };
  }

  private static boolean isWhitespace(char ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\f' || ch == '\r';
  }

  private static boolean isDigit(char ch) {
    return ch >= '0' && ch <= '9';
  }

  private static boolean isWord(char ch) {
    return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || isDigit(ch) || ch == '_';
  }

  private static boolean isBoundary(LinearTokenSequencePlan.Op op) {
    return op.kind() == LinearTokenSequencePlan.OpKind.WHITESPACE_PLUS
        || op.kind() == LinearTokenSequencePlan.OpKind.LITERAL && !op.literal().isEmpty();
  }
}
