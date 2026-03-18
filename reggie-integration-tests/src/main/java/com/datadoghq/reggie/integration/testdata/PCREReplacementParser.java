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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for PCRE replacement/substitution tests.
 *
 * <p>Format: /pattern/flags,replace=<replacement>,substitute_options test_input
 *
 * <p>Expected output in testoutput file shows the replacement result.
 */
public class PCREReplacementParser {

  private static final Pattern REPLACE_PATTERN =
      Pattern.compile("^/(.*?)/(\\w*),.*replace=([^,]+)(?:,(.*))?$");
  private static final Pattern TEST_STRING = Pattern.compile("^\\s+(.+)$");

  public List<ReplacementTest> parse(InputStream inputStream, InputStream outputStream)
      throws IOException {

    List<ReplacementTest> tests = new ArrayList<>();

    // Parse input file
    List<ReplacementInput> inputs = parseInput(inputStream);

    // Parse output file to get expected results
    // For simplicity, we'll extract expected outputs by matching patterns
    // In a full implementation, we'd correlate input/output line by line

    for (ReplacementInput input : inputs) {
      // For now, we'll just create tests without expected output
      // A full implementation would parse testoutput12 to extract expected results
      tests.add(
          new ReplacementTest(
              input.pattern,
              input.testInput,
              input.replacement,
              null, // Expected output would come from testoutput file
              input.global,
              "PCRE",
              input.features));
    }

    return tests;
  }

  private List<ReplacementInput> parseInput(InputStream stream) throws IOException {
    List<ReplacementInput> inputs = new ArrayList<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

      String currentPattern = null;
      String currentReplacement = null;
      boolean currentGlobal = false;
      Set<String> currentFeatures = new HashSet<>();

      String line;
      while ((line = reader.readLine()) != null) {
        // Skip comments
        if (line.trim().startsWith("#")) {
          continue;
        }

        // Check for replacement pattern line
        Matcher replaceMatcher = REPLACE_PATTERN.matcher(line.trim());
        if (replaceMatcher.matches()) {
          currentPattern = replaceMatcher.group(1);
          String flags = replaceMatcher.group(2);
          currentReplacement = replaceMatcher.group(3);
          String options = replaceMatcher.group(4);

          currentGlobal = flags.contains("g");
          currentFeatures = detectFeatures(currentPattern, flags, options);

          // Unescape replacement string
          currentReplacement = unescapeReplacement(currentReplacement);
          continue;
        }

        // Check for test string
        Matcher testMatcher = TEST_STRING.matcher(line);
        if (testMatcher.matches() && currentPattern != null) {
          String testString = testMatcher.group(1).trim();

          // Skip directives
          if (testString.startsWith("\\=")) {
            continue;
          }

          testString = unescapeTestString(testString);

          inputs.add(
              new ReplacementInput(
                  currentPattern, testString, currentReplacement, currentGlobal, currentFeatures));
        }
      }
    }

    return inputs;
  }

  private String unescapeReplacement(String replacement) {
    // Remove angle brackets if present
    if (replacement.startsWith("<") && replacement.endsWith(">")) {
      replacement = replacement.substring(1, replacement.length() - 1);
    }

    return replacement;
  }

  private String unescapeTestString(String str) {
    return str.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
  }

  private Set<String> detectFeatures(String pattern, String flags, String options) {
    Set<String> features = new HashSet<>();

    features.add("replacement");

    if (pattern.contains("(") && !pattern.contains("(?:")) {
      features.add("capturing_groups");
    }
    if (pattern.contains("(?:")) {
      features.add("non_capturing_groups");
    }
    if (pattern.contains("+") || pattern.contains("*") || pattern.contains("{")) {
      features.add("quantifier");
    }
    if (pattern.contains("|")) {
      features.add("alternation");
    }
    if (pattern.contains("(?=") || pattern.contains("(?!")) {
      features.add("lookahead");
    }
    if (pattern.contains("(?<=") || pattern.contains("(?<!")) {
      features.add("lookbehind");
    }
    if (pattern.matches(".*\\\\\\d+.*")) {
      features.add("backref");
    }
    if (flags.contains("g")) {
      features.add("global_replace");
    }

    if (options != null) {
      if (options.contains("substitute_extended")) {
        features.add("extended_replacement");
      }
      if (options.contains("\\U") || options.contains("\\L")) {
        features.add("case_conversion");
      }
    }

    // Unsupported features
    if (pattern.contains("(?>")) {
      features.add("atomic_group");
    }
    if (pattern.matches(".*[*+?}]\\+.*")) {
      features.add("possessive_quantifier");
    }
    if (pattern.contains("\\p{") || pattern.contains("\\P{")) {
      features.add("unicode_property");
    }
    if (pattern.matches(".*\\(\\?[imsxU-]+:.*")) {
      features.add("inline_flags");
    }

    return features;
  }

  private static class ReplacementInput {
    final String pattern;
    final String testInput;
    final String replacement;
    final boolean global;
    final Set<String> features;

    ReplacementInput(
        String pattern,
        String testInput,
        String replacement,
        boolean global,
        Set<String> features) {
      this.pattern = pattern;
      this.testInput = testInput;
      this.replacement = replacement;
      this.global = global;
      this.features = features;
    }
  }
}
