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
 * Represents lookahead/lookbehind assertions. Zero-width assertions that check conditions without
 * consuming characters.
 *
 * <p>Supported types: - (?=...) : Positive lookahead - (?!...) : Negative lookahead - (?<=...) :
 * Positive lookbehind (fixed-width only) - (?<!...) : Negative lookbehind (fixed-width only)
 */
public final class AssertionNode implements RegexNode {

  public enum Type {
    POSITIVE_LOOKAHEAD, // (?=...)
    NEGATIVE_LOOKAHEAD, // (?!...)
    POSITIVE_LOOKBEHIND, // (?<=...)
    NEGATIVE_LOOKBEHIND // (?<!...)
  }

  public final Type type;
  public final RegexNode subPattern; // The assertion pattern
  public final int fixedWidth; // For lookbehind: computed width, -1 for lookahead

  public AssertionNode(Type type, RegexNode subPattern, int fixedWidth) {
    this.type = type;
    this.subPattern = subPattern;
    this.fixedWidth = fixedWidth;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitAssertion(this);
  }

  @Override
  public String toString() {
    return "Assertion(" + type + ", width=" + fixedWidth + ", " + subPattern + ")";
  }
}
