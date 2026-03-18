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

import java.util.List;

/**
 * Represents a branch reset group: (?|alternative1|alternative2|...)
 *
 * <p>Branch reset allows capturing groups with the same group numbers across different
 * alternatives. Each alternative can have its own capturing groups, and they all reuse the same
 * group numbers.
 *
 * <p>Example: - (?|(abc)|(xyz)) - Both alternatives use group 1 - "abc" matches with group 1 =
 * "abc" - "xyz" matches with group 1 = "xyz"
 *
 * <p>This is different from regular alternation (abc)|(xyz) where: - "abc" matches with group 1 =
 * "abc" - "xyz" matches with group 1 = null, group 2 = "xyz"
 */
public final class BranchResetNode implements RegexNode {

  public final List<RegexNode> alternatives;
  public final int maxGroupNumber; // Highest group number used across all branches

  /**
   * Create a branch reset group.
   *
   * @param alternatives List of alternative patterns
   * @param maxGroupNumber Highest group number used across all branches
   */
  public BranchResetNode(List<RegexNode> alternatives, int maxGroupNumber) {
    this.alternatives = List.copyOf(alternatives);
    this.maxGroupNumber = maxGroupNumber;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitBranchReset(this);
  }

  @Override
  public String toString() {
    return "BranchReset(maxGroup=" + maxGroupNumber + ", " + alternatives + ")";
  }
}
