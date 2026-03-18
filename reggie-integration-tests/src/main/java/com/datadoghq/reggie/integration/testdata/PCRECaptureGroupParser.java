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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for PCRE test format with capturing groups.
 *
 * <p>Parses testinput and testoutput files together to extract: - Pattern: /regex/flags - Test
 * strings (lines starting with space) - Expected captures from output (lines like "0: match", "1:
 * group1", etc.)
 */
public class PCRECaptureGroupParser {

  private static final Pattern PATTERN_LINE = Pattern.compile("^/(.*?)/(\\w*)$");
  private static final Pattern TEST_STRING = Pattern.compile("^\\s+(.+)$");
  private static final Pattern CAPTURE_OUTPUT = Pattern.compile("^\\s*(\\d+):\\s*(.*)$");

  public List<CaptureGroupTest> parse(InputStream inputStream, InputStream outputStream)
      throws IOException {

    List<CaptureGroupTest> tests = new ArrayList<>();

    // First, parse the input file to get patterns and test strings
    Map<String, List<PatternTest>> inputData = parseInput(inputStream);

    // Then parse the output file to get expected captures
    Map<String, Map<String, List<GroupCapture>>> outputData = parseOutput(outputStream);

    // Combine input and output data
    for (Map.Entry<String, List<PatternTest>> entry : inputData.entrySet()) {
      String patternKey = entry.getKey();
      List<PatternTest> patternTests = entry.getValue();

      Map<String, List<GroupCapture>> testOutputs = outputData.get(patternKey);
      if (testOutputs == null) {
        continue; // No output data for this pattern
      }

      for (PatternTest pt : patternTests) {
        List<GroupCapture> captures = testOutputs.get(pt.input);
        if (captures != null && !captures.isEmpty()) {
          tests.add(
              new CaptureGroupTest(
                  pt.pattern, pt.input, pt.shouldMatch, captures, "PCRE", pt.features));
        }
      }
    }

    return tests;
  }

  private Map<String, List<PatternTest>> parseInput(InputStream stream) throws IOException {
    Map<String, List<PatternTest>> patterns = new HashMap<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

      String currentPattern = null;
      String currentPatternKey = null;
      Set<String> currentFeatures = new HashSet<>();
      List<PatternTest> currentTests = new ArrayList<>();
      boolean expectNoMatch = false;

      String line;
      while ((line = reader.readLine()) != null) {
        // Skip comments starting with #
        if (line.trim().startsWith("#")) {
          continue;
        }

        // Check for pattern line
        Matcher patternMatcher = PATTERN_LINE.matcher(line.trim());
        if (patternMatcher.matches()) {
          // Save previous pattern if exists
          if (currentPattern != null && !currentTests.isEmpty()) {
            patterns.put(currentPatternKey, new ArrayList<>(currentTests));
          }

          // Start new pattern
          String rawPattern = patternMatcher.group(1);
          String flags = patternMatcher.group(2);

          // Apply PCRE flags as inline modifiers
          // This ensures /pattern/i becomes (?i)pattern
          currentPattern = applyFlags(rawPattern, flags);
          currentPatternKey = rawPattern + "/" + flags; // Key uses original for matching
          currentFeatures = detectFeatures(rawPattern, flags);
          currentTests.clear();
          expectNoMatch = false;
          continue;
        }

        // Check for test string (starts with whitespace)
        Matcher testMatcher = TEST_STRING.matcher(line);
        if (testMatcher.matches() && currentPattern != null) {
          String testString = testMatcher.group(1).trim();

          // Check for "Expect no match" directive
          if (testString.startsWith("\\=")) {
            if (testString.contains("no match")) {
              expectNoMatch = true;
            }
            continue;
          }

          // Unescape test string
          testString = unescapeTestString(testString);

          currentTests.add(
              new PatternTest(currentPattern, testString, !expectNoMatch, currentFeatures));
          continue;
        }
      }

      // Save last pattern
      if (currentPattern != null && !currentTests.isEmpty()) {
        patterns.put(currentPatternKey, currentTests);
      }
    }

    return patterns;
  }

  private Map<String, Map<String, List<GroupCapture>>> parseOutput(InputStream stream)
      throws IOException {

    Map<String, Map<String, List<GroupCapture>>> outputs = new HashMap<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

      String currentPatternKey = null;
      String currentInput = null;
      List<GroupCapture> currentCaptures = new ArrayList<>();

      String line;
      while ((line = reader.readLine()) != null) {
        // Check for pattern line
        Matcher patternMatcher = PATTERN_LINE.matcher(line.trim());
        if (patternMatcher.matches()) {
          String pattern = patternMatcher.group(1);
          String flags = patternMatcher.group(2);
          currentPatternKey = pattern + "/" + flags;
          outputs.putIfAbsent(currentPatternKey, new HashMap<>());
          continue;
        }

        // Check for test input (starts with space, not a capture line)
        if (line.startsWith(" ") && !line.matches("^\\s*\\d+:.*") && currentPatternKey != null) {
          // Save previous captures
          if (currentInput != null && !currentCaptures.isEmpty()) {
            outputs.get(currentPatternKey).put(currentInput, new ArrayList<>(currentCaptures));
          }

          currentInput = unescapeTestString(line.trim());
          currentCaptures.clear();
          continue;
        }

        // Check for capture output
        Matcher captureMatcher = CAPTURE_OUTPUT.matcher(line);
        if (captureMatcher.matches() && currentInput != null) {
          int groupNum = Integer.parseInt(captureMatcher.group(1));
          String value = captureMatcher.group(2);

          // Skip group 0 (full match), only capture explicit groups
          if (groupNum > 0 && !value.equals("<unset>")) {
            currentCaptures.add(new GroupCapture(groupNum, value));
          }
        }
      }

      // Save last captures
      if (currentPatternKey != null && currentInput != null && !currentCaptures.isEmpty()) {
        outputs.get(currentPatternKey).put(currentInput, currentCaptures);
      }
    }

    return outputs;
  }

  private String unescapeTestString(String str) {
    // Basic unescape for PCRE test format
    return str.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
  }

  /** Apply PCRE flags as inline modifiers. Converts /pattern/ims to (?ims)pattern */
  private String applyFlags(String pattern, String flags) {
    if (flags == null || flags.isEmpty()) {
      return pattern;
    }

    StringBuilder modifiers = new StringBuilder();
    for (char c : flags.toCharArray()) {
      switch (c) {
        case 'i': // case insensitive
        case 'm': // multiline
        case 's': // dotall
        case 'x': // extended
          modifiers.append(c);
          break;
        // Skip other flags that Reggie doesn't support
        default:
          break;
      }
    }

    if (modifiers.length() == 0) {
      return pattern;
    }

    return "(?" + modifiers + ")" + pattern;
  }

  private Set<String> detectFeatures(String pattern, String flags) {
    Set<String> features = new HashSet<>();

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
    if (pattern.contains("^") || pattern.contains("$")) {
      features.add("anchor");
    }
    if (pattern.contains("(?=") || pattern.contains("(?!")) {
      features.add("lookahead");
    }
    if (pattern.contains("(?<=") || pattern.contains("(?<!")) {
      features.add("lookbehind");
    }
    if (pattern.contains("\\b") || pattern.contains("\\B")) {
      features.add("word_boundary");
    }
    if (pattern.matches(".*\\\\\\d+.*")) {
      features.add("backref");
    }
    if (pattern.contains("(?P<") || pattern.contains("?<")) {
      features.add("named_groups");
    }
    if (flags.contains("i")) {
      features.add("case_insensitive");
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
    if (pattern.matches(".*\\(\\?\\(.*\\).*\\|.*\\).*")) {
      features.add("conditional");
    }
    // PCRE-specific features
    if (pattern.contains("(*ACCEPT)")
        || pattern.contains("(*COMMIT)")
        || pattern.contains("(*THEN)")
        || pattern.contains("(*F)")) {
      features.add("pcre_verb");
    }

    return features;
  }

  private static class PatternTest {
    final String pattern;
    final String input;
    final boolean shouldMatch;
    final Set<String> features;

    PatternTest(String pattern, String input, boolean shouldMatch, Set<String> features) {
      this.pattern = pattern;
      this.input = input;
      this.shouldMatch = shouldMatch;
      this.features = features;
    }
  }
}
