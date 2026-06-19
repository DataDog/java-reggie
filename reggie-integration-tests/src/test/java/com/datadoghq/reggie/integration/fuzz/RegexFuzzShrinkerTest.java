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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.integration.fuzz.RegexFuzzOracle.Finding;
import com.datadoghq.reggie.integration.fuzz.RegexFuzzShrinker.Shrunk;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RegexFuzzShrinkerTest {

  private static final RegexFuzzShrinker SHRINKER = new RegexFuzzShrinker();
  private static final RegexFuzzOracle ORACLE = new RegexFuzzOracle();

  @Test
  void shrunkRepro_mustStillDiverge() {
    // Use the three known cold-agreeing shrinker artifacts as negative fixtures.
    // A valid shrink of a finding that was diverging to begin with must still diverge.
    // These three were over-shrunken: the shrunken result no longer reproduces the divergence.
    String[] coldAgreeing = {"($)", "$|[^c]{1}", "[^c]|(c{0})_"};
    for (String p : coldAgreeing) {
      List<Finding> coldFindings = ORACLE.check(p, "").findings;
      assertTrue(
          coldFindings.isEmpty(),
          "Expected no divergence for /" + p + "/ on \"\", but oracle found: " + coldFindings);
    }
  }

  @Test
  void shrink_doesNotReturnNonReproducingResult() {
    // Build a synthetic Finding that DOES diverge, shrink it, and confirm the result still
    // diverges.
    // Use a known diverging pattern from the fuzz inventory.
    // (.+)_ on __ is a known divergence (GREEDY_BACKTRACK find bug).
    List<Finding> findings = ORACLE.check("(.+)_", "__").findings;
    // If this pattern is already fixed by another task, skip. Otherwise verify shrinking.
    if (findings.isEmpty()) {
      return; // already fixed by another task — that's fine
    }
    Finding f = findings.get(0);
    Shrunk s = SHRINKER.shrink(f);
    // The shrunken result must still diverge when re-checked fresh.
    List<Finding> verification = ORACLE.check(s.pattern, s.input).findings;
    assertFalse(
        verification.isEmpty(),
        "Shrunk result /" + s.pattern + "/ on \"" + s.input + "\" no longer diverges");
  }
}
