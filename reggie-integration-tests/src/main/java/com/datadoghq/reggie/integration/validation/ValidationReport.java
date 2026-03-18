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
package com.datadoghq.reggie.integration.validation;

import com.datadoghq.reggie.integration.testdata.TestCase;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.List;

/** Report of correctness validation results. */
public class ValidationReport {
  private int passed = 0;
  private int failed = 0;
  private int skipped = 0;
  private int errors = 0;

  private final List<TestFailure> failures = new ArrayList<>();
  private final List<TestError> testErrors = new ArrayList<>();

  public void addPass(TestCase tc) {
    passed++;
  }

  public void addFail(TestCase tc, String message) {
    failed++;
    failures.add(new TestFailure(tc, message));
  }

  public void addSkip(TestCase tc, String reason) {
    skipped++;
  }

  public void addError(TestCase tc, Exception e) {
    errors++;
    testErrors.add(new TestError(tc, e));
  }

  public int getPassed() {
    return passed;
  }

  public int getFailed() {
    return failed;
  }

  public double getPassRate() {
    int total = passed + failed;
    return total == 0 ? 0.0 : passed / (double) total;
  }

  public String getSummary() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Correctness Validation Report ===\n\n");
    sb.append(String.format("Passed:  %d\n", passed));
    sb.append(String.format("Failed:  %d\n", failed));
    sb.append(String.format("Skipped: %d\n", skipped));
    sb.append(String.format("Errors:  %d\n", errors));
    sb.append(String.format("\nPass Rate: %.1f%%\n", getPassRate() * 100));

    if (!failures.isEmpty()) {
      sb.append("\n=== Failures ===\n");
      for (TestFailure failure : failures) {
        if (failure.testCase != null) {
          sb.append(
              String.format(
                  "  %s [%s]: %s\n",
                  failure.testCase.pattern(), failure.testCase.source(), failure.message));
        } else {
          sb.append(String.format("  %s\n", failure.message));
        }
      }
    }

    if (!testErrors.isEmpty()) {
      sb.append("\n=== Errors ===\n");
      for (TestError error : testErrors) {
        if (error.testCase != null) {
          sb.append(
              String.format(
                  "  %s [%s]: %s\n",
                  error.testCase.pattern(), error.testCase.source(), error.exception.getMessage()));
        } else {
          sb.append(String.format("  %s\n", error.exception.getMessage()));
        }
      }
    }

    return sb.toString();
  }

  public String toJSON() {
    // Generate JSON for CI/CD integration
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    return gson.toJson(this);
  }

  record TestFailure(TestCase testCase, String message) {}

  record TestError(TestCase testCase, Exception exception) {}
}
