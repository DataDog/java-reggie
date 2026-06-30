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
package com.datadoghq.reggie.integration.fuzz;

import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Finding;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Driver that pairs a {@link RandomRegexGenerator} with {@link RandomInputGenerator} and runs each
 * (pattern, input) through {@link RegexFuzzOracle}. Reports aggregated stats and a deduped list of
 * findings.
 */
public final class FuzzRunner {

  public static final class Report {
    public final int patternsTried;
    public final int patternsSkipped;
    public final int inputsChecked;
    public final List<Finding> findings;

    public Report(int patternsTried, int patternsSkipped, int inputsChecked, List<Finding> f) {
      this.patternsTried = patternsTried;
      this.patternsSkipped = patternsSkipped;
      this.inputsChecked = inputsChecked;
      this.findings = f;
    }

    public String summary() {
      return String.format(
          "patterns=%d skipped=%d inputs-checked=%d findings=%d",
          patternsTried, patternsSkipped, inputsChecked, findings.size());
    }
  }

  /** Builder-style config so test methods can override defaults without long argument lists. */
  public static final class Config {
    public long seed = 0xC0DEFEED_DEADBEEFL;
    public int patternCount = 500;
    public int inputsPerPattern = 8;
    public int patternDepth = 3;
    public int inputMaxLength = 12;

    /**
     * Number of (pattern, input) batches to skip at the start of the sequence. Both the pattern RNG
     * and input RNG are advanced by {@code patternSkip * inputsPerPattern} steps so the remaining
     * run covers fresh territory not exercised by a sweep of the same seed with a lower pattern
     * count. Reproducible: given identical (seed, patternSkip, patternCount) the findings are
     * always the same.
     */
    public int patternSkip = 0;

    /** Cap the number of findings retained per pattern to avoid quadratic-style log explosions. */
    public int findingsPerPatternCap = 3;
  }

  public Report run(Config cfg) {
    Random patternRng = new Random(cfg.seed);
    Random inputRng = new Random(cfg.seed ^ 0x9E3779B97F4A7C15L);

    RandomRegexGenerator regexGen = new RandomRegexGenerator(patternRng, cfg.patternDepth);
    RandomInputGenerator inputGen = new RandomInputGenerator(inputRng, cfg.inputMaxLength);
    RegexFuzzOracle oracle = new RegexFuzzOracle();

    // Advance both RNGs past the skip window so the active range starts at a fresh position.
    // inputsPerPattern steps per skipped pattern is conservative (ignores compile-time rejects
    // that would consume fewer inputs in a real run) but keeps the skip deterministic without
    // running the oracle.
    for (int p = 0; p < cfg.patternSkip; p++) {
      regexGen.generate();
      for (int i = 0; i < cfg.inputsPerPattern; i++) {
        inputGen.generate();
      }
    }

    int skipped = 0;
    int inputs = 0;
    List<Finding> findings = new ArrayList<>();

    for (int p = 0; p < cfg.patternCount; p++) {
      String pattern = regexGen.generate();
      int findingsThisPattern = 0;
      boolean patternSkipped = false;

      for (int i = 0; i < cfg.inputsPerPattern; i++) {
        String input = inputGen.generate();
        Result result = oracle.check(pattern, input);

        if (result.skipped) {
          // Most "skipped" reasons are pattern-level (compile-time rejection from either engine);
          // bail on the remaining inputs for this pattern when that's the case.
          if (i == 0) {
            patternSkipped = true;
            break;
          }
          // Mid-iteration skip — e.g. a runtime throw on a specific input. Record once.
          break;
        }
        inputs++;

        for (Finding f : result.findings) {
          if (findingsThisPattern < cfg.findingsPerPatternCap) {
            findings.add(f);
            findingsThisPattern++;
          }
        }
      }

      if (patternSkipped) skipped++;
    }

    return new Report(cfg.patternCount - skipped, skipped, inputs, findings);
  }
}
