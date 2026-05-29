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

import com.datadoghq.reggie.codegen.ast.AlternationNode;
import com.datadoghq.reggie.codegen.ast.AnchorNode;
import com.datadoghq.reggie.codegen.ast.AssertionNode;
import com.datadoghq.reggie.codegen.ast.BackreferenceNode;
import com.datadoghq.reggie.codegen.ast.BranchResetNode;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.ConcatNode;
import com.datadoghq.reggie.codegen.ast.ConditionalNode;
import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.QuantifierNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.ast.RegexVisitor;
import com.datadoghq.reggie.codegen.ast.SubroutineNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically classifies parsed regex ASTs into reusable execution categories.
 *
 * <p>This is intentionally structural: categories are derived from AST shape and reusable semantic
 * atoms, not from Grok capture names or exact pattern strings. The initial category vocabulary is
 * focused on linear delimited log templates, but unsupported shapes simply classify as {@code
 * GENERAL_REGEX} so normal Reggie strategy selection can continue unchanged.
 */
public final class PatternCategorizer {

  private PatternCategorizer() {}

  public static PatternCategorization categorize(RegexNode node) {
    Collector collector = new Collector();
    boolean recognized = collector.collect(node);
    if (!recognized) {
      return new PatternCategorization(
          PatternCategorization.Category.GENERAL_REGEX,
          List.copyOf(collector.atoms),
          List.copyOf(collector.notes));
    }

    collector.flushLiteral();
    boolean onlyLiterals =
        collector.atoms.stream().allMatch(a -> a.kind() == PatternAtom.Kind.LITERAL);
    return new PatternCategorization(
        onlyLiterals
            ? PatternCategorization.Category.LITERAL_SEQUENCE
            : PatternCategorization.Category.LINEAR_TEMPLATE,
        List.copyOf(collector.atoms),
        List.copyOf(collector.notes));
  }

  private static final class Collector implements RegexVisitor<Boolean> {
    private final List<PatternAtom> atoms = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder literal = new StringBuilder();

    boolean collect(RegexNode node) {
      return node.accept(this);
    }

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      literal.append(node.ch);
      return true;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      flushLiteral();
      if (node.chars.equals(CharSet.WHITESPACE) && !node.negated) {
        atoms.add(PatternAtom.uncaptured(PatternAtom.Kind.WHITESPACE_PLUS));
        notes.add("bare whitespace character class is categorized as a single whitespace atom");
        return true;
      }
      notes.add("unsupported bare character class: " + node);
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      for (RegexNode child : node.children) {
        if (!collect(child)) return false;
      }
      return true;
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      flushLiteral();
      atoms.add(PatternAtom.uncaptured(PatternAtom.Kind.COMPLEX_ALTERNATION));
      notes.add("alternation categorized as complex reusable atom");
      return true;
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      PatternAtom atom = atomForQuantifier(node, 0, null);
      if (atom == null) {
        notes.add("unsupported quantifier shape: " + node);
        return false;
      }
      flushLiteral();
      atoms.add(atom);
      return true;
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      PatternAtom atom = atomForGroup(node);
      if (atom != null) {
        flushLiteral();
        atoms.add(atom);
        return true;
      }
      if (!node.capturing) {
        return collect(node.child);
      }
      notes.add("capturing group is not a recognized linear atom: " + node);
      return false;
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      flushLiteral();
      atoms.add(PatternAtom.uncaptured(PatternAtom.Kind.ANCHOR));
      return true;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      notes.add("backreference is not linear-template categorizable");
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      notes.add("lookaround assertion is not linear-template categorizable yet");
      return false;
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      notes.add("subroutine is not linear-template categorizable");
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      notes.add("conditional is not linear-template categorizable");
      return false;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      notes.add("branch-reset group is not linear-template categorizable");
      return false;
    }

    void flushLiteral() {
      if (literal.length() > 0) {
        atoms.add(PatternAtom.literal(literal.toString()));
        literal.setLength(0);
      }
    }

