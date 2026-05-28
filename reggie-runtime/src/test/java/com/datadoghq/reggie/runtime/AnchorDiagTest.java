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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Temporary diagnostic for fuzz $ anchor findings. */
public class AnchorDiagTest {
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
      String pat = tc[0], inp = tc[1];
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
      java.util.regex.Matcher jm = jdk.matcher(inp);
      boolean jdkFound = jm.find();

      ReggieMatcher rm = Reggie.compile(pat);
      MatchResult r = rm.findMatch(inp);
      boolean reggieFound = r != null;

      boolean ok =
          (jdkFound == reggieFound)
              && (!jdkFound || (jm.start() == r.start() && jm.end() == r.end()));
      System.out.printf(
          "%s  pat=%-20s inp=%-5s jdk=%s reggie=%s class=%s%n",
          ok ? "OK  " : "FAIL",
          pat,
          "\"" + inp + "\"",
          jdkFound ? "[" + jm.start() + "," + jm.end() + ")" : "null",
          reggieFound ? "[" + r.start() + "," + r.end() + ")" : "null",
          rm.getClass().getSimpleName());
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
    RuntimeCompiler.clearCache();
    Pattern jdk = Pattern.compile(pat);
    Matcher jm = jdk.matcher(inp);
    boolean jdkFound = jm.find();

    ReggieMatcher rm = Reggie.compile(pat);
    MatchResult r = rm.findMatch(inp);
    boolean reggieFound = r != null;

    String jdkSpan = jdkFound ? "[" + jm.start() + "," + jm.end() + ")" : "null";
    String reggieSpan = reggieFound ? "[" + r.start() + "," + r.end() + ")" : "null";
    boolean ok =
        (jdkFound == reggieFound)
            && (!jdkFound || (jm.start() == r.start() && jm.end() == r.end()));
    System.out.printf(
        "%s  pat=%-25s inp=%-8s jdk=%-12s reggie=%-12s strategy=%s%n",
        ok ? "OK  " : "FAIL",
        pat,
        "\"" + inp + "\"",
        jdkSpan,
        reggieSpan,
        rm.getClass().getSimpleName());
  }
}
