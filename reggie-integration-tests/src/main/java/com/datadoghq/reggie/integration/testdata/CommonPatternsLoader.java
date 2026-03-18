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

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Loads common regex patterns from JSON test data. */
public class CommonPatternsLoader {

  public List<TestCase> load() throws IOException {
    InputStream is = getClass().getResourceAsStream("/testsuites/common/patterns.json");
    if (is == null) {
      throw new IOException("Common patterns file not found");
    }

    Gson gson = new Gson();
    CommonPatternsData data =
        gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), CommonPatternsData.class);

    List<TestCase> testCases = new ArrayList<>();
    for (PatternEntry entry : data.patterns) {
      for (TestCaseEntry tc : entry.testCases) {
        testCases.add(
            new TestCase(
                entry.pattern,
                tc.input,
                tc.shouldMatch,
                "common:" + entry.name,
                Set.copyOf(entry.features)));
      }
    }

    return testCases;
  }

  private static class CommonPatternsData {
    List<PatternEntry> patterns;
  }

  private static class PatternEntry {
    String name;
    String pattern;
    List<String> features;
    List<TestCaseEntry> testCases;
  }

  private static class TestCaseEntry {
    String input;
    boolean shouldMatch;
  }
}
