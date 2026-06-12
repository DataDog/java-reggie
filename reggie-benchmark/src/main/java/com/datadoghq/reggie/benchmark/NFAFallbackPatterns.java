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
package com.datadoghq.reggie.benchmark;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

/**
 * Patterns that should force NFA fallback instead of DFA. Tests the boundary conditions where DFA
 * optimization cannot be applied.
 */
public abstract class NFAFallbackPatterns implements ReggiePatterns {

  // ====================
  // BACKREFERENCES (always NFA)
  // ====================

  // Uses runtime compilation: variable-length capture + backref resolves to
  // VARIABLE_CAPTURE_BACKREF, a FULL_FALLBACK strategy that cannot be compiled at
  // annotation-processing time. Reggie.compile() routes it to java.util.regex at runtime.
  public ReggieMatcher duplicateWord() {
    return DUPLICATE_WORD;
  }

  // Uses runtime compilation: capture-ambiguous group spans cannot be determined at
  // annotation-processing time. Reggie.compile() routes to java.util.regex at runtime.
  private static final ReggieMatcher BACKREF_WITH_CONTENT = Reggie.compile("([a-z]{3}).*\\1");

  public ReggieMatcher backrefWithContent() {
    return BACKREF_WITH_CONTENT;
  }

  // Uses runtime compilation: VARIABLE_CAPTURE_BACKREF (FULL_FALLBACK).
  public ReggieMatcher repeatedSequence() {
    return REPEATED_SEQUENCE;
  }

  // ====================
  // VARIABLE-LENGTH QUANTIFIERS IN GROUPS (forces NFA for group tracking)
  // ====================

  @RegexPattern("([a-z]+)@([a-z]+)\\.com")
  public abstract ReggieMatcher emailWithGroups();

  @RegexPattern("(\\d{3})-(\\d+)-(\\d{4})")
  public abstract ReggieMatcher phoneWithVariableLength();

  // PIKEVM_CAPTURE: processor generates a delegating stub that calls compilePikeVm() at runtime.
  @RegexPattern("(<\\w+>).*(</\\w+>)")
  public abstract ReggieMatcher xmlTags();

  // ====================
  // COMPLEX ASSERTIONS (forces NFA)
  // These assertions contain quantifiers that make them complex
  // ====================

  // Uses runtime compilation: lookahead with quantifier resolves to HYBRID_DFA_LOOKAHEAD, a
  // FULL_FALLBACK strategy that cannot be compiled at annotation-processing time.
  public ReggieMatcher lookaheadWithQuantifier() {
    return LOOKAHEAD_WITH_QUANTIFIER;
  }

  @RegexPattern("(?=\\w+@).*@example\\.com")
  public abstract ReggieMatcher lookaheadWithPlusQuantifier();

  // Pattern without literal suffix - prevents Boyer-Moore optimization in JDK.
  // Uses runtime compilation: HYBRID_DFA_LOOKAHEAD (FULL_FALLBACK).
  public ReggieMatcher lookaheadNoBoyerMoore() {
    return LOOKAHEAD_NO_BOYER_MOORE;
  }

  // Pattern with multiple lookaheads. Uses runtime compilation: resolves to
  // SPECIALIZED_LITERAL_LOOKAHEADS, a FULL_FALLBACK strategy that cannot be compiled at
  // annotation-processing time.
  public ReggieMatcher multipleLookaheads() {
    return MULTIPLE_LOOKAHEADS;
  }

  // ====================
  // POTENTIAL STATE EXPLOSION PATTERNS
  // These might force NFA if DFA state count exceeds thresholds
  // ====================

  // Capture-ambiguous at compile time (group in * quantifier). Runtime compilation routes to JDK.
  private static final ReggieMatcher LARGE_ALTERNATION_WITH_STAR =
      Reggie.compile("(a|b|c|d|e|f|g|h|i|j)*(x|y|z)");

  public ReggieMatcher largeAlternationWithStar() {
    return LARGE_ALTERNATION_WITH_STAR;
  }

  @RegexPattern("[a-z]*[0-9]*[A-Z]*[!@#$]*")
  public abstract ReggieMatcher multipleCharClassStars();

  // Exponential blowup: (a|ab)* can cause DFA state explosion
  // Capture-ambiguous at compile time (group in * quantifier). Runtime compilation routes to JDK.
  private static final ReggieMatcher OVERLAPPING_ALTERNATION =
      Reggie.compile("(a|ab|abc|abcd|abcde)*x");

  public ReggieMatcher overlappingAlternation() {
    return OVERLAPPING_ALTERNATION;
  }

  // ====================
  // SHOULD STILL USE DFA (for comparison)
  // These patterns should NOT fall back to NFA
  // ====================

  @RegexPattern("([a-z]{3})@([a-z]{3})\\.com")
  public abstract ReggieMatcher fixedLengthGroups();

  @RegexPattern("(?<=[0-9]{3})x")
  public abstract ReggieMatcher fixedLengthLookbehind();

  @RegexPattern("a{5,10}b{3,7}")
  public abstract ReggieMatcher boundedQuantifiers();

  // Runtime-compiled matchers for FULL_FALLBACK patterns (see methods above). These cannot be
  // generated at annotation-processing time, so they go through Reggie.compile()'s runtime path,
  // which delegates to java.util.regex — preserving each benchmark's intended pattern.
  private static final ReggieMatcher XML_TAGS = Reggie.compile("(<\\w+>).*(</\\w+>)");
  private static final ReggieMatcher DUPLICATE_WORD = Reggie.compile("(\\w+)\\s+\\1");
  private static final ReggieMatcher REPEATED_SEQUENCE = Reggie.compile("(a+)\\1");
  private static final ReggieMatcher LOOKAHEAD_WITH_QUANTIFIER = Reggie.compile("(?=.*\\d{3})\\w+");
  private static final ReggieMatcher LOOKAHEAD_NO_BOYER_MOORE =
      Reggie.compile("(?=\\w+@).*@\\w+\\.\\w+");
  private static final ReggieMatcher MULTIPLE_LOOKAHEADS =
      Reggie.compile("(?=\\w+@)(?=.*example).*@\\w+\\.com");
}
