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
 * Pattern information for NESTED_QUANTIFIED_GROUPS strategy.
 *
 * <p>Handles patterns where quantifiers are nested within capturing groups:
 *
 * <ul>
 *   <li>{@code ((a|bc)+)*} - outer quantifier on group containing inner quantified content
 *   <li>{@code ((a+|b)*)?c} - optional outer quantifier with inner quantified alternation
 *   <li>{@code ^((a|b)+)*ax} - nested quantifiers with prefix/suffix
 * </ul>
 *
 * <h3>POSIX Last-Match Semantics</h3>
 *
 * When a group is inside a quantifier, the group captures from the LAST iteration only:
 *
 * <ul>
 *   <li>{@code ((a)+)*} matching "aaa" → Group 1 = "a" (from last outer iteration)
 *   <li>Not "aaa" (concatenation of all iterations)
 * </ul>
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * boolean matches(String input) {
 *     int pos = 0, len = input.length();
 *     int outerCount = 0;
 *     int group1Start = -1, group1End = -1;  // Only track last iteration
 *
 *     // Outer loop for * quantifier
 *     while (pos < len) {
 *         int outerStart = pos;
 *         int innerCount = 0;
 *
 *         // Inner loop for + quantifier
 *         while (pos < len) {
 *             if (matchInnerContent(input, pos)) {
 *                 innerCount++;
 *                 pos = newPos;
 *             } else {
 *                 break;
 *             }
 *         }
 *
 *         if (innerCount >= innerMin) {
 *             outerCount++;
 *             // Update group capture (POSIX: last iteration only)
 *             group1Start = outerStart;
 *             group1End = pos;
 *         } else {
 *             break;
 *         }
 *     }
 *
 *     return outerCount >= outerMin;
 * }
 * }</pre>
 */
public class NestedQuantifiedGroupsInfo implements PatternInfo {

  /** Information about a quantifier level in the nesting structure. */
  public static class QuantifierLevel {
    /** The quantifier min bound (0 for *, 1 for +, etc.) */
    public final int min;

    /** The quantifier max bound (Integer.MAX_VALUE for unbounded) */
    public final int max;

    /** The capturing group number at this level (-1 if non-capturing) */
    public final int groupNumber;

    /** The content being quantified (child of the quantifier) */
    public final RegexNode content;

    /** If the content is a simple charset, store it here */
    public final CharSet charSet;

    /** If the content is a simple literal, store it here */
    public final String literal;

    public QuantifierLevel(
        int min, int max, int groupNumber, RegexNode content, CharSet charSet, String literal) {
      this.min = min;
      this.max = max;
      this.groupNumber = groupNumber;
      this.content = content;
      this.charSet = charSet;
      this.literal = literal;
    }

    public boolean isUnbounded() {
      return max == Integer.MAX_VALUE;
    }
  }

  /**
   * The nesting levels from outermost to innermost. For ((a|bc)+)*, this would be: [0] = outer *
   * (min=0, max=MAX, groupNumber=1) [1] = inner + on (a|bc) (min=1, max=MAX, groupNumber=-1)
   */
  public final List<QuantifierLevel> levels;

  /** Nodes before the nested quantified group (may be empty). */
  public final List<RegexNode> prefix;

  /** Nodes after the nested quantified group (may be empty). */
  public final List<RegexNode> suffix;

  /** Whether pattern has start anchor (^). */
  public final boolean hasStartAnchor;

  /** Whether pattern has end anchor ($). */
  public final boolean hasEndAnchor;

  /** Total number of capturing groups in the pattern. */
  public final int totalGroupCount;

  public NestedQuantifiedGroupsInfo(
      List<QuantifierLevel> levels,
      List<RegexNode> prefix,
      List<RegexNode> suffix,
      boolean hasStartAnchor,
      boolean hasEndAnchor,
      int totalGroupCount) {
    this.levels = Objects.requireNonNull(levels);
    this.prefix = Objects.requireNonNull(prefix);
    this.suffix = Objects.requireNonNull(suffix);
    this.hasStartAnchor = hasStartAnchor;
    this.hasEndAnchor = hasEndAnchor;
    this.totalGroupCount = totalGroupCount;
  }

  /** Get the outermost quantifier level. */
  public QuantifierLevel getOuterLevel() {
    return levels.isEmpty() ? null : levels.get(0);
  }

  /** Get the innermost quantifier level. */
  public QuantifierLevel getInnerLevel() {
    return levels.isEmpty() ? null : levels.get(levels.size() - 1);
  }

  /** Get the nesting depth (number of quantifier levels). */
  public int getNestingDepth() {
    return levels.size();
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + levels.size();
    for (QuantifierLevel level : levels) {
      hash = 31 * hash + level.min;
      hash = 31 * hash + (level.isUnbounded() ? 1 : 0);
      hash = 31 * hash + level.groupNumber;
      hash = 31 * hash + (level.charSet != null ? level.charSet.hashCode() : 0);
      hash = 31 * hash + (level.literal != null ? level.literal.hashCode() : 0);
    }
    hash = 31 * hash + prefix.size();
    hash = 31 * hash + suffix.size();
    hash = 31 * hash + (hasStartAnchor ? 1 : 0);
    hash = 31 * hash + (hasEndAnchor ? 1 : 0);
    hash = 31 * hash + totalGroupCount;
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("NestedQuantifiedGroups[");
    sb.append("depth=").append(levels.size());
    sb.append(", levels=[");
    for (int i = 0; i < levels.size(); i++) {
      if (i > 0) sb.append(", ");
      QuantifierLevel level = levels.get(i);
      sb.append("{").append(level.min).append(",").append(level.max).append("}");
      if (level.groupNumber >= 0) {
        sb.append(" group").append(level.groupNumber);
      }
    }
    sb.append("]");
    if (!prefix.isEmpty()) sb.append(", prefix=").append(prefix.size());
    if (!suffix.isEmpty()) sb.append(", suffix=").append(suffix.size());
    if (hasStartAnchor) sb.append(" ^");
    if (hasEndAnchor) sb.append(" $");
    sb.append("]");
    return sb.toString();
  }
}
