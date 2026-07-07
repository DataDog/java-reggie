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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadoghq.reggie.integration.fuzz.FuzzRunner;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Finding;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzShrinker;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzShrinker.Shrunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Grammar-driven fuzz test cross-checking Reggie against {@link java.util.regex.Pattern}.
 *
 * <p>The {@code @Test} method below runs a small deterministic sample on every {@code check}, so CI
 * catches new regressions cheaply. Larger sweeps are gated behind the {@code reggie.fuzz.size}
 * system property to keep day-to-day test runs fast.
 *
 * <p>Findings are printed but the test does <em>not</em> fail automatically — Reggie has known
 * pre-existing divergences from JDK semantics, and a noisy assertion would obscure real
 * regressions. The {@code maxFindings} guard exists to detect runaway regressions: if a new bug
 * suddenly produces thousands of findings in the default 500-pattern sweep, the test fails.
 */
public class AlgorithmicFuzzTest {

  private static final long BASE_SEED = 0xC0DEFEED_DEADBEEFL;

  /**
   * Known pre-existing divergence budget for the active fuzz window: {@link #BASE_SEED}, patterns
   * 25 001–50 000 (skip=25 000, count=25 000, depth=3, 16 inputs × max-length 16). The window
   * starts after the range already cleared to zero by the B3a/B3b/B4/B5/B6 fixes. Every finding is
   * a tracked bug — not a regression. Clustered inventory: {@code doc/fuzz/2026-06-29.md}.
   *
   * <p>When this reaches 0: advance the window — run {@code -Dreggie.fuzz.skip=50000
   * -Dreggie.fuzz.size=25000 -Dreggie.fuzz.maxFindings=9999}, document new findings in {@code
   * doc/fuzz/YYYY-MM-DD.md}, then update skip and this budget to match.
   *
   * <p>History: 18→78 (findAll group-span oracle) → 69→65→13→0 (B3a/B3b/B4/B5/B6, window 0–25k) →
   * window advanced to 25k–50k → 34 (E1–E6 found; see {@code doc/fuzz/2026-06-29.md}) → 28 (B-CGG-1
   * negated-CharClass in SPECIALIZED_CONCAT_GREEDY_GROUP, B-SQG-1 inner-min&gt;1 in
   * SPECIALIZED_QUANTIFIED_GROUP routed to fallback).
   */
  private static final int KNOWN_FINDINGS_BUDGET = 28;

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void smokeFuzz_smallDeterministicSweep() {
    FuzzRunner.Config cfg = new FuzzRunner.Config();
    cfg.seed = BASE_SEED;
    cfg.patternCount = sizedPatternCount(2000);
    cfg.inputsPerPattern = intProp("reggie.fuzz.inputsPerPattern", 8);
    cfg.patternDepth = intProp("reggie.fuzz.patternDepth", 3);
    cfg.inputMaxLength = intProp("reggie.fuzz.inputMaxLength", 12);

    FuzzRunner.Report report = new FuzzRunner().run(cfg);
    System.out.println("[algorithmic-fuzz] " + report.summary());

    // Shrink each finding and dedupe by (shrunk pattern, shrunk input, kind). Raw findings are
    // often 30-char patterns reproducing the same underlying bug at different sizes; shrinking
    // collapses them to a handful of unique minimal repros that can be triaged directly.
    RegexFuzzShrinker shrinker = new RegexFuzzShrinker();
    Map<String, Shrunk> uniqueShrunk = new LinkedHashMap<>();
    int shrunk = 0;
    int shrinkLimit = Math.min(report.findings.size(), 80); // bound CPU on enormous reports
    for (Finding f : report.findings) {
      if (shrunk >= shrinkLimit) break;
      Shrunk s = shrinker.shrink(f);
      String key = s.findingKind + "||" + s.pattern + "||" + s.input;
      uniqueShrunk.putIfAbsent(key, s);
      shrunk++;
    }
    System.out.println(
        "[algorithmic-fuzz] shrunk "
            + shrunk
            + " findings -> "
            + uniqueShrunk.size()
            + " unique minimal repros");

    int printed = 0;
    for (Shrunk s : uniqueShrunk.values()) {
      if (printed >= 40) {
        System.out.println(
            "[algorithmic-fuzz] ... and " + (uniqueShrunk.size() - 40) + " more unique repros");
        break;
      }
      System.out.println(
          "[algorithmic-fuzz-repro] "
              + s.findingKind
              + ": pattern="
              + s.pattern
              + " input="
              + s.input);
      printed++;
    }

    // Zero-tolerance gate: all checked patterns must agree with JDK. Both gates are
    // enforced in CI. Any finding is a regression.
    assertTrue(
        report.findings.size() == 0,
        "Fuzz produced "
            + report.findings.size()
            + " findings (expected 0). Look at the printed findings; this is a regression.");
  }

