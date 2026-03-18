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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;

/**
 * Pattern information for SPECIALIZED_QUANTIFIED_GROUP strategy.
 *
 * <p>Handles patterns with quantified capturing groups that require POSIX last-match semantics: -
 * (a)+ - literal in quantified group - (a|b)+ - alternation in quantified group - ([a-z])* - char
 * class in quantified group - (a+|b)+ - alternation with internal quantifiers - ([a-z]+)* - nested
 * quantifier (inner quantifier on charset)
 *
 * <p>This strategy generates bytecode that explicitly tracks the last successful iteration, fixing
 * the POSIX semantics where groups should capture from the LAST iteration.
 */
public class QuantifiedGroupInfo implements PatternInfo {
  public final int groupNumber; // Capturing group number
  public final RegexNode groupChild; // The node inside the group
  public final int minQuantifier; // Quantifier min (0 for *, 1 for +, n for {n,m})
  public final int maxQuantifier; // Quantifier max (Integer.MAX_VALUE for unbounded)
  public final CharSet charSet; // Character set if child is a char class or literal
  public final String literal; // Literal string if child is a literal
  public final boolean isAlternation; // True if child is an alternation
  public final CharSet[]
      alternationCharSets; // CharSets for each alternation branch (if isAlternation)
  public final boolean hasComplexAlternation; // True if alternation has internal quantifiers
  public final int[]
      alternationMinBounds; // Min bounds for each alternative (1 for simple, n for {n,})
  public final int[]
      alternationMaxBounds; // Max bounds for each alternative (1 for simple, MAX for +)
  public final boolean[] alternationNegated; // Whether each alternative's charset is negated
  public final List<RegexNode>
      alternatives; // The actual alternative nodes (for complex bytecode generation)

  // Nested quantifier support: ([a-z]+)* pattern
  public final boolean hasNestedQuantifier; // True if group child is itself a quantifier
  public final int innerMinQuantifier; // Inner quantifier min (1 for +, 0 for *)
  public final int innerMaxQuantifier; // Inner quantifier max (MAX for + or *)
  public final boolean isNegatedCharSet; // True if charset is negated ([^...])

  public QuantifiedGroupInfo(
      int groupNumber,
      RegexNode groupChild,
      int minQuantifier,
      int maxQuantifier,
      CharSet charSet,
      String literal,
      boolean isAlternation,
      CharSet[] alternationCharSets) {
    this(
        groupNumber,
        groupChild,
        minQuantifier,
        maxQuantifier,
        charSet,
        literal,
        isAlternation,
        alternationCharSets,
        false,
        null,
        null,
        null,
        null,
        false,
        1,
        1,
        false);
  }

  public QuantifiedGroupInfo(
      int groupNumber,
      RegexNode groupChild,
      int minQuantifier,
      int maxQuantifier,
      CharSet charSet,
      String literal,
      boolean isAlternation,
      CharSet[] alternationCharSets,
      boolean hasComplexAlternation,
      int[] alternationMinBounds,
      int[] alternationMaxBounds,
      List<RegexNode> alternatives,
      boolean[] alternationNegated) {
    this(
        groupNumber,
        groupChild,
        minQuantifier,
        maxQuantifier,
        charSet,
        literal,
        isAlternation,
        alternationCharSets,
        hasComplexAlternation,
        alternationMinBounds,
        alternationMaxBounds,
        alternationNegated,
        alternatives,
        false,
        1,
        1,
        false);
  }

