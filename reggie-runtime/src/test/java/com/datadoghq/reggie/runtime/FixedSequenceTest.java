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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Test specialized bytecode generation for fixed-length sequence patterns. */
public class FixedSequenceTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testPhoneNumberMatches() {
    // Pattern: \d{3}-\d{3}-\d{4} (12 chars fixed)
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");

    // Should match valid phone numbers
    assertTrue(matcher.matches("123-456-7890"), "Should match valid phone number");
    assertTrue(matcher.matches("000-000-0000"), "Should match all zeros");
    assertTrue(matcher.matches("999-999-9999"), "Should match all nines");

    // Should not match invalid formats
    assertFalse(matcher.matches("12-456-7890"), "Should not match wrong first group");
    assertFalse(matcher.matches("123-45-7890"), "Should not match wrong second group");
    assertFalse(matcher.matches("123-456-789"), "Should not match wrong third group");
    assertFalse(matcher.matches("123-456-78901"), "Should not match too long");
    assertFalse(matcher.matches("abc-def-ghij"), "Should not match letters");
    assertFalse(matcher.matches(""), "Should not match empty string");
  }

  @Test
  public void testDateMatches() {
    // Pattern: \d{4}-\d{2}-\d{2} (10 chars fixed)
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{4}-\\d{2}-\\d{2}");

    // Should match valid dates
    assertTrue(matcher.matches("2025-12-26"), "Should match valid date");
    assertTrue(matcher.matches("2000-01-01"), "Should match start of millennium");
    assertTrue(matcher.matches("9999-99-99"), "Should match all nines");

    // Should not match invalid formats
    assertFalse(matcher.matches("25-12-26"), "Should not match short year");
    assertFalse(matcher.matches("2025-2-26"), "Should not match short month");
    assertFalse(matcher.matches("2025-12-6"), "Should not match short day");
    assertFalse(matcher.matches("2025-12-266"), "Should not match too long");
    assertFalse(matcher.matches("abcd-ef-gh"), "Should not match letters");
  }

  @Test
  public void testSimpleSequence() {
    // Pattern: abc (3 chars fixed - literals)
    ReggieMatcher matcher = RuntimeCompiler.compile("abc");

    // Should match exact string
    assertTrue(matcher.matches("abc"), "Should match exact string");

    // Should not match other strings
    assertFalse(matcher.matches("ab"), "Should not match prefix");
    assertFalse(matcher.matches("abcd"), "Should not match with suffix");
    assertFalse(matcher.matches("ABC"), "Should not match different case");
    assertFalse(matcher.matches(""), "Should not match empty");
  }

  @Test
  public void testSingleFixedElement() {
    // Pattern: \d{10} (single element, 10 chars)
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{10}");

    // Should match 10 digits
    assertTrue(matcher.matches("1234567890"), "Should match 10 digits");
    assertTrue(matcher.matches("0000000000"), "Should match all zeros");

    // Should not match wrong length
    assertFalse(matcher.matches("123456789"), "Should not match 9 digits");
    assertFalse(matcher.matches("12345678901"), "Should not match 11 digits");
    assertFalse(matcher.matches("abc1234567"), "Should not match with letters");
  }
}
