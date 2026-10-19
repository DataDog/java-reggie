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
import com.datadoghq.reggie.codegen.ast.EpsilonNode;
import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.QuantifierNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.ast.RegexVisitor;
import com.datadoghq.reggie.codegen.ast.SubroutineNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pattern information for the GREEDY_SUFFIX strategy: {@code P (G{m,}) S} at the top level, with
 * {@code S = [\b]? T [END]?} (see doc/plans/greedy-suffix-strategy.md).
 *
 * <ul>
 *   <li>P: zero or more single chars (literals or char classes).
 *   <li>Group 1: a greedy {@code G*} or {@code G+}, {@code G} being {@code .} with or without
 *       {@code (?s)}.
 *   <li>T: either a non-empty sequence of single chars (not captured), or group 2 over a greedy
 *       {@code C{k,max}}.
 *   <li>END: non-multiline {@code $}, {@code \Z} or {@code \z}.
 * </ul>
 */
public final class GreedySuffixInfo implements PatternInfo {

  /** End anchor after the suffix. */
  public enum EndKind {
    NONE,
    /** Non-multiline {@code $} and {@code \Z}: {@code LineTerminators.isEnd}. */
    END,
    /** {@code \z}: only at the end. */
    ABSOLUTE
  }

  public final CharSet[] prefix;
  public final CharSet greedy;
  public final int greedyMin;
  public final boolean wordBoundary;

  /** Fixed suffix chars, or null when the suffix is the captured run. */
  public final CharSet[] fixedSuffix;

  /** Captured-run charset, or null when the suffix is fixed. */
  public final CharSet runCharSet;

  public final int runMin;

  /** Captured-run upper bound; -1 for unbounded. */
  public final int runMax;

  public final EndKind endKind;

  private GreedySuffixInfo(
      CharSet[] prefix,
      CharSet greedy,
      int greedyMin,
      boolean wordBoundary,
      CharSet[] fixedSuffix,
      CharSet runCharSet,
      int runMin,
      int runMax,
      EndKind endKind) {
    this.prefix = prefix;
    this.greedy = greedy;
    this.greedyMin = greedyMin;
    this.wordBoundary = wordBoundary;
    this.fixedSuffix = fixedSuffix;
    this.runCharSet = runCharSet;
    this.runMin = runMin;
    this.runMax = runMax;
    this.endKind = endKind;
  }

  public boolean hasCapturedRun() {
    return runCharSet != null;
  }

  /** Capturing groups: the greedy group, plus the captured run. */
  public int groupCount() {
    return hasCapturedRun() ? 2 : 1;
  }

  /** Minimum number of chars the suffix consumes. */
  public int minSuffixWidth() {
    return hasCapturedRun() ? runMin : fixedSuffix.length;
  }

  @Override
  public int structuralHashCode() {
    int hash = 31 * Arrays.hashCode(prefix) + greedy.hashCode();
    hash = 31 * hash + greedyMin;
    hash = 31 * hash + (wordBoundary ? 1 : 0);
    hash = 31 * hash + Arrays.hashCode(fixedSuffix);
    hash = 31 * hash + (runCharSet == null ? 0 : runCharSet.hashCode());
    hash = 31 * hash + runMin;
    hash = 31 * hash + runMax;
    hash = 31 * hash + endKind.ordinal();
    return hash;
  }

  /** Returns the info for {@code ast}, or null if the pattern does not have the accepted shape. */
  public static GreedySuffixInfo detect(RegexNode ast) {
    List<RegexNode> items = new ArrayList<>();
    flatten(ast, items);
    List<Element> elements = new ArrayList<>(items.size());
    for (RegexNode item : items) {
      Element e = item.accept(CLASSIFIER);
      if (e == null) {
        return null;
      }
      elements.add(e);
    }

    int i = 0;
    int n = elements.size();
    List<CharSet> prefix = new ArrayList<>();
    while (i < n && elements.get(i).kind == Kind.CHAR) {
      prefix.add(elements.get(i++).charSet);
    }

    if (i == n) {
      return null;
    }
    Element group = elements.get(i++);
    if (group.kind != Kind.CAPTURED_RUN
        || group.groupNumber != 1
        || group.min > 1
        || group.max != -1
        || !(group.charSet.equals(CharSet.ANY)
            || group.charSet.equals(CharSet.ANY_EXCEPT_NEWLINE))) {
      return null;
    }

    boolean wordBoundary = false;
    if (i < n && elements.get(i).kind == Kind.WORD_BOUNDARY) {
      wordBoundary = true;
      i++;
    }

    if (i == n) {
      return null;
    }
    CharSet[] fixedSuffix = null;
    Element run = null;
    if (elements.get(i).kind == Kind.CAPTURED_RUN) {
      run = elements.get(i++);
      if (run.groupNumber != 2 || run.max == 0) {
        return null;
      }
    } else {
      List<CharSet> suffix = new ArrayList<>();
      while (i < n && elements.get(i).kind == Kind.CHAR) {
        suffix.add(elements.get(i++).charSet);
      }
      if (suffix.isEmpty()) {
        return null;
      }
      fixedSuffix = suffix.toArray(new CharSet[0]);
    }

    EndKind endKind = EndKind.NONE;
    if (i < n && elements.get(i).kind == Kind.END) {
      endKind = elements.get(i++).endKind;
    }
    if (i != n) {
      return null;
    }

    // END is checked only where the captured run stops. That is exact only when the run cannot
    // consume a line terminator: otherwise a shorter run could end before a final terminator.
    if (run != null && endKind != EndKind.NONE && containsLineTerminator(run.charSet)) {
      return null;
    }

    return new GreedySuffixInfo(
        prefix.toArray(new CharSet[0]),
        group.charSet,
        group.min,
        wordBoundary,
        fixedSuffix,
        run == null ? null : run.charSet,
        run == null ? 0 : run.min,
        run == null ? 0 : run.max,
        endKind);
  }

