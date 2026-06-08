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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pattern information for OPTIONAL_GROUP_BACKREF strategy.
 *
 * <p>Handles patterns where an optional capturing group is followed by a backreference to that
 * group.
 *
 * <p>Examples: - (a|)\1 - always-capturing group, backref - (a)?\1 - quantified optional, backref -
 * ^(a)?(b)?\1\2 - multiple optional groups with backrefs
 *
 * <p>Algorithm: 1. Try to match optional group (track if matched) 2. Match any middle elements 3.
 * For backref: if group matched, verify content; else (for alwaysCaptures groups) match empty, or
 * (for quantified optional groups) fail
 *
 * <p>Java Semantics: - Group matched: backref must match captured content - Group did not
 * participate ((X)? form, not matched): backref FAILS - Group always captures ((X|) form, empty
 * alt): backref matches empty string
 */
public class OptionalGroupBackrefInfo implements PatternInfo {

  /** Information about an optional group and its backref. */
  public static class OptionalGroupEntry {
    /** The group number (1-based) */
    public final int groupNumber;

    /** The AST node for the optional group content */
    public final RegexNode groupContent;

    /** Whether the group content is a single character */
    public final boolean isSingleChar;

    /** If single char, the literal character (-1 if charset or complex) */
    public final int literalChar;

    /** If literal string, the string value (null if not a simple literal sequence) */
    public final String literalString;

    /**
     * True if the group always captures (e.g. (X|) empty-alt form). False for (X)? quantified
     * optional form. When false and the group did not participate, the backref fails rather than
     * matching empty.
     */
    public final boolean alwaysCaptures;

    public OptionalGroupEntry(
        int groupNumber,
        RegexNode groupContent,
        boolean isSingleChar,
        int literalChar,
        String literalString,
        boolean alwaysCaptures) {
      this.groupNumber = groupNumber;
      this.groupContent = groupContent;
      this.isSingleChar = isSingleChar;
      this.literalChar = literalChar;
      this.literalString = literalString;
      this.alwaysCaptures = alwaysCaptures;
    }
  }

  /** Information about a backreference with its quantifier. */
  public static class BackrefEntry {
    /** The group number being referenced (1-based) */
    public final int groupNumber;

    /** Minimum repetitions (1 for \1+, 0 for \1*, etc.) */
    public final int minCount;

    /** Maximum repetitions (Integer.MAX_VALUE for unbounded) */
    public final int maxCount;

    public BackrefEntry(int groupNumber, int minCount, int maxCount) {
      this.groupNumber = groupNumber;
      this.minCount = minCount;
      this.maxCount = maxCount;
    }

