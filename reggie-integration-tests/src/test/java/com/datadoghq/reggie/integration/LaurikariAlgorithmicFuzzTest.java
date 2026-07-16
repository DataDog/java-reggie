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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import com.datadoghq.reggie.integration.fuzz.RandomInputGenerator;
import com.datadoghq.reggie.integration.fuzz.RandomRegexGenerator;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Finding;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Result;
import com.datadoghq.reggie.runtime.LaurikariDfaSupport;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Task 1.6b (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): JDK-oracle fuzz coverage for
 * {@code LaurikariDfaMatcher}, exercised directly via {@link LaurikariDfaSupport#tryCreate}
 * (bypassing the compile pipeline — Phase 2's routing wire-up hasn't landed yet).
 *
 * <p>Reuses {@link RandomRegexGenerator}/{@link RandomInputGenerator} from the existing algorithmic
 * fuzz harness but with an independent seed, so this sweep covers a disjoint area of the pattern
 * space from {@link AlgorithmicFuzzTest}. Patterns that {@code LaurikariEligibility.isEligible}
 * rejects are skipped (not findings) — this test is only about whether the new engine agrees with
 * the JDK on the patterns it does accept.
 *
 * <p>Unlike {@link AlgorithmicFuzzTest}'s {@code KNOWN_FINDINGS_BUDGET} (pre-existing, tracked
 * divergences in the shipped fallback routing), this gate is zero-tolerance: {@code
 * LaurikariDfaMatcher} has no production callers yet, so any divergence here is a genuine new bug,
 * not a known one.
 */
public class LaurikariAlgorithmicFuzzTest {

  private static final long BASE_SEED = 0xCAFEF00D_ABCDEFL;

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void smokeFuzz_eligiblePatternsAgreeWithJdk() {
    int patternCount = intProp("reggie.fuzz.laurikari.size", 2000);
    int inputsPerPattern = intProp("reggie.fuzz.laurikari.inputsPerPattern", 8);
    int patternDepth = intProp("reggie.fuzz.laurikari.patternDepth", 3);
    int inputMaxLength = intProp("reggie.fuzz.laurikari.inputMaxLength", 12);

    Random patternRng = new Random(BASE_SEED);
    Random inputRng = new Random(BASE_SEED ^ 0x9E3779B97F4A7C15L);
    RandomRegexGenerator regexGen = new RandomRegexGenerator(patternRng, patternDepth);
    RandomInputGenerator inputGen = new RandomInputGenerator(inputRng, inputMaxLength);
    RegexFuzzOracle oracle = new RegexFuzzOracle();

    int patternsTried = 0;
    int patternsEligible = 0;
    int inputsChecked = 0;
    List<Finding> findings = new ArrayList<>();

    for (int p = 0; p < patternCount; p++) {
      String pattern = regexGen.generate();
      patternsTried++;

      ReggieMatcher candidate = tryBuildLaurikari(pattern);
      if (candidate == null) continue;
      patternsEligible++;

      for (int i = 0; i < inputsPerPattern; i++) {
        String input = inputGen.generate();
        Result result = oracle.checkAgainst(pattern, input, candidate);
        if (result.skipped) continue;
        inputsChecked++;
        findings.addAll(result.findings);
      }
    }

    System.out.println(
        String.format(
            "[laurikari-fuzz] patterns=%d eligible=%d inputs-checked=%d findings=%d",
            patternsTried, patternsEligible, inputsChecked, findings.size()));
    for (Finding f : findings) {
      System.out.println("[laurikari-fuzz-finding] " + f);
    }

    assertTrue(
        patternsEligible > 0,
        "no generated pattern was Laurikari-eligible; the eligibility gate or generator may have"
            + " drifted");
    assertTrue(
        findings.isEmpty(),
        "LaurikariDfaMatcher diverged from JDK on "
            + findings.size()
            + " (pattern, input) pairs — see printed findings above; this is a new engine with no"
            + " production callers yet, so any divergence is a real bug, not a known one.");
  }

  /**
   * Parses {@code pattern}, builds its NFA, and asks {@link LaurikariDfaSupport#tryCreate} for a
   * matcher — mirroring {@code LaurikariDfaMatcherTest}'s direct-construction pattern. Returns
   * {@code null} if the JDK rejects the pattern, Reggie's parser/NFA builder rejects it, or {@code
   * LaurikariEligibility} rejects it (none of these are findings — only comparable pairs are).
   */
  private static ReggieMatcher tryBuildLaurikari(String pattern) {
    int groupCount;
    try {
      groupCount = Pattern.compile(pattern).matcher("").groupCount();
    } catch (PatternSyntaxException e) {
      return null;
    }
    RegexNode ast;
    NFA nfa;
    try {
      ast = new RegexParser().parse(pattern);
      // lazyAware=true: non-greedy quantifiers (e.g. `.+?`) need the "prefer accept, then loop"
      // epsilon-priority ordering to produce shortest-match semantics -- see ThompsonBuilder's
      // lazyAware javadoc and PatternAnalyzer's identical lazyNfa flag for the PIKEVM_CAPTURE
      // route. Safe for every other pattern: lazyAware only changes construction for quantifiers
      // with !greedy, so greedy/possessive shapes build identically either way.
      nfa = new ThompsonBuilder(true).build(ast, groupCount);
    } catch (Throwable t) {
      return null;
    }
    boolean usePosixLastMatch;
    try {
      usePosixLastMatch = new PatternAnalyzer(ast, nfa).analyzeAndRecommend().usePosixLastMatch;
    } catch (Throwable t) {
      // PatternAnalyzer has its own pre-existing gaps (e.g. some anchor combinations
      // LinearPatternAnalyzer doesn't handle) unrelated to Laurikari; skip rather than fail this
      // sweep on an unrelated bug.
      return null;
    }
    return LaurikariDfaSupport.tryCreate(nfa, pattern, groupCount, usePosixLastMatch);
  }

  private static int intProp(String name, int dflt) {
    String v = System.getProperty(name);
    if (v == null || v.isEmpty()) return dflt;
    try {
      int parsed = Integer.parseInt(v);
      return parsed > 0 ? parsed : dflt;
    } catch (NumberFormatException e) {
      return dflt;
    }
  }
}
