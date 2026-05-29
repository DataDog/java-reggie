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

/** A semantic atom recognized by {@link PatternCategorizer}. */
public record PatternAtom(
    Kind kind, int groupNumber, String groupName, String literal, char delimiter) {

  public enum Kind {
    LITERAL,
    WHITESPACE_PLUS,
    NON_SPACE_PLUS,
    DIGITS_PLUS,
    SIGNED_INTEGER,
    DECIMAL_NUMBER,
    SIGNED_DECIMAL_NUMBER,
    WORD,
    UNTIL_DELIMITER,
    QUOTED_UNTIL_DELIMITER,
    COMPLEX_ALTERNATION,
    ANY_STAR,
    ANCHOR
  }

  public static PatternAtom literal(String literal) {
    return new PatternAtom(Kind.LITERAL, 0, null, literal, (char) 0);
  }

  public static PatternAtom uncaptured(Kind kind) {
    return new PatternAtom(kind, 0, null, null, (char) 0);
  }

  public static PatternAtom captured(Kind kind, int groupNumber, String groupName) {
    return new PatternAtom(kind, groupNumber, groupName, null, (char) 0);
  }

  public static PatternAtom capturedUntil(int groupNumber, String groupName, char delimiter) {
    return new PatternAtom(Kind.UNTIL_DELIMITER, groupNumber, groupName, null, delimiter);
  }

  public static PatternAtom capturedQuotedUntil(int groupNumber, String groupName, char delimiter) {
    return new PatternAtom(Kind.QUOTED_UNTIL_DELIMITER, groupNumber, groupName, null, delimiter);
  }

  public boolean isCaptured() {
    return groupNumber > 0;
  }
}
