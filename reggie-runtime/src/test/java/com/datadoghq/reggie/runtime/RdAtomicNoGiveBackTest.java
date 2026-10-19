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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A possessive quantifier or an atomic group must not give back when a later concat child fails,
 * both in the recursive-descent generator's choice-point code (compiled through {@link
 * RuntimeCompiler#compileRecursiveDescentForTest}) and on the route {@link RuntimeCompiler#compile}
 * picks (the backtracker, or the one-way generator). The conditional {@code (x)?..(?(1)y)} makes
 * the pattern recursive-descent; on inputs without 'x' it matches like the JDK pattern without it.
 */
class RdAtomicNoGiveBackTest {

  @Test
  void possessiveAndAtomicChildrenDoNotGiveBack() throws Exception {
    String[][] cases = {
      {"a?+a", "a"},
      {"(?>a?)a", "a"},
      {"(?>a{1,3})a", "aa"},
      {"(?>[ab]*)b", "ab"},
      {"(?>a|ab)c", "abc"},
      {"(?>(a+))a", "aa"},
      {"a{1,3}+a", "aaaa"},
      {"(?>a?)b", "ab"},
    };
    for (String[] c : cases) {
      String pattern = "(x)?" + c[0] + "(?(1)y)";
      RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(pattern);
      assertTrue(
          info.routing.startsWith("BACKREF_BACKTRACK")
              || info.routing.startsWith("RECURSIVE_DESCENT"),
          pattern + " -> " + info.routing);
      assertAgreesWithJdk(c[0], c[1], pattern, info.matcher);
      assertAgreesWithJdk(
          c[0], c[1], pattern, RuntimeCompiler.compileRecursiveDescentForTest(pattern));
    }
  }

  private static void assertAgreesWithJdk(
      String jdkPattern, String input, String pattern, ReggieMatcher m) {
    Matcher jdk = Pattern.compile(jdkPattern).matcher(input);
    assertEquals(jdk.matches(), m.matches(input), pattern);
    boolean found = jdk.find(0);
    MatchResult r = m.findMatch(input);
    assertEquals(found, r != null, pattern);
    if (found) {
      assertEquals(jdk.start(), r.start(), pattern);
      assertEquals(jdk.end(), r.end(), pattern);
    }
  }
}
