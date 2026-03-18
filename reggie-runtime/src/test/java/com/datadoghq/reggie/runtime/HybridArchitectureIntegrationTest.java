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

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test demonstrating hybrid architecture: - Static patterns via @RegexPattern
 * annotation processor - Dynamic patterns via Runtime API
 */
public class HybridArchitectureIntegrationTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testDynamicPatternCompilation() {
    ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
    assertNotNull(phone);
    assertTrue(phone.matches("123-456-7890"));
    assertFalse(phone.matches("invalid"));
  }

  @Test
  public void testDynamicPatternCaching() {
    ReggieMatcher first = Reggie.compile("\\d+");
    ReggieMatcher second = Reggie.compile("\\d+");

    assertSame(first, second, "Same pattern should return cached instance");
  }

  @Test
  public void testExplicitCaching() {
    String userPattern = "[a-z]+";
    ReggieMatcher matcher = Reggie.cached("user-input", userPattern);

    assertNotNull(matcher);
    assertTrue(matcher.matches("hello"));
    assertFalse(matcher.matches("123"));
  }

  @Test
  public void testCacheManagement() {
    Reggie.clearCache();
    assertEquals(0, Reggie.cacheSize());

    Reggie.compile("\\d+");
    Reggie.compile("[a-z]+");
    assertEquals(2, Reggie.cacheSize());

    assertTrue(Reggie.cachedPatterns().contains("\\d+"));
    assertTrue(Reggie.cachedPatterns().contains("[a-z]+"));

    Reggie.clearCache();
    assertEquals(0, Reggie.cacheSize());
  }

  @Test
  public void testMultiplePatternsIndependence() {
    ReggieMatcher digits = Reggie.compile("\\d+");
    ReggieMatcher letters = Reggie.compile("[a-z]+");
    ReggieMatcher email = Reggie.compile("[a-z]+@[a-z]+");

    assertTrue(digits.matches("123"));
    assertFalse(digits.matches("abc"));

    assertTrue(letters.matches("abc"));
    assertFalse(letters.matches("123"));

    assertTrue(email.matches("user@domain"));
    assertFalse(email.matches("user"));
  }

  @Test
  public void testPerformanceComparison() {
    String pattern = "\\d{3}-\\d{3}-\\d{4}";

    long dynamicStart = System.nanoTime();
    ReggieMatcher dynamic = Reggie.compile(pattern);
    long dynamicDuration = System.nanoTime() - dynamicStart;

    assertTrue(dynamic.matches("123-456-7890"));

    long cachedStart = System.nanoTime();
    ReggieMatcher cached = Reggie.compile(pattern);
    long cachedDuration = System.nanoTime() - cachedStart;

    assertTrue(cached.matches("123-456-7890"));
    assertSame(dynamic, cached);

    double dynamicMs = dynamicDuration / 1_000_000.0;
    double cachedMs = cachedDuration / 1_000_000.0;

    System.out.printf("Dynamic compilation: %.2f ms%n", dynamicMs);
    System.out.printf("Cached access: %.2f ms%n", cachedMs);
    System.out.printf("Speedup: %.1fx%n", dynamicMs / cachedMs);

    assertTrue(cachedMs < dynamicMs, "Cached access should be faster");
    assertTrue(cachedMs < 1.0, "Cached access should be under 1ms");
  }
}
