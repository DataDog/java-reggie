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

/**
 * Tests for bounded quantifier sequence patterns. Examples: IPv4, credit cards, MAC addresses,
 * UUIDs
 */
public class BoundedQuantifierTest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ========== IPv4 Address Tests ==========

  @Test
  public void testIPv4ValidAddresses() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    assertTrue(matcher.matches("192.168.1.1"), "Should match valid IPv4");
    assertTrue(matcher.matches("0.0.0.0"), "Should match all zeros");
    assertTrue(matcher.matches("255.255.255.255"), "Should match all 255s");
    assertTrue(matcher.matches("10.0.0.1"), "Should match private IP");
    assertTrue(matcher.matches("1.2.3.4"), "Should match single digit octets");
  }

  @Test
  public void testIPv4InvalidAddresses() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    // NOTE: This pattern matches the FORMAT of IPv4 addresses, not their validity.
    // It will match "256.1.1.1" because 256 is 3 digits. To validate ranges (0-255),
    // you'd need a more complex pattern like: (?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)
    assertTrue(
        matcher.matches("256.1.1.1"),
        "Should match (256 is 3 digits, pattern doesn't validate ranges)");
    assertFalse(matcher.matches("1.2.3"), "Should not match incomplete address");
    assertFalse(matcher.matches("1.2.3.4.5"), "Should not match too many octets");
    assertFalse(matcher.matches("1.2.3.a"), "Should not match non-digits");
    assertFalse(matcher.matches(""), "Should not match empty string");
  }

  @Test
  public void testIPv4FindInText() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    assertTrue(matcher.find("Server IP is 192.168.1.1 on port 80"), "Should find IPv4 in text");

    MatchResult result = matcher.findMatch("Connect to 10.0.0.1 for access");
    assertNotNull(result, "Should find IPv4 match");
    assertEquals("10.0.0.1", result.group(), "Should extract correct IPv4");
    assertEquals(11, result.start(), "Should have correct start");
    assertEquals(19, result.end(), "Should have correct end");
  }

  // ========== Credit Card Tests ==========

  @Test
  public void testCreditCardWithOptionalSeparators() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}");

    assertTrue(matcher.matches("1234567890123456"), "Should match no separators");
    assertTrue(matcher.matches("1234-5678-9012-3456"), "Should match hyphen separators");
    assertTrue(matcher.matches("1234 5678 9012 3456"), "Should match space separators");
    assertTrue(matcher.matches("1234-5678 9012-3456"), "Should match mixed separators");
  }

  @Test
  public void testCreditCardInvalid() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}");

    assertFalse(matcher.matches("123456789012345"), "Should not match 15 digits");
    assertFalse(matcher.matches("12345678901234567"), "Should not match 17 digits");
    assertFalse(matcher.matches("1234-5678-9012-345a"), "Should not match non-digits");
  }

  // ========== MAC Address Tests ==========

  @Test
  public void testMACAddressValid() {
    ReggieMatcher matcher =
        RuntimeCompiler.compile(
            "[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}");

    assertTrue(matcher.matches("00:1B:44:11:3A:B7"), "Should match uppercase MAC");
    assertTrue(matcher.matches("00:1b:44:11:3a:b7"), "Should match lowercase MAC");
    assertTrue(matcher.matches("00:1B:44:11:3a:B7"), "Should match mixed case MAC");
    assertTrue(matcher.matches("FF:FF:FF:FF:FF:FF"), "Should match broadcast MAC");
    assertTrue(matcher.matches("00:00:00:00:00:00"), "Should match zero MAC");
  }

  @Test
  public void testMACAddressInvalid() {
    ReggieMatcher matcher =
        RuntimeCompiler.compile(
            "[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}");

    assertFalse(matcher.matches("00:1B:44:11:3A"), "Should not match incomplete MAC");
    assertFalse(matcher.matches("00:1B:44:11:3A:B7:FF"), "Should not match too long");
    assertFalse(matcher.matches("00:1B:44:11:3A:GZ"), "Should not match invalid hex");
    assertFalse(matcher.matches("001B44113AB7"), "Should not match without colons");
  }

  // ========== UUID Tests ==========

  @Test
  public void testUUIDValid() {
    ReggieMatcher matcher =
        RuntimeCompiler.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    assertTrue(matcher.matches("550e8400-e29b-41d4-a716-446655440000"), "Should match valid UUID");
    assertTrue(matcher.matches("00000000-0000-0000-0000-000000000000"), "Should match nil UUID");
    assertTrue(matcher.matches("ffffffff-ffff-ffff-ffff-ffffffffffff"), "Should match all-f UUID");
  }

  @Test
  public void testUUIDInvalid() {
    ReggieMatcher matcher =
        RuntimeCompiler.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    assertFalse(
        matcher.matches("550e8400-e29b-41d4-a716-44665544000"), "Should not match too short");
    assertFalse(
        matcher.matches("550e8400-e29b-41d4-a716-4466554400000"), "Should not match too long");
    assertFalse(
        matcher.matches("550e8400e29b41d4a716446655440000"), "Should not match without hyphens");
    assertFalse(
        matcher.matches("550E8400-E29B-41D4-A716-446655440000"),
        "Should not match uppercase (pattern requires lowercase)");
  }

  // ========== Edge Cases ==========

  @Test
  public void testNullInput() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    assertFalse(matcher.matches(null), "Should not match null");
    assertNull(matcher.match(null), "Should return null MatchResult for null input");
    assertFalse(matcher.find(null), "Should not find in null");
    assertNull(matcher.findMatch(null), "Should return null for findMatch on null");
  }

  @Test
  public void testEmptyInput() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    assertFalse(matcher.matches(""), "Should not match empty string");
    assertNull(matcher.match(""), "Should return null for empty string");
  }

  @Test
  public void testVeryLongNonMatchingInput() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    String longText = "a".repeat(10000);
    assertFalse(matcher.find(longText), "Should not find in long non-matching text");
  }

  @Test
  public void testMultipleMatches() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    String text = "Server 1: 192.168.1.1, Server 2: 10.0.0.1, Server 3: 172.16.0.1";

    MatchResult result1 = matcher.findMatch(text);
    assertNotNull(result1, "Should find first IPv4");
    assertEquals("192.168.1.1", result1.group());

    MatchResult result2 = matcher.findMatchFrom(text, result1.end());
    assertNotNull(result2, "Should find second IPv4");
    assertEquals("10.0.0.1", result2.group());

    MatchResult result3 = matcher.findMatchFrom(text, result2.end());
    assertNotNull(result3, "Should find third IPv4");
    assertEquals("172.16.0.1", result3.group());

    MatchResult result4 = matcher.findMatchFrom(text, result3.end());
    assertNull(result4, "Should not find fourth IPv4");
  }

  // ========== Bounded Methods Tests ==========

  @Test
  public void testMatchesBounded() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    assertTrue(matcher.matchesBounded("IP: 192.168.1.1!", 4, 15), "Should match bounded region");
    assertFalse(
        matcher.matchesBounded("IP: 192.168.1.1!", 0, 15), "Should not match (includes 'IP: ')");
    assertFalse(
        matcher.matchesBounded("IP: 192.168.1.1!", 4, 14), "Should not match (incomplete IP)");
  }

  @Test
  public void testMatchBounded() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    MatchResult result = matcher.matchBounded("IP: 192.168.1.1!", 4, 15);
    assertNotNull(result, "Should match bounded region");
    assertEquals("192.168.1.1", result.group(), "Should extract IP from bounded region");

    MatchResult result2 = matcher.matchBounded("IP: 192.168.1.1!", 0, 15);
    assertNull(result2, "Should not match (includes prefix)");
  }

  // ========== Performance Comparison Tests ==========

  @Test
  public void testIPv4PerformanceVsJDK() {
    ReggieMatcher reggieMatcher =
        RuntimeCompiler.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    java.util.regex.Pattern jdkPattern =
        java.util.regex.Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    String validIP = "192.168.1.1";
    String invalidIP = "256.300.400.500";
    String textWithIP = "Server IP is 192.168.1.1 on port 8080";

    // Verify correctness matches JDK
    assertEquals(
        jdkPattern.matcher(validIP).matches(),
        reggieMatcher.matches(validIP),
        "Should match JDK result for valid IP");

    assertEquals(
        jdkPattern.matcher(invalidIP).matches(),
        reggieMatcher.matches(invalidIP),
        "Should match JDK result for invalid IP");

    assertEquals(
        jdkPattern.matcher(textWithIP).find(),
        reggieMatcher.find(textWithIP),
        "Should match JDK find result");
  }
}
