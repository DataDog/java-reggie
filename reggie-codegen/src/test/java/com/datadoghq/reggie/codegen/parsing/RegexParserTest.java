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
package com.datadoghq.reggie.codegen.parsing;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegexParser}: typed exceptions (F8) and validation gaps (F17). End-to-end
 * tests using {@code Reggie.compile()} live in {@code reggie-runtime}.
 */
class RegexParserTest {

  private RegexNode parse(String pattern) throws RegexParser.ParseException {
    return new RegexParser().parse(pattern);
  }

  // ==================== F8: Typed parser exceptions ====================

  /** Unclosed character class must throw ParseException, not StringIndexOutOfBoundsException. */
  @Test
  void truncatedCharClass_throwsParseException() {
    assertThrows(
        RegexParser.ParseException.class,
        () -> parse("[a"),
        "Unclosed '[' must throw ParseException");
  }

  /**
   * Truncated group construct "(?" must throw ParseException, not StringIndexOutOfBoundsException.
   */
  @Test
  void truncatedGroupConstruct_throwsParseException() {
    assertThrows(
        RegexParser.ParseException.class,
        () -> parse("(?"),
        "Truncated '(?' must throw ParseException");
  }

  /** Quantifier count overflow must throw ParseException, not NumberFormatException. */
  @Test
  void quantifierCountOverflow_throwsParseException() {
    assertThrows(
        RegexParser.ParseException.class,
        () -> parse("a{2147483648}"),
        "Quantifier count overflow must throw ParseException");
  }

  // ==================== F17.2: (?(N)...) — forward/non-existent group condition
  // ====================

  /**
   * A conditional whose group number references a non-existent group must throw ParseException.
   * Pattern "(?(2)a|b)" has no group 2.
   */
  @Test
  void conditional_nonExistentGroup_throwsParseException() {
    assertThrows(
        RegexParser.ParseException.class,
        () -> parse("(?(2)a|b)"),
        "(?(2)a|b) with no group 2 must throw ParseException");
  }

  /** A conditional that refers to an existing group must still parse successfully. */
  @Test
  void conditional_existingGroup_parsesSuccessfully() {
    assertDoesNotThrow(() -> parse("(a)?(?(1)b|c)"));
  }

  // ==================== F17.3: \x{NNNN} braced hex ====================

  /** \x{61} must parse to literal 'a' (U+0061). */
  @Test
  void bracedHexEscape_matchesCorrectChar() throws RegexParser.ParseException {
    RegexNode node = parse("\\x{61}");
    assertInstanceOf(LiteralNode.class, node);
    assertEquals('a', ((LiteralNode) node).ch, "\\x{61} must parse to literal 'a'");
  }

  /** \x{FFFF} must parse to U+FFFF (max BMP). */
  @Test
  void bracedHexEscape_maxBmp_parsesCorrectly() throws RegexParser.ParseException {
    RegexNode node = parse("\\x{FFFF}");
    assertInstanceOf(LiteralNode.class, node);
    assertEquals('￿', ((LiteralNode) node).ch, "\\x{FFFF} must parse to U+FFFF");
  }

  /** \x{10000} overflows char range — must throw ParseException. */
  @Test
  void bracedHexEscape_overflow_throwsParseException() {
    assertThrows(
        RegexParser.ParseException.class,
        () -> parse("\\x{10000}"),
        "\\x{10000} overflows char range and must throw ParseException");
  }

  /** \x{} with no digits must throw ParseException. */
  @Test
  void bracedHexEscape_empty_throwsParseException() {
    assertThrows(
        RegexParser.ParseException.class,
        () -> parse("\\x{}"),
        "\\x{} with no hex digits must throw ParseException");
  }

  // ==================== Wave 1: possessive quantifiers throw UnsupportedPatternException ====================

  /** Possessive quantifier a*+ must throw UnsupportedPatternException. */
  @Test
  void possessiveQuantifier_starPlus_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("a*+"),
        "a*+ must throw UnsupportedPatternException");
  }

  /** Possessive quantifier \\d++ must throw UnsupportedPatternException. */
  @Test
  void possessiveQuantifier_plusPlus_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("\\d++"),
        "\\d++ must throw UnsupportedPatternException");
  }

  /** Possessive quantifier [a-z]?+ must throw UnsupportedPatternException. */
  @Test
  void possessiveQuantifier_questionPlus_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("[a-z]?+"),
        "[a-z]?+ must throw UnsupportedPatternException");
  }

  /** Possessive quantifier a{2,4}+ must throw UnsupportedPatternException. */
  @Test
  void possessiveQuantifier_boundedPlus_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("a{2,4}+"),
        "a{2,4}+ must throw UnsupportedPatternException");
  }

  // ==================== Wave 1: atomic groups throw UnsupportedPatternException ====================

  /** Atomic group (?>a*) must throw UnsupportedPatternException. */
  @Test
  void atomicGroup_starQuantifier_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("(?>a*)"),
        "(?>a*) must throw UnsupportedPatternException");
  }

  /** Atomic group (?>a+)b must throw UnsupportedPatternException. */
  @Test
  void atomicGroup_withSuffix_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("(?>a+)b"),
        "(?>a+)b must throw UnsupportedPatternException");
  }

  /** Atomic group (?>\\d+) followed by a literal suffix must throw UnsupportedPatternException. */
  @Test
  void atomicGroup_digitPlusWithSuffix_throwsUnsupported() {
    assertThrows(
        RegexParser.UnsupportedPatternException.class,
        () -> parse("(?>\\d+)abc"),
        "(?>\\d+)abc must throw UnsupportedPatternException");
  }
}
