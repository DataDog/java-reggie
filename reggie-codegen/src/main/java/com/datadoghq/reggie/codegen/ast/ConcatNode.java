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

/** Represents concatenation of regex nodes (e.g., "abc" is concat of 'a', 'b', 'c'). */
public final class ConcatNode implements RegexNode {

  public final List<RegexNode> children;

  public ConcatNode(List<RegexNode> children) {
    this.children = List.copyOf(children);
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitConcat(this);
  }

  @Override
  public String toString() {
    return "Concat" + children;
  }
}
