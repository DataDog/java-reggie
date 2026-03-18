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

/** Represents quantifiers like *, +, ?, {n}, {n,m}. */
public final class QuantifierNode implements RegexNode {

  public final RegexNode child;
  public final int min;
  public final int max; // -1 for unlimited
  public final boolean greedy;

  public QuantifierNode(RegexNode child, int min, int max, boolean greedy) {
    this.child = child;
    this.min = min;
    this.max = max;
    this.greedy = greedy;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitQuantifier(this);
  }

  @Override
  public String toString() {
    String quant;
    if (min == 0 && max == 1) quant = "?";
    else if (min == 0 && max == -1) quant = "*";
    else if (min == 1 && max == -1) quant = "+";
    else if (max == -1) quant = "{" + min + ",}";
    else if (min == max) quant = "{" + min + "}";
    else quant = "{" + min + "," + max + "}";

    return "Quantifier(" + child + quant + (greedy ? "" : "?") + ")";
  }
}