    private static PatternAtom atomForGroup(GroupNode node) {
      int groupNumber = node.capturing ? node.groupNumber : 0;
      String groupName = node.name;
      RegexNode child = stripNonCapturingGroup(node.child);

      if (child instanceof QuantifierNode quantifier) {
        return atomForQuantifier(quantifier, groupNumber, groupName);
      }
      if (isWordBoundaryWordBoundary(child)) {
        return PatternAtom.captured(PatternAtom.Kind.WORD, groupNumber, groupName);
      }
      if (isSignedInteger(child)) {
        return PatternAtom.captured(PatternAtom.Kind.SIGNED_INTEGER, groupNumber, groupName);
      }
      if (isDecimalNumber(child)) {
        return PatternAtom.captured(PatternAtom.Kind.DECIMAL_NUMBER, groupNumber, groupName);
      }
      if (isSignedDecimalNumber(child)) {
        return PatternAtom.captured(PatternAtom.Kind.SIGNED_DECIMAL_NUMBER, groupNumber, groupName);
      }
      if (child instanceof AlternationNode alternation) {
        if (isIpOrHostAlternation(alternation)) {
          return PatternAtom.captured(PatternAtom.Kind.IP_OR_HOST, groupNumber, groupName);
        }
        return PatternAtom.captured(PatternAtom.Kind.COMPLEX_ALTERNATION, groupNumber, groupName);
      }
      return null;
    }

    private static PatternAtom atomForQuantifier(
        QuantifierNode node, int groupNumber, String groupName) {
      if (!node.greedy) return null;
      RegexNode child = stripNonCapturingGroup(node.child);
      if (node.min == 1 && node.max == -1 && child instanceof CharClassNode charClass) {
        if (charClass.chars.equals(CharSet.WHITESPACE) && charClass.negated) {
          return PatternAtom.captured(PatternAtom.Kind.NON_SPACE_PLUS, groupNumber, groupName);
        }
        if (charClass.chars.equals(CharSet.WHITESPACE) && !charClass.negated) {
          return PatternAtom.captured(PatternAtom.Kind.WHITESPACE_PLUS, groupNumber, groupName);
        }
        if (charClass.chars.equals(CharSet.DIGIT) && !charClass.negated) {
          return PatternAtom.captured(PatternAtom.Kind.DIGITS_PLUS, groupNumber, groupName);
        }
        if (charClass.chars.equals(CharSet.WORD) && !charClass.negated) {
          return PatternAtom.captured(PatternAtom.Kind.WORD, groupNumber, groupName);
        }
        Character delimiter = singleNegatedDelimiter(charClass);
        if (delimiter != null) {
          return PatternAtom.capturedUntil(groupNumber, groupName, delimiter);
        }
      }
      if (node.min == 0 && node.max == -1) {
        if (child instanceof CharClassNode charClass) {
          Character delimiter = singleNegatedDelimiter(charClass);
          if (delimiter != null) {
            return PatternAtom.capturedUntil(groupNumber, groupName, delimiter);
          }
          if ((charClass.chars.equals(CharSet.ANY)
                  || charClass.chars.equals(CharSet.ANY_EXCEPT_NEWLINE))
              && !charClass.negated) {
            return PatternAtom.captured(PatternAtom.Kind.ANY_STAR, groupNumber, groupName);
          }
        }
      }
      return null;
    }

    private static RegexNode stripNonCapturingGroup(RegexNode node) {
      while (node instanceof GroupNode group && !group.capturing) {
        node = group.child;
      }
      return node;
    }

    private static Character singleNegatedDelimiter(CharClassNode node) {
      if (!node.negated || !node.chars.isSingleChar()) return null;
      return node.chars.getSingleChar();
    }

    private static boolean isIpOrHostAlternation(AlternationNode node) {
      boolean hasIpLikeAlternative = false;
      boolean hasHostLikeAlternative = false;
      for (RegexNode alternative : node.alternatives) {
        hasIpLikeAlternative |= isIpLikeAlternative(alternative);
        hasHostLikeAlternative |= isHostLikeAlternative(alternative);
      }
      return hasIpLikeAlternative && hasHostLikeAlternative;
    }

