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
 * Represents a capturing or non-capturing group. Named groups like (?<name>...) or (?'name'...)
 * have both a name and a number.
 */
public final class GroupNode implements RegexNode {

  public final RegexNode child;
  public final int groupNumber; // 0 for non-capturing
  public final boolean capturing;
  public final String name; // null for unnamed groups

  public GroupNode(RegexNode child, int groupNumber, boolean capturing) {
    this(child, groupNumber, capturing, null);
  }

  public GroupNode(RegexNode child, int groupNumber, boolean capturing, String name) {
    this.child = child;
    this.groupNumber = groupNumber;
    this.capturing = capturing;
    this.name = name;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitGroup(this);
  }

  @Override
  public String toString() {
    if (name != null) {
      return "Group(" + groupNumber + " '" + name + "', " + child + ")";
    }
    return "Group(" + (capturing ? groupNumber : "non-capturing") + ", " + child + ")";
  }
}
