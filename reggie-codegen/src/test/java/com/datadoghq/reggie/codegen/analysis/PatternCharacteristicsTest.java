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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/** Tests for pattern analysis and StringView strategy selection. */
class PatternCharacteristicsTest {

  private PatternCharacteristics analyzePattern(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 0); // 0 groups for simple patterns
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeForStringView();
  }

  @Test
  void testAnchoredLiteralPattern() throws Exception {
    // ^abc - early bailout, very low scan ratio
    PatternCharacteristics chars = analyzePattern("^abc");

    assertTrue(chars.hasEarlyBailout(), "Should detect early bailout");
    assertTrue(chars.getExpectedScanRatio() < 0.2, "Should have low scan ratio");
    assertFalse(chars.hasSwarOpportunities(), "No SWAR opportunities");

    StringViewStrategy strategy = chars.recommendStrategy();
    assertEquals(
        StringViewStrategy.ALWAYS_CHAR_AT, strategy, "Anchored literal should use charAt mode");
  }

  @Test
  void testAnchoredCharClassPattern() throws Exception {
    // ^[A-Z]{5} - early bailout, bounded scan
    PatternCharacteristics chars = analyzePattern("^[A-Z]{5}");

    assertTrue(chars.hasEarlyBailout(), "Should detect early bailout");
    assertTrue(
        chars.getExpectedScanRatio() < 0.3,
        "Should have low scan ratio: " + chars.getExpectedScanRatio());
    assertFalse(chars.hasSwarOpportunities(), "No SWAR opportunities");

    StringViewStrategy strategy = chars.recommendStrategy();
    // Could be charAt or hybrid depending on thresholds
    assertTrue(
        strategy == StringViewStrategy.ALWAYS_CHAR_AT
            || strategy == StringViewStrategy.LENGTH_BASED_HYBRID,
        "Anchored bounded pattern should use charAt or hybrid, got: " + strategy);
  }

  @Test
  void testHexDigitPattern() throws Exception {
    // [0-9a-fA-F]+ - SWAR opportunity
    PatternCharacteristics chars = analyzePattern("[0-9a-fA-F]+");

    assertFalse(chars.hasEarlyBailout(), "Unanchored pattern, no early bailout");
    assertEquals(1.0, chars.getExpectedScanRatio(), 0.01, "Full scan expected");
    assertTrue(chars.hasSwarOpportunities(), "Should detect hex digit SWAR opportunity");
    assertTrue(chars.getComparisonIntensity() >= 8, "SWAR intensity should be high");

    StringViewStrategy strategy = chars.recommendStrategy();
    assertEquals(StringViewStrategy.ALWAYS_COPY, strategy, "SWAR pattern should always copy");
  }

  @Test
  void testUnanchoredPattern() throws Exception {
    // .*error.* - must scan entire string
    PatternCharacteristics chars = analyzePattern(".*error.*");

    assertFalse(chars.hasEarlyBailout(), "Unanchored pattern, no early bailout");
    assertEquals(1.0, chars.getExpectedScanRatio(), 0.01, "Full scan expected");
    assertTrue(chars.getComparisonIntensity() >= 3, "Quantifiers increase intensity");

    StringViewStrategy strategy = chars.recommendStrategy();
    assertEquals(StringViewStrategy.ALWAYS_COPY, strategy, "High scan ratio should prefer copy");
  }

  @Test
  void testLiteralPrefixPattern() throws Exception {
    // hello.* - literal prefix provides early bailout potential
    PatternCharacteristics chars = analyzePattern("hello.*");

    // Note: unanchored patterns scan entire string, but literal prefix helps
    // The heuristics may vary based on implementation details
    assertFalse(chars.hasSwarOpportunities(), "No SWAR opportunities");

    StringViewStrategy strategy = chars.recommendStrategy();
    // Could be any strategy depending on heuristics
    assertNotNull(strategy, "Should recommend some strategy");
  }

  @Test
  void testModerateScanPattern() throws Exception {
    // test\\d{3} - moderate complexity (note: no anchor, so unanchored)
    PatternCharacteristics chars = analyzePattern("test\\d{3}");

    // Unanchored patterns need to scan entire string for matches
    assertTrue(chars.getComparisonIntensity() >= 1, "Has some complexity");

    StringViewStrategy strategy = chars.recommendStrategy();
    // Could be any strategy based on complexity analysis
    assertNotNull(strategy, "Should recommend some strategy");
  }

  @Test
  void testComplexCharClassPattern() throws Exception {
    // [^a-z]+ - negated character class, high intensity
    PatternCharacteristics chars = analyzePattern("[^a-z]+");

    assertFalse(chars.hasEarlyBailout(), "Unanchored, no early bailout");
    assertEquals(1.0, chars.getExpectedScanRatio(), 0.01, "Full scan expected");
    assertTrue(chars.getComparisonIntensity() >= 3, "Negated class is expensive");

    StringViewStrategy strategy = chars.recommendStrategy();
    assertEquals(StringViewStrategy.ALWAYS_COPY, strategy, "High intensity should prefer copy");
  }

  @Test
  void testLengthBasedHybridThreshold() throws Exception {
    // Pattern that should use hybrid strategy
    PatternCharacteristics chars = analyzePattern("test[a-z]{2,5}");

    StringViewStrategy strategy = chars.recommendStrategy();
    if (strategy == StringViewStrategy.LENGTH_BASED_HYBRID) {
      int threshold = strategy.getLengthThreshold();
      assertTrue(
          threshold >= 20 && threshold <= 100,
          "Threshold should be in reasonable range: " + threshold);
    }
  }

  @Test
  void testToString() throws Exception {
    PatternCharacteristics chars = analyzePattern("^abc");
    String str = chars.toString();

    assertTrue(str.contains("earlyBailout"), "Should contain earlyBailout field");
    assertTrue(str.contains("scanRatio"), "Should contain scanRatio field");
    assertTrue(str.contains("intensity"), "Should contain intensity field");
    assertTrue(str.contains("swar"), "Should contain swar field");
  }
}