  /**
   * Large deterministic sweep that asserts divergences between Reggie and the JDK stay within the
   * known budget. This is the production-readiness gate. It runs from the same fixed {@link
   * #BASE_SEED} as the smoke test, so the (pattern, input) stream and minimal repro set are fully
   * reproducible.
   *
   * <p>Runs unconditionally. The companion {@link #divergenceGate_enforcedViaProperty()} can also
   * be triggered via {@code -Dreggie.fuzz.enforce=true} without editing source.
   */
  @Test
  @Timeout(value = 600, unit = TimeUnit.SECONDS)
  public void divergenceGate() {
    runDivergenceGate();
  }

  /**
   * Second-seed gate: same dimensions as {@link #divergenceGate} but with an independent seed, so
   * it covers a disjoint area of the pattern/input space. Self-skips unless {@code
   * -Dreggie.fuzz.altSeed=true} is set — the alt seed can surface pre-existing bugs in strategies
   * not reached by {@link #BASE_SEED}, so it serves as a discovery tool rather than a hard CI gate.
   * Use {@code -Dreggie.fuzz.maxFindings=N} to allow a known number of pre-existing divergences.
   */
  @Test
  @Timeout(value = 600, unit = TimeUnit.SECONDS)
  public void divergenceGate_altSeed() {
    assumeTrue(
        Boolean.getBoolean("reggie.fuzz.altSeed"),
        "set -Dreggie.fuzz.altSeed=true to run the alt-seed discovery sweep");
    FuzzRunner.Config cfg = largeSweepConfig();
    cfg.seed = BASE_SEED ^ 0x5555_AAAA_1234_5678L;
    runDivergenceGate(cfg, "[divergence-gate-alt]");
  }

  /**
   * Companion entry point that is <em>not</em> {@code @Disabled}: it self-skips unless {@code
   * -Dreggie.fuzz.enforce=true} is set, letting CI exercise the gate without editing source.
   *
   * <p>Defaults to {@link #KNOWN_FINDINGS_BUDGET}; override via {@code
   * -Dreggie.fuzz.maxFindings=N}. Use {@code -Dreggie.fuzz.maxFindings=0} to enforce strict zero
   * divergences locally.
   */
  @Test
  @Timeout(value = 600, unit = TimeUnit.SECONDS)
  public void divergenceGate_enforcedViaProperty() {
    assumeTrue(
        Boolean.getBoolean("reggie.fuzz.enforce"),
        "set -Dreggie.fuzz.enforce=true to activate the divergence gate");
    runDivergenceGate(largeSweepConfig(), "[divergence-gate]", KNOWN_FINDINGS_BUDGET);
  }

  private void runDivergenceGate() {
    runDivergenceGate(largeSweepConfig(), "[divergence-gate]");
  }

  private void runDivergenceGate(FuzzRunner.Config cfg, String tag) {
    runDivergenceGate(cfg, tag, KNOWN_FINDINGS_BUDGET);
  }

