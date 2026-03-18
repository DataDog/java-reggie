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
package com.datadoghq.reggie.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.integration.testdata.*;
import com.datadoghq.reggie.integration.validation.CaptureGroupValidator;
import com.datadoghq.reggie.integration.validation.CorrectnessValidator;
import com.datadoghq.reggie.integration.validation.ReplacementValidator;
import com.datadoghq.reggie.integration.validation.ValidationReport;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** JUnit integration tests for correctness validation. */
public class CorrectnessTest {

  @Test
  public void testCommonPatterns() throws Exception {
    List<TestCase> tests = new CommonPatternsLoader().load();
    ValidationReport report = new CorrectnessValidator().validate(tests);

    System.out.println(report.getSummary());
    assertTrue(
        report.getPassRate() >= 0.90,
        "Should pass >=90% of common patterns, got: "
            + String.format("%.1f%%", report.getPassRate() * 100));
  }

  @Test
  public void testRE2Suite() throws Exception {
    List<TestCase> tests =
        new RE2TestParser().parse(getClass().getResourceAsStream("/testsuites/re2/re2-basic.txt"));
    ValidationReport report = new CorrectnessValidator().validate(tests);

    System.out.println(report.getSummary());
    assertTrue(
        report.getPassRate() >= 0.85,
        "Should pass >=85% of RE2 tests, got: "
            + String.format("%.1f%%", report.getPassRate() * 100));
  }

  @Test
  public void testPCREPatterns() throws Exception {
    List<TestCase> tests =
        new PCRETestParser()
            .parse(getClass().getResourceAsStream("/testsuites/pcre/pcre-patterns.txt"));
    ValidationReport report = new CorrectnessValidator().validate(tests);

    System.out.println(report.getSummary());
    assertTrue(
        report.getPassRate() >= 0.85,
        "Should pass >=85% of PCRE patterns, got: "
            + String.format("%.1f%%", report.getPassRate() * 100));
  }

  @Test
  public void testRE2CapturingGroups() throws Exception {
    List<CaptureGroupTest> tests =
        new CaptureGroupTestLoader()
            .load(getClass().getResourceAsStream("/testsuites/re2/re2-capturing-groups.txt"));
    ValidationReport report = new CaptureGroupValidator().validate(tests);

    System.out.println("RE2 Capturing Groups Validation:");
    System.out.println(report.getSummary());
    // Note: Capturing group extraction is work in progress (Phase 5)
    // Current pass rate expected to be lower until implementation is complete
    assertTrue(
        report.getPassRate() >= 0.30,
        "Should pass >=30% of RE2 capturing group tests, got: "
            + String.format("%.1f%%", report.getPassRate() * 100));
  }

  @Test
  @Timeout(
      value = 1800,
      unit = TimeUnit.SECONDS) // 30 minutes - 403 patterns with validation overhead
  public void testPCRECapturingGroups() throws Exception {
    // Clear cache and measure compilation with structural caching
    com.datadoghq.reggie.runtime.RuntimeCompiler.clearCache();
    long startTime = System.currentTimeMillis();

    List<CaptureGroupTest> tests =
        new CaptureGroupTestLoader()
            .load(getClass().getResourceAsStream("/testsuites/pcre/pcre-capturing-groups.txt"));

    System.out.println("\n=== PCRE Capturing Groups Test with Structural Caching ===");
    System.out.println("Test patterns: " + tests.size());

    ValidationReport report = new CaptureGroupValidator().validate(tests);

    long endTime = System.currentTimeMillis();
    double totalSeconds = (endTime - startTime) / 1000.0;

    System.out.println("\n=== Cache Statistics ===");
    System.out.println(
        "Pattern cache size: " + com.datadoghq.reggie.runtime.RuntimeCompiler.cacheSize());
    System.out.println(
        "Structural cache size: "
            + com.datadoghq.reggie.runtime.RuntimeCompiler.structuralCacheSize());
    System.out.println(
        "Cache hit rate: "
            + String.format(
                "%.1f%%",
                (1.0
                        - ((double)
                                com.datadoghq.reggie.runtime.RuntimeCompiler.structuralCacheSize()
                            / com.datadoghq.reggie.runtime.RuntimeCompiler.cacheSize()))
                    * 100));
    System.out.println("Total time: " + String.format("%.1f seconds", totalSeconds));
    System.out.println(
        "Average per pattern: " + String.format("%.1f ms", (totalSeconds * 1000) / tests.size()));

    System.out.println("\nPCRE Capturing Groups Validation:");
    System.out.println(report.getSummary());
    // Note: Capturing group extraction is work in progress (Phase 5)
    // Current pass rate expected to be lower until implementation is complete
    assertTrue(
        report.getPassRate() >= 0.25,
        "Should pass >=25% of PCRE capturing group tests, got: "
            + String.format("%.1f%%", report.getPassRate() * 100));
  }

  @Test
  public void testPCREReplacements() throws Exception {
    List<ReplacementTest> tests =
        new ReplacementTestLoader()
            .load(getClass().getResourceAsStream("/testsuites/pcre/pcre-replacements.txt"));
    ValidationReport report = new ReplacementValidator().validate(tests);

    System.out.println("PCRE Replacement Validation:");
    System.out.println(report.getSummary());

    // Replacement functionality should work for basic cases
    assertTrue(
        report.getPassRate() >= 0.80,
        "Should pass >=80% of PCRE replacement tests, got: "
            + String.format("%.1f%%", report.getPassRate() * 100));
  }
}
