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

/**
 * Information about a simple backreference pattern that can be optimized with specialized bytecode
 * generation.
 */
public class BackreferencePatternInfo implements PatternInfo {

  /** Type of backreference pattern detected. */
  public enum BackrefType {
    /**
     * HTML/XML tag pattern: {@code <(\w+)>.*</\1>} Structure: literal prefix, capturing group,
     * middle content, closing tag with backref
     */
    HTML_TAG,

    /**
     * Repeated word pattern: {@code \b(\w+)\s+\1\b} Structure: word boundary, capturing group,
     * separator, backreference, word boundary
     */
    REPEATED_WORD,

    /**
     * Attribute matching pattern: {@code "([^"]+)"\s*=\s*"\1"} Structure: quoted string capture,
     * separator, quoted backreference
     */
    ATTRIBUTE_MATCH,

    /**
     * Greedy-any backreference pattern: {@code (.*)\d+\1} or {@code (.+)X\1} Structure: greedy
     * capturing group (.*|.+), separator, backreference Uses backtracking by trying different group
     * lengths from longest to shortest.
     */
    GREEDY_ANY_BACKREF
  }

  public final BackrefType type;
  public final int groupNumber; // Which group is referenced
  public final RegexNode fullPattern; // Full AST for complex patterns

  // HTML_TAG specific fields
  public final String openPrefix; // e.g., "<"
  public final String openSuffix; // e.g., ">"
  public final CharSet tagCharSet; // e.g., \w for tag name
  public final String closePrefix; // e.g., "</"
  public final String closeSuffix; // e.g., ">"

  // REPEATED_WORD specific fields
  public final boolean hasWordBoundary; // true if \b present
  public final CharSet wordCharSet; // e.g., \w for word chars
  public final String separator; // e.g., whitespace pattern

  // ATTRIBUTE_MATCH specific fields
  public final String quoteChar; // e.g., "\""
  public final CharSet contentCharSet; // e.g., [^"] for content
  public final String assignmentOp; // e.g., "="

  // GREEDY_ANY_BACKREF specific fields
  public final CharSet groupCharSet; // char class for group (e.g., . for anything)
  public final int groupMinCount; // 0 for *, 1 for +
  public final RegexNode separatorNode; // AST node for separator between group and backref
  public final int totalGroupCount; // total number of capturing groups (including nested)

  /** Constructor for HTML_TAG pattern. */
  public BackreferencePatternInfo(
      BackrefType type,
      int groupNumber,
      String openPrefix,
      String openSuffix,
      CharSet tagCharSet,
      String closePrefix,
      String closeSuffix) {
    this.type = type;
    this.groupNumber = groupNumber;
    this.fullPattern = null;
    this.openPrefix = openPrefix;
    this.openSuffix = openSuffix;
    this.tagCharSet = tagCharSet;
    this.closePrefix = closePrefix;
    this.closeSuffix = closeSuffix;
    this.hasWordBoundary = false;
    this.wordCharSet = null;
    this.separator = null;
    this.quoteChar = null;
    this.contentCharSet = null;
    this.assignmentOp = null;
    this.groupCharSet = null;
    this.groupMinCount = 0;
    this.separatorNode = null;
    this.totalGroupCount = 1;
  }

  /** Constructor for REPEATED_WORD pattern. */
  public BackreferencePatternInfo(
      BackrefType type,
      int groupNumber,
      boolean hasWordBoundary,
      CharSet wordCharSet,
      String separator) {
    this.type = type;
    this.groupNumber = groupNumber;
    this.fullPattern = null;
    this.hasWordBoundary = hasWordBoundary;
    this.wordCharSet = wordCharSet;
    this.separator = separator;
    this.openPrefix = null;
    this.openSuffix = null;
    this.tagCharSet = null;
    this.closePrefix = null;
    this.closeSuffix = null;
    this.quoteChar = null;
    this.contentCharSet = null;
    this.assignmentOp = null;
    this.groupCharSet = null;
    this.groupMinCount = 0;
    this.separatorNode = null;
    this.totalGroupCount = 1;
  }

  /** Constructor for ATTRIBUTE_MATCH pattern. */
  public BackreferencePatternInfo(
      BackrefType type,
      int groupNumber,
      String quoteChar,
      CharSet contentCharSet,
      String assignmentOp) {
    this.type = type;
    this.groupNumber = groupNumber;
    this.fullPattern = null;
    this.quoteChar = quoteChar;
    this.contentCharSet = contentCharSet;
    this.assignmentOp = assignmentOp;
    this.openPrefix = null;
    this.openSuffix = null;
    this.tagCharSet = null;
    this.closePrefix = null;
    this.closeSuffix = null;
    this.hasWordBoundary = false;
    this.wordCharSet = null;
    this.separator = null;
    this.groupCharSet = null;
    this.groupMinCount = 0;
    this.separatorNode = null;
    this.totalGroupCount = 1;
  }

  /**
   * Constructor for GREEDY_ANY_BACKREF pattern. Used for patterns like {@code (.*)\d+\1} where
   * group has variable length.
   *
   * @param totalGroupCount total number of capturing groups (e.g., 1 for (.*)\1, 2 for ((.*))\\1)
   */
  public BackreferencePatternInfo(
      BackrefType type,
      int groupNumber,
      CharSet groupCharSet,
      int groupMinCount,
      RegexNode separatorNode,
      int totalGroupCount) {
    this.type = type;
    this.groupNumber = groupNumber;
    this.fullPattern = null;
    this.groupCharSet = groupCharSet;
    this.groupMinCount = groupMinCount;
    this.separatorNode = separatorNode;
    this.totalGroupCount = totalGroupCount;
    this.openPrefix = null;
    this.openSuffix = null;
    this.tagCharSet = null;
    this.closePrefix = null;
    this.closeSuffix = null;
    this.hasWordBoundary = false;
    this.wordCharSet = null;
    this.separator = null;
    this.quoteChar = null;
    this.contentCharSet = null;
    this.assignmentOp = null;
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + type.hashCode();
    hash = 31 * hash + (hasWordBoundary ? 1 : 0);
    return hash;
  }

  @Override
  public String toString() {
    return "BackreferencePatternInfo{" + "type=" + type + ", groupNumber=" + groupNumber + '}';
  }
}
