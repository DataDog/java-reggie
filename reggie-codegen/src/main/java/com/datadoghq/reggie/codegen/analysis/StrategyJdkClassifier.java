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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;

/**
 * Authoritative, single-source-of-truth classification of every {@link MatchingStrategy} by its
 * dependency on {@code java.util.regex} at runtime.
 *
 * <p>The three classes are:
 *
 * <ul>
 *   <li>{@link StrategyJdkClass#NATIVE} — booleans <em>and</em> the rich MatchResult API ({@code
 *       match}/{@code findMatch}/{@code findMatchFrom}) are fully generated; the matcher never
 *       touches {@code java.util.regex} at runtime.
 *   <li>{@link StrategyJdkClass#RICH_API_HYBRID} — the boolean fast path ({@code matches}/{@code
 *       find}/{@code findFrom}) is generated, but the rich MatchResult API is inherited from the
 *       JDK-backed base defaults in {@code ReggieMatcher}, so group extraction delegates to a
 *       lazily-compiled {@code java.util.regex.Pattern}.
 *   <li>{@link StrategyJdkClass#FULL_FALLBACK} — the runtime compiler routes the whole matcher to
 *       {@code JavaRegexFallbackMatcher} (every method delegates to {@code java.util.regex}).
 * </ul>
 *
 * <p>The classification was determined by reading the strategy switch in {@code
 * RuntimeCompiler.compileInternal} (which strategies are routed to {@code
 * JavaRegexFallbackMatcher}) and the per-strategy method emission in {@code
 * RuntimeCompiler.generateBytecode}: a strategy is {@code NATIVE} only if its generator emits its
 * own {@code generateMatchMethod}/{@code generateFindMatchMethod}/{@code
 * generateFindMatchFromMethod}; {@code RICH_API_HYBRID} if it emits the booleans but not those rich
 * methods (inheriting the base JDK-backed defaults).
 *
 * <p>Currently no strategy is classified as {@code RICH_API_HYBRID}: both former hybrids ({@code
 * SPECIALIZED_LITERAL_ALTERNATION} and {@code FIXED_REPETITION_BACKREF}) have been promoted to
 * {@code NATIVE} after their rich MatchResult methods were implemented.
 *
 * <p>This classification reflects the <strong>runtime path</strong> ({@code Reggie.compile()}). On
 * the compile-time annotation-processor path the {@code FULL_FALLBACK} set is rejected: {@code
 * ReggieMatcherBytecodeGenerator.generate()} throws {@code UnsupportedOperationException} for every
 * {@code FULL_FALLBACK} strategy (alongside the AST-level fallbacks), which {@code
 * RegexPatternProcessor} surfaces as a build error. A fixed {@code @RegexPattern} class cannot fall
 * back to {@code java.util.regex} at runtime, so the (known-incorrect) native bytecode is never
 * emitted; {@code Reggie.compile()} must be used for those patterns instead.
 */
public final class StrategyJdkClassifier {

  private StrategyJdkClassifier() {}

  /** JDK-dependency class of a {@link MatchingStrategy}. */
  public enum StrategyJdkClass {
    /** Booleans and rich API fully generated; no {@code java.util.regex} at runtime. */
    NATIVE,
    /** Native booleans, JDK-backed rich API (group extraction). */
    RICH_API_HYBRID,
    /** Entire matcher delegates to {@code java.util.regex}. */
    FULL_FALLBACK
  }

  /**
   * Classifies a {@link MatchingStrategy} by its runtime dependency on {@code java.util.regex}.
   *
   * @param strategy the matching strategy
   * @return the JDK-dependency class; never null
   */
  public static StrategyJdkClass classifyJdkDependency(MatchingStrategy strategy) {
    switch (strategy) {
      // FULL_FALLBACK: RuntimeCompiler returns JavaRegexFallbackMatcher for these strategies.
      // Lookahead boolean-engine defects (lookaheadBooleanEngineDefectReason):
      case SPECIALIZED_MULTIPLE_LOOKAHEADS:
      case SPECIALIZED_LITERAL_LOOKAHEADS:
      case HYBRID_DFA_LOOKAHEAD:
      // Incomplete MatchResult API (incompleteMatchResultApiReason):
      case VARIABLE_CAPTURE_BACKREF:
      case NESTED_QUANTIFIED_GROUPS:
        return StrategyJdkClass.FULL_FALLBACK;

      // NATIVE: generator emits its own match/findMatch/findMatchFrom (correct rich API).
      default:
        return StrategyJdkClass.NATIVE;
    }
  }

  /**
   * Returns a human-readable reason string for {@link StrategyJdkClass#RICH_API_HYBRID} strategies,
   * suitable for the loud "uses java.util.regex under the hood" warning, or {@code null} for
   * non-hybrid strategies.
   *
   * @param strategy the matching strategy
   * @return the hybrid reason, or null if the strategy is not RICH_API_HYBRID
   */
  public static String richApiHybridReason(MatchingStrategy strategy) {
    if (classifyJdkDependency(strategy) != StrategyJdkClass.RICH_API_HYBRID) {
      return null;
    }
    return "native boolean matching but group extraction (match/findMatch) delegates to"
        + " java.util.regex (strategy "
        + strategy
        + ")";
  }
}
