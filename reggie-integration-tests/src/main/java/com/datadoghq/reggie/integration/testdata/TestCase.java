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
package com.datadoghq.reggie.integration.testdata;

import java.util.Set;

/** Represents a single regex test case with pattern, input, expected result, and metadata. */
public record TestCase(
    String pattern,
    String input,
    boolean shouldMatch,
    String source, // "RE2", "PCRE", "common", etc.
    Set<String> features // "lookahead", "backref", "quantifier", etc.
    ) {
  /** Check if Reggie supports all features required by this test case. */
  public boolean isSupported() {
    // Check features against known unsupported features
    // Backreferences ARE supported (via NFA)
    return !features.contains("conditional")
        && !features.contains("atomic_group")
        && !features.contains("possessive_quantifier")
        && !features.contains("unicode_property")
        && !features.contains("inline_flags");
  }

  /** Get feature category for reporting purposes. */
  public String getCategory() {
    if (features.contains("lookahead") || features.contains("lookbehind")) {
      return "Assertions";
    }
    if (features.contains("quantifier")) {
      return "Quantifiers";
    }
    return "Basic";
  }
}
