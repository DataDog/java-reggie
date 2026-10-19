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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * VARIABLE_CAPTURE_BACKREF matchers shrink the group one character at a time until separator and
 * backreference match; the give-back is bounded only by the group length, so matches needing more
 * than 10000 give-back steps must still be found.
 */
class VariableCaptureBackrefGiveBackTest {

  @Test
  void giveBackPastFormerIterationCapMatchesLikeJdk() {
    String n = "a".repeat(11_000);
    String[][] cases = {
      {"(.*)\\d+\\1", n + "7" + n},
      {"(.+)=\\1", n + "=" + n},
      {"x(a+):\\1", "x" + n + ":" + n},
    };
    for (String[] c : cases) {
      RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(c[0]);
      assertTrue(info.routing.startsWith("VARIABLE_CAPTURE_BACKREF"), c[0] + " -> " + info.routing);
      ReggieMatcher m = info.matcher;
      Matcher jdk = Pattern.compile(c[0]).matcher(c[1]);
      assertTrue(jdk.find(), c[0]);

      assertTrue(m.matches(c[1]), c[0]);
      assertTrue(m.find(c[1]), c[0]);
      MatchResult whole = m.match(c[1]);
      assertNotNull(whole, c[0]);
      assertEquals(jdk.end(1), whole.end(1), c[0]);
      MatchResult r = m.findMatch(c[1]);
      assertNotNull(r, c[0]);
      assertEquals(jdk.start(), r.start(), c[0]);
      assertEquals(jdk.end(), r.end(), c[0]);
      assertEquals(jdk.end(1), r.end(1), c[0]);
    }
  }
}
