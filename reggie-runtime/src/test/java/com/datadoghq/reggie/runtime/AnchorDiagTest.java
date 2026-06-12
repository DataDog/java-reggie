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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Regression coverage for fuzz $ anchor findings. */
class AnchorDiagTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void diagNoClearCacheEver() {
    // Verify that $ patterns work correctly even when compiled AFTER many other patterns,
    // without any clearCache() in between. Bypasses check() to avoid its internal clearCache().
    RuntimeCompiler.clearCache(); // single clear at start only
    for (char c = 'a'; c <= 'z'; c++) {
      Reggie.compile(String.valueOf(c));
      Reggie.compile("[" + c + "]");
      Reggie.compile(c + ".");
      Reggie.compile("." + c);
      if (c != 'a') Reggie.compile(c + "$");
      if (c != 'z') Reggie.compile("." + c + "$");
    }
    for (char d = '0'; d <= '9'; d++) {
      Reggie.compile(String.valueOf(d));
      Reggie.compile(d + "$");
    }
    // Now test the $ patterns without any additional clearCache
    String[][] cases = {
      {"c$", "c"}, {".$", "b"}, {"[b]${1}", "b"}, {"$", "c"}, {"$", "_"},
      {"a?$", ""}, {".{0}$", ""}, {"${1}", ""}, {"Z{1}|$", ""}, {"0|${1}", ""}
    };
    for (String[] tc : cases) {
      assertFindEquivalent(tc[0], tc[1], false);
    }
  }

  @Test
  void diag() {
    check("c$", "c");
    check(".$", "b");
    check("[b]${1}", "b");
    check("$", "c");
    check("$", "_");
    check("a?$", "");
    check(".{0}$", "");
    check("$c?", "");
    check("${1}", "");
    check("${3}", "");
    check("Z{1}|$", "");
    check("0|${1}", "");
    check("[c]*(?:[_]?-)$|]", "-");
    check("^{1}|.", "b");
  }

  static void check(String pat, String inp) {
    assertFindEquivalent(pat, inp, true, WITH_FALLBACK);
  }

  private static void assertFindEquivalent(String pat, String inp, boolean clearCache) {
    assertFindEquivalent(pat, inp, clearCache, ReggieOptions.DEFAULT);
  }

  private static void assertFindEquivalent(
      String pat, String inp, boolean clearCache, ReggieOptions options) {
    if (clearCache) {
      RuntimeCompiler.clearCache();
    }
    Pattern jdk = Pattern.compile(pat);
    Matcher jm = jdk.matcher(inp);
    boolean jdkFound = jm.find();

    ReggieMatcher rm = Reggie.compile(pat, options);
    MatchResult r = rm.findMatch(inp);
    boolean reggieFound = r != null;

    assertEquals(jdkFound, reggieFound, failureMessage(pat, inp, jm, jdkFound, r, rm));
    if (jdkFound) {
      assertEquals(jm.start(), r.start(), failureMessage(pat, inp, jm, jdkFound, r, rm));
      assertEquals(jm.end(), r.end(), failureMessage(pat, inp, jm, jdkFound, r, rm));
    }
  }

  private static String failureMessage(
      String pat, String inp, Matcher jm, boolean jdkFound, MatchResult r, ReggieMatcher rm) {
    return "pat="
        + pat
        + " input=\""
        + inp
        + "\" jdk="
        + span(jm, jdkFound)
        + " reggie="
        + span(r)
        + " strategy="
        + rm.getClass().getSimpleName();
  }

  private static String span(Matcher matcher, boolean found) {
    return found ? "[" + matcher.start() + "," + matcher.end() + ")" : "null";
  }

  private static String span(MatchResult result) {
    return result != null ? "[" + result.start() + "," + result.end() + ")" : "null";
  }
}
