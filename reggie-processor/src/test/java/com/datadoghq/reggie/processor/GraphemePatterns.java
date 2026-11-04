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

import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

/** Compiled with the Java 21 toolchain and exercised on each test JVM. */
public abstract class GraphemePatterns implements ReggiePatterns {
  @RegexPattern("\\X")
  public abstract ReggieMatcher strict();

  @RegexPattern(value = "\\X", options = ReggieOption.ALLOW_JDK_FALLBACK)
  public abstract ReggieMatcher allowingFallback();
}
