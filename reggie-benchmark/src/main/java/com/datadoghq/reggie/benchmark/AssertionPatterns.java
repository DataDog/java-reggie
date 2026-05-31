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
 * Defines regex patterns with assertions for benchmarking. Use {@link
 * com.datadoghq.reggie.Reggie#patterns(Class)} to obtain an instance.
 */
public abstract class AssertionPatterns implements ReggiePatterns {

  @RegexPattern("a(?=bc)")
  public abstract ReggieMatcher positiveLookahead();

  @RegexPattern("a(?!bc)")
  public abstract ReggieMatcher negativeLookahead();

  @RegexPattern("(?<=ab)c")
  public abstract ReggieMatcher positiveLookbehind();

  @RegexPattern("(?<!ab)c")
  public abstract ReggieMatcher negativeLookbehind();

  // Uses runtime compilation: multiple lookaheads resolve to SPECIALIZED_MULTIPLE_LOOKAHEADS,
  // a FULL_FALLBACK strategy that cannot be compiled at annotation-processing time.
  // Reggie.compile()
  // routes it to java.util.regex at runtime (the correct path).
  public ReggieMatcher passwordValidation() {
    return PASSWORD_VALIDATION;
  }

  @RegexPattern("a(?=bc)bc")
  public abstract ReggieMatcher prototypePattern();

  // Uses runtime compilation: multiple lookaheads (SPECIALIZED_MULTIPLE_LOOKAHEADS, FULL_FALLBACK).
  public ReggieMatcher minLengthFive() {
    return MIN_LENGTH_FIVE;
  }

  // DFA-friendly: fixed-width character class assertions
  @RegexPattern("(?<=[A-Z])c")
  public abstract ReggieMatcher charClassLookbehind();

  @RegexPattern("(?=[0-9])")
  public abstract ReggieMatcher charClassLookahead();

  // Uses runtime compilation: variable-width lookahead resolves to HYBRID_DFA_LOOKAHEAD, a
  // FULL_FALLBACK strategy that cannot be compiled at annotation-processing time.
  public ReggieMatcher variableWidthLookahead() {
    return VARIABLE_WIDTH_LOOKAHEAD;
  }

  // Uses runtime compilation: alternation inside lookbehind triggers automatic fallback
  // to java.util.regex and cannot be compiled at annotation-processing time.
  public ReggieMatcher alternationLookbehind() {
    return ALT_LOOKBEHIND;
  }

  private static final ReggieMatcher PASSWORD_VALIDATION =
      Reggie.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}");
  private static final ReggieMatcher MIN_LENGTH_FIVE = Reggie.compile("(?=.*[A-Z])(?=.*\\d).{5,}");
  private static final ReggieMatcher VARIABLE_WIDTH_LOOKAHEAD = Reggie.compile("(?=\\w*\\d)");
  private static final ReggieMatcher ALT_LOOKBEHIND = Reggie.compile("(?<=a|b)c");
}
