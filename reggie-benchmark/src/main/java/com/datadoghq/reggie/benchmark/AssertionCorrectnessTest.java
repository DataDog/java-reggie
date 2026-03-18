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
package com.datadoghq.reggie.benchmark;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.regex.Pattern;

/** Simple correctness test for assertion patterns. Compares Reggie results with JDK results. */
public class AssertionCorrectnessTest {

  public static void main(String[] args) {
    AssertionPatterns patterns = Reggie.patterns(AssertionPatterns.class);
    boolean allPassed = true;

    System.out.println("=== Assertion Correctness Tests ===\n");

    // Positive Lookahead: a(?=bc)
    allPassed &=
        test(
            "Positive Lookahead Match",
            patterns.positiveLookahead(),
            Pattern.compile("a(?=bc)"),
            "abc",
            true);
    allPassed &=
        test(
            "Positive Lookahead No Match",
            patterns.positiveLookahead(),
            Pattern.compile("a(?=bc)"),
            "axc",
            false);
    allPassed &=
        test(
            "Positive Lookahead Match Start",
            patterns.positiveLookahead(),
            Pattern.compile("a(?=bc)"),
            "abc123",
            true);

    // Negative Lookahead: a(?!bc)
    allPassed &=
        test(
            "Negative Lookahead Match",
            patterns.negativeLookahead(),
            Pattern.compile("a(?!bc)"),
            "axc",
            true);
    allPassed &=
        test(
            "Negative Lookahead No Match",
            patterns.negativeLookahead(),
            Pattern.compile("a(?!bc)"),
            "abc",
            false);
    allPassed &=
        test(
            "Negative Lookahead Match Other",
            patterns.negativeLookahead(),
            Pattern.compile("a(?!bc)"),
            "a123",
            true);

    // Positive Lookbehind: (?<=ab)c
    allPassed &=
        test(
            "Positive Lookbehind Match",
            patterns.positiveLookbehind(),
            Pattern.compile("(?<=ab)c"),
            "abc",
            true);
    allPassed &=
        test(
            "Positive Lookbehind No Match",
            patterns.positiveLookbehind(),
            Pattern.compile("(?<=ab)c"),
            "xbc",
            false);
    allPassed &=
        test(
            "Positive Lookbehind Middle",
            patterns.positiveLookbehind(),
            Pattern.compile("(?<=ab)c"),
            "xyzabcdef",
            true);

    // Negative Lookbehind: (?<!ab)c
    allPassed &=
        test(
            "Negative Lookbehind Match",
            patterns.negativeLookbehind(),
            Pattern.compile("(?<!ab)c"),
            "xbc",
            true);
    allPassed &=
        test(
            "Negative Lookbehind No Match",
            patterns.negativeLookbehind(),
            Pattern.compile("(?<!ab)c"),
            "abc",
            false);
    allPassed &=
        test(
            "Negative Lookbehind Start",
            patterns.negativeLookbehind(),
            Pattern.compile("(?<!ab)c"),
            "c",
            true);

    // Password Validation: (?=.*[A-Z])(?=.*\d)(?=.*[!@#$%]).{8,}
    allPassed &=
        test(
            "Password Valid",
            patterns.passwordValidation(),
            Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}"),
            "Password123!",
            true);
    allPassed &=
        test(
            "Password No Uppercase",
            patterns.passwordValidation(),
            Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}"),
            "password123!",
            false);
    allPassed &=
        test(
            "Password No Digit",
            patterns.passwordValidation(),
            Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}"),
            "Password!",
            false);
    allPassed &=
        test(
            "Password No Special",
            patterns.passwordValidation(),
            Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}"),
            "Password123",
            false);
    allPassed &=
        test(
            "Password Too Short",
            patterns.passwordValidation(),
            Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}"),
            "Pass1!",
            false);

    System.out.println("\n=== Results ===");
    if (allPassed) {
      System.out.println("\u001B[32mAll tests PASSED!\u001B[0m");
      System.exit(0);
    } else {
      System.out.println("\u001B[31mSome tests FAILED!\u001B[0m");
      System.exit(1);
    }
  }

  private static boolean test(
      String name,
      ReggieMatcher reggieMatcher,
      Pattern jdkPattern,
      String input,
      boolean expectedMatch) {
    boolean reggieResult = reggieMatcher.find(input);
    boolean jdkResult = jdkPattern.matcher(input).find();
    boolean passed = (reggieResult == jdkResult) && (reggieResult == expectedMatch);

    String status = passed ? "\u001B[32mPASS\u001B[0m" : "\u001B[31mFAIL\u001B[0m";
    System.out.printf(
        "[%s] %s: input='%s', expected=%b, reggie=%b, jdk=%b%n",
        status, name, input, expectedMatch, reggieResult, jdkResult);

    if (!passed) {
      if (reggieResult != expectedMatch) {
        System.out.println("  ERROR: Reggie result doesn't match expected!");
      }
      if (jdkResult != expectedMatch) {
        System.out.println("  ERROR: JDK result doesn't match expected (test bug?)");
      }
      if (reggieResult != jdkResult) {
        System.out.println("  ERROR: Reggie and JDK disagree!");
      }
    }

    return passed;
  }
}
