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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests that verify {@link Reggie#compile} surfaces typed exceptions for malformed
 * patterns (F8) and validates correctness fixes in the parser (F17).
 */
class ParserExceptionTest {

  // ==================== F8: Typed exceptions from Reggie.compile ====================

  /** Reggie.compile("[a") must not throw a raw StringIndexOutOfBoundsException. */
  @Test
  void compile_truncatedCharClass_throwsTypedException() {
    RuntimeException ex = assertThrows(RuntimeException.class, () -> Reggie.compile("[a"));
    assertFalse(
        ex instanceof StringIndexOutOfBoundsException,
        "Should not be StringIndexOutOfBoundsException; got " + ex.getClass().getName());
  }

  /**
   * A leading {@code ]} is a literal, so {@code []} and {@code [[]} are unclosed, as in the JDK.
   */
  @Test
  void compile_unclosedClassWithLeadingBracket_throwsPatternSyntaxException() {
    for (String p : new String[] {"[]", "[[]", "[^]"}) {
      assertThrows(PatternSyntaxException.class, () -> Pattern.compile(p), p);
      assertThrows(PatternSyntaxException.class, () -> Reggie.compile(p), p);
    }
  }

  /** Reggie.compile("(?") must not throw a raw StringIndexOutOfBoundsException. */
  @Test
  void compile_truncatedGroup_throwsTypedException() {
    RuntimeException ex = assertThrows(RuntimeException.class, () -> Reggie.compile("(?"));
    assertFalse(
        ex instanceof StringIndexOutOfBoundsException,
        "Should not be StringIndexOutOfBoundsException; got " + ex.getClass().getName());
  }

  /** Reggie.compile with overflowing quantifier must not throw NumberFormatException. */
  @Test
  void compile_quantifierOverflow_throwsTypedException() {
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> Reggie.compile("a{2147483648}"));
    assertFalse(
        ex instanceof NumberFormatException,
        "Should not be NumberFormatException; got " + ex.getClass().getName());
  }

  // ==================== F17.1: [\b] — backspace in character class ====================

  /** [\b] must match the backspace character (U+0008), not the literal 'b'. */
  @Test
  void charClass_backspaceEscape_matchesBackspace() {
    assertTrue(Reggie.compile("[\\b]").matches(""), "[\\b] should match backspace (U+0008)");
    assertFalse(Reggie.compile("[\\b]").matches("b"), "[\\b] must NOT match literal 'b'");
  }

  // ==================== F17.2: (?(N)...) — forward-reference condition ====================

  /** Reggie.compile("(?(2)a|b)") must not silently compile when no group 2 exists. */
  @Test
  void compile_conditionalNonExistentGroup_throwsTypedException() {
    assertThrows(
        RuntimeException.class,
        () -> Reggie.compile("(?(2)a|b)"),
        "Compiling (?(2)a|b) with no group 2 must throw");
  }

  /** A reversed class range is a syntax error on every entry point, as in the JDK. */
  @Test
  void reversedClassRange_throwsPatternSyntaxException() {
    String p = "[z-a]";
    assertThrows(PatternSyntaxException.class, () -> Reggie.compile(p));
    assertThrows(PatternSyntaxException.class, () -> RuntimeCompiler.compilePikeVm(p, ""));
    assertThrows(
        PatternSyntaxException.class, () -> RuntimeCompiler.compileBackrefBacktrack(p, ""));
    assertThrows(PatternSyntaxException.class, () -> RuntimeCompiler.createChainFallback(p, null));
    assertEquals(
        RuntimeCompiler.FullCaptureLtsRejection.PARSE_FAILURE,
        RuntimeCompiler.tryCompileFullCaptureLinearTokenSequence(p, 0).rejection());
    assertEquals(
        RuntimeCompiler.NamedOnlyLtsRejection.PARSE_FAILURE,
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(p, 0).rejection());
  }

  // ==================== F17.3: \x{NNNN} braced hex ====================

  /** \x{61} must match 'a'. */
  @Test
  void compile_bracedHexEscape_matchesCorrectly() {
    assertTrue(Reggie.compile("\\x{61}").matches("a"), "\\x{61} must match 'a'");
    assertFalse(Reggie.compile("\\x{61}").matches("b"), "\\x{61} must not match 'b'");
  }
}
