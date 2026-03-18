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

import static com.datadoghq.reggie.benchmark.BenchmarkPatterns.*;

import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

/**
 * Example patterns to demonstrate the annotation processor. Use {@link
 * com.datadoghq.reggie.Reggie#patterns(Class)} to obtain an instance.
 */
public abstract class ExamplePatterns implements ReggiePatterns {

  @RegexPattern(PHONE)
  public abstract ReggieMatcher phone();

  @RegexPattern(EMAIL)
  public abstract ReggieMatcher email();

  @RegexPattern(LITERAL_HELLO)
  public abstract ReggieMatcher hello();

  @RegexPattern(DIGITS)
  public abstract ReggieMatcher digits();
}
