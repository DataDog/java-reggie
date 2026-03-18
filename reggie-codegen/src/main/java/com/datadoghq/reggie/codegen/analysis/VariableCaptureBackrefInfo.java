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
import java.util.Objects;

/**
 * Pattern information for VARIABLE_CAPTURE_BACKREF strategy.
 *
 * <p>Handles patterns where a variable-length capturing group is followed by a separator and then a
 * backreference to that group. Requires backtracking from longest to shortest capture.
 *
 * <p>Examples: - (.*)\d+\1 - greedy any, digit separator, backref - (.+)=\1 - greedy any (1+),
 * literal separator, backref - (a+):\1 - greedy char class, literal separator, backref -
 * ^(.*)(\d+)\1$ - with anchors and capturing separator
 *
 * <p>Algorithm (longest-first backtracking): 1. Try maximum possible group capture (greedy) 2.
 * Check if separator matches at current position 3. Check if backreference matches captured content
 * 4. If fail, shrink group capture by 1 and retry 5. Respect BacktrackConfig.MAX_ITERATIONS limit
 */
public class VariableCaptureBackrefInfo implements PatternInfo {

  /** Type of separator between the capturing group and backreference. */
  public enum SeparatorType {
    LITERAL, // Literal string: (.*)=\1
    DIGIT_SEQ, // \d+ sequence: (.*)\d+\1
    WORD_SEQ, // \w+ sequence: (.*)\w+\1
    WHITESPACE_SEQ, // \s+ sequence: (.*)\s+\1
    CHAR_CLASS_SEQ, // Other char class sequence
    NONE // No separator: (.*)\1 (direct backref)
  }

  /**
   * Prefix nodes before the capturing group (may be empty). For pattern like "^(.*)\d+\1", prefix
   * contains the ^ anchor.
   */
  public final List<RegexNode> prefix;

  /** The capturing group number (1-based). */
  public final int groupNumber;

  /**
   * Character set the capturing group matches. For (.*) this is CharSet.ANY, for (a+) this is
   * CharSet containing 'a'.
   */
  public final CharSet groupCharSet;

  /** Minimum repetitions for the capturing group quantifier. 0 for .*, 1 for .+ */
  public final int groupMinCount;

  /** Maximum repetitions for the capturing group quantifier. -1 for unbounded (*, +) */
  public final int groupMaxCount;

  /** Type of separator between group and backreference. */
  public final SeparatorType separatorType;

  /** For LITERAL separator: the literal string. */
  public final String separatorLiteral;

  /** For char class separators: the charset. */
  public final CharSet separatorCharSet;

  /** Minimum repetitions for separator (1 for +, 0 for *, etc.) */
  public final int separatorMinCount;

  /** Group number of separator if it's a capturing group (-1 if not). */
  public final int separatorGroupNumber;

  /** Backreference group number (should match groupNumber). */
  public final int backrefGroupNumber;

  /** Minimum repetitions for the backreference (usually 1). */
  public final int backrefMinCount;

  /** Maximum repetitions for the backreference (-1 for unbounded). */
  public final int backrefMaxCount;

  /**
   * Suffix nodes after the backreference (may be empty). For pattern like "(.*)\d+\1$", suffix
   * contains the $ anchor.
   */
  public final List<RegexNode> suffix;

  /** Whether pattern has start anchor (^). */
  public final boolean hasStartAnchor;

  /** Whether pattern has end anchor ($). */
  public final boolean hasEndAnchor;

  /** Total number of capturing groups in the pattern. */
  public final int totalGroupCount;

