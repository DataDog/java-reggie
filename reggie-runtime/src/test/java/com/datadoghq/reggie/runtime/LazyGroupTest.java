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

public class LazyGroupTest {
  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  private void check(String pat, String input) {
    Matcher jdk = Pattern.compile(pat).matcher(input);
    boolean jdkMatch = jdk.find();
    ReggieMatcher reg = Reggie.compile(pat, WITH_FALLBACK);
    MatchResult r = reg.findMatch(input);
    System.out.printf(
        "%-30s on %-15s JDK=%s Reggie=%s%n",
        pat,
        input,
        jdkMatch ? "g1=" + jdk.group(1) : "no-match",
        r != null ? "g1=" + r.group(1) : "no-match");
    assertEquals(jdkMatch, r != null, "presence");
    if (jdkMatch && r != null) assertEquals(jdk.group(1), r.group(1), "group 1");
  }

  @Test
  void lazyInGroup() {
    check("(|ab)*?d", "abd");
    check("^[ab]{1,3}?(ab*?|b)", "aabbbbb");
  }
}
