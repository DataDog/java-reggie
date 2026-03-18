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
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata about a linear regex pattern for specialized bytecode generation.
 *
 * <p>Contains the execution plan (sequence of operations) needed to match the pattern without NFA
 * simulation overhead. Used by LinearPatternBytecodeGenerator to generate direct execution code
 * similar to JDK's node-based approach.
 */
public class LinearPatternInfo implements PatternInfo {
  /** The full AST of the pattern */
  public final RegexNode ast;

  /** Number of capturing groups in the pattern */
  public final int groupCount;

  /** Whether the pattern contains backreferences (\1, \2, etc.) */
  public final boolean hasBackreferences;

  /** Whether the pattern contains anchors (^, $, \A, \z, \Z) */
  public final boolean hasAnchors;

  /** Whether the pattern contains quantifiers (+, *, ?, {n,m}) */
  public final boolean hasQuantifiers;

  /** Sequence of operations to execute for matching */
  public final List<LinearOperation> operations;

  public LinearPatternInfo(
      RegexNode ast,
      int groupCount,
      boolean hasBackreferences,
      boolean hasAnchors,
      boolean hasQuantifiers,
      List<LinearOperation> operations) {
    this.ast = ast;
    this.groupCount = groupCount;
    this.hasBackreferences = hasBackreferences;
    this.hasAnchors = hasAnchors;
    this.hasQuantifiers = hasQuantifiers;
    this.operations = operations != null ? operations : new ArrayList<>();
  }

  /**
   * A single operation in the linear execution plan.
   *
   * <p>Each operation represents a step in matching the pattern (e.g., match a literal, loop over a
   * character class, check a backreference, etc.).
   */
  public static class LinearOperation {
    /** Type of operation */
    public enum Type {
      /** Match a fixed literal string */
      MATCH_LITERAL,

      /** Match a single character from a character class */
      MATCH_CHARCLASS,

      /** Loop to match a quantified expression (+, *, ?, {n,m}) */
      MATCH_QUANTIFIER,

      /** Record the start position of a capturing group */
      START_GROUP,

      /** Record the end position of a capturing group */
      END_GROUP,

      /** Validate a backreference matches the captured group */
      CHECK_BACKREF,

      /** Validate an anchor (^, $) */
      CHECK_ANCHOR,

      /** Validate a word boundary (\b, \B) */
      CHECK_WORD_BOUNDARY
    }

    /** The type of this operation */
    public final Type type;

    /**
     * Operation-specific data:
     *
     * <ul>
     *   <li>MATCH_LITERAL: String (the literal to match)
     *   <li>MATCH_CHARCLASS: CharSet (the character class)
     *   <li>MATCH_QUANTIFIER: QuantifierData (bounds and child operations)
     *   <li>START_GROUP/END_GROUP: Integer (group number)
     *   <li>CHECK_BACKREF: Integer (group number to reference)
     *   <li>CHECK_ANCHOR: AnchorType (START or END)
     *   <li>CHECK_WORD_BOUNDARY: Boolean (true for \b, false for \B)
     * </ul>
     */
    public final Object data;

    public LinearOperation(Type type, Object data) {
      this.type = type;
      this.data = data;
    }

    @Override
    public String toString() {
      return type + "(" + data + ")";
    }
  }

  /** Data for quantifier operations */
  public static class QuantifierData {
    /** Minimum repetitions required (0 for *, 1 for +, etc.) */
    public final int min;

    /** Maximum repetitions allowed (-1 for unlimited) */
    public final int max;

    /** Whether the quantifier is greedy (true) or reluctant (false) */
    public final boolean greedy;

    /** The child operation(s) to repeat */
    public final List<LinearOperation> childOperations;

    public QuantifierData(int min, int max, boolean greedy, List<LinearOperation> childOperations) {
      this.min = min;
      this.max = max;
      this.greedy = greedy;
      this.childOperations = childOperations != null ? childOperations : new ArrayList<>();
    }

    @Override
    public String toString() {
      return String.format("min=%d, max=%d, greedy=%b", min, max, greedy);
    }
  }

  /** Anchor types */
  public enum AnchorType {
    /** Start of input (^) - non-multiline */
    START,

    /** End of input ($) - non-multiline */
    END,

    /** Start of line (^ in multiline mode) - matches at start or after \n */
    START_MULTILINE,

    /** End of line ($ in multiline mode) - matches at end or before \n */
    END_MULTILINE
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + groupCount;
    hash = 31 * hash + (hasBackreferences ? 1 : 0);
    hash = 31 * hash + (hasAnchors ? 1 : 0);
    hash = 31 * hash + (hasQuantifiers ? 1 : 0);
    hash = 31 * hash + operations.size();
    return hash;
  }

  @Override
  public String toString() {
    return String.format(
        "LinearPatternInfo(groups=%d, backrefs=%b, operations=%d)",
        groupCount, hasBackreferences, operations.size());
  }
}
