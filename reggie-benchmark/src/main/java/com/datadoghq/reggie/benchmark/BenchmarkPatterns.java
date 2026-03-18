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

/**
 * Shared pattern string constants for benchmarks. Used by all regex engines (Reggie, JDK, RE2J) to
 * ensure consistency.
 */
public final class BenchmarkPatterns {

  private BenchmarkPatterns() {} // utility class

  // Basic patterns (supported by all engines)
  public static final String PHONE = "\\d{3}-\\d{3}-\\d{4}";
  public static final String EMAIL = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
  public static final String DIGITS = "\\d+";
  public static final String LITERAL_HELLO = "hello";

  // Patterns for find operations
  public static final String WORD = "\\w+";
  public static final String NUMBER = "[0-9]+";

  // Group extraction patterns
  public static final String PHONE_GROUPS = "(\\d{3})-(\\d{3})-(\\d{4})";
  public static final String DATE = "(\\d{4})-(\\d{2})-(\\d{2})";
  public static final String NAME_VALUE = "(\\w+)=(\\w+)";

  // State explosion patterns (RE2J handles well, JDK may struggle)
  public static final String BACKTRACK_A = "a*a*a*a*b";
  public static final String NESTED_QUANTIFIER = "(a+)+b";
}
