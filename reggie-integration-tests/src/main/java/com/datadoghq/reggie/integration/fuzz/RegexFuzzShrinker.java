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

/**
 * Reduces a divergent (pattern, input) pair to a smaller pair that still diverges between Reggie
 * and JDK. The shrinker is dumb on purpose — single-char deletions, iterated to a fixpoint — which
 * is enough to take 30-char findings down to 4-6 chars in a few hundred milliseconds.
 *
 * <p>"Still diverges" is defined as: oracle returns at least one finding whose <em>description
 * starts with the same kind</em> as the original (e.g. {@code "matches() differs"}, {@code "find()
 * boolean differs"}, {@code "first-match span differs"}). We deliberately ignore the specific
 * numeric span — the shrunk pattern matches a different input, so spans will differ.
 */
public final class RegexFuzzShrinker {

  private final RegexFuzzOracle oracle = new RegexFuzzOracle();

  /** Result of shrinking. Always returns a valid divergent pair. */
  public static final class Shrunk {
    public final String pattern;
    public final String input;
    public final String findingKind;

    public Shrunk(String pattern, String input, String findingKind) {
      this.pattern = pattern;
      this.input = input;
      this.findingKind = findingKind;
    }
  }

  public Shrunk shrink(Finding original) {
    String kind = findingKind(original.description);
    String pattern = original.pattern;
    String input = original.input;

    boolean changed = true;
    while (changed) {
      changed = false;
      // Try shrinking the input first (always safe to delete chars).
      final String capturedPattern = pattern;
      String shorterInput =
          tryShrinkString(input, s -> stillDivergesSameKind(capturedPattern, s, kind));
      if (!shorterInput.equals(input)) {
        input = shorterInput;
        changed = true;
      }
      // Then shrink the pattern. Deleting a char may produce something the JDK rejects;
      // tryShrinkString re-runs the oracle which handles that gracefully.
      final String capturedInput = input;
      String shorterPattern =
          tryShrinkString(pattern, p -> stillDivergesSameKind(p, capturedInput, kind));
      if (!shorterPattern.equals(pattern)) {
        pattern = shorterPattern;
        changed = true;
      }
    }
    // Re-verify the shrunken pair. The shrink loop accepts a deletion if ANY finding of the same
    // kind exists in the oracle report for that (pattern, input) — but the kind check is coarse
    // and can be satisfied by a finding produced by a completely different pattern in a multi-
    // pattern run. If the final shrunken pair no longer diverges, fall back to the original.
    if (!stillDivergesSameKind(pattern, input, kind)) {
      return new Shrunk(original.pattern, original.input, kind);
    }
    return new Shrunk(pattern, input, kind);
  }

  /**
   * Greedy single-pass: try deleting each char left-to-right; keep deletions that still diverge.
   */
  private static String tryShrinkString(String s, java.util.function.Predicate<String> stillBad) {
    StringBuilder cur = new StringBuilder(s);
    int i = 0;
    while (i < cur.length()) {
      char removed = cur.charAt(i);
      cur.deleteCharAt(i);
      if (stillBad.test(cur.toString())) {
        // Keep the deletion; do NOT advance i — the char at position i is new.
      } else {
        cur.insert(i, removed);
        i++;
      }
    }
    return cur.toString();
  }

  private boolean stillDivergesSameKind(String pattern, String input, String kind) {
    Result r = oracle.check(pattern, input);
    if (r.skipped) return false;
    for (Finding f : r.findings) {
      if (findingKind(f.description).equals(kind)) return true;
    }
    return false;
  }

  /** First word(s) of a finding description, used as the equivalence class for shrinking. */
  private static String findingKind(String description) {
    int colon = description.indexOf(':');
    return colon < 0 ? description : description.substring(0, colon);
  }
}
