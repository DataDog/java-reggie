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
package com.datadoghq.reggie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regression tests for issue #122: a backreference to a group that has not completed matching (a
 * self- or forward-reference) computed a negative group length in the linear-backreference
 * strategy, moving the match position backward and producing bogus matches (ReDoS-like CPU/memory
 * blowup in findAll).
 *
 * <p>JDK semantics (java.util.regex Pattern$BackRef): a backreference whose group has no completed
 * span never matches. Expected values are computed from {@link Pattern} at runtime — never
 * hand-derived.
 */
class BackrefSelfReferenceTest {

  private static void assertParity(String pattern, String input) {
    java.util.regex.Matcher jdk = Pattern.compile(pattern).matcher(input);
    com.datadoghq.reggie.runtime.ReggieMatcher reggie =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(pattern);

    boolean jdkFind = jdk.find();
    assertEquals(jdkFind, reggie.find(input), () -> "find('" + input + "') for /" + pattern + "/");

    if (jdkFind) {
      assertEquals(jdk.start(), reggie.findMatch(input).start(), pattern);
      assertEquals(jdk.end(), reggie.findMatch(input).end(), pattern);
    }

    var reggieAll = reggie.findAll(input);
    int jdkCount = 0;
    jdk.reset();
    while (jdk.find()) {
      jdkCount++;
    }
    assertEquals(
        jdkCount, reggieAll.size(), () -> "findAll('" + input + "') for /" + pattern + "/");
  }

  @ParameterizedTest
  @CsvSource({
    // Self-references: group not complete when the backref evaluates (issue #122 OOM shape)
    "'(\\1)', 'a'",
    "'(\\1)', 'aa'",
    "'(\\1)', 'aaa'",
    "'(a\\1)', 'a'",
    "'(a\\1)', 'aa'",
    "'(\\1a)', 'a'",
    "'(\\1a)', 'aa'",
    // Forward references: group defined after the backref
    "'(a\\2)(b)', 'ab'",
    "'(a\\2)(b)', 'abbb'",
    // Nested self-reference
    "'((\\2))', 'a'",
    // Controls: valid backreferences must keep working
    "'(a)\\1', 'aa'",
    "'(a)\\1', 'ab'",
    "'(ab)\\1', 'abab'",
    "'(a+)\\1', 'aaaa'"
  })
  void backrefParityWithJdk(String pattern, String input) {
    assertParity(pattern, input);
  }
}
