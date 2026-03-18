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
import com.datadoghq.reggie.integration.testdata.ReplacementTest;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.List;

/** Validates replacement/substitution correctness. */
public class ReplacementValidator {

  public ValidationReport validate(List<ReplacementTest> tests) {
    ValidationReport report = new ValidationReport();

    for (ReplacementTest test : tests) {
      // Clear cache before each test to ensure fresh compilation and avoid cache pollution
      RuntimeCompiler.clearCache();

      if (!test.isSupported()) {
        report.addSkip(null, "Unsupported features: " + test.features());
        continue;
      }

      // Skip tests without expected output (can't validate)
      if (test.expectedOutput() == null) {
        report.addSkip(null, "No expected output to validate");
        continue;
      }

      try {
        // Compile pattern with Reggie
        ReggieMatcher matcher = Reggie.compile(test.pattern());

        // Perform replacement
        String actual;
        if (test.global()) {
          actual = matcher.replaceAll(test.input(), test.replacement());
        } else {
          actual = matcher.replaceFirst(test.input(), test.replacement());
        }

        if (test.expectedOutput().equals(actual)) {
          report.addPass(null);
        } else {
          report.addFail(
              null,
              String.format(
                  "Pattern '%s' on '%s' with replacement '%s': " + "expected '%s', got '%s'",
                  test.pattern(), test.input(), test.replacement(), test.expectedOutput(), actual));
        }

      } catch (UnsupportedOperationException e) {
        report.addSkip(null, "Unsupported: " + e.getMessage());
      } catch (Exception e) {
        report.addError(null, e);
      }
    }

    return report;
  }
}
