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

/** Represents a regex replacement/substitution test case. */
public record ReplacementTest(
    String pattern,
    String input,
    String replacement,
    String expectedOutput,
    boolean global, // true for replaceAll, false for replaceFirst
    String source,
    java.util.Set<String> features) {
  public boolean isSupported() {
    // Check if Reggie supports all features
    // Backreferences ARE supported (via NFA)
    return !features.contains("conditional")
        && !features.contains("atomic_group")
        && !features.contains("possessive_quantifier")
        && !features.contains("unicode_property")
        && !features.contains("inline_flags")
        && !features.contains("case_conversion"); // \U, \L modifiers
  }
}
