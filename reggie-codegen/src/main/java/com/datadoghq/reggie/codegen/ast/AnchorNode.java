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

/** Represents anchors like ^, $, \b, \A, \Z, \z. */
public final class AnchorNode implements RegexNode {

  public enum Type {
    START, // ^ (affected by multiline mode)
    END, // $ (affected by multiline mode)
    WORD_BOUNDARY, // \b
    STRING_START, // \A (start of string, not affected by multiline)
    STRING_END, // \Z (end of string or before final newline)
    STRING_END_ABSOLUTE, // \z (absolute end of string)
    RESET_MATCH // \K (reset match start - everything before is consumed but not reported)
  }

  public final Type type;
  public final boolean multiline; // In multiline mode, ^ and $ match line boundaries

  public AnchorNode(Type type) {
    this(type, false);
  }

  public AnchorNode(Type type, boolean multiline) {
    this.type = type;
    this.multiline = multiline;
  }

  @Override
  public <T> T accept(RegexVisitor<T> visitor) {
    return visitor.visitAnchor(this);
  }

  @Override
  public String toString() {
    return "Anchor(" + type + ")";
  }
}
