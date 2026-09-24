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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * Locks the PikeVM re-route for alternation-priority-conflict patterns (find-refusal-set-parity):
 * the conflict only means the DFA cannot express Java's first-alternative preference — PikeVM can,
 * in linear time — so such patterns must compile natively instead of being refused, EXCEPT when
 * FallbackPatternDetector finds a real PikeVM divergence (kept refused via {@link
 * com.datadoghq.reggie.UnsupportedPatternException}).
 *
 * <p>Each pattern is checked against the JDK oracle on a crafted input battery (boolean find + full
 * group spans), because the whole point of the re-route is byte-identical JDK semantics.
 */
class AlternationPriorityPikeVmRouteTest {

  // (pattern, battery of inputs exercising first-alternative preference + spans)
  private static final String[][] CASES = {
    {
      "(?<tableName>\\w+)(\\[org=>(?<orgIdColumnName>\\w+)(:(?<orgIdColumnType>string|int))?])?",
      "users[org=>tenant_id:string] more",
      "events[org=>org_id]",
      "logs",
      "a[]",
      ""
    },
    {
      "(?<pre>[-_.]?(?<prel>(a|b|c|rc|alpha|beta|pre|preview))[-_.]?(?<pren>[0-9]+)?)?"
          + "(?<post>-(?<ipostn>[0-9]+)|[-_.]?(?<postl>post|rev|r)[-_.]?(?<postn>[0-9]+)?)?"
          + "(?<dev>[-_.]?(?<devl>dev)[-_.]?(?<devn>[0-9]+)?)?",
      "1.2.3-alpha.1+build",
      "2.0.0-post2.dev5",
      "3rc4-5",
      "plain",
      "-r",
      "pre"
    },
    {
      "red|green|blue-ish", // minimal alternation-priority shape: prefix branch must win
      "color is blue-ish here",
      "color is red",
      "greenish",
      "blue",
      ""
    }
  };

  // Still-refused: PikeVM itself is unsafe for these (needsFallback guards fire)
  private static final String[] STILL_REFUSED = {
    "\\s*((?<kind>[a-zA-Z0-9_:()$ -]+):( |$))?(?<message>.+)?", // anchor inside quantifier
  };

  @Test
  void alternationPriorityPatternsRouteToPikeVmNatively() {
    for (String[] c : CASES) {
      String pattern = c[0];
      ReggieMatcher m = Reggie.compile(pattern); // plain compile: must not throw
      assertFalse(
          m.isJdkFallback(), "pattern must be served natively, not via JDK fallback: " + pattern);
      for (int i = 1; i < c.length; i++) {
        String input = c[i];
        Matcher jm = java.util.regex.Pattern.compile(pattern).matcher(input);
        boolean jdkFind = jm.find();
        assertEquals(jdkFind, m.find(input), "find() parity for " + pattern);
        if (jdkFind) {
          MatchResult rm = m.findMatch(input);
          assertTrue(rm != null, "findMatch must return the same match JDK found");
          int gc = jm.groupCount();
          for (int g = 0; g <= gc; g++) {
            final int group = g;
            assertEquals(
                jm.start(group),
                rm.start(group),
                () -> "group " + group + " start span parity: " + pattern + " on [" + input + "]");
            assertEquals(
                jm.end(group),
                rm.end(group),
                () -> "group " + group + " end span parity: " + pattern);
          }
        }
      }
    }
  }

  @Test
  void pikeVmUnsafeAlternationShapesStayRefused() {
    for (String pattern : STILL_REFUSED) {
      assertThrows(
          Exception.class,
          () -> Reggie.compile(pattern),
          "needsFallback-unsafe shape must stay refused: " + pattern);
    }
  }
}