    @Override
    public int hashCode() {
      return 31 * (31 * groupNumber + minCount) + maxCount;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof BackrefEntry)) return false;
      BackrefEntry other = (BackrefEntry) obj;
      return groupNumber == other.groupNumber
          && minCount == other.minCount
          && maxCount == other.maxCount;
    }
  }

  /** Prefix nodes before the first optional group (may be empty). */
  public final List<RegexNode> prefix;

  /** Information about each optional group in order. */
  public final List<OptionalGroupEntry> optionalGroups;

  /** Set of group numbers that are optional. */
  public final Set<Integer> optionalGroupNumbers;

  /** Middle nodes between optional groups and backrefs (may be empty). */
  public final List<RegexNode> middle;

  /**
   * Backref group numbers in order of appearance. Each must reference one of the optional groups.
   *
   * @deprecated Use {@link #backrefEntries} for full quantifier info.
   */
  public final List<Integer> backrefGroupNumbers;

  /** Backreference entries with quantifier info, in order of appearance. */
  public final List<BackrefEntry> backrefEntries;

  /** Suffix nodes after all backrefs (may be empty). */
  public final List<RegexNode> suffix;

  /** If suffix is a simple literal character, its value; otherwise -1. */
  public final int suffixLiteralChar;

  /**
   * If suffix is a capturing group with literal content, the group number (1-based); otherwise 0.
   * For example, in pattern ^(cow|)\1(bell), suffixGroupNumber would be 2.
   */
  public final int suffixGroupNumber;

  /**
   * If suffix is a capturing group with literal content, the literal string; otherwise null. For
   * example, in pattern ^(cow|)\1(bell), suffixGroupLiteral would be "bell".
   */
  public final String suffixGroupLiteral;

  /** Whether pattern has start anchor (^). */
  public final boolean hasStartAnchor;

  /** Whether pattern has end anchor ($). */
  public final boolean hasEndAnchor;

  /** Total number of capturing groups in the pattern. */
  public final int totalGroupCount;

  public OptionalGroupBackrefInfo(
      List<RegexNode> prefix,
      List<OptionalGroupEntry> optionalGroups,
      Set<Integer> optionalGroupNumbers,
      List<RegexNode> middle,
      List<Integer> backrefGroupNumbers,
      List<BackrefEntry> backrefEntries,
      List<RegexNode> suffix,
      int suffixLiteralChar,
      int suffixGroupNumber,
      String suffixGroupLiteral,
      boolean hasStartAnchor,
      boolean hasEndAnchor,
      int totalGroupCount) {
    this.prefix = Objects.requireNonNull(prefix);
    this.optionalGroups = Objects.requireNonNull(optionalGroups);
    this.optionalGroupNumbers = Objects.requireNonNull(optionalGroupNumbers);
    this.middle = Objects.requireNonNull(middle);
    this.backrefGroupNumbers = Objects.requireNonNull(backrefGroupNumbers);
    this.backrefEntries = Objects.requireNonNull(backrefEntries);
    this.suffix = Objects.requireNonNull(suffix);
    this.suffixLiteralChar = suffixLiteralChar;
    this.suffixGroupNumber = suffixGroupNumber;
    this.suffixGroupLiteral = suffixGroupLiteral;
    this.hasStartAnchor = hasStartAnchor;
    this.hasEndAnchor = hasEndAnchor;
    this.totalGroupCount = totalGroupCount;
  }

  /** Check if a group number is optional. */
  public boolean isOptional(int groupNumber) {
    return optionalGroupNumbers.contains(groupNumber);
  }

  /** Get the OptionalGroupEntry for a group number, or null if not found. */
  public OptionalGroupEntry getGroupEntry(int groupNumber) {
    for (OptionalGroupEntry entry : optionalGroups) {
      if (entry.groupNumber == groupNumber) {
        return entry;
      }
    }
    return null;
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + prefix.size();
    hash = 31 * hash + optionalGroups.size();
    for (OptionalGroupEntry entry : optionalGroups) {
      hash = 31 * hash + entry.groupNumber;
      hash = 31 * hash + (entry.isSingleChar ? 1 : 0);
      hash = 31 * hash + entry.literalChar;
      hash = 31 * hash + (entry.literalString != null ? entry.literalString.hashCode() : 0);
      hash = 31 * hash + (entry.alwaysCaptures ? 1 : 0);
    }
    hash = 31 * hash + middle.size();
    hash = 31 * hash + backrefEntries.hashCode();
    hash = 31 * hash + suffix.size();
    hash = 31 * hash + suffixLiteralChar;
    hash = 31 * hash + suffixGroupNumber;
    hash = 31 * hash + (suffixGroupLiteral != null ? suffixGroupLiteral.hashCode() : 0);
    hash = 31 * hash + (hasStartAnchor ? 1 : 0);
    hash = 31 * hash + (hasEndAnchor ? 1 : 0);
    hash = 31 * hash + totalGroupCount;
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("OptionalGroupBackref[");
    sb.append("optGroups=").append(optionalGroupNumbers);
    sb.append(", backrefs=").append(backrefGroupNumbers);
    if (!middle.isEmpty()) {
      sb.append(", middle=").append(middle.size()).append(" nodes");
    }
    if (hasStartAnchor) sb.append(" ^");
    if (hasEndAnchor) sb.append(" $");
    sb.append("]");
    return sb.toString();
  }
}
