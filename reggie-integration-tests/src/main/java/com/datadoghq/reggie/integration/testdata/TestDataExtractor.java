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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Extracts test data from RE2 and PCRE test suites and generates Reggie-format test files.
 *
 * <p>Usage: java TestDataExtractor [output-directory]
 *
 * <p>This will download and parse RE2 and PCRE test files and generate: - re2-capturing-groups.txt
 * - pcre-capturing-groups.txt - pcre-replacements.txt
 */
public class TestDataExtractor {

  public static void main(String[] args) throws IOException {
    Path outputDir =
        args.length > 0
            ? Paths.get(args[0])
            : Paths.get("reggie-integration-tests/src/main/resources/testsuites");

    Files.createDirectories(outputDir.resolve("re2"));
    Files.createDirectories(outputDir.resolve("pcre"));

    System.out.println("Extracting test data to: " + outputDir);

    // Extract RE2 capturing group tests
    extractRE2CapturingGroups(outputDir);

    // Extract PCRE capturing group tests
    extractPCRECapturingGroups(outputDir);

    // Extract PCRE replacement tests
    extractPCREReplacements(outputDir);

    System.out.println("Test data extraction complete!");
  }

  private static void extractRE2CapturingGroups(Path outputDir) throws IOException {
    System.out.println("Extracting RE2 capturing group tests...");

    Path inputFile = Paths.get("/tmp/re2-search.txt");
    if (!Files.exists(inputFile)) {
      System.err.println("Warning: /tmp/re2-search.txt not found. Skipping RE2 tests.");
      return;
    }

    RE2CaptureGroupParser parser = new RE2CaptureGroupParser();
    List<CaptureGroupTest> tests;

    try (FileInputStream fis = new FileInputStream(inputFile.toFile())) {
      tests = parser.parse(fis);
    }

    // Filter for tests with capturing groups only
    tests =
        tests.stream()
            .filter(t -> !t.expectedCaptures().isEmpty())
            .filter(CaptureGroupTest::isSupported)
            .toList();

    Path outputFile = outputDir.resolve("re2/re2-capturing-groups.txt");
    writeCaptureGroupTests(tests, outputFile);

    System.out.println("  Extracted " + tests.size() + " RE2 capturing group tests");
  }

  private static void extractPCRECapturingGroups(Path outputDir) throws IOException {
    System.out.println("Extracting PCRE capturing group tests...");

    Path inputFile = Paths.get("/tmp/pcre-testinput1.txt");
    Path outputFile = Paths.get("/tmp/pcre-testoutput1.txt");

    if (!Files.exists(inputFile) || !Files.exists(outputFile)) {
      System.err.println("Warning: PCRE test files not found. Skipping PCRE tests.");
      return;
    }

    PCRECaptureGroupParser parser = new PCRECaptureGroupParser();
    List<CaptureGroupTest> tests;

    try (FileInputStream inputStream = new FileInputStream(inputFile.toFile());
        FileInputStream outputStream = new FileInputStream(outputFile.toFile())) {
      tests = parser.parse(inputStream, outputStream);
    }

    // Filter for supported tests
    tests = tests.stream().filter(CaptureGroupTest::isSupported).toList();

    Path output = outputDir.resolve("pcre/pcre-capturing-groups.txt");
    writeCaptureGroupTests(tests, output);

    System.out.println("  Extracted " + tests.size() + " PCRE capturing group tests");
  }

  private static void extractPCREReplacements(Path outputDir) throws IOException {
    System.out.println("Extracting PCRE replacement tests...");

    Path inputFile = Paths.get("/tmp/pcre-testinput12.txt");

    // Download testinput12 if not exists
    if (!Files.exists(inputFile)) {
      System.err.println("Warning: PCRE testinput12 not found. Skipping replacement tests.");
      return;
    }

    PCREReplacementParser parser = new PCREReplacementParser();
    List<ReplacementTest> tests;

    try (FileInputStream inputStream = new FileInputStream(inputFile.toFile())) {
      tests = parser.parse(inputStream, null);
    }

    // Filter for supported tests
    tests = tests.stream().filter(ReplacementTest::isSupported).toList();

    Path output = outputDir.resolve("pcre/pcre-replacements.txt");
    writeReplacementTests(tests, output);

    System.out.println("  Extracted " + tests.size() + " PCRE replacement tests");
  }

  private static void writeCaptureGroupTests(List<CaptureGroupTest> tests, Path outputFile)
      throws IOException {

    try (PrintWriter writer = new PrintWriter(new FileOutputStream(outputFile.toFile()))) {

      writer.println("# Capturing Group Tests");
      writer.println("# Format: pattern;input;group1Value;group2Value;...");
      writer.println(
          "# Position format: pattern;input;group1Start-group1End;group2Start-group2End;...");
      writer.println();

      for (CaptureGroupTest test : tests) {
        // Write pattern and input
        writer.print(escapeField(test.pattern()));
        writer.print(";");
        writer.print(escapeField(test.input()));

        // Write expected captures
        for (GroupCapture capture : test.expectedCaptures()) {
          writer.print(";");
          if (capture.hasValue()) {
            writer.print(escapeField(capture.expectedValue()));
          } else if (capture.hasPosition()) {
            writer.print(capture.startPos() + "-" + capture.endPos());
          }
        }

        writer.println();
      }
    }

    System.out.println("  Wrote: " + outputFile);
  }

  private static void writeReplacementTests(List<ReplacementTest> tests, Path outputFile)
      throws IOException {

    try (PrintWriter writer = new PrintWriter(new FileOutputStream(outputFile.toFile()))) {

      writer.println("# Replacement/Substitution Tests");
      writer.println("# Format: pattern;input;replacement;expectedOutput;global");
      writer.println();

      for (ReplacementTest test : tests) {
        writer.print(escapeField(test.pattern()));
        writer.print(";");
        writer.print(escapeField(test.input()));
        writer.print(";");
        writer.print(escapeField(test.replacement()));
        writer.print(";");
        writer.print(escapeField(test.expectedOutput() != null ? test.expectedOutput() : ""));
        writer.print(";");
        writer.print(test.global());
        writer.println();
      }
    }

    System.out.println("  Wrote: " + outputFile);
  }

  private static String escapeField(String field) {
    if (field == null) {
      return "";
    }

    // Escape special characters
    return field
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
