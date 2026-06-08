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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class EscapedQuoteGroupTest {
  @Test
  void escapedQuoteLastCapture_issue33() {
    // DFA_UNROLLED_WITH_GROUPS: alternation+plus inside star loses final capture
    String pat = "\"([^\\\\\"]+|\\\\.)*\"";
    String input = "\"1234\\\"5678\"";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.find(), "JDK should find");
    System.out.println("JDK group 1: " + jdk.group(1));
    ReggieMatcher reg = Reggie.compile(pat);
    MatchResult r = reg.findMatch(input);
    assertNotNull(r, "Reggie should find");
    System.out.println("Reggie group 1: " + r.group(1));
    assertEquals(jdk.group(1), r.group(1), "group 1 should match JDK");
  }
}