  public VariableCaptureBackrefInfo(
      List<RegexNode> prefix,
      int groupNumber,
      CharSet groupCharSet,
      int groupMinCount,
      int groupMaxCount,
      SeparatorType separatorType,
      String separatorLiteral,
      CharSet separatorCharSet,
      int separatorMinCount,
      int separatorGroupNumber,
      int backrefGroupNumber,
      int backrefMinCount,
      int backrefMaxCount,
      List<RegexNode> suffix,
      boolean hasStartAnchor,
      boolean hasEndAnchor,
      int totalGroupCount) {
    this.prefix = Objects.requireNonNull(prefix);
    this.groupNumber = groupNumber;
    this.groupCharSet = groupCharSet;
    this.groupMinCount = groupMinCount;
    this.groupMaxCount = groupMaxCount;
    this.separatorType = Objects.requireNonNull(separatorType);
    this.separatorLiteral = separatorLiteral;
    this.separatorCharSet = separatorCharSet;
    this.separatorMinCount = separatorMinCount;
    this.separatorGroupNumber = separatorGroupNumber;
    this.backrefGroupNumber = backrefGroupNumber;
    this.backrefMinCount = backrefMinCount;
    this.backrefMaxCount = backrefMaxCount;
    this.suffix = Objects.requireNonNull(suffix);
    this.hasStartAnchor = hasStartAnchor;
    this.hasEndAnchor = hasEndAnchor;
    this.totalGroupCount = totalGroupCount;
  }

  /** Check if the separator is present. */
  public boolean hasSeparator() {
    return separatorType != SeparatorType.NONE;
  }

  /** Get minimum length of separator. */
  public int getSeparatorMinLength() {
    switch (separatorType) {
      case LITERAL:
        return separatorLiteral != null ? separatorLiteral.length() : 0;
      case DIGIT_SEQ:
      case WORD_SEQ:
      case WHITESPACE_SEQ:
      case CHAR_CLASS_SEQ:
        return separatorMinCount;
      case NONE:
      default:
        return 0;
    }
  }

  /** Check if backreference has variable repetition. */
  public boolean hasVariableBackref() {
    return backrefMinCount != backrefMaxCount || backrefMaxCount < 0;
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + prefix.size();
    hash = 31 * hash + groupNumber;
    hash = 31 * hash + (groupCharSet != null ? groupCharSet.hashCode() : 0);
    hash = 31 * hash + groupMinCount;
    hash = 31 * hash + groupMaxCount;
    hash = 31 * hash + separatorType.ordinal();
    hash = 31 * hash + (separatorLiteral != null ? separatorLiteral.hashCode() : 0);
    hash = 31 * hash + (separatorCharSet != null ? separatorCharSet.hashCode() : 0);
    hash = 31 * hash + separatorMinCount;
    hash = 31 * hash + separatorGroupNumber;
    hash = 31 * hash + backrefGroupNumber;
    hash = 31 * hash + backrefMinCount;
    hash = 31 * hash + backrefMaxCount;
    hash = 31 * hash + suffix.size();
    hash = 31 * hash + (hasStartAnchor ? 1 : 0);
    hash = 31 * hash + (hasEndAnchor ? 1 : 0);
    hash = 31 * hash + totalGroupCount;
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("VariableCaptureBackref[");
    sb.append("group=").append(groupNumber);
    sb.append(", groupMin=").append(groupMinCount);
    if (groupMaxCount >= 0) {
      sb.append(", groupMax=").append(groupMaxCount);
    }
    sb.append(", sep=").append(separatorType);
    if (separatorLiteral != null) {
      sb.append(" '").append(separatorLiteral).append("'");
    }
    sb.append(", backref=").append(backrefGroupNumber);
    if (backrefMinCount != 1 || backrefMaxCount != 1) {
      sb.append("{").append(backrefMinCount);
      if (backrefMaxCount < 0) {
        sb.append(",}");
      } else if (backrefMaxCount != backrefMinCount) {
        sb.append(",").append(backrefMaxCount).append("}");
      } else {
        sb.append("}");
      }
    }
    if (hasStartAnchor) sb.append(" ^");
    if (hasEndAnchor) sb.append(" $");
    sb.append("]");
    return sb.toString();
  }
}
