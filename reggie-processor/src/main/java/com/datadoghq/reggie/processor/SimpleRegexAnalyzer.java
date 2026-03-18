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
package com.datadoghq.reggie.processor;

/**
 * Analyzes regex patterns to determine the best matching strategy. For PoC, this provides basic
 * pattern classification.
 */
public class SimpleRegexAnalyzer {
  private final String pattern;
  private boolean isLiteral;
  private String literalValue;
  private boolean isSimple;

  public SimpleRegexAnalyzer(String pattern) {
    this.pattern = pattern;
    analyze();
  }

  private void analyze() {
    // Check if it's a literal string (no regex metacharacters)
    if (!containsMetacharacters(pattern)) {
      this.isLiteral = true;
      this.literalValue = pattern;
      this.isSimple = true;
      return;
    }

    // Check for simple patterns we can optimize
    // For PoC: \d{n} patterns, literal strings with simple quantifiers
    this.isSimple = isSimplePattern(pattern);
  }

  private boolean containsMetacharacters(String s) {
    String metachars = ".^$*+?{}[]()\\|";
    for (char c : s.toCharArray()) {
      if (metachars.indexOf(c) >= 0) {
        return true;
      }
    }
    return false;
  }

  private boolean isSimplePattern(String pattern) {
    // Simple patterns for PoC:
    // - \d{n} (fixed digit count)
    // - \d{n}-\d{m}-... (phone numbers, etc.)
    // - [a-z]+ (character classes with simple quantifiers)
    // - literal1|literal2|... (alternation of literals)

    // Phone-like patterns: \d{3}-\d{3}-\d{4}
    if (pattern.matches("\\\\d\\{\\d+\\}(-\\\\d\\{\\d+\\})*")) {
      return true;
    }

    // Email-like pattern (very simplified)
    if (pattern.matches("[a-zA-Z0-9._%-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) {
      return true;
    }

    return false;
  }

  public boolean isLiteral() {
    return isLiteral;
  }

  public String getLiteralValue() {
    return literalValue;
  }

  public boolean isSimplePattern() {
    return isSimple && !isLiteral;
  }

  public String getPattern() {
    return pattern;
  }

  /**
   * Estimates the number of DFA states this pattern would generate. Used to decide between Pure
   * DFA, Lazy DFA, or Thompson NFA.
   */
  public int estimateDFAStates() {
    // Very rough estimation for PoC
    if (isLiteral) return pattern.length();

    // Count special constructs that increase state count
    int states = 0;
    boolean inCharClass = false;
    boolean escaped = false;

    for (char c : pattern.toCharArray()) {
      if (escaped) {
        escaped = false;
        states += 2;
        continue;
      }

      switch (c) {
        case '\\':
          escaped = true;
          break;
        case '[':
          inCharClass = true;
          states += 5; // Character class adds states
          break;
        case ']':
          inCharClass = false;
          break;
        case '*':
        case '+':
          states += 10; // Kleene closure significantly increases states
          break;
        case '|':
          states += 20; // Alternation can double states
          break;
        case '(':
          states += 3; // Groups add some states
          break;
        default:
          if (!inCharClass) {
            states += 1;
          }
      }
    }

    return Math.max(states, pattern.length());
  }

  /** Determines if the pattern has backreferences (requires NFA). */
  public boolean hasBackreferences() {
    // Check for \1, \2, etc.
    return pattern.matches(".*\\\\\\d+.*");
  }

  /** Determines if the pattern has lookahead/lookbehind (requires NFA). */
  public boolean hasLookaround() {
    return pattern.contains("(?=")
        || pattern.contains("(?!")
        || pattern.contains("(?<=")
        || pattern.contains("(?<!");
  }

  /** Recommends the matching strategy based on pattern analysis. */
  public MatchingStrategy recommendStrategy() {
    if (hasBackreferences() || hasLookaround()) {
      return MatchingStrategy.THOMPSON_NFA;
    }

    int estimatedStates = estimateDFAStates();
    if (estimatedStates < 50) {
      return MatchingStrategy.PURE_DFA;
    } else if (estimatedStates < 500) {
      return MatchingStrategy.LAZY_DFA;
    } else {
      return MatchingStrategy.THOMPSON_NFA;
    }
  }

  public enum MatchingStrategy {
    PURE_DFA, // Direct state machine bytecode
    LAZY_DFA, // On-demand state construction
    THOMPSON_NFA // Non-backtracking NFA simulation
  }
}
