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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LazyDFABytecodeGeneratorTest {

  private static final String LARGE_NFA_PATTERN = "(?:a+b+|b+a+){75}";
  private static final String MATCH_INPUT = "ab".repeat(75); // 150 chars, accepted

  @Test
  void testGeneratedClassMatchesNFAForSameInputs() {
    ReggieMatcher lazyMatcher = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    Pattern jdk = Pattern.compile(LARGE_NFA_PATTERN);

    // Deterministic positive case — exercises the accept path in the generated class.
    String positive = "ab".repeat(75);
    assertTrue(jdk.matcher(positive).matches(), "JDK must accept the positive input");
    assertTrue(lazyMatcher.matches(positive), "LAZY_DFA must accept the positive input");

    // Random corpus — exercises reject paths and correctness across diverse inputs.
    Random rng = new Random(42);
    String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
    for (int i = 0; i < 500; i++) {
      int len = rng.nextInt(800);
      StringBuilder sb = new StringBuilder(len);
      for (int j = 0; j < len; j++) sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
      String s = sb.toString();
      boolean expected = jdk.matcher(s).matches();
      boolean actual = lazyMatcher.matches(s);
      assertEquals(expected, actual, "Mismatch for: " + s.substring(0, Math.min(s.length(), 40)));
    }
  }

  @Test
  void testNfaStepMethodPresent() throws Exception {
    ReggieMatcher m = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    Method nfaStep = m.getClass().getDeclaredMethod("nfaStep", int[].class, int.class);
    assertNotNull(nfaStep);
  }

  @Test
  void testCacheIsSharedAcrossInstances() throws Exception {
    RuntimeCompiler.clearCache();
    ReggieMatcher m1 = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    // Use a distinct cache key to force a new ReggieMatcher instance while reusing the same
    // generated class from the level-2 structural cache, giving two distinct objects.
    ReggieMatcher m2 = RuntimeCompiler.cached("alt-key-shared-cache-test", LARGE_NFA_PATTERN);
    assertNotSame(m1, m2); // different instances
    assertSame(m1.getClass(), m2.getClass()); // same generated class
    // Verify the static CACHE field is the same object across both instances.
    Field f1 = m1.getClass().getDeclaredField("CACHE");
    Field f2 = m2.getClass().getDeclaredField("CACHE");
    f1.setAccessible(true);
    f2.setAccessible(true);
    assertSame(f1.get(null), f2.get(null));
  }

  @Test
  void testCacheIsNotSharedAcrossPatterns() throws Exception {
    RuntimeCompiler.clearCache();
    ReggieMatcher m1 = RuntimeCompiler.compile("(?:a+b+|b+a+){75}");
    ReggieMatcher m2 = RuntimeCompiler.compile("(?:a+b+|b+a+){76}");
    Field f1 = m1.getClass().getDeclaredField("CACHE");
    Field f2 = m2.getClass().getDeclaredField("CACHE");
    f1.setAccessible(true);
    f2.setAccessible(true);
    assertNotSame(f1.get(null), f2.get(null));
  }

  @Test
  void testMatchMethod() {
    ReggieMatcher m = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    MatchResult r = m.match(MATCH_INPUT);
    assertNotNull(r, "match() must return non-null for a full-input accept");
    assertEquals(0, r.start(0));
    assertEquals(150, r.end(0));
    assertNull(m.match("ab".repeat(74)), "match() must return null for a non-matching input");
  }

  @Test
  void testMatchBoundedMethod() {
    ReggieMatcher m = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    // input = "xx" + "ab"*75, substring [2, 152) is the ab-repeat portion
    String input = "xx" + MATCH_INPUT;
    MatchResult r = m.matchBounded(input, 2, 152);
    assertNotNull(r, "matchBounded() must return non-null when bounded region matches");
    assertEquals(2, r.start(0));
    assertEquals(152, r.end(0));
    // Region [0, 152) starts with "xx" — does not match the pattern
    assertNull(
        m.matchBounded(input, 0, 152),
        "matchBounded() must return null when region does not match");
  }

  @Test
  void testFindMatchFromMethod() {
    ReggieMatcher m = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    // embed the match at offset 2
    String input = "xx" + MATCH_INPUT + "yy";
    MatchResult r = m.findMatchFrom(input, 0);
    assertNotNull(r, "findMatchFrom() must find the ab-repeat substring");
    assertEquals(2, r.start(0));
    assertEquals(152, r.end(0));
    assertNull(m.findMatchFrom("xxxx", 0), "findMatchFrom() must return null when no match exists");
  }
}