  /** Full constructor with nested quantifier support. */
  public QuantifiedGroupInfo(
      int groupNumber,
      RegexNode groupChild,
      int minQuantifier,
      int maxQuantifier,
      CharSet charSet,
      String literal,
      boolean isAlternation,
      CharSet[] alternationCharSets,
      boolean hasComplexAlternation,
      int[] alternationMinBounds,
      int[] alternationMaxBounds,
      boolean[] alternationNegated,
      List<RegexNode> alternatives,
      boolean hasNestedQuantifier,
      int innerMinQuantifier,
      int innerMaxQuantifier,
      boolean isNegatedCharSet) {
    this.groupNumber = groupNumber;
    this.groupChild = groupChild;
    this.minQuantifier = minQuantifier;
    this.maxQuantifier = maxQuantifier;
    this.charSet = charSet;
    this.literal = literal;
    this.isAlternation = isAlternation;
    this.alternationCharSets = alternationCharSets;
    this.hasComplexAlternation = hasComplexAlternation;
    this.alternationMinBounds = alternationMinBounds;
    this.alternationMaxBounds = alternationMaxBounds;
    this.alternationNegated = alternationNegated;
    this.alternatives = alternatives;
    this.hasNestedQuantifier = hasNestedQuantifier;
    this.innerMinQuantifier = innerMinQuantifier;
    this.innerMaxQuantifier = innerMaxQuantifier;
    this.isNegatedCharSet = isNegatedCharSet;
  }

  /** Returns true if this is an unbounded greedy quantifier (*, +, {n,}). */
  public boolean isUnbounded() {
    return maxQuantifier == Integer.MAX_VALUE;
  }

  /** Returns true if the group contains a single literal character. */
  public boolean isSingleCharLiteral() {
    return literal != null && literal.length() == 1;
  }

  /** Returns true if the group contains a character class. */
  public boolean isCharClass() {
    return charSet != null && !isAlternation;
  }

  /** Returns true if the group has a nested quantifier (like [a-z]+ inside ([a-z]+)*). */
  public boolean hasNestedQuantifier() {
    return hasNestedQuantifier;
  }

  /** Returns true if the charset is negated (like [^"]). */
  public boolean isNegatedCharSet() {
    return isNegatedCharSet;
  }

  /** Compute structural hash for bytecode caching. */
  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + groupNumber;
    hash = 31 * hash + minQuantifier;
    hash = 31 * hash + (isUnbounded() ? 1 : 0);
    hash = 31 * hash + (isSingleCharLiteral() ? 1 : 0);
    hash = 31 * hash + (isCharClass() ? 2 : 0);
    hash = 31 * hash + (isAlternation ? 4 : 0);
    hash = 31 * hash + (hasComplexAlternation ? 8 : 0);
    hash = 31 * hash + (hasNestedQuantifier ? 16 : 0);
    hash = 31 * hash + (isNegatedCharSet ? 32 : 0);
    hash = 31 * hash + innerMinQuantifier;
    hash = 31 * hash + innerMaxQuantifier;
    if (alternationCharSets != null) {
      hash = 31 * hash + alternationCharSets.length;
      for (CharSet cs : alternationCharSets) {
        hash = 31 * hash + (cs != null ? cs.hashCode() : 0);
      }
    }
    if (alternationNegated != null) {
      for (boolean neg : alternationNegated) {
        hash = 31 * hash + (neg ? 1 : 0);
      }
    }
    if (charSet != null) {
      hash = 31 * hash + charSet.hashCode();
    }
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("QuantifiedGroupInfo[");
    sb.append("group=").append(groupNumber);
    sb.append(", quantifier={").append(minQuantifier).append(",").append(maxQuantifier).append("}");
    if (literal != null) {
      sb.append(", literal='").append(literal).append("'");
    }
    if (charSet != null) {
      sb.append(", charset");
      if (isNegatedCharSet) {
        sb.append("(negated)");
      }
    }
    if (isAlternation) {
      sb.append(", alternation[")
          .append(alternationCharSets != null ? alternationCharSets.length : 0)
          .append(" branches");
      if (alternationNegated != null) {
        sb.append(", negated=[");
        for (int i = 0; i < alternationNegated.length; i++) {
          if (i > 0) sb.append(",");
          sb.append(alternationNegated[i]);
        }
        sb.append("]");
      }
      sb.append("]");
    }
    if (hasNestedQuantifier) {
      sb.append(", nested{")
          .append(innerMinQuantifier)
          .append(",")
          .append(innerMaxQuantifier)
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }
}