  private static boolean containsLineTerminator(CharSet cs) {
    return cs.contains('\n')
        || cs.contains('\r')
        || cs.contains('\u0085')
        || cs.contains('\u2028')
        || cs.contains('\u2029');
  }

  /** Top-level sequence, with nested non-capturing concatenations (e.g. from \Q..\E) inlined. */
  private static void flatten(RegexNode node, List<RegexNode> out) {
    if (node instanceof ConcatNode concat) {
      for (RegexNode child : concat.children) {
        flatten(child, out);
      }
    } else {
      out.add(node);
    }
  }

  private enum Kind {
    CHAR,
    RUN,
    CAPTURED_RUN,
    WORD_BOUNDARY,
    END
  }

  private static final class Element {
    final Kind kind;
    final CharSet charSet;
    final int min;
    final int max;
    final int groupNumber;
    final EndKind endKind;

    Element(Kind kind, CharSet charSet, int min, int max, int groupNumber, EndKind endKind) {
      this.kind = kind;
      this.charSet = charSet;
      this.min = min;
      this.max = max;
      this.groupNumber = groupNumber;
      this.endKind = endKind;
    }
  }

  /** Classifies one top-level element; null for any construct outside the accepted shape. */
  private static final RegexVisitor<Element> CLASSIFIER =
      new RegexVisitor<>() {
        @Override
        public Element visitLiteral(LiteralNode node) {
          if (node instanceof EpsilonNode) {
            return null;
          }
          return new Element(Kind.CHAR, CharSet.of(node.ch), 1, 1, 0, null);
        }

        @Override
        public Element visitCharClass(CharClassNode node) {
          CharSet cs = node.negated ? node.chars.complement() : node.chars;
          return new Element(Kind.CHAR, cs, 1, 1, 0, null);
        }

        @Override
        public Element visitConcat(ConcatNode node) {
          return null;
        }

        @Override
        public Element visitAlternation(AlternationNode node) {
          return null;
        }

        @Override
        public Element visitQuantifier(QuantifierNode node) {
          if (!node.greedy || node.possessive) {
            return null;
          }
          Element child = node.child.accept(this);
          if (child == null || child.kind != Kind.CHAR) {
            return null;
          }
          return new Element(Kind.RUN, child.charSet, node.min, node.max, 0, null);
        }

        @Override
        public Element visitGroup(GroupNode node) {
          if (!node.capturing || node.atomic || node.name != null) {
            return null;
          }
          Element child = node.child.accept(this);
          if (child == null || child.kind != Kind.RUN) {
            return null;
          }
          return new Element(
              Kind.CAPTURED_RUN, child.charSet, child.min, child.max, node.groupNumber, null);
        }

        @Override
        public Element visitAnchor(AnchorNode node) {
          switch (node.type) {
            case WORD_BOUNDARY:
              return new Element(Kind.WORD_BOUNDARY, null, 0, 0, 0, null);
            case END:
              return node.multiline ? null : new Element(Kind.END, null, 0, 0, 0, EndKind.END);
            case STRING_END:
              return new Element(Kind.END, null, 0, 0, 0, EndKind.END);
            case STRING_END_ABSOLUTE:
              return new Element(Kind.END, null, 0, 0, 0, EndKind.ABSOLUTE);
            default:
              return null;
          }
        }

        @Override
        public Element visitBackreference(BackreferenceNode node) {
          return null;
        }

        @Override
        public Element visitAssertion(AssertionNode node) {
          return null;
        }

        @Override
        public Element visitSubroutine(SubroutineNode node) {
          return null;
        }

        @Override
        public Element visitConditional(ConditionalNode node) {
          return null;
        }

        @Override
        public Element visitBranchReset(BranchResetNode node) {
          return null;
        }
      };
}
