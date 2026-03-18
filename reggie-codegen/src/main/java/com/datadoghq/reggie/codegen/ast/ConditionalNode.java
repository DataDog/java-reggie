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
package com.datadoghq.reggie.codegen.ast;

/**
 * Represents a conditional pattern: (?(condition)yes|no)
 *
 * <p>Conditionals allow branching based on whether a capturing group matched. If the condition (a
 * group number) has matched, the "then" branch is tried. Otherwise, the "else" branch is tried (if
 * present).
 *
 * <p>Examples: - (?(1)yes|no) - if group 1 matched, try "yes", else try "no" - (?(1)yes) - if group
 * 1 matched, try "yes", else match empty - (\()?blah(?(1)\)) - optional parentheses that must be
 * balanced
 */
public final class ConditionalNode implements RegexNode {

  public final int condition; // Group number to check
  public final RegexNode thenBranch; // Branch if condition matched
  public final RegexNode elseBranch; // Branch if condition didn't match (may be null)

  /**
   * Create a conditional with both then and else branches.
   *
   * @param condition Group number to check
   * @param thenBranch Pattern to match if group matched
   * @param elseBranch Pattern to match if group didn't match (null for empty)
   */
  public ConditionalNode(int condition, RegexNode thenBranch, RegexNode elseBranch) {
    this.condition = condition;
    this.thenBranch = thenBranch;
    this.elseBranch = elseBranch;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitConditional(this);
  }

  @Override
  public String toString() {
    if (elseBranch != null) {
      return "Conditional(" + condition + ", then=" + thenBranch + ", else=" + elseBranch + ")";
    } else {
      return "Conditional(" + condition + ", then=" + thenBranch + ")";
    }
  }
}
