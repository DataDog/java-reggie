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
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.List;
import java.util.Set;

/**
 * Pattern information for multiple lookaheads with extractable literals.
 *
 * <p>Used by the SPECIALIZED_LITERAL_LOOKAHEADS strategy to optimize lookahead evaluation using
 * indexOf() and character checks instead of full DFA simulation.
 *
 * <p>Example pattern: {@code (?=\w+@)(?=.*example).*@\w+\.com}
 *
 * <ul>
 *   <li>First lookahead {@code (?=\w+@)} extracts required char '@'
 *   <li>Second lookahead {@code (?=.*example)} extracts required substring "example"
 *   <li>Both can use indexOf() for 10-15x speedup over DFA simulation
 *   <li>Main pattern {@code .*@\w+\.com} compiled separately using optimal strategy
 * </ul>
 */
public class LiteralLookaheadPatternInfo implements PatternInfo {

  /** List of lookaheads with extractable literals, in order of appearance. */
  public final List<LiteralLookaheadInfo> lookaheads;

  /**
   * Set of required literal characters from the main pattern (not lookaheads). Used for additional
   * optimization after lookahead checks.
   */
  public final Set<Character> requiredLiterals;

  /**
   * Main pattern AST (pattern without leading assertions). Null if the pattern is only assertions.
   */
  public final RegexNode mainPatternAST;

  /** NFA for main pattern only (without assertions). Null if the pattern is only assertions. */
  public final NFA mainPatternNFA;

  /** Optimal strategy for executing the main pattern. Null if the pattern is only assertions. */
  public final PatternAnalyzer.MatchingStrategyResult mainPatternStrategy;

  /**
   * Creates pattern info for literal lookahead optimization.
   *
   * @param lookaheads list of lookaheads with extractable literals
   * @param requiredLiterals required characters from the main pattern
   */
  public LiteralLookaheadPatternInfo(
      List<LiteralLookaheadInfo> lookaheads, Set<Character> requiredLiterals) {
    this(lookaheads, requiredLiterals, null, null, null);
  }

  /**
   * Creates pattern info with separate main pattern strategy.
   *
   * @param lookaheads list of lookaheads with extractable literals
   * @param requiredLiterals required characters from the main pattern
   * @param mainPatternAST AST of main pattern (without assertions)
   * @param mainPatternNFA NFA of main pattern
   * @param mainPatternStrategy optimal strategy for main pattern
   */
  public LiteralLookaheadPatternInfo(
      List<LiteralLookaheadInfo> lookaheads,
      Set<Character> requiredLiterals,
      RegexNode mainPatternAST,
      NFA mainPatternNFA,
      PatternAnalyzer.MatchingStrategyResult mainPatternStrategy) {
    this.lookaheads = lookaheads;
    this.requiredLiterals = requiredLiterals;
    this.mainPatternAST = mainPatternAST;
    this.mainPatternNFA = mainPatternNFA;
    this.mainPatternStrategy = mainPatternStrategy;
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + lookaheads.size();
    hash = 31 * hash + requiredLiterals.size();
    hash = 31 * hash + (mainPatternStrategy != null ? mainPatternStrategy.strategy.hashCode() : 0);
    return hash;
  }

  @Override
  public String toString() {
    return "LiteralLookaheadPatternInfo{"
        + "lookaheads="
        + lookaheads.size()
        + ", requiredLiterals="
        + requiredLiterals
        + ", mainPatternStrategy="
        + (mainPatternStrategy != null ? mainPatternStrategy.strategy : "null")
        + '}';
  }
}
