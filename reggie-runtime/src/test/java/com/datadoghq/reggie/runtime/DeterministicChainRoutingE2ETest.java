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
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * End-to-end for the DETERMINISTIC_CHAIN_BYTECODE routing (stage 5): the real IAST hot-path
 * patterns (mirrored from {@code IastRegexpBenchmark}) compile through {@link Reggie#compile},
 * route to the deterministic-chain generator, and stay at java.util.regex parity — booleans, spans,
 * named groups, and the adversarial budget-overflow path.
 */
class DeterministicChainRoutingE2ETest {

  private static final String URL =
      "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";

  private static final String LDAP = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";

  private static final String XML_TAGS = "(<\\w+>).*?(</\\w+>)";

  private static void assertParity(
      com.datadoghq.reggie.runtime.ReggieMatcher m, java.util.regex.Pattern jdk, String input) {
    String where = "on \"" + (input.length() > 40 ? input.substring(0, 40) + "…" : input) + "\"";

    Matcher jm = jdk.matcher(input);
    if (jm.matches()) {
      assertTrue(m.matches(input), where + ": matches");
    } else {
      assertFalse(m.matches(input), where + ": matches");
    }

    for (int start = 0; start <= input.length(); start++) {
      String at = where + ", start=" + start;
      jm = jdk.matcher(input);
      if (jm.find(start)) {
        assertEquals(jm.start(), m.findFrom(input, start), at + ": findFrom");
        MatchResult r = m.findMatchFrom(input, start);
        assertNotNull(r, at + ": findMatchFrom");
        assertEquals(jm.groupCount(), r.groupCount(), at + ": groupCount");
        for (int g = 0; g <= jm.groupCount(); g++) {
          assertEquals(jm.start(g), r.start(g), at + ": start(" + g + ")");
          assertEquals(jm.end(g), r.end(g), at + ": end(" + g + ")");
          assertEquals(jm.group(g), r.group(g), at + ": group(" + g + ")");
        }
      } else {
        assertEquals(-1, m.findFrom(input, start), at + ": findFrom (no match)");
        assertNull(m.findMatchFrom(input, start), at + ": findMatchFrom (no match)");
      }
    }
  }

  private static void assertParityAll(
      com.datadoghq.reggie.runtime.ReggieMatcher m, java.util.regex.Pattern jdk, String... inputs) {
    for (String input : inputs) {
      assertParity(m, jdk, input);
    }
  }

  @Test
  void urlPatternE2EParity() {
    com.datadoghq.reggie.runtime.ReggieMatcher m =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(URL);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(URL);
    assertParityAll(
        m,
        jdk,
        "",
        "//user@host",
        "http://user@host",
        "https://u@h",
        "?a=b",
        "#a=b",
        "&a=b",
        "http://u@h?a=b#v=1&x=2",
        "//@@",
        "z?a=b",
        "?=",
        "?a=",
        "?a=b&c=d",
        "zhttps://auth@example.com?k=v");
  }

  @Test
  void urlPatternNamedGroups() {
    com.datadoghq.reggie.runtime.ReggieMatcher m =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(URL);
    MatchResult r = m.findMatch("http://alice@example.com/#/x?a=b");
    assertNotNull(r);
    assertEquals("alice", r.group("AUTHORITY"));
    MatchResult r2 = m.findMatch("?user=admin");
    assertNotNull(r2);
    assertEquals("admin", r2.group("QUERY"));
    assertNull(r2.group("AUTHORITY")); // branch 2 leaves branch-1 groups unmatched
  }

  @Test
  void ldapPatternE2EParity() {
    com.datadoghq.reggie.runtime.ReggieMatcher m =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(LDAP);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(LDAP);
    assertParityAll(
        m,
        jdk,
        "",
        "(a=b)",
        "(attr~=val)",
        "(attr>=val)",
        "x(a=b)y",
        "(a<=b)c(d=e)",
        "()",
        "(=)",
        "(a=)",
        "(cn=Smith)(uid=jdoe)");
  }

  @Test
  void xmlTagsPatternE2EParity() {
    com.datadoghq.reggie.runtime.ReggieMatcher m =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(XML_TAGS);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(XML_TAGS);
    assertParityAll(
        m,
        jdk,
        "",
        "<a>",
        "</a>",
        "<a></a>",
        "<ab></ab>",
        "<a><b></a></b>",
        "xx <tag1> middle </tag2> yy",
        "<a>no close");
  }

  @Test
  void adversarialOverflowStillCorrect() {
    // Budget overflow delegates to the PikeVM fallback — the answer must stay JDK-correct.
    com.datadoghq.reggie.runtime.ReggieMatcher m =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(XML_TAGS);
    String adversarial = ("<a" + "x".repeat(2000) + ">").repeat(3);
    assertEquals(-1, m.findFrom(adversarial, 0));

    com.datadoghq.reggie.runtime.ReggieMatcher m2 =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(URL);
    // Union-gate-heavy input with no authority terminator: every '/' gate hit re-consumes.
    String slashes = "/".repeat(20000);
    assertEquals(-1, m2.findFrom(slashes, 0));
  }

  @Test
  void generatedClassIsDeterministicChainNotInterpreter() {
    // Sanity: the compiled matcher is NOT a BitStateMatcher/PikeVMMatcher instance (the routing
    // substitution actually took effect for the runtime path).
    com.datadoghq.reggie.runtime.ReggieMatcher m =
        (com.datadoghq.reggie.runtime.ReggieMatcher) Reggie.compile(XML_TAGS);
    assertFalse(m instanceof BitStateMatcher, "XML_TAGS must route off the BitState interpreter");
    assertFalse(m instanceof PikeVMMatcher, "XML_TAGS must route off the PikeVM interpreter");
  }
}
