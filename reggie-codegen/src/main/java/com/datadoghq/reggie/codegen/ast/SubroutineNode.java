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
 * Represents a subroutine call: (?1), (?R), (?&name)
 *
 * <p>Subroutines allow calling a capturing group or the entire pattern as a subroutine, enabling
 * recursive patterns and pattern reuse.
 *
 * <p>Examples: - (?1) - call group 1 - (?R) - recursive call to entire pattern - (?&name) - call
 * named group (not yet supported)
 */
public final class SubroutineNode implements RegexNode {

  public final int groupNumber; // -1 for (?R) = entire pattern, 0+ for group number
  public final String name; // null for numeric calls, set for (?&name)

  /**
   * Create a subroutine call to a numbered group or entire pattern.
   *
   * @param groupNumber Group number to call (-1 for entire pattern)
   */
  public SubroutineNode(int groupNumber) {
    this(groupNumber, null);
  }

  /**
   * Create a subroutine call to a named group.
   *
   * @param name Group name to call
   */
  public SubroutineNode(String name) {
    this(-2, name); // -2 indicates named reference
  }

  /**
   * Internal constructor.
   *
   * @param groupNumber Group number (-1 for entire pattern, -2 for named, 0+ for numbered)
   * @param name Group name (null for numeric calls)
   */
  private SubroutineNode(int groupNumber, String name) {
    this.groupNumber = groupNumber;
    this.name = name;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitSubroutine(this);
  }

  @Override
  public String toString() {
    if (name != null) {
      return "Subroutine(&" + name + ")";
    } else if (groupNumber == -1) {
      return "Subroutine(R)";
    } else {
      return "Subroutine(" + groupNumber + ")";
    }
  }
}
