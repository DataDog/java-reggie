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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;

/**
 * Represents a lookahead assertion that contains an extractable literal, substring, or character
 * class. Used to optimize lookahead evaluation by using indexOf() or simple character checks
 * instead of full DFA simulation.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code (?=\w+@)} - SINGLE_CHAR type with '@' as the required character
 *   <li>{@code (?=.*example)} - SUBSTRING type with "example" as the required substring
 *   <li>{@code (?=[A-Z])} - CHAR_CLASS type with [A-Z] character set
 * </ul>
 */
public class LiteralLookaheadInfo {

  /** The type of literal that can be extracted from the lookahead. */
  public enum Type {
    /** Single required character (e.g., '@' in {@code (?=\w+@)}) */
    SINGLE_CHAR,

    /** Multi-character substring (e.g., "example" in {@code (?=.*example)}) */
    SUBSTRING,

    /** Character class at start (e.g., [A-Z] in {@code (?=[A-Z])}) */
    CHAR_CLASS
  }

  private final Type type;
  private final String literal; // For SUBSTRING
  private final Character singleChar; // For SINGLE_CHAR
  private final CharSet charClass; // For CHAR_CLASS
  private final int assertionStateId;

  // PHASE 3: Separated lookahead execution - store AST and NFA for atomic checking
  private final RegexNode lookaheadAST; // AST of the lookahead pattern (without assertion wrapper)
  private final NFA lookaheadNFA; // NFA for the lookahead pattern

  /**
   * Creates a LiteralLookaheadInfo for a single character lookahead.
   *
   * @param singleChar the required character
   * @param assertionStateId the NFA state ID of the assertion
   * @return LiteralLookaheadInfo instance
   */
  public static LiteralLookaheadInfo singleChar(char singleChar, int assertionStateId) {
    return new LiteralLookaheadInfo(
        Type.SINGLE_CHAR, null, singleChar, null, assertionStateId, null, null);
  }

  /**
   * Creates a LiteralLookaheadInfo for a substring lookahead.
   *
   * @param literal the required substring
   * @param assertionStateId the NFA state ID of the assertion
   * @return LiteralLookaheadInfo instance
   */
  public static LiteralLookaheadInfo substring(String literal, int assertionStateId) {
    return new LiteralLookaheadInfo(
        Type.SUBSTRING, literal, null, null, assertionStateId, null, null);
  }

  /**
   * Creates a LiteralLookaheadInfo for a character class lookahead.
   *
   * @param charClass the character class
   * @param assertionStateId the NFA state ID of the assertion
   * @return LiteralLookaheadInfo instance
   */
  public static LiteralLookaheadInfo charClass(CharSet charClass, int assertionStateId) {
    return new LiteralLookaheadInfo(
        Type.CHAR_CLASS, null, null, charClass, assertionStateId, null, null);
  }

  /**
   * Creates a LiteralLookaheadInfo with full AST and NFA for separated execution.
   *
   * @param type the type of literal
   * @param literal the literal string (for SUBSTRING)
   * @param singleChar the single character (for SINGLE_CHAR)
   * @param charClass the character class (for CHAR_CLASS)
   * @param assertionStateId the NFA state ID
   * @param lookaheadAST the AST of the lookahead pattern
   * @param lookaheadNFA the NFA of the lookahead pattern
   * @return LiteralLookaheadInfo instance
   */
  public static LiteralLookaheadInfo withNFA(
      Type type,
      String literal,
      Character singleChar,
      CharSet charClass,
      int assertionStateId,
      RegexNode lookaheadAST,
      NFA lookaheadNFA) {
    return new LiteralLookaheadInfo(
        type, literal, singleChar, charClass, assertionStateId, lookaheadAST, lookaheadNFA);
  }

  private LiteralLookaheadInfo(
      Type type,
      String literal,
      Character singleChar,
      CharSet charClass,
      int assertionStateId,
      RegexNode lookaheadAST,
      NFA lookaheadNFA) {
    this.type = type;
    this.literal = literal;
    this.singleChar = singleChar;
    this.charClass = charClass;
    this.assertionStateId = assertionStateId;
    this.lookaheadAST = lookaheadAST;
    this.lookaheadNFA = lookaheadNFA;
  }

  public Type getType() {
    return type;
  }

  public String getLiteral() {
    return literal;
  }

  public Character getSingleChar() {
    return singleChar;
  }

  public CharSet getCharClass() {
    return charClass;
  }

  public int getAssertionStateId() {
    return assertionStateId;
  }

  public RegexNode getLookaheadAST() {
    return lookaheadAST;
  }

  public NFA getLookaheadNFA() {
    return lookaheadNFA;
  }

  public boolean hasNFA() {
    return lookaheadNFA != null;
  }

  @Override
  public String toString() {
    switch (type) {
      case SINGLE_CHAR:
        return "LiteralLookahead(CHAR: '" + singleChar + "', state=" + assertionStateId + ")";
      case SUBSTRING:
        return "LiteralLookahead(SUBSTRING: \"" + literal + "\", state=" + assertionStateId + ")";
      case CHAR_CLASS:
        return "LiteralLookahead(CHARCLASS: " + charClass + ", state=" + assertionStateId + ")";
      default:
        return "LiteralLookahead(UNKNOWN)";
    }
  }
}
