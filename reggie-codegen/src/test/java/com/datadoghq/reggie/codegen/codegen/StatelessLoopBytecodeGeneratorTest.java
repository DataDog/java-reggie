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
package com.datadoghq.reggie.codegen.codegen;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;

/**
 * Tests for StatelessLoopBytecodeGenerator - verifies pattern detection and bytecode generation for
 * stateless patterns.
 *
 * <p>Note: These tests validate detection logic and bytecode generation correctness. End-to-end
 * execution tests are in reggie-runtime tests.
 */
class StatelessLoopBytecodeGeneratorTest {

  /** Analyze pattern and return strategy result. */
  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);

    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 0);

    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeAndRecommend();
  }

  /** Generate bytecode for pattern (validates generation doesn't crash). */
  private byte[] generateBytecode(String pattern) throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    assertEquals(
        PatternAnalyzer.MatchingStrategy.STATELESS_LOOP,
        result.strategy,
        "Expected STATELESS_LOOP strategy for: " + pattern);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    String className = "TestMatcher";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    cw.visit(
        org.objectweb.asm.Opcodes.V21,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
        className,
        null,
        "java/lang/Object",
        null);

    StatelessLoopBytecodeGenerator gen = new StatelessLoopBytecodeGenerator(info);
    gen.generateMatchesMethod(cw, className);
    gen.generateFindMethod(cw, className);
    gen.generateFindFromMethod(cw, className);

    cw.visitEnd();
    return cw.toByteArray();
  }

  // ==================== LOOKAHEAD + LITERAL PATTERN DETECTION ====================

  @Test
  void testLookaheadLiteral_BasicDetection() throws Exception {
    String pattern = "(?=\\w+@).*@example\\.com";
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
    assertNotNull(result.patternInfo);
    assertTrue(result.patternInfo instanceof PatternAnalyzer.StatelessPatternInfo);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    assertEquals(PatternAnalyzer.StatelessPatternInfo.PatternType.LOOKAHEAD_LITERAL, info.type);
    assertEquals("@example.com", info.literalSuffix);
    assertNotNull(info.lookahead);
  }

  @ParameterizedTest
  @ValueSource(strings = {"(?=\\w+@).*@example\\.com", "(?=\\d+x).*xend", "(?=[a-z]+!).*!suffix"})
  void testLookaheadLiteral_DetectionVariations(String pattern) throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);
    assertEquals(
        PatternAnalyzer.MatchingStrategy.STATELESS_LOOP,
        result.strategy,
        "Should detect as STATELESS_LOOP: " + pattern);
  }

  @Test
  void testLookaheadLiteral_LiteralSuffixExtraction() throws Exception {
    // Test that literal suffix is correctly extracted
    String pattern = "(?=\\w+@).*@test\\.org";
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    assertEquals("@test.org", info.literalSuffix);
  }

  @Test
  void testLookaheadLiteral_LongLiteralSuffix() throws Exception {
    // Test with longer literal suffix
    String pattern = "(?=\\w+@).*@example\\.com\\.au";
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    assertEquals("@example.com.au", info.literalSuffix);
  }

  // ==================== BYTECODE GENERATION ====================

  @Test
  void testBytecodeGeneration_DoesNotCrash() throws Exception {
    // Verify bytecode generation completes without errors
    String pattern = "(?=\\w+@).*@example\\.com";
    byte[] bytecode = generateBytecode(pattern);

    assertNotNull(bytecode);
    assertTrue(bytecode.length > 0);
  }

  @Test
  void testBytecodeGeneration_MultiplePatterns() throws Exception {
    // Test bytecode generation for multiple patterns
    String[] patterns = {"(?=\\w+@).*@example\\.com", "(?=\\d+x).*xend", "(?=[a-z]+!).*!suffix"};

    for (String pattern : patterns) {
      byte[] bytecode = generateBytecode(pattern);
      assertNotNull(bytecode, "Bytecode generation failed for: " + pattern);
      assertTrue(bytecode.length > 0, "Empty bytecode for: " + pattern);
    }
  }

  @Test
  void testBytecodeGeneration_ClassStructure() throws Exception {
    // Verify generated bytecode has reasonable size (indicates methods were generated)
    String pattern = "(?=\\w+@).*@example\\.com";
    byte[] bytecode = generateBytecode(pattern);

    // Should have constructor + 3 methods (matches, find, findFrom)
    // Reasonable size: at least 1KB
    assertTrue(
        bytecode.length > 1000,
        "Bytecode too small, may be missing methods. Size: " + bytecode.length);
  }

  // ==================== NEGATIVE TESTS ====================

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(\\w+)\\s+\\1", // Backreference - should not be STATELESS_LOOP
        "([a-z]+)@([a-z]+)\\.com", // Groups - may use different strategy
        ".*@example\\.com" // No lookahead - different strategy
      })
  void testNotStatelessLoop(String pattern) throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // These patterns should NOT be detected as STATELESS_LOOP
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.STATELESS_LOOP,
        result.strategy,
        "Pattern should NOT be STATELESS_LOOP: " + pattern);
  }

  @Test
  void testLookaheadLiteral_TooShortLiteral() throws Exception {
    // Literal suffix must be at least 3 characters for STATELESS_LOOP
    String pattern = "(?=\\w+@).*@x"; // Only 2 chars: "@x"
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // Should not be detected as STATELESS_LOOP (literal too short)
    assertNotEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
  }

  // ==================== INFO OBJECT VALIDATION ====================

  @Test
  void testStatelessPatternInfo_LookaheadLiteralFields() throws Exception {
    String pattern = "(?=\\w+@).*@example\\.com";
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    // Verify LOOKAHEAD_LITERAL specific fields
    assertEquals(PatternAnalyzer.StatelessPatternInfo.PatternType.LOOKAHEAD_LITERAL, info.type);
    assertNotNull(info.literalSuffix);
    assertNotNull(info.lookahead);
    assertNull(info.charset); // Should be null for LOOKAHEAD_LITERAL
    assertEquals(0, info.minReps); // Not used for LOOKAHEAD_LITERAL
    assertEquals(0, info.maxReps); // Not used for LOOKAHEAD_LITERAL
  }

  @Test
  void testLookaheadLiteral_AssertionType() throws Exception {
    String pattern = "(?=\\w+@).*@example\\.com";
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    // Verify it's a positive lookahead
    assertEquals(
        com.datadoghq.reggie.codegen.ast.AssertionNode.Type.POSITIVE_LOOKAHEAD,
        info.lookahead.type);
  }

  // ==================== EDGE CASES ====================

  @Test
  void testLookaheadLiteral_EscapedCharactersInLiteral() throws Exception {
    // Test with escaped dot in literal
    String pattern = "(?=\\w+@).*@example\\.com";
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    PatternAnalyzer.StatelessPatternInfo info =
        (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;

    // The literal should have the actual dot character
    assertEquals("@example.com", info.literalSuffix);
    assertTrue(info.literalSuffix.contains("."));
  }

  @Test
  void testLookaheadLiteral_MinimumLiteralLength() throws Exception {
    // Literal suffix must be >= 3 characters
    String pattern = "(?=\\w+@).*@abc"; // Exactly 4 chars: "@abc"
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // Should be detected if >= 3 chars
    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
  }

  @Test
  void testBytecodeGeneration_NoExceptions() throws Exception {
    // Comprehensive test: ensure no exceptions during generation
    String[] patterns = {
      "(?=\\w+@).*@example\\.com",
      "(?=\\d+x).*xend",
      "(?=[a-z]+!).*!suffix",
      "(?=\\w+@).*@test\\.org",
      "(?=\\w+@).*@example\\.com\\.au"
    };

    for (String pattern : patterns) {
      assertDoesNotThrow(
          () -> generateBytecode(pattern), "Bytecode generation threw exception for: " + pattern);
    }
  }
}
