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

  /**
   * Sub-DFA gate for a variable-width lookahead (e.g. {@code (?=.{1,64}@)}, {@code (?=\w+@)}): the
   * assertion body is compiled to its own DFA and evaluated as an anchored run starting at the
   * current position (scan until the gate DFA accepts or dies). Null for the fixed-width
   * literal/charSet forms; when non-null, literal/charSets are null and the check ignores offset.
   */
  public final DFA gateDfa;

  // Constructor for literal assertions (e.g., "ab")
  public AssertionCheck(Type type, String literal, int offset) {
    this(type, literal, offset, Collections.emptyList());
  }

  // Constructor for the sub-DFA gate form (variable-width lookahead). The gate DFA's acceptance
  // is evaluated from the current position; groups inside the assertion body are not admitted
  // (see SubsetConstructor.extractAssertions).
  public AssertionCheck(Type type, DFA gateDfa) {
    if (gateDfa == null) throw new IllegalArgumentException("gateDfa required");
    if (type != Type.POSITIVE_LOOKAHEAD && type != Type.NEGATIVE_LOOKAHEAD) {
      throw new IllegalArgumentException("gate form supports lookaheads only: " + type);
    }
    this.type = type;
    this.literal = null;
    this.charSets = null;
    this.offset = 0;
    this.width = -1;
    this.isLiteral = false;
    this.groups = java.util.Collections.emptyList();
    this.gateDfa = gateDfa;
  }

  /** True for the sub-DFA gate form (variable-width lookahead). */
  public boolean isGateDfa() {
    return gateDfa != null;
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
    this.gateDfa = null;
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
    this.gateDfa = null;
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
