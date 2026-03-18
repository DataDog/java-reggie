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
 * Parser for RE2 test format with capturing groups.
 *
 * <p>Format: strings "test_string" regexps "pattern" result1;result2;result3;result4
 *
 * <p>Results format: "start-end group1Start-group1End group2Start-group2End..." or "-" for no match
 */
public class RE2CaptureGroupParser {

  private static final Pattern QUOTED_STRING = Pattern.compile("^\"(.*)\"$");

  public List<CaptureGroupTest> parse(InputStream stream) throws IOException {
    List<CaptureGroupTest> tests = new ArrayList<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

      String line;
      List<String> currentStrings = new ArrayList<>();
      String currentPattern = null;
      boolean inStrings = false;
      boolean inRegexps = false;

      while ((line = reader.readLine()) != null) {
        line = line.trim();

        // Skip comments and empty lines
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }

        if (line.equals("strings")) {
          inStrings = true;
          inRegexps = false;
          currentStrings.clear();
          continue;
        }

        if (line.equals("regexps")) {
          inStrings = false;
          inRegexps = true;
          continue;
        }

        if (inStrings) {
          String unquoted = unquote(line);
          if (unquoted != null) {
            currentStrings.add(unquoted);
          }
        } else if (inRegexps) {
          String unquoted = unquote(line);
          if (unquoted != null) {
            // This is a pattern
            currentPattern = unquoted;
          } else if (currentPattern != null && !currentStrings.isEmpty()) {
            // This is a results line
            // Format: result1;result2;result3;result4
            // We'll use the first result (basic match)
            String[] results = line.split(";");
            if (results.length > 0) {
              String result = results[0];

              // Parse result for the current string (last string in list)
              String input = currentStrings.get(currentStrings.size() - 1);
              CaptureGroupTest test = parseResult(currentPattern, input, result);
              if (test != null) {
                tests.add(test);
              }
            }
          }
        }
      }
    }

    return tests;
  }

  private String unquote(String str) {
    Matcher m = QUOTED_STRING.matcher(str);
    if (m.matches()) {
      return unescapeString(m.group(1));
    }
    return null;
  }

  private String unescapeString(String str) {
    // Basic unescape - handle \n, \t, \r, \\, \"
    return str.replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\r", "\r")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\");
  }

  private CaptureGroupTest parseResult(String pattern, String input, String result) {
    result = result.trim();

    if (result.equals("-")) {
      // No match expected
      return new CaptureGroupTest(pattern, input, false, List.of(), "RE2", detectFeatures(pattern));
    }

    // Parse "0-2 1-2" format
    String[] ranges = result.split("\\s+");
    List<GroupCapture> captures = new ArrayList<>();

    for (int i = 0; i < ranges.length; i++) {
      String range = ranges[i];
      String[] parts = range.split("-");
      if (parts.length == 2) {
        try {
          int start = Integer.parseInt(parts[0]);
          int end = Integer.parseInt(parts[1]);

          // Group 0 is the full match, groups 1+ are capturing groups
          if (i > 0) { // Skip group 0, only capture explicit groups
            captures.add(new GroupCapture(i, start, end));
          }
        } catch (NumberFormatException e) {
          // Skip invalid ranges
        }
      }
    }

    return new CaptureGroupTest(pattern, input, true, captures, "RE2", detectFeatures(pattern));
  }

  private Set<String> detectFeatures(String pattern) {
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

    return features;
  }
}