    private static boolean isIpLikeAlternative(RegexNode node) {
      if (!(node instanceof ConcatNode concat) || concat.children.size() != 2) return false;
      RegexNode repeatedOctet = stripNonCapturingGroup(concat.children.get(0));
      return repeatedOctet instanceof QuantifierNode quantifier
          && quantifier.min == 3
          && quantifier.max == 3
          && stripNonCapturingGroup(quantifier.child) instanceof ConcatNode octetWithDot
          && octetWithDot.children.size() == 2
          && isDigitRepeat(octetWithDot.children.get(0), 1, 3)
          && octetWithDot.children.get(1) instanceof LiteralNode dot
          && dot.ch == '.'
          && isDigitRepeat(concat.children.get(1), 1, 3);
    }

    private static boolean isHostLikeAlternative(RegexNode node) {
      if (!(node instanceof QuantifierNode quantifier)
          || quantifier.min != 1
          || quantifier.max != -1
          || !(quantifier.child instanceof CharClassNode charClass)
          || charClass.negated) {
        return false;
      }
      return charClass.chars.contains('a')
          && charClass.chars.contains('z')
          && charClass.chars.contains('A')
          && charClass.chars.contains('Z')
          && charClass.chars.contains('0')
          && charClass.chars.contains('9')
          && charClass.chars.contains('.')
          && charClass.chars.contains('-');
    }

    private static boolean isDigitRepeat(RegexNode node, int min, int max) {
      return node instanceof QuantifierNode quantifier
          && quantifier.min == min
          && quantifier.max == max
          && quantifier.child instanceof CharClassNode charClass
          && !charClass.negated
          && charClass.chars.equals(CharSet.DIGIT);
    }

    private static boolean isWordBoundaryWordBoundary(RegexNode node) {
      if (!(node instanceof ConcatNode concat) || concat.children.size() != 3) return false;
      return isWordBoundary(concat.children.get(0))
          && isWordPlus(concat.children.get(1))
          && isWordBoundary(concat.children.get(2));
    }

    private static boolean isWordBoundary(RegexNode node) {
      return node instanceof AnchorNode anchor && anchor.type == AnchorNode.Type.WORD_BOUNDARY;
    }

    private static boolean isWordPlus(RegexNode node) {
      return node instanceof QuantifierNode quantifier
          && quantifier.min == 1
          && quantifier.max == -1
          && quantifier.child instanceof CharClassNode charClass
          && charClass.chars.equals(CharSet.WORD)
          && !charClass.negated;
    }

    private static boolean isSignedInteger(RegexNode node) {
      if (!(node instanceof ConcatNode concat) || concat.children.size() != 2) return false;
      return isOptionalSign(concat.children.get(0)) && isDigitPlus(concat.children.get(1));
    }

    private static boolean isDecimalNumber(RegexNode node) {
      if (!(node instanceof ConcatNode concat) || concat.children.size() != 3) return false;
      return isDigitPlus(concat.children.get(0))
          && concat.children.get(1) instanceof LiteralNode literal
          && literal.ch == '.'
          && isDigitPlus(concat.children.get(2));
    }

    private static boolean isSignedDecimalNumber(RegexNode node) {
      if (!(node instanceof ConcatNode concat) || concat.children.size() != 3) return false;
      return isOptionalSign(concat.children.get(0))
          && isDigitPlus(concat.children.get(1))
          && isOptionalDotDigits(concat.children.get(2));
    }

    private static boolean isOptionalSign(RegexNode node) {
      return node instanceof QuantifierNode quantifier
          && quantifier.min == 0
          && quantifier.max == 1
          && quantifier.child instanceof CharClassNode charClass
          && !charClass.negated
          && charClass.chars.contains('+')
          && charClass.chars.contains('-');
    }

    private static boolean isDigitPlus(RegexNode node) {
      return node instanceof QuantifierNode quantifier
          && quantifier.min == 1
          && quantifier.max == -1
          && quantifier.child instanceof CharClassNode charClass
          && !charClass.negated
          && charClass.chars.equals(CharSet.DIGIT);
    }

    private static boolean isOptionalDotDigits(RegexNode node) {
      if (!(node instanceof QuantifierNode quantifier)
          || quantifier.min != 0
          || quantifier.max != 1) {
        return false;
      }
      RegexNode child = stripNonCapturingGroup(quantifier.child);
      if (!(child instanceof ConcatNode concat) || concat.children.size() != 2) return false;
      return concat.children.get(0) instanceof LiteralNode literal
          && literal.ch == '.'
          && isDigitPlus(concat.children.get(1));
    }
  }
}
