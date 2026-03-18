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
package com.datadoghq.reggie.benchmark.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main class for generating HTML benchmark reports from JMH JSON results.
 *
 * <p>Usage: java BenchmarkReport <jmh-results.json> <output.html> [--console-summary] [--baseline
 * <baseline.json>]
 */
public class BenchmarkReport {

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println(
          "Usage: BenchmarkReport <jmh-results.json> <output.html> [--console-summary] [--baseline <baseline.json>]");
      System.err.println();
      System.err.println("Generates an HTML report from JMH benchmark results.");
      System.err.println();
      System.err.println("Options:");
      System.err.println("  --console-summary    Print concise summary to console");
      System.err.println(
          "  --baseline <file>    Compare against baseline results and detect regressions");
      System.err.println();
      System.err.println("Example:");
      System.err.println(
          "  java BenchmarkReport build/reports/jmh/results.json build/reports/benchmark.html --console-summary --baseline build/reports/jmh/baseline.json");
      System.exit(1);
    }

    Path inputPath = Path.of(args[0]);
    Path outputPath = Path.of(args[1]);
    boolean consoleSummary = false;
    Path baselinePath = null;

    // Parse optional arguments
    for (int i = 2; i < args.length; i++) {
      if ("--console-summary".equals(args[i])) {
        consoleSummary = true;
      } else if ("--baseline".equals(args[i]) && i + 1 < args.length) {
        baselinePath = Path.of(args[i + 1]);
        i++; // Skip next argument
      }
    }

    try {
      generateReport(inputPath, outputPath, baselinePath, consoleSummary);
      System.out.println("✅ Benchmark report generated successfully!");
      System.out.println("📊 Report: " + outputPath.toAbsolutePath());
    } catch (IOException e) {
      System.err.println("❌ Error generating report: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  public static void generateReport(
      Path inputPath, Path outputPath, Path baselinePath, boolean consoleSummary)
      throws IOException {
    // Read JMH results JSON
    if (!Files.exists(inputPath)) {
      throw new IOException("JMH results file not found: " + inputPath);
    }

    String json = Files.readString(inputPath);

    // Parse JSON - JMH outputs an array at the root level
    Gson gson = new GsonBuilder().create();
    JMHResults.BenchmarkEntry[] entries = gson.fromJson(json, JMHResults.BenchmarkEntry[].class);

    if (entries == null || entries.length == 0) {
      throw new IOException("No benchmark results found in: " + inputPath);
    }

    JMHResults results = new JMHResults(java.util.Arrays.asList(entries));

    // Load baseline if specified
    JMHResults baseline = null;
    if (baselinePath != null && Files.exists(baselinePath)) {
      try {
        String baselineJson = Files.readString(baselinePath);
        JMHResults.BenchmarkEntry[] baselineEntries =
            gson.fromJson(baselineJson, JMHResults.BenchmarkEntry[].class);
        if (baselineEntries != null && baselineEntries.length > 0) {
          baseline = new JMHResults(java.util.Arrays.asList(baselineEntries));
          System.out.println("Loaded baseline with " + baseline.size() + " benchmark results");
        }
      } catch (IOException e) {
        System.err.println("Warning: Could not load baseline file: " + e.getMessage());
      }
    }

    // Print console summary if requested
    if (consoleSummary) {
      ConsoleSummary summary = new ConsoleSummary();
      summary.print(results, baseline);
    }

    // Generate HTML report
    HTMLReporter reporter = new HTMLReporter();
    String html = reporter.generate(results, baseline);

    // Create output directory if needed
    Path outputDir = outputPath.getParent();
    if (outputDir != null && !Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }

    // Write HTML file
    Files.writeString(outputPath, html);

    if (!consoleSummary) {
      // Print basic info if not using console summary
      System.out.println("Generated report with " + results.size() + " benchmark results");
      System.out.println(
          "Average throughput: "
              + String.format("%.2f", results.getAverageThroughput())
              + " ops/ms");
      System.out.println("Best performer: " + results.getBestBenchmark());
    }
  }

  // Legacy method for backward compatibility
  public static void generateReport(Path inputPath, Path outputPath) throws IOException {
    generateReport(inputPath, outputPath, null, false);
  }
}
