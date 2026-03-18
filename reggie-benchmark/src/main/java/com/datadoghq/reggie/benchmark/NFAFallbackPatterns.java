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

  @RegexPattern("(\\w+)\\s+\\1")
  public abstract ReggieMatcher duplicateWord();

  @RegexPattern("([a-z]{3}).*\\1")
  public abstract ReggieMatcher backrefWithContent();

  @RegexPattern("(a+)\\1")
  public abstract ReggieMatcher repeatedSequence();

  // ====================
  // VARIABLE-LENGTH QUANTIFIERS IN GROUPS (forces NFA for group tracking)
  // ====================

  @RegexPattern("([a-z]+)@([a-z]+)\\.com")
  public abstract ReggieMatcher emailWithGroups();

  @RegexPattern("(\\d{3})-(\\d+)-(\\d{4})")
  public abstract ReggieMatcher phoneWithVariableLength();

  @RegexPattern("(<\\w+>).*?(</\\w+>)")
  public abstract ReggieMatcher xmlTags();

  // ====================
  // COMPLEX ASSERTIONS (forces NFA)
  // These assertions contain quantifiers that make them complex
  // ====================

  @RegexPattern("(?=.*\\d{3})\\w+")
  public abstract ReggieMatcher lookaheadWithQuantifier();

  @RegexPattern("(?=\\w+@).*@example\\.com")
  public abstract ReggieMatcher lookaheadWithPlusQuantifier();

  // Pattern without literal suffix - prevents Boyer-Moore optimization in JDK
  @RegexPattern("(?=\\w+@).*@\\w+\\.\\w+")
  public abstract ReggieMatcher lookaheadNoBoyerMoore();

  // Pattern with multiple lookaheads - forces NFA, avoids STATELESS_LOOP detection
  // Has ≤64 states but requires state tracking due to complexity
  @RegexPattern("(?=\\w+@)(?=.*example).*@\\w+\\.com")
  public abstract ReggieMatcher multipleLookaheads();

  // ====================
  // POTENTIAL STATE EXPLOSION PATTERNS
  // These might force NFA if DFA state count exceeds thresholds
  // ====================

  @RegexPattern("(a|b|c|d|e|f|g|h|i|j)*(x|y|z)")
  public abstract ReggieMatcher largeAlternationWithStar();

  @RegexPattern("[a-z]*[0-9]*[A-Z]*[!@#$]*")
  public abstract ReggieMatcher multipleCharClassStars();

  // Exponential blowup: (a|ab)* can cause DFA state explosion
  @RegexPattern("(a|ab|abc|abcd|abcde)*x")
  public abstract ReggieMatcher overlappingAlternation();

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
}
