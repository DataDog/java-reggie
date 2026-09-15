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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Driver that pairs a {@link RandomRegexGenerator} with {@link RandomInputGenerator} and runs each
 * (pattern, input) through {@link RegexFuzzOracle}. Reports aggregated stats and a deduped list of
 * findings.
 *
 * <p>Each {@code oracle.check()} is run on a daemon thread with a wall-clock budget ({@link
 * Config#checkTimeoutMs}). This prevents a single (pattern, input) pair that triggers JDK
 * catastrophic backtracking (ReDoS) from stalling the entire sweep. JDK backtracking is CPU-bound
 * and ignores {@code Thread.interrupt()}, so a timed-out check is abandoned: the daemon thread
 * keeps running in the background but the sweep moves on to the next input. Abandoned threads are
 * daemons and do not block JVM exit.
 */
public final class FuzzRunner {

  public static final class Report {
    public final int patternsTried;
    public final int patternsSkipped;
    public final int inputsChecked;
    public final int checksTimedOut;
    public final List<Finding> findings;

    public Report(
        int patternsTried,
        int patternsSkipped,
        int inputsChecked,
        int checksTimedOut,
        List<Finding> f) {
      this.patternsTried = patternsTried;
      this.patternsSkipped = patternsSkipped;
      this.inputsChecked = inputsChecked;
      this.checksTimedOut = checksTimedOut;
      this.findings = f;
    }

    public String summary() {
      return String.format(
          "patterns=%d skipped=%d inputs-checked=%d checks-timed-out=%d findings=%d",
          patternsTried, patternsSkipped, inputsChecked, checksTimedOut, findings.size());
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

    /**
     * Per-check wall-clock budget in milliseconds. A single {@code oracle.check()} that exceeds
     * this is abandoned and counted as timed out. Default 5 s; override via {@code
     * -Dreggie.fuzz.checkTimeoutMs=N}. Set to 0 to disable the timeout (not recommended for large
     * sweeps — a single JDK ReDoS input can stall for hours).
     */
    public long checkTimeoutMs = Long.getLong("reggie.fuzz.checkTimeoutMs", 5_000);
  }

  /** Progress print interval (number of patterns between milestone lines). */
  private static final int PROGRESS_INTERVAL =
      Math.max(1, Integer.getInteger("reggie.fuzz.progressInterval", 500));

  private final ExecutorService checkPool =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "fuzz-check");
            t.setDaemon(true);
            return t;
          });

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
    int timedOut = 0;
    List<Finding> findings = new ArrayList<>();

    int redosSkipped = 0;

    for (int p = 0; p < cfg.patternCount; p++) {
      String pattern = regexGen.generate();

      // Pre-screen for nested-quantifier ReDoS shapes (e.g. (a+)+, (a*)*, (a?){2,}).
      // The JDK oracle backtracks catastrophically on these and Thread.stop() is gone in
      // Java 21+, so a timed-out check leaks heap until OOM. Skip the oracle entirely.
      if (hasNestedQuantifier(pattern)) {
        skipped++;
        redosSkipped++;
        if (p % PROGRESS_INTERVAL == 0) {
          System.out.printf(
              "[fuzz] p=%d/%d skipped=%d inputs=%d timed-out=%d findings=%d pattern=%s [ReDoS skip]%n",
              p,
              cfg.patternCount,
              skipped,
              inputs,
              timedOut,
              findings.size(),
              pattern.length() > 120 ? pattern.substring(0, 120) + "..." : pattern);
        }
        continue;
      }

      if (p % PROGRESS_INTERVAL == 0) {
        System.out.printf(
            "[fuzz] p=%d/%d skipped=%d inputs=%d timed-out=%d findings=%d pattern=%s%n",
            p,
            cfg.patternCount,
            skipped,
            inputs,
            timedOut,
            findings.size(),
            pattern.length() > 120 ? pattern.substring(0, 120) + "..." : pattern);
      }
      int findingsThisPattern = 0;
      boolean patternSkipped = false;

      for (int i = 0; i < cfg.inputsPerPattern; i++) {
        String input = inputGen.generate();
        Result result = checkWithTimeout(oracle, pattern, input, cfg.checkTimeoutMs);

        if (result.skipped) {
          if (result.skipReason != null && result.skipReason.startsWith("check timeout")) {
            timedOut++;
            System.out.printf(
                "[fuzz]   TIMEOUT p=%d i=%d (%dms) pattern=%s input=%s%n",
                p,
                i,
                cfg.checkTimeoutMs,
                pattern,
                input.length() > 60 ? input.substring(0, 60) + "..." : input);
            // A timeout is input-specific, not pattern-level: keep checking remaining inputs.
            continue;
          }
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

    checkPool.shutdownNow();
    if (redosSkipped > 0) {
      System.out.printf("[fuzz] ReDoS pre-screen skipped %d patterns%n", redosSkipped);
    }
    return new Report(cfg.patternCount - skipped, skipped, inputs, timedOut, findings);
  }

  /**
   * Run {@code oracle.check()} on a daemon thread with a wall-clock budget. On timeout the check is
   * abandoned (the daemon thread continues in the background) and a skipped {@link Result} is
   * returned. When {@code timeoutMs <= 0} the check runs inline with no timeout.
   */
  private Result checkWithTimeout(
      RegexFuzzOracle oracle, String pattern, String input, long timeoutMs) {
    if (timeoutMs <= 0) {
      return oracle.check(pattern, input);
    }
    Future<Result> f = checkPool.submit(() -> oracle.check(pattern, input));
    try {
      return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      f.cancel(true); // best-effort interrupt; JDK backtracking ignores it
      return Result.skipped("check timeout >" + timeoutMs + "ms");
    } catch (Exception e) {
      f.cancel(true);
      return Result.skipped("check threw: " + e);
    }
  }

  /**
   * Detects nested-quantifier ReDoS shapes: a quantified group that contains a quantified atom
   * inside it. This covers the classic catastrophic-backtracking patterns like {@code (a+)+},
   * {@code (a*)*}, {@code (a?){2,}}, {@code (.*b+)+}, etc.
   *
   * <p>The detection is a heuristic string scan, not a full parse. It tracks parenthesis depth and
   * flags when a quantifier appears inside a group that is itself quantified. False positives
   * (skipping a safe pattern) are acceptable — those patterns would timeout anyway. False negatives
   * are caught by the per-check timeout safety net.
   */
  static boolean hasNestedQuantifier(String pattern) {
    // depth: current parenthesis nesting (0 = top level)
    // quantInside[d]: a quantifier was seen inside the group at depth d
    // groupResultQuantified[d]: the group at depth d is followed by a quantifier (set when ')' is
    //   consumed and a quantifier follows)
    int depth = 0;
    boolean[] quantInside = new boolean[64];
    int len = pattern.length();

    for (int i = 0; i < len; i++) {
      char c = pattern.charAt(i);
      if (c == '(') {
        depth++;
        if (depth >= quantInside.length) return true; // deep nesting — be safe
        quantInside[depth] = false;
        // Skip (?: prefix — the '?' here is not a quantifier.
        if (i + 1 < len && pattern.charAt(i + 1) == '?') {
          i++;
          if (i + 1 < len && pattern.charAt(i + 1) == ':') i++;
        }
      } else if (c == ')') {
        if (depth > 0) {
          boolean innerQ = quantInside[depth];
          depth--;
          // Check if this group close is followed by a quantifier.
          int qEnd = consumeQuantifier(pattern, i + 1);
          if (qEnd > i + 1) {
            // Group is quantified. If it also had a quantifier inside, that's ReDoS.
            if (innerQ) return true;
            // Mark the parent depth as having a quantifier inside (the group itself is an
            // atom with a quantifier from the parent's perspective).
            if (depth > 0) quantInside[depth] = true;
            i = qEnd - 1; // advance past the quantifier (loop's i++ handles the rest)
          } else if (innerQ && depth > 0) {
            // Group is not quantified but contains a quantifier — propagate to parent.
            quantInside[depth] = true;
          }
        }
      } else if (c == '[') {
        // Skip character class contents — quantifiers inside [...] are literal.
        i++;
        while (i < len && pattern.charAt(i) != ']') {
          if (pattern.charAt(i) == '\\') i++;
          i++;
        }
      } else if (c == '\\') {
        i++; // skip escaped char
      }
      // After any non-group atom, check for a quantifier suffix.
      // (Group closes are handled above; atoms are everything else that's not a structural
      // char.) We detect by checking if the *next* char starts a quantifier and the current
      // char is not a structural char that can't be quantified.
      if (c != '(' && c != '|' && i + 1 < len && isQuantifierStart(pattern.charAt(i + 1))) {
        int qEnd = consumeQuantifier(pattern, i + 1);
        if (qEnd > i + 1) {
          if (depth > 0) quantInside[depth] = true;
          i = qEnd - 1;
        }
      }
    }
    return false;
  }

  /**
   * If a quantifier token starts at {@code start}, returns the index just past it; otherwise
   * returns {@code start} (no quantifier).
   */
  private static int consumeQuantifier(String pattern, int start) {
    int len = pattern.length();
    if (start >= len) return start;
    char c = pattern.charAt(start);
    if (c == '?' || c == '*' || c == '+') {
      // Optional lazy modifier '?'
      if (start + 1 < len && pattern.charAt(start + 1) == '?') return start + 2;
      return start + 1;
    }
    if (c == '{') {
      int j = start + 1;
      while (j < len && pattern.charAt(j) != '}') j++;
      if (j < len) {
        j++; // past '}'
        if (j < len && pattern.charAt(j) == '?') j++; // lazy modifier
        return j;
      }
    }
    return start;
  }

  private static boolean isQuantifierStart(char c) {
    return c == '?' || c == '*' || c == '+' || c == '{';
  }
}
