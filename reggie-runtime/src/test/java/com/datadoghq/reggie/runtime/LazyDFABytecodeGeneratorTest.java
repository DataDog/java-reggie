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

  @Test
  void testGeneratedClassMatchesNFAForSameInputs() {
    ReggieMatcher lazyMatcher = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    Pattern jdk = Pattern.compile(LARGE_NFA_PATTERN);

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
    ReggieMatcher m2 = RuntimeCompiler.compile(LARGE_NFA_PATTERN);
    Field cache1 = m1.getClass().getDeclaredField("CACHE");
    Field cache2 = m2.getClass().getDeclaredField("CACHE");
    cache1.setAccessible(true);
    cache2.setAccessible(true);
    assertSame(cache1.get(null), cache2.get(null));
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
}
