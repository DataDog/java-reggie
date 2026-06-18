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
package com.datadoghq.reggie.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.integration.fuzz.RandomRegexGenerator;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Diagnoses whether $ anchor patterns fail due to structural-cache collisions when compiled after
 * the same 2000-pattern fuzz sweep that the AlgorithmicFuzzTest uses.
 */
public class DollarAnchorCacheDiagTest {

  private static final long BASE_SEED = 0xC0DEFEED_DEADBEEFL;
  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void remainingReprosDiagnosticAfterSweep() {
    // Run the same 2000-pattern sweep as AlgorithmicFuzzTest, then check repro patterns.
    RuntimeCompiler.clearCache();
    Random rng = new Random(BASE_SEED);
    com.datadoghq.reggie.integration.fuzz.RandomRegexGenerator gen =
        new com.datadoghq.reggie.integration.fuzz.RandomRegexGenerator(rng, 3);
    for (int i = 0; i < 2000; i++) {
      try {
        Reggie.compile(gen.generate());
      } catch (Exception ignored) {
      }
    }
    System.out.println("[sweep-diag] cache size after sweep: " + RuntimeCompiler.cacheSize());
    // Check if problematic patterns were generated during the sweep
    for (String probe :
        new String[] {"[^1]\\Z|-", "(.)\\1+", "(-)a|[^b](?:\\1[_-b][-]|1{2}\\1{0})"}) {
      boolean inCache = RuntimeCompiler.cachedPatterns().contains(probe);
      System.out.println("[sweep-diag] pattern-in-L1-cache: " + probe + " = " + inCache);
    }
    doRemainingReprosDiag("sweep");
  }

  @Test
  void remainingReprosDiagnostic() {
    RuntimeCompiler.clearCache();
    doRemainingReprosDiag("fresh");
  }

  private static void doRemainingReprosDiag(String tag) {
    com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle oracle =
        new com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle();
    String[][] cases = {
      {"[^1]\\Z|-", ""},
      {"[^1]\\Z|-", "\n"},
      {"([0]?-*).(1{3}|-\\1)", "0-"},
      {"()(\\1|1{2}1{0})", ""},
      {"(-)a|[^b](?:\\1[_-b][-]|1{2}\\1{0})", ""},
      {"(.)\\1+", ""},
      {"(]{1})(1{0})|(\\1{2})[-]", "-"},
      {"(]{1})(1{0})|(\\1{2})[0]", "0"},
      {"_*0|(a{2}|-+){3,}Z", "0"},
      {"[--a]c?()|([^a]\\1)\\1+", "b0"},
      {"(){1}|.(\\1)", "1"},
      {"(){1}(\\1)", ""},
      {"(){1}|.(\\1)", "-"},
    };
    for (String[] tc : cases) {
      com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Result r = oracle.check(tc[0], tc[1]);
      String cls = "?";
      try {
        cls = Reggie.compile(tc[0], WITH_FALLBACK).getClass().getSimpleName();
      } catch (Exception ignored) {
      }
      System.out.printf(
          "[%s-diag] pat=%-35s inp=%s class=%-35s skipped=%s findings=%d%n",
          tag, tc[0], "\"" + tc[1].replace("\n", "\\n") + "\"", cls, r.skipped, r.findings.size());
      for (var f : r.findings) System.out.println("  -> " + f.description);
    }
  }

