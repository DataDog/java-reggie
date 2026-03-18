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

  @RegexPattern("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}")
  public abstract ReggieMatcher passwordValidation();

  @RegexPattern("a(?=bc)bc")
  public abstract ReggieMatcher prototypePattern();

  @RegexPattern("(?=.*[A-Z])(?=.*\\d).{5,}")
  public abstract ReggieMatcher minLengthFive();

  // DFA-friendly: fixed-width character class assertions
  @RegexPattern("(?<=[A-Z])c")
  public abstract ReggieMatcher charClassLookbehind();

  @RegexPattern("(?=[0-9])")
  public abstract ReggieMatcher charClassLookahead();

  // Complex patterns that may force NFA fallback
  @RegexPattern("(?=\\w*\\d)")
  public abstract ReggieMatcher variableWidthLookahead();

  @RegexPattern("(?<=a|b)c")
  public abstract ReggieMatcher alternationLookbehind();
}
