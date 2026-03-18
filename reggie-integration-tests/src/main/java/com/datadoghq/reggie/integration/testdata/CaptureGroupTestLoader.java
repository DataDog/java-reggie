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

/**
 * Loads capturing group test data from semicolon-delimited files.
 *
 * <p>Format: pattern;input;group1Value;group2Value;... or:
 * pattern;input;group1Start-group1End;group2Start-group2End;...
 */
public class CaptureGroupTestLoader {

  public List<CaptureGroupTest> load(InputStream stream) throws IOException {
    List<CaptureGroupTest> tests = new ArrayList<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

      String line;
      int lineNumber = 0;

      while ((line = reader.readLine()) != null) {
        lineNumber++;
        // Only trim leading whitespace, preserve trailing spaces in expected values
        String trimmedLine = line.stripLeading();

        // Skip comments and empty lines
        if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
          continue;
        }
        line = trimmedLine;

        // Skip lines with inline comments indicating failures (# NO_MATCH, # PATTERN_ERROR)
        if (line.contains(" # NO_MATCH") || line.contains(" # PATTERN_ERROR")) {
          continue;
        }

        String[] parts = line.split(";", -1);
        if (parts.length < 2) {
          System.err.println("Warning: Invalid line " + lineNumber + ": " + line);
          continue;
        }

        String pattern = unescapeField(parts[0]);
        String input = unescapeField(parts[1]);
        List<GroupCapture> captures = new ArrayList<>();

        // Parse expected captures (starting from index 2)
        for (int i = 2; i < parts.length; i++) {
          String captureSpec = parts[i];
          if (captureSpec.isEmpty()) {
            // Empty capture
            captures.add(new GroupCapture(i - 1, ""));
          } else if (captureSpec.matches("\\d+-\\d+")) {
            // Position format: start-end
            String[] pos = captureSpec.split("-");
            int start = Integer.parseInt(pos[0]);
            int end = Integer.parseInt(pos[1]);
            captures.add(new GroupCapture(i - 1, start, end));
          } else {
            // Value format
            captures.add(new GroupCapture(i - 1, unescapeField(captureSpec)));
          }
        }

        tests.add(
            new CaptureGroupTest(
                pattern,
                input,
                true, // All loaded tests should match
                captures,
                "loaded",
                detectFeatures(pattern)));
      }
    }

    return tests;
  }

  private String unescapeField(String field) {
    // Handle hex escapes \xhh
    StringBuilder result = new StringBuilder();
    int i = 0;
    while (i < field.length()) {
      if (i < field.length() - 3 && field.charAt(i) == '\\' && field.charAt(i + 1) == 'x') {
        // Hex escape: \xhh
        String hex = field.substring(i + 2, Math.min(i + 4, field.length()));
        try {
          int value = Integer.parseInt(hex, 16);
          result.append((char) value);
          i += 4;
          continue;
        } catch (NumberFormatException e) {
          // Not a valid hex escape, treat as literal
        }
      }
      result.append(field.charAt(i));
      i++;
    }

    // Handle other escapes
    // IMPORTANT: Order matters! Quote escapes must be processed before backslash escapes
    // so that \\" becomes " and \\\\" becomes \" (backslash + quote)
    return result
        .toString()
        .replace("\\;", ";")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\");
  }

  private Set<String> detectFeatures(String pattern) {
    Set<String> features = new HashSet<>();

    if (pattern.contains("(") && !pattern.contains("(?:")) {
      features.add("capturing_groups");
    }
    if (pattern.matches(".*\\\\\\d+.*")) {
      features.add("backref");
    }
    if (pattern.contains("(?=") || pattern.contains("(?!")) {
      features.add("lookahead");
    }
    if (pattern.contains("(?<=") || pattern.contains("(?<!")) {
      features.add("lookbehind");
    }

    return features;
  }
}
