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
 * Pattern information for GREEDY_BACKTRACK strategy.
 *
 * <p>Handles patterns where a greedy quantifier (.*, .+) must "give back" characters for a
 * following pattern element to match. Requires backtracking.
 *
 * <p>Examples: - (.*)bar - greedy any followed by literal - (.*)(\d+) - greedy any followed by char
 * class quantifier - (.*)(\d+)$ - greedy any followed by char class quantifier and anchor -
 * foo(.*)bar - prefix, greedy any, suffix
 */
public class GreedyBacktrackInfo implements PatternInfo {

  /** Type of suffix that follows the greedy group. */
  public enum SuffixType {
    LITERAL, // Single literal: (.*)bar
    CHAR_CLASS, // Single char class: (.*)[0-9]
    QUANTIFIED_CHAR_CLASS, // Quantified char class: (.*)(\d+)
    WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS, // Word boundary + quantified char class: (.*)\b(\d+)
    ANCHORED // End anchor: (.*)X$
  }

  // Prefix elements (before the greedy group)
  public final List<RegexNode> prefix;

  // The greedy capturing group info
  public final int greedyGroupNumber;
  public final int greedyMinCount; // 0 for .*, 1 for .+
  public final CharSet greedyCharSet; // Character set of the greedy element

  // Suffix info
  public final SuffixType suffixType;
  public final String suffixLiteral; // For LITERAL type
  public final CharSet suffixCharSet; // For CHAR_CLASS/QUANTIFIED_CHAR_CLASS
  public final int suffixMinCount; // Minimum repetitions for quantified suffix
  public final int suffixGroupNumber; // Group number if suffix is a capturing group (-1 if not)
  public final boolean hasEndAnchor; // True if pattern ends with $

  // Total group count in the pattern
  public final int totalGroupCount;

  // Original suffix AST nodes (for recursive processing if needed)
  public final List<RegexNode> suffixNodes;

  public GreedyBacktrackInfo(
      List<RegexNode> prefix,
      int greedyGroupNumber,
      int greedyMinCount,
      CharSet greedyCharSet,
      SuffixType suffixType,
      String suffixLiteral,
      CharSet suffixCharSet,
      int suffixMinCount,
      int suffixGroupNumber,
      boolean hasEndAnchor,
      int totalGroupCount,
      List<RegexNode> suffixNodes) {
    this.prefix = prefix;
    this.greedyGroupNumber = greedyGroupNumber;
    this.greedyMinCount = greedyMinCount;
    this.greedyCharSet = greedyCharSet;
    this.suffixType = suffixType;
    this.suffixLiteral = suffixLiteral;
    this.suffixCharSet = suffixCharSet;
    this.suffixMinCount = suffixMinCount;
    this.suffixGroupNumber = suffixGroupNumber;
    this.hasEndAnchor = hasEndAnchor;
    this.totalGroupCount = totalGroupCount;
    this.suffixNodes = suffixNodes;
  }

  /** Calculate minimum length of the suffix pattern. */
  public int getSuffixMinLength() {
    switch (suffixType) {
      case LITERAL:
        return suffixLiteral != null ? suffixLiteral.length() : 0;
      case CHAR_CLASS:
        return 1;
      case QUANTIFIED_CHAR_CLASS:
      case WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS:
        return suffixMinCount;
      case ANCHORED:
        return 0;
      default:
        return 0;
    }
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + prefix.size();
    hash = 31 * hash + greedyMinCount;
    hash = 31 * hash + (greedyCharSet != null ? greedyCharSet.hashCode() : 0);
    hash = 31 * hash + suffixType.ordinal();
    hash = 31 * hash + (suffixLiteral != null ? suffixLiteral.hashCode() : 0);
    hash = 31 * hash + (suffixCharSet != null ? suffixCharSet.hashCode() : 0);
    hash = 31 * hash + suffixMinCount;
    hash = 31 * hash + suffixGroupNumber;
    hash = 31 * hash + (hasEndAnchor ? 1 : 0);
    hash = 31 * hash + totalGroupCount;
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("GreedyBacktrack[");
    sb.append("prefix=").append(prefix.size()).append(" nodes, ");
    sb.append("greedyGroup=").append(greedyGroupNumber).append(", ");
    sb.append("greedyMin=").append(greedyMinCount).append(", ");
    sb.append("suffix=").append(suffixType);
    if (suffixLiteral != null) {
      sb.append(" '").append(suffixLiteral).append("'");
    }
    if (suffixGroupNumber >= 0) {
      sb.append(" group=").append(suffixGroupNumber);
    }
    if (hasEndAnchor) {
      sb.append(" $");
    }
    sb.append("]");
    return sb.toString();
  }
}
