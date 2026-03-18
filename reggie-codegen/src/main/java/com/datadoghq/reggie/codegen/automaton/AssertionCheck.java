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
package com.datadoghq.reggie.codegen.automaton;

import java.util.Collections;
import java.util.List;

/**
 * Represents an assertion check that should be performed during DFA execution. Supports fixed-width
 * lookahead/lookbehind with literals and character classes.
 */
public final class AssertionCheck {
  public enum Type {
    POSITIVE_LOOKAHEAD,
    NEGATIVE_LOOKAHEAD,
    POSITIVE_LOOKBEHIND,
    NEGATIVE_LOOKBEHIND
  }

  /**
   * Information about a capturing group inside this assertion. Used to track group boundaries
   * during assertion evaluation.
   */
  public static final class GroupCapture {
    public final int groupNumber; // The group number (1-based)
    public final int startOffset; // Start offset relative to assertion position
    public final int length; // Length of the group capture

    public GroupCapture(int groupNumber, int startOffset, int length) {
      this.groupNumber = groupNumber;
      this.startOffset = startOffset;
      this.length = length;
    }

    @Override
    public String toString() {
      return "Group" + groupNumber + "@" + startOffset + ":" + length;
    }
  }

  public final Type type;
  public final String literal; // Literal string to check (if isLiteral==true)
  public final List<CharSet> charSets; // Character sets to check (if isLiteral==false)
  public final int offset; // Offset from current position
  public final int width; // Width of the assertion
  public final boolean isLiteral; // True if literal, false if charSets
  public final List<GroupCapture> groups; // Groups inside this assertion (may be empty)

  // Constructor for literal assertions (e.g., "ab")
  public AssertionCheck(Type type, String literal, int offset) {
    this(type, literal, offset, Collections.emptyList());
  }

  // Constructor for literal assertions with groups
  public AssertionCheck(Type type, String literal, int offset, List<GroupCapture> groups) {
    this.type = type;
    this.literal = literal;
    this.charSets = null;
    this.offset = offset;
    this.width = literal.length();
    this.isLiteral = true;
    this.groups = groups;
  }

  // Constructor for character class sequences (e.g., [A-Z][0-9])
  public AssertionCheck(Type type, List<CharSet> charSets, int offset) {
    this(type, charSets, offset, Collections.emptyList());
  }

  // Constructor for character class sequences with groups
  public AssertionCheck(Type type, List<CharSet> charSets, int offset, List<GroupCapture> groups) {
    this.type = type;
    this.literal = null;
    this.charSets = charSets;
    this.offset = offset;
    this.width = charSets.size();
    this.isLiteral = false;
    this.groups = groups;
  }

  /** Check if this assertion contains any capturing groups. */
  public boolean hasGroups() {
    return !groups.isEmpty();
  }

  public boolean isLookahead() {
    return type == Type.POSITIVE_LOOKAHEAD || type == Type.NEGATIVE_LOOKAHEAD;
  }

  public boolean isLookbehind() {
    return type == Type.POSITIVE_LOOKBEHIND || type == Type.NEGATIVE_LOOKBEHIND;
  }

  public boolean isPositive() {
    return type == Type.POSITIVE_LOOKAHEAD || type == Type.POSITIVE_LOOKBEHIND;
  }

  @Override
  public String toString() {
    return "AssertionCheck{"
        + "type="
        + type
        + ", literal='"
        + literal
        + '\''
        + ", offset="
        + offset
        + '}';
  }
}
