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
package com.datadoghq.reggie.codegen.analysis.pbt;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Pattern generators for property-based testing of PatternAnalyzer routing.
 *
 * <p>Uses compositional approach: build complex patterns from simple building blocks. All
 * generators produce syntactically valid regex patterns.
 */
public class PatternArbitraries {

  // ============================================
  // ATOMIC GENERATORS
  // ============================================

  /** Generate simple literal strings. Examples: "a", "foo", "test" */
  public static Arbitrary<String> literal() {
    return Arbitraries.strings()
        .alpha()
        .numeric()
        .ofMinLength(1)
        .ofMaxLength(5)
        .map(PatternArbitraries::escapeSpecialChars);
  }

  /** Generate character classes. Examples: [a-z], [0-9], \d, \w, [^abc] */
  public static Arbitrary<String> charClass() {
    return Arbitraries.oneOf(
        Arbitraries.just("\\d"),
        Arbitraries.just("\\w"),
        Arbitraries.just("[a-z]"),
        Arbitraries.just("[0-9]"),
        Arbitraries.just("[A-Z]"),
        customCharClass());
  }

  /** Generate custom character classes with random characters. Examples: [abc], [^xyz], [a-zA-Z] */
  private static Arbitrary<String> customCharClass() {
    return Combinators.combine(
            Arbitraries.integers().between(0, 1), // negated?
            Arbitraries.integers().between(2, 4) // how many chars
            )
        .as(
            (negated, count) -> {
              String chars = generateRandomChars(count);
              return negated == 1 ? "[^" + chars + "]" : "[" + chars + "]";
            });
  }

  /** Generate a sequence of random alphabetic characters for char classes. */
  private static String generateRandomChars(int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      char c = (char) ('a' + (i * 3) % 26); // Deterministic but varied
      sb.append(c);
    }
    return sb.toString();
  }

  // ============================================
  // COMBINATORS
  // ============================================

  /** Add quantifiers to a pattern. Examples: a+, b*, c{2,5}, d? */
  public static Arbitrary<String> quantified(Arbitrary<String> basePattern) {
    Arbitrary<String> quantifier =
        Arbitraries.oneOf(
            Arbitraries.just("+"),
            Arbitraries.just("*"),
            Arbitraries.just("?"),
            Arbitraries.integers().between(2, 10).map(n -> "{" + n + "}"),
            Combinators.combine(
                    Arbitraries.integers().between(1, 5), Arbitraries.integers().between(6, 20))
                .as((min, max) -> "{" + min + "," + max + "}"));

    return Combinators.combine(basePattern, quantifier).as((base, quant) -> base + quant);
  }

  /** Create alternations. Examples: a|b, foo|bar|baz */
  public static Arbitrary<String> alternation(Arbitrary<String> elementPattern) {
    return elementPattern
        .list()
        .ofMinSize(2)
        .ofMaxSize(5)
        .map(alternatives -> String.join("|", alternatives));
  }

  /** Wrap in capturing group. Examples: (a+), (foo|bar) */
  public static Arbitrary<String> capturingGroup(Arbitrary<String> content) {
    return content.map(c -> "(" + c + ")");
  }

  // ============================================
  // CATEGORY-SPECIFIC GENERATORS
  // ============================================

  /**
   * Generate patterns with backreferences. Examples: (a)\1, (foo)\1, (\d)\1
   *
   * <p>Strategy: Generate a capturing group, then reference it
   */
  public static Arbitrary<String> withBackrefs() {
    // Simplified: generate a single group and reference it
    Arbitrary<String> groupContent = Arbitraries.oneOf(literal(), charClass());
    return groupContent.map(content -> "(" + content + ")\\1");
  }

  /**
   * Generate patterns with optional groups and quantified backreferences. Examples: (a|)\1+,
   * (foo|)\1{2}, (a)?\1
   *
   * <p>This is the pattern type that had the routing bug - CRITICAL for testing!
   */
  public static Arbitrary<String> withOptionalGroupBackrefs() {
    Arbitrary<String> optionalGroup =
        Arbitraries.oneOf(
            // Empty alternation: (a|), (foo|)
            literal().map(lit -> "(" + lit + "|)"),
            // Optional quantifier: (a)?
            literal().map(lit -> "(" + lit + ")?"));

    Arbitrary<String> backrefQuantifier =
        Arbitraries.oneOf(
            Arbitraries.just("+"),
            Arbitraries.just("*"),
            Arbitraries.integers().between(2, 5).map(n -> "{" + n + "}"));

    return Combinators.combine(optionalGroup, backrefQuantifier)
        .as((group, quant) -> group + "\\1" + quant);
  }

  /**
   * Generate patterns with context-free features (subroutines, conditionals). Examples: (?R),
   * a(?1)b, (?(1)yes|no)
   *
   * <p>Note: These are limited to known valid patterns since context-free syntax is complex and
   * error-prone to generate randomly.
   */
  public static Arbitrary<String> contextFree() {
    return Arbitraries.oneOf(
        Arbitraries.just("(?R)"), // Recursive subroutine
        Arbitraries.just("a(?1)b"), // Numbered subroutine
        Arbitraries.just("(a)(?(1)b|c)"), // Conditional
        Arbitraries.just("(?|a|b)") // Branch reset
        );
  }

  /**
   * Generate patterns that create large DFA state spaces (>300 states). Examples: (a|b|c){50},
   * (a|b|c|d|e){75}
   *
   * <p>Strategy: Large alternations with high repetition counts
   */
  public static Arbitrary<String> largeStateSpace() {
    return Combinators.combine(
            Arbitraries.integers().between(3, 6), // alternation size
            Arbitraries.integers().between(50, 100) // repetition count
            )
        .as(
            (altSize, repCount) -> {
              // Generate altSize alternatives: a|b|c|...
              StringBuilder alt = new StringBuilder("(");
              for (int i = 0; i < altSize; i++) {
                if (i > 0) alt.append("|");
                alt.append((char) ('a' + i));
              }
              alt.append(")");

              // Add repetition: (a|b|c){50}
              return alt.toString() + "{" + repCount + "}";
            });
  }

  // ============================================
  // UTILITY
  // ============================================

  /**
   * Escape special regex characters in literals. Ensures generated literals don't accidentally
   * include regex metacharacters.
   */
  private static String escapeSpecialChars(String s) {
    return s.replaceAll("([.*+?^${}()\\[\\]\\\\|])", "\\\\$1");
  }
}
