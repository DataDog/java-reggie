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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class BackrefDigitAmbiguityTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void backrefFollowedByGroupThenDigit() {
    // (cat(a(ract|tonic)|erpillar)) \1()2(3) on "cataract cataract23"
    String pat = "(cat(a(ract|tonic)|erpillar)) \\1()2(3)";
    String input = "cataract cataract23";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    MatchResult r = reg.match(input);
    assertNotNull(r, "Reggie should match");
    // Check each group
    for (int i = 1; i <= 5; i++) {
      System.out.printf("group(%d): JDK=%s Reggie=%s%n", i, jdk.group(i), r.group(i));
    }
    assertEquals(jdk.group(1), r.group(1));
    assertEquals(jdk.group(2), r.group(2));
    assertEquals(jdk.group(3), r.group(3));
    assertEquals(jdk.group(4), r.group(4));
    assertEquals(jdk.group(5), r.group(5));
  }
}
