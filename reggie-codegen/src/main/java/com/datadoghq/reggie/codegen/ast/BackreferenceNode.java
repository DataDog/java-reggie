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

/** Represents a backreference like \1, \2, or named backreferences like \k<name>, \k'name'. */
public final class BackreferenceNode implements RegexNode {

  public final int groupNumber;
  public final String name; // null for numbered backreferences

  public BackreferenceNode(int groupNumber) {
    this(groupNumber, null);
  }

  public BackreferenceNode(int groupNumber, String name) {
    this.groupNumber = groupNumber;
    this.name = name;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitBackreference(this);
  }

  @Override
  public String toString() {
    if (name != null) {
      return "Backref(\\k'" + name + "')";
    }
    return "Backref(\\" + groupNumber + ")";
  }
}
