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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * findFrom/findMatchFrom parity of the generated OPTIMIZED_NFA engine (the hybrid nfa-half) on
 * quantified-group give-back shapes.
 *
 * <p>History: the generated findFrom initialized its candidate scan at the first occurrence of a
 * required literal ({@code tryPos = input.indexOf(requiredChar, start)}) and retried at the next
 * occurrence. requiredLiterals only guarantee the literal appears SOMEWHERE in every match, so for
 * {@code (.c)+} (required char at offset 1 of the match) the scan skipped the leftmost match start
 * — {@code (.c)+} on "-cc" returned start 1 (JDK: 0) and {@code (.0){3,} } returned -1 entirely.
 * Latent while the hybrid served find() from its DFA half and never called the nfa half's own
 * search; the jump is now gated on the literal being a verified match prefix
 * (NFABytecodeGenerator.everyMatchStartsWith).
 *
 * <p>These patterns route HYBRID_DFA, so the nfa half is reached through {@link
 * HybridMatcher#nfaMatcher} — unwrapped via {@link EngineRouting}, never by hand (the R1 prefilter
 * wraps most matchers).
 */
class NfaFindFromRegressionTest {

  private static ReggieMatcher nfaHalf(String pattern) throws Exception {
    ReggieMatcher outer = com.datadoghq.reggie.Reggie.compile(pattern);
    Object engine = EngineRouting.unwrap(outer);
    if (engine instanceof HybridMatcher) {
      Field f = HybridMatcher.class.getDeclaredField("nfaMatcher");
      f.setAccessible(true);
      return (ReggieMatcher) f.get(engine);
    }
    // Standalone routing: the generated engine itself is the same NFABytecodeGenerator product.
    return (ReggieMatcher) engine;
  }

  private static void assertHalfAgrees(String pattern, String input) throws Exception {
    ReggieMatcher half = nfaHalf(pattern);
    java.util.regex.Matcher jm = Pattern.compile(pattern).matcher(input);
    boolean jdkFind = jm.find();
    MatchResult halfResult = half.findMatchFrom(input, 0);
    String failure = "findMatchFrom mismatch for /" + pattern + "/ on \"" + input + "\"";
    assertEquals(jdkFind, halfResult != null, failure);
    if (jdkFind) {
      assertEquals(jm.start(), halfResult.start(), failure + " (start)");
      assertEquals(jm.end(), halfResult.end(), failure + " (end)");
      for (int g = 1; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), halfResult.start(g), failure + " (group " + g + " start)");
        assertEquals(jm.end(g), halfResult.end(g), failure + " (group " + g + " end)");
      }
    }
  }

  // Give-back shapes: the required literal sits at a non-zero offset of the match, so the old
  // scan-start jump skipped the leftmost start.
  @Test
  void quantifiedGroupGiveBackLeftmost() throws Exception {
    assertHalfAgrees("(.c)+", "-cc");
    assertHalfAgrees("(.c)+", "xxccq-ccz");
    assertHalfAgrees("(.c)+", "acbcc");
    assertHalfAgrees("(.0){3,}", "1b0c010bc-a");
    assertHalfAgrees("(.0){3,}", "x0y0z0w");
    assertHalfAgrees("(.b){2,}", "a-abab-q");
    assertHalfAgrees("(..x){3,}", "01x02x03xq");
    assertHalfAgrees("x(.a)+y", "qx-a-ay");
  }

  // Alternation with different branch lengths: last-iteration group spans.
  @Test
  void alternationGiveBackGroups() throws Exception {
    assertHalfAgrees("(a|bc)+d", "xxbcad");
    assertHalfAgrees("(ab|ba)+cdefg", "baabcdebaabcdefg");
    assertHalfAgrees("(?:z|q)(a|b){2,}cdefg", "zabcdefgq");
  }

  // Control: prefix-literal patterns where the indexOf jump stays sound and must keep working.
  @Test
  void prefixLiteralJumpStillSound() throws Exception {
    assertHalfAgrees("abc(.*)", "zzabczz");
    assertHalfAgrees("abc(.d)+", "zabcadbdcd");
    assertHalfAgrees("e(?:f|gh)+i", "xxefghfghieff");
  }
}
