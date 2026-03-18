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
import com.datadoghq.reggie.integration.testdata.CaptureGroupTest;
import com.datadoghq.reggie.integration.testdata.GroupCapture;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.List;

/** Validates capturing group extraction correctness. */
public class CaptureGroupValidator {

  public ValidationReport validate(List<CaptureGroupTest> tests) {
    ValidationReport report = new ValidationReport();

    for (CaptureGroupTest test : tests) {
      if (!test.isSupported()) {
        report.addSkip(null, "Unsupported features: " + test.features());
        continue;
      }

      try {
        // Compile pattern with Reggie
        ReggieMatcher matcher = Reggie.compile(test.pattern());

        // Find match
        MatchResult result = matcher.findMatch(test.input());

        if (result == null) {
          if (test.shouldMatch()) {
            report.addFail(
                null,
                String.format(
                    "Pattern '%s' should match '%s' but didn't", test.pattern(), test.input()));
          } else {
            report.addPass(null);
          }
          continue;
        }

        if (!test.shouldMatch()) {
          report.addFail(
              null,
              String.format(
                  "Pattern '%s' shouldn't match '%s' but did", test.pattern(), test.input()));
          continue;
        }

        // Validate captured groups
        boolean allGroupsMatch = true;
        StringBuilder errors = new StringBuilder();

        for (GroupCapture expected : test.expectedCaptures()) {
          int groupNum = expected.groupNumber();

          if (groupNum > result.groupCount()) {
            errors.append(
                String.format(
                    "Group %d not found (only %d groups); ", groupNum, result.groupCount()));
            allGroupsMatch = false;
            continue;
          }

          if (expected.hasValue()) {
            // Value-based comparison
            String actual = result.group(groupNum);
            String expectedValue = expected.expectedValue();

            // Normalize null to empty string for comparison
            // PCRE returns "" for non-participating groups, JDK returns null
            // We treat them as equivalent
            String actualNormalized = actual == null ? "" : actual;

            if (!expectedValue.equals(actualNormalized)) {
              errors.append(
                  String.format(
                      "Group %d: expected '%s', got '%s'; ", groupNum, expectedValue, actual));
              allGroupsMatch = false;
            }
          } else if (expected.hasPosition()) {
            // Position-based comparison
            int actualStart = result.start(groupNum);
            int actualEnd = result.end(groupNum);

            if (actualStart != expected.startPos() || actualEnd != expected.endPos()) {
              errors.append(
                  String.format(
                      "Group %d position: expected %d-%d, got %d-%d; ",
                      groupNum, expected.startPos(), expected.endPos(), actualStart, actualEnd));
              allGroupsMatch = false;
            }
          }
        }

        if (allGroupsMatch) {
          report.addPass(null);
        } else {
          report.addFail(
              null,
              String.format(
                  "Pattern '%s' on '%s': %s", test.pattern(), test.input(), errors.toString()));
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
