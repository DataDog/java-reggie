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
 * Pattern information for FIXED_REPETITION_BACKREF strategy.
 *
 * <p>Handles patterns where a capturing group is followed by a backreference with fixed repetition
 * bounds. No backtracking needed - just verify the captured content repeats the required number of
 * times.
 *
 * <p>Examples: - (a)\1{8,} - capture 'a', verify 8+ more 'a's follow - (abc|def)=(\1){2,3} -
 * capture alternation, verify 2-3 exact repetitions - ^(foo)\1$ - capture 'foo', verify exactly one
 * repetition (foofoo) - (ab)\1{3} - capture 'ab', verify exactly 3 repetitions (abababab)
 *
 * <p>Algorithm: 1. Match and capture the referenced group 2. Loop: verify the captured content
 * repeats [minReps, maxReps] times 3. The captured group content is fixed once matched - no
 * backtracking
 */
public class FixedRepetitionBackrefInfo implements PatternInfo {

  /**
   * Prefix nodes before the capturing group (may be empty). For pattern like "^(a)\1{8,}", prefix
   * contains the ^ anchor.
   */
  public final List<RegexNode> prefix;

  /**
   * The AST node for the capturing group being referenced. Used to generate code for initial match
   * and capture.
   */
  public final RegexNode groupNode;

  /** Group number of the referenced capturing group (1-based). */
  public final int referencedGroupNumber;

  /**
   * Minimum number of times the backreference must repeat. For \1{8,} this is 8. For \1 this is 1.
   */
  public final int backrefMinReps;

  /**
   * Maximum number of times the backreference can repeat. For \1{8,} this is -1 (unbounded). For
   * \1{2,3} this is 3.
   */
  public final int backrefMaxReps;

  /**
   * Suffix nodes after the backreference (may be empty). For pattern like "(a)\1{8,}$", suffix
   * contains the $ anchor.
   */
  public final List<RegexNode> suffix;

  /** Total number of capturing groups in the pattern. */
  public final int totalGroupCount;

  /**
   * Whether the group content is a single character or char class. Enables optimization: can
   * compare char-by-char instead of regionMatches.
   */
  public final boolean isSingleCharGroup;

  /**
   * If isSingleCharGroup is true and group matches a char class, this is the charset. Null if
   * literal or complex group.
   */
  public final CharSet groupCharSet;

  /**
   * If group is a single literal character (e.g., (a)), this is that char. -1 if group is a char
   * class or multi-char.
   */
  public final int literalChar;

  public FixedRepetitionBackrefInfo(
      List<RegexNode> prefix,
      RegexNode groupNode,
      int referencedGroupNumber,
      int backrefMinReps,
      int backrefMaxReps,
      List<RegexNode> suffix,
      int totalGroupCount,
      boolean isSingleCharGroup,
      CharSet groupCharSet,
      int literalChar) {
    this.prefix = Objects.requireNonNull(prefix);
    this.groupNode = Objects.requireNonNull(groupNode);
    this.referencedGroupNumber = referencedGroupNumber;
    this.backrefMinReps = backrefMinReps;
    this.backrefMaxReps = backrefMaxReps;
    this.suffix = Objects.requireNonNull(suffix);
    this.totalGroupCount = totalGroupCount;
    this.isSingleCharGroup = isSingleCharGroup;
    this.groupCharSet = groupCharSet;
    this.literalChar = literalChar;
  }

  /** Check if the repetition is unbounded (e.g., \1{8,} or \1+). */
  public boolean isUnbounded() {
    return backrefMaxReps < 0;
  }

  /** Check if this is an exact repetition (e.g., \1{3} not \1{3,5}). */
  public boolean isExactRepetition() {
    return backrefMinReps == backrefMaxReps;
  }

  /**
   * Get the minimum total length this pattern can match. For (a)\1{8,}, minimum is 1 (group) + 8
   * (backrefs) = 9 chars.
   */
  public int getMinMatchLength() {
    // Group length is at least 1 (captured content)
    // Plus minReps repetitions of that content
    // Note: actual group length depends on runtime capture
    return 1 + backrefMinReps; // Approximation for single-char groups
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + referencedGroupNumber;
    hash = 31 * hash + backrefMinReps;
    hash = 31 * hash + backrefMaxReps;
    hash = 31 * hash + totalGroupCount;
    hash = 31 * hash + (isSingleCharGroup ? 1 : 0);
    hash = 31 * hash + (groupCharSet != null ? groupCharSet.hashCode() : 0);
    hash = 31 * hash + literalChar;
    hash = 31 * hash + prefix.size();
    hash = 31 * hash + suffix.size();
    // Include group node structure
    hash = 31 * hash + groupNode.getClass().getName().hashCode();
    return hash;
  }

  @Override
  public String toString() {
    return "FixedRepetitionBackrefInfo{"
        + "group="
        + referencedGroupNumber
        + ", reps="
        + backrefMinReps
        + (backrefMaxReps < 0 ? "+" : "," + backrefMaxReps)
        + ", totalGroups="
        + totalGroupCount
        + ", singleChar="
        + isSingleCharGroup
        + '}';
  }
}
