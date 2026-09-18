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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Huge-charset codegen: {@code .*[\p{IsAlphabetic}].*}-shaped patterns (~420-range classes) used to
 * exceed the JVM 64 KB per-method limit and were rejected ("generated method too large") — subset
 * construction emits one DFA transition map entry per partition piece of the class, and the
 * unrolled generator emitted a full range cascade per piece. Two coordinated fixes:
 *
 * <ol>
 *   <li><b>Transition merging</b> (DFAUnrolledBytecodeGenerator.emitTransitionChecks and the
 *       sibling emitters): transitions sharing a target state — and, where emitted, an equal entry
 *       guard / tag-op list — merge into ONE charset check. Partition pieces are disjoint, so the
 *       merge cannot change which target wins.
 *   <li><b>Lookup tables</b> (generateLookupTables): merged charsets at or above 100 ranges match
 *       through a static {@code boolean[65536]} built once in {@code <clinit>} from a compact
 *       encoded-ranges string constant — one array load instead of a ~420-range cascade, which also
 *       matches faster.
 * </ol>
 *
 * <p>Before the fix, the affected logs-backend site fell back to java.util.regex; it now compiles
 * strict-native. All expectations below are JDK-differential.
 */
class HugeCharsetCodegenTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".*[\\p{IsAlphabetic}].*",
        "^.*[\\p{IsAlphabetic}].*$",
        ".*[\\p{IsAlphabetic}x].*",
        ".*[^\\p{IsAlphabetic}].*",
        ".*[\\p{IsLetter}].*",
        "[\\p{IsAlphabetic}]+",
        "x[\\p{IsAlphabetic}]+y",
        "(.*)[\\p{IsAlphabetic}](.*)",
      })
  void hugeCharsetShapesCompileNativelyAndMatchJdk(String pattern) {
    Pattern jp = Pattern.compile(pattern);
    ReggieMatcher rm = Reggie.compile(pattern); // strict: no fallback
    String[] inputs = {
      "abc",
      "123",
      "a1",
      "xyz\u00e9",
      "\u4e2d\u6587",
      "  ",
      "a b",
      "\u03a3\u03b9\u03c2",
      "z1z",
      "zzabczz",
      "a\u00e9b",
    };
    for (String in : inputs) {
      assertEquals(jp.matcher(in).matches(), rm.matches(in), "matches for " + pattern);
      java.util.regex.Matcher jm = jp.matcher(in);
      boolean jf = jm.find();
      MatchResult rr = rm.findMatch(in);
      assertEquals(jf, rr != null, "find presence for " + pattern);
      if (jf) {
        assertEquals(jm.start(), rr.start(), "find start for " + pattern);
        assertEquals(jm.end(), rr.end(), "find end for " + pattern);
        for (int g = 1; g <= jm.groupCount(); g++) {
          assertEquals(jm.group(g), rr.group(g), "group " + g + " for " + pattern);
        }
      }
    }
  }

  /** The bounded/CharSequence path has its own merged emission — pin it too. */
  @Test
  void boundedRegionHugeCharsetMatchesJdk() {
    String pattern = ".*[\\p{IsAlphabetic}].*";
    Pattern jp = Pattern.compile(pattern);
    ReggieMatcher rm = Reggie.compile(pattern);
    CharSequence[] ins = {
      "a\u00e9b", new StringBuilder("123"), java.nio.CharBuffer.wrap("\u4e2d\u6587x"),
    };
    for (CharSequence in : ins) {
      for (int s = 0; s <= in.length(); s++) {
        for (int e = s; e <= in.length(); e++) {
          assertEquals(
              jp.matcher(in).region(s, e).matches(),
              rm.matchesBounded(in, s, e),
              pattern + " on [" + s + "," + e + ")");
        }
      }
    }
  }

  /** The lookup table is one static boolean[] per range-heavy charset — verify via a huge class. */
  @Test
  void lookupTableMatchesBmpMembership() {
    // The merged [^IsAlphabetic] set (the dot-minus-class partition piece) must agree with the
    // JDK over a dense sample of the BMP.
    ReggieMatcher rm = Reggie.compile(".*[^\\p{IsAlphabetic}].*");
    Pattern jp = Pattern.compile(".*[^\\p{IsAlphabetic}].*");
    int checked = 0;
    for (int c = 0; c <= 0xFFFF; c += 7) { // dense stride
      if (Character.isSurrogate((char) c)) continue;
      String in = new String(Character.toChars(c));
      assertEquals(jp.matcher(in).matches(), rm.matches(in), "U+" + Integer.toHexString(c));
      checked++;
    }
    assertTrue(checked > 9000);
    assertNotNull(rm);
  }
}
