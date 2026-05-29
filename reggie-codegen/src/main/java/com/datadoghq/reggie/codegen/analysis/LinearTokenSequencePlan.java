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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Executable, deterministic plan for a categorized linear-token-sequence regex. */
public record LinearTokenSequencePlan(List<Op> ops, int groupCount) {

  public enum OpKind {
    LITERAL,
    WHITESPACE_PLUS,
    CAPTURE_NON_SPACE,
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
    SKIP_ANY,
    ANCHOR,
    OPTIONAL_SEQUENCE
  }

  public record Op(
      OpKind kind, int groupNumber, String literal, char delimiter, List<Op> children) {
    public Op {
      children = children == null ? List.of() : List.copyOf(children);
    }

    static Op literal(String literal) {
      return new Op(OpKind.LITERAL, 0, literal, (char) 0, List.of());
    }

    static Op capture(OpKind kind, int groupNumber) {
      return new Op(kind, groupNumber, null, (char) 0, List.of());
    }

    static Op captureUntil(OpKind kind, int groupNumber, char delimiter) {
      return new Op(kind, groupNumber, null, delimiter, List.of());
    }

    static Op uncaptured(OpKind kind) {
      return new Op(kind, 0, null, (char) 0, List.of());
    }

    static Op optional(List<Op> children) {
      return new Op(OpKind.OPTIONAL_SEQUENCE, 0, null, (char) 0, children);
    }
  }

  public LinearTokenSequencePlan {
    ops = List.copyOf(ops);
  }

  /** Converts categorizer atoms into a closed, executable linear-token-sequence plan. */
  public static Optional<LinearTokenSequencePlan> from(PatternCategorization categorization) {
    if (!categorization.isLinearTokenSequence()) return Optional.empty();

    List<Op> ops = new ArrayList<>();
    List<PatternAtom> atoms = categorization.atoms();
    int maxGroup = 0;

    for (int i = 0; i < atoms.size(); i++) {
      PatternAtom atom = atoms.get(i);
      maxGroup = Math.max(maxGroup, atom.groupNumber());

      if (isQuotedCapture(atoms, i)) {
        trimTrailingQuote(ops);
        ops.add(quotedCaptureOp(atom));
        PatternAtom next = atoms.get(++i);
        String remainder = next.literal().substring(1);
        if (!remainder.isEmpty()) addLiteral(ops, remainder);
        continue;
      }

      Op op = opFor(atom);
      if (op == null) return Optional.empty();
      if (op.kind == OpKind.LITERAL) {
        addLiteral(ops, op.literal);
      } else {
        ops.add(op);
      }
    }

    return Optional.of(new LinearTokenSequencePlan(ops, maxGroup));
  }

  private static Op opFor(PatternAtom atom) {
    return switch (atom.kind()) {
      case LITERAL -> Op.literal(atom.literal());
      case WHITESPACE_PLUS -> Op.uncaptured(OpKind.WHITESPACE_PLUS);
      case NON_SPACE_PLUS -> Op.capture(OpKind.CAPTURE_NON_SPACE, atom.groupNumber());
      case DIGITS_PLUS -> Op.capture(OpKind.CAPTURE_DIGITS, atom.groupNumber());
      case SIGNED_INTEGER -> Op.capture(OpKind.CAPTURE_SIGNED_INTEGER, atom.groupNumber());
      case SIGNED_INTEGER_OR_DASH ->
          Op.capture(OpKind.CAPTURE_SIGNED_INTEGER_OR_DASH, atom.groupNumber());
      case SIGNED_INTEGER_OR_UNCAPTURED_DASH ->
          Op.capture(OpKind.CAPTURE_SIGNED_INTEGER_OR_UNCAPTURED_DASH, atom.groupNumber());
      case DECIMAL_NUMBER -> Op.capture(OpKind.CAPTURE_DECIMAL_NUMBER, atom.groupNumber());
      case SIGNED_DECIMAL_NUMBER ->
          Op.capture(OpKind.CAPTURE_SIGNED_DECIMAL_NUMBER, atom.groupNumber());
      case WORD -> Op.capture(OpKind.CAPTURE_WORD, atom.groupNumber());
      case IP_OR_HOST -> Op.capture(OpKind.CAPTURE_IP_OR_HOST, atom.groupNumber());
      case BRACKETED_WORD_AFTER_SKIP ->
          Op.capture(OpKind.CAPTURE_BRACKETED_WORD_AFTER_SKIP, atom.groupNumber());
      case UNTIL_DELIMITER ->
          Op.captureUntil(OpKind.CAPTURE_UNTIL_DELIMITER, atom.groupNumber(), atom.delimiter());
      case QUOTED_UNTIL_DELIMITER ->
          Op.captureUntil(
              OpKind.CAPTURE_QUOTED_UNTIL_DELIMITER, atom.groupNumber(), atom.delimiter());
      case ANY_STAR -> Op.uncaptured(OpKind.SKIP_ANY);
      case ANCHOR -> Op.uncaptured(OpKind.ANCHOR);
      case OPTIONAL_SEQUENCE -> optionalOpFor(atom);
      case COMPLEX_ALTERNATION -> null;
    };
  }

  private static Op quotedCaptureOp(PatternAtom atom) {
    if (atom.kind() == PatternAtom.Kind.NON_SPACE_PLUS) {
      return Op.captureUntil(OpKind.CAPTURE_QUOTED_NON_SPACE, atom.groupNumber(), '"');
    }
    return Op.captureUntil(
        OpKind.CAPTURE_QUOTED_UNTIL_DELIMITER, atom.groupNumber(), atom.delimiter());
  }

  private static Op optionalOpFor(PatternAtom atom) {
    PatternCategorization nested =
        new PatternCategorization(
            PatternCategorization.Category.LINEAR_TOKEN_SEQUENCE, atom.children(), List.of());
    return LinearTokenSequencePlan.from(nested).map(plan -> Op.optional(plan.ops())).orElse(null);
  }

  private static boolean isQuotedCapture(List<PatternAtom> atoms, int index) {
    PatternAtom atom = atoms.get(index);
    if (!((atom.kind() == PatternAtom.Kind.UNTIL_DELIMITER && atom.delimiter() == '"')
        || atom.kind() == PatternAtom.Kind.NON_SPACE_PLUS)) return false;
    return index > 0
        && index + 1 < atoms.size()
        && atoms.get(index - 1).kind() == PatternAtom.Kind.LITERAL
        && atoms.get(index - 1).literal().endsWith("\"")
        && atoms.get(index + 1).kind() == PatternAtom.Kind.LITERAL
        && atoms.get(index + 1).literal().startsWith("\"");
  }

  private static void trimTrailingQuote(List<Op> ops) {
    if (ops.isEmpty()) throw new IllegalStateException("missing literal before quoted capture");
    Op previous = ops.remove(ops.size() - 1);
    if (previous.kind() != OpKind.LITERAL || !previous.literal().endsWith("\"")) {
      throw new IllegalStateException("missing quote literal before quoted capture");
    }
    String trimmed = previous.literal().substring(0, previous.literal().length() - 1);
    if (!trimmed.isEmpty()) addLiteral(ops, trimmed);
  }

  private static void addLiteral(List<Op> ops, String literal) {
    if (literal.isEmpty()) return;
    if (!ops.isEmpty() && ops.get(ops.size() - 1).kind() == OpKind.LITERAL) {
      Op previous = ops.remove(ops.size() - 1);
      ops.add(Op.literal(previous.literal() + literal));
    } else {
      ops.add(Op.literal(literal));
    }
  }
}
