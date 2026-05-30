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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.integration.testdata.CommonPatternsLoader;
import com.datadoghq.reggie.integration.testdata.TestCase;
import java.util.ArrayList;
import java.util.List;

/** Validates regex patterns against test cases for correctness. */
public class CorrectnessValidator {

  public ValidationReport validate(List<TestCase> testCases) {
    ValidationReport report = new ValidationReport();

    for (TestCase tc : testCases) {
      try {
        // Compile pattern with Reggie
        // Note: Need to generate matcher class or use reflection
        boolean result = matchWithReggie(tc.pattern(), tc.input());

        if (result == tc.shouldMatch()) {
          report.addPass(tc);
        } else {
          report.addFail(tc, "Expected: " + tc.shouldMatch() + ", got: " + result);
        }
      } catch (UnsupportedOperationException e) {
        report.addSkip(tc, "Unsupported feature: " + e.getMessage());
      } catch (Exception e) {
        report.addError(tc, e);
      }
    }

    return report;
  }

  private boolean matchWithReggie(String pattern, String input) {
    return Reggie.compile(pattern).find(input);
  }

  public static void main(String[] args) throws Exception {
    CorrectnessValidator validator = new CorrectnessValidator();

    // Load and validate all test suites
    List<TestCase> allTests = new ArrayList<>();
    allTests.addAll(new CommonPatternsLoader().load());

    ValidationReport report = validator.validate(allTests);
    System.out.println(report.getSummary());

    System.exit(report.getPassRate() >= 0.90 ? 0 : 1);
  }
}
