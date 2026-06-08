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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for lazy (non-greedy) quantifier patterns that failed in earlier engine
 * versions. All patterns currently fall back to JDK via the {@code hasLazyQuantifier} guard; once
 * RECURSIVE_DESCENT is extended with full continuation-passing backtracking these tests will remain
 * correct with native routing (issue #37).
 */
class LazyQuantifierNativeTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Correctness against JDK ──────────────────────────────────────────────

  @Test
  void boundedLazyOuter_lazyInner_correctGroup() {
    // ^[ab]{1,3}?(ab*?|b) on "aabbbbb" — lazy outer prefers 1 char, inner is lazy too
    String pat = "^[ab]{1,3}?(ab*?|b)";
    ReggieMatcher m = Reggie.compile(pat);
    Matcher jdk = Pattern.compile(pat).matcher("aabbbbb");
    assertTrue(jdk.find(), "JDK should find match");
    MatchResult r = m.findMatch("aabbbbb");
    assertNotNull(r, "Reggie should find match");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
  }

  @Test
  void boundedLazyOuter_greedyInner_correctGroup() {
    // ^[ab]{1,3}?(ab*|b) on "aabbbbb" — lazy outer, greedy inner
    String pat = "^[ab]{1,3}?(ab*|b)";
    ReggieMatcher m = Reggie.compile(pat);
    Matcher jdk = Pattern.compile(pat).matcher("aabbbbb");
    assertTrue(jdk.find(), "JDK should find match");
    MatchResult r = m.findMatch("aabbbbb");
    assertNotNull(r, "Reggie should find match");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
  }

  @Test
  void lazyOptional_noGroupParticipation() {
    // (?i)(a+|b){0,1}? on "AB" — lazy optional prefers 0 iterations
    String pat = "(?i)(a+|b){0,1}?";
    ReggieMatcher m = Reggie.compile(pat);
    Matcher jdk = Pattern.compile(pat).matcher("AB");
    assertTrue(jdk.find(), "JDK should find match");
    MatchResult r = m.findMatch("AB");
    assertNotNull(r, "Reggie should find match");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
  }

  @Test
  void fixedRepetitionWithInnerLazy() {
    // (([a-c])b*?\2){3} on "ababbbcbc" — fixed outer {3}, lazy inner b*?\2
    String pat = "(([a-c])b*?\\2){3}";
    ReggieMatcher m = Reggie.compile(pat);
    Matcher jdk = Pattern.compile(pat).matcher("ababbbcbc");
    boolean jdkFound = jdk.find();
    MatchResult r = m.findMatch("ababbbcbc");
    assertEquals(jdkFound, r != null, "find result must match JDK");
    if (jdkFound && r != null) {
      assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
      assertEquals(jdk.group(2), r.group(2), "group(2) must match JDK");
    }
  }

  @Test
  void nullableChildLazyQuantifier_matchesJdk() {
    // (|ab)*?d on "abd" — nullable child lazy, must match JDK (currently via fallback)
    String pat = "(|ab)*?d";
    ReggieMatcher m = Reggie.compile(pat);
    Matcher jdk = Pattern.compile(pat).matcher("abd");
    boolean jdkFound = jdk.find();
    MatchResult r = m.findMatch("abd");
    assertEquals(jdkFound, r != null, "find result must match JDK");
    if (jdkFound && r != null) {
      assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    }
  }
}
