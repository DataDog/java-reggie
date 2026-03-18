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
import java.util.List;
import java.util.Set;

/** Parser for RE2 test format. Format: pattern;input;should_match;features */
public class RE2TestParser {

  public List<TestCase> parse(InputStream stream) throws IOException {
    List<TestCase> testCases = new ArrayList<>();

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

        String[] parts = line.split(";", 4);
        if (parts.length < 3) {
          System.err.println("Warning: Invalid line " + lineNumber + ": " + line);
          continue;
        }

        String pattern = parts[0];
        String input = parts[1];
        boolean shouldMatch = Boolean.parseBoolean(parts[2]);
        Set<String> features = parts.length > 3 ? Set.of(parts[3].split(",")) : Set.of();

        testCases.add(new TestCase(pattern, input, shouldMatch, "RE2", features));
      }
    }

    return testCases;
  }
}
