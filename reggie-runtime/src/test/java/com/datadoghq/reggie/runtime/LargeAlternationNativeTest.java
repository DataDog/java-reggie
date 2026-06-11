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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code DFASwitchBytecodeGenerator} patterns with more than {@code
 * STATE_SPLIT_THRESHOLD} DFA states compile correctly after method splitting is applied.
 *
 * <p>When the DFA state count exceeds the threshold, the generator emits private bucket-helper
 * methods ({@code $ng_step_J} / {@code $gt_step_J}) instead of inlining all case logic. These tests
 * confirm that the split bytecode produces the same results as {@link java.util.regex}.
 */
class LargeAlternationNativeTest {

  /**
   * Builds a no-group pattern that routes to {@code DFA_SWITCH} and exercises the bucket-helper
   * split. With 101 alternatives each starting with {@code [a-z]}, the resulting DFA has more than
   * 100 states (the split threshold), triggering {@code $ng_step_J} helper generation.
   */
  private static String dfaSwitchNgSplitPattern() {
    // 101 alternatives, each with a [a-z] prefix + unique 4-digit suffix.
    // This produces enough DFA states to exceed STATE_SPLIT_THRESHOLD=100.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 101; i++) {
      if (i > 0) sb.append('|');
      sb.append("[a-z]");
      sb.append(String.format("x%04d", i));
    }
    return sb.toString();
  }

  /**
   * Builds a pattern with a capturing group that routes to {@code DFA_SWITCH_WITH_GROUPS} and
   * exercises the group-tracking bucket-helper split. 200 alternatives produce ~225 DFA states,
   * well above the 100-state threshold.
   */
  private static String dfaSwitchGtSplitPattern() {
    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < 200; i++) {
      if (i > 0) sb.append('|');
      sb.append("[\\x00-\\x09\\x0B-\\x0C\\x0E-\\x1F]x");
      sb.append(String.format("%03d", i));
    }
    sb.append(")");
    return sb.toString();
  }

  @Test
  void dfaSwitchNgSplitCompilesNativelyAndMatchesCorrectly() {
    String pat = dfaSwitchNgSplitPattern();
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "DFA_SWITCH pattern with split must compile natively, not fall back to JDK");
    Pattern jdk = Pattern.compile(pat);
    for (String in : new String[] {"ax0000", "zx0100", "mx0050", "x0000", "nope", "", "ax0001"}) {
      boolean jdkFind = jdk.matcher(in).find();
      assertEquals(jdkFind, reggie.find(in), () -> "find() mismatch for input='" + in + "'");
    }
  }

  @Test
  void dfaSwitchGtSplitCompilesNativelyAndMatchesCorrectly() {
    String pat = dfaSwitchGtSplitPattern();
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "DFA_SWITCH_WITH_GROUPS pattern with 225 states must compile natively (split applies)");
    Pattern jdk = Pattern.compile(pat);
    for (String in : new String[] {"x000", "x199", "x100", "nope", ""}) {
      boolean jdkFind = jdk.matcher(in).find();
      assertEquals(jdkFind, reggie.find(in), () -> "find() mismatch for input='" + in + "'");
    }
  }
}
