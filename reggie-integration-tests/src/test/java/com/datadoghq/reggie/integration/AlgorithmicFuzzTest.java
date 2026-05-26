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

import com.datadoghq.reggie.integration.fuzz.FuzzRunner;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Finding;
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

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void smokeFuzz_smallDeterministicSweep() {
    FuzzRunner.Config cfg = new FuzzRunner.Config();
    cfg.seed = BASE_SEED;
    cfg.patternCount = sizedPatternCount(500);
    cfg.inputsPerPattern = 8;
    cfg.patternDepth = 3;
    cfg.inputMaxLength = 12;

    FuzzRunner.Report report = new FuzzRunner().run(cfg);
    System.out.println("[algorithmic-fuzz] " + report.summary());

    // Print the first several findings for triage. Cap to keep CI logs sane.
    int printed = 0;
    for (Finding f : report.findings) {
      if (printed >= 30) {
        System.out.println("[algorithmic-fuzz] ... and " + (report.findings.size() - 30) + " more");
        break;
      }
      System.out.println("[algorithmic-fuzz] " + f);
      printed++;
    }

    // Backstop: if the divergence count blows up beyond a generous ceiling, fail. This is a
    // regression-detection guard, not a quality target — tighten the threshold as bugs are
    // fixed and confirmed.
    int ceiling = (int) (cfg.patternCount * cfg.inputsPerPattern * 0.25);
    assertTrue(
        report.findings.size() < ceiling,
        "Fuzz produced "
            + report.findings.size()
            + " findings (> ceiling "
            + ceiling
            + "). Look at the printed findings; this is likely a regression.");
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
