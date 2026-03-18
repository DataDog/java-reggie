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
 * Loads replacement test data from semicolon-delimited files.
 *
 * <p>Format: pattern;input;replacement;expectedOutput;global
 */
public class ReplacementTestLoader {

  public List<ReplacementTest> load(InputStream stream) throws IOException {
    List<ReplacementTest> tests = new ArrayList<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

      String line;
      int lineNumber = 0;

      while ((line = reader.readLine()) != null) {
        lineNumber++;
        line = line.trim();

        // Skip comments and empty lines
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }

        String[] parts = line.split(";", -1);
        if (parts.length < 5) {
          System.err.println("Warning: Invalid line " + lineNumber + ": " + line);
          continue;
        }

        String pattern = unescapeField(parts[0]);
        String input = unescapeField(parts[1]);
        String replacement = unescapeField(parts[2]);
        String expectedOutput = parts[3].isEmpty() ? null : unescapeField(parts[3]);
        boolean global = Boolean.parseBoolean(parts[4]);

        tests.add(
            new ReplacementTest(
                pattern,
                input,
                replacement,
                expectedOutput,
                global,
                "loaded",
                detectFeatures(pattern)));
      }
    }

    return tests;
  }

  private String unescapeField(String field) {
    return field
        .replace("\\;", ";")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\");
  }

  private Set<String> detectFeatures(String pattern) {
    Set<String> features = new HashSet<>();

    features.add("replacement");

    if (pattern.contains("(") && !pattern.contains("(?:")) {
      features.add("capturing_groups");
    }
    if (pattern.matches(".*\\\\\\d+.*")) {
      features.add("backref");
    }

    return features;
  }
}