  @Test
  void dollarPatternsOracleCheck() {
    // Directly call the oracle (which calls matches() + findMatch() in sequence)
    // on the exact repro patterns after the fuzz sweep.
    RuntimeCompiler.clearCache();
    Random rng = new Random(BASE_SEED);
    RandomRegexGenerator gen = new RandomRegexGenerator(rng, 3);
    for (int i = 0; i < 2000; i++) {
      try {
        Reggie.compile(gen.generate());
      } catch (Exception ignored) {
      }
    }
    System.out.println("[oracle-diag] cache after sweep: " + RuntimeCompiler.cacheSize());

    com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle oracle =
        new com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle();
    String[][] cases = {
      {"c$", "c"}, {".$", "b"}, {"[b]${1}", "b"}, {"$", "c"}, {"a?$", ""},
      {".{0}$", ""}, {"${1}", ""}, {"Z{1}|$", ""}, {"0|${1}", ""}, {"$", ""},
    };
    boolean anyFail = false;
    for (String[] tc : cases) {
      com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Result r = oracle.check(tc[0], tc[1]);
      boolean hasFinding = !r.skipped && !r.findings.isEmpty();
      System.out.printf(
          "[oracle-diag] pat=%-20s inp=%-5s skipped=%s findings=%d%n",
          tc[0], "\"" + tc[1] + "\"", r.skipped, r.findings.size());
      for (var f : r.findings) System.out.println("  -> " + f.description);
      if (hasFinding) anyFail = true;
    }
    if (anyFail) {
      throw new AssertionError("Oracle reports findings for $ patterns — see stdout");
    }
  }

  @Test
  void dollarPatternsWorkAfterFuzzSweep() {
    // Start with a clean cache, then populate it the same way the fuzz test does.
    RuntimeCompiler.clearCache();

    Random rng = new Random(BASE_SEED);
    RandomRegexGenerator gen = new RandomRegexGenerator(rng, 3);
    for (int i = 0; i < 2000; i++) {
      String pat = gen.generate();
      try {
        Reggie.compile(pat);
      } catch (Exception ignored) {
        // Some patterns may be rejected; that's fine.
      }
    }

    System.out.println("[diag] cache size after sweep: " + RuntimeCompiler.cacheSize());

    // Now test the failing $ patterns — calling matches() FIRST (like the oracle does),
    // then findMatch(). If the matcher has mutable NFA state, matches() may corrupt it.
    String[][] cases = {
      {"c$", "c"},
      {".$", "b"},
      {"[b]${1}", "b"},
      {"$", "c"},
      {"a?$", ""},
      {".{0}$", ""},
      {"${1}", ""},
      {"Z{1}|$", ""},
      {"0|${1}", ""},
      {"$c?", ""},
      {"$", ""},
      {"a?$", ""},
    };

    boolean anyFail = false;
    for (String[] tc : cases) {
      String pat = tc[0], inp = tc[1];
      Pattern jdk = Pattern.compile(pat);
      Matcher jm = jdk.matcher(inp);
      boolean jdkFound = jm.find();

      ReggieMatcher rm = Reggie.compile(pat, WITH_FALLBACK);
      // Call matches() first, like the oracle does — this may corrupt NFA state
      rm.matches(inp);
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

      if (!ok) anyFail = true;
    }

    if (anyFail) {
      throw new AssertionError("$ anchor pattern(s) failed after fuzz sweep — see stdout above");
    }
  }

  @Test
  void backrefEmptyGroupDirectTest() {
    RuntimeCompiler.clearCache();
    String[][] cases = {
      {"(){1}(\\1)", ""},
      {"()(\\1|1{2}1{0})", ""},
      {"(){1}|.(\\1)", "1"},
      {"([0]?-*).(1{3}|-\\1)", "0-"},
    };
    for (String[] tc : cases) {
      String pat = tc[0], inp = tc[1];
      Pattern jdk = Pattern.compile(pat);
      ReggieMatcher rm = Reggie.compile(pat, WITH_FALLBACK);
      boolean jdkM = jdk.matcher(inp).matches();
      boolean reggieM = rm.matches(inp);
      Matcher jm = jdk.matcher(inp);
      boolean jdkF = jm.find();
      MatchResult rmr = rm.findMatch(inp);
      boolean reggieF = rmr != null;
      System.out.printf(
          "[backref-diag] pat=%-30s inp=%-5s class=%-20s%n  matches: jdk=%s reg=%s  find: jdk=%s reg=%s%n",
          pat, "\"" + inp + "\"", rm.getClass().getSimpleName(), jdkM, reggieM, jdkF, reggieF);
      assertEquals(jdkM, reggieM, "matches() mismatch for pat='" + pat + "' inp='" + inp + "'");
      assertEquals(jdkF, reggieF, "find() mismatch for pat='" + pat + "' inp='" + inp + "'");
    }
  }
}