  private void runDivergenceGate(FuzzRunner.Config cfg, String tag, int maxFindingsDefault) {
    FuzzRunner.Report report = new FuzzRunner().run(cfg);
    System.out.println(tag + " " + report.summary());

    int totalChecks = cfg.patternCount * cfg.inputsPerPattern;
    assertTrue(
        totalChecks >= 50_000,
        "gate must sweep at least 50k checks; configured for " + totalChecks);

    List<Shrunk> repros = shrinkAndDedupe(report);
    for (Shrunk s : repros) {
      System.out.println(
          tag + "-repro " + s.findingKind + ": pattern=" + s.pattern + " input=" + s.input);
    }

    int maxFindings = Integer.getInteger("reggie.fuzz.maxFindings", maxFindingsDefault);
    if (maxFindings > 0) {
      System.out.println(tag + " budget=" + maxFindings + " (known pre-existing findings)");
    }
    assertTrue(
        report.findings.size() <= maxFindings,
        tag
            + " found "
            + report.findings.size()
            + " divergences (budget="
            + maxFindings
            + ", "
            + repros.size()
            + " unique minimal repros). See printed repros and"
            + " doc/temp/prod-readiness/fuzz-inventory.md.");
  }

  /**
   * Single source of truth for the large sweep dimensions, so the gate and any discovery run use
   * identical (deterministic) parameters.
   *
   * <p>Tunable via system properties:
   *
   * <ul>
   *   <li>{@code -Dreggie.fuzz.size=N} — pattern count (default 25_000)
   *   <li>{@code -Dreggie.fuzz.skip=N} — patterns to skip at the start of the sequence (default
   *       25_000; use 0 to rerun from the beginning of the sequence)
   *   <li>{@code -Dreggie.fuzz.inputsPerPattern=N} — inputs per pattern (default 16)
   *   <li>{@code -Dreggie.fuzz.inputMaxLength=N} — max input string length (default 16)
   *   <li>{@code -Dreggie.fuzz.patternDepth=N} — max regex AST depth (default 3)
   * </ul>
   */
  static FuzzRunner.Config largeSweepConfig() {
    FuzzRunner.Config cfg = new FuzzRunner.Config();
    cfg.seed = BASE_SEED;
    cfg.patternCount = sizedPatternCount(25_000);
    cfg.patternSkip = intPropNonNeg("reggie.fuzz.skip", 25_000);
    cfg.inputsPerPattern = intProp("reggie.fuzz.inputsPerPattern", 16);
    cfg.patternDepth = intProp("reggie.fuzz.patternDepth", 3);
    cfg.inputMaxLength = intProp("reggie.fuzz.inputMaxLength", 16);
    return cfg;
  }

  /** Read an int system property, returning {@code dflt} when absent or unparseable. */
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

  /**
   * Read an int system property, returning {@code dflt} when absent or unparseable. Unlike {@link
   * #intProp}, this helper accepts zero as a valid value; only negative values fall back to the
   * default.
   */
  private static int intPropNonNeg(String name, int dflt) {
    String v = System.getProperty(name);
    if (v == null || v.isEmpty()) return dflt;
    try {
      int parsed = Integer.parseInt(v);
      return parsed >= 0 ? parsed : dflt;
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  /**
   * Shrink every finding to a minimal repro and dedupe by (kind, pattern, input). Deterministic
   * across runs with the same seed.
   */
  static List<Shrunk> shrinkAndDedupe(FuzzRunner.Report report) {
    RegexFuzzShrinker shrinker = new RegexFuzzShrinker();
    Map<String, Shrunk> unique = new LinkedHashMap<>();
    for (Finding f : report.findings) {
      Shrunk s = shrinker.shrink(f);
      String key = s.findingKind + "||" + s.pattern + "||" + s.input;
      unique.putIfAbsent(key, s);
    }
    return new ArrayList<>(unique.values());
  }

  /**
   * Allow CI / local invocations to scale the sweep up via {@code -Dreggie.fuzz.size=...}. The
   * value is interpreted as a pattern count; a value of 0 keeps the default.
   */
  private static int sizedPatternCount(int dflt) {
    String prop = System.getProperty("reggie.fuzz.size");
    if (prop == null || prop.isEmpty()) return dflt;
    try {
      int v = Integer.parseInt(prop);
      return v > 0 ? v : dflt;
    } catch (NumberFormatException nfe) {
      return dflt;
    }
  }
}
