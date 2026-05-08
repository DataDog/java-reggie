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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies that known-broken patterns fall back to java.util.regex and produce correct results. */
class FallbackVerificationTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // Bug 1: multiple backrefs to same group in NFA mode
  @Test
  void tripleBackrefNoFalsePositive() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1\\s+\\1");
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    assertTrue(m.find("go go go"));
    assertFalse(m.find("go go stop")); // was incorrectly true before fallback
  }

  // Bug 2: lookahead inside quantified group
  @Test
  void lookaheadInQuantifiedGroup() {
    ReggieMatcher m = Reggie.compile("(?:(?=\\d)\\d)+");
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    assertTrue(m.find("123"));
    assertFalse(m.find("abc"));
  }

  // Bug 3: lookbehind followed by unbounded quantifier
  @Test
  void lookbehindUnboundedQuantifier() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    assertTrue(m.find("3abc"));
    assertFalse(m.find("abc"));
  }

  // Bug 4: alternation inside lookbehind
  @Test
  void alternationInsideLookbehind() {
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    assertTrue(m.find("ac"));
    assertTrue(m.find("bc")); // was incorrectly false before fallback
    assertFalse(m.find("xc"));
  }

  // Bug 5: lookbehind + lookahead sandwich
  @Test
  void lookbehindLookaheadSandwich() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    assertTrue(m.find("[value]")); // was incorrectly false before fallback
    assertFalse(m.find("value"));
  }

  // Named groups via fallback matcher: hasNamedGroups() must return true
  @Test
  void namedGroupFallbackHasNamedGroupsTrue() {
    // alternation inside lookbehind triggers fallback; named group present
    ReggieMatcher m = Reggie.compile("(?<=a|b)(?<x>c)");
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    MatchResult r = m.findMatch("ac");
    assertNotNull(r);
    assertTrue(r.hasNamedGroups());
    assertEquals("c", r.group("x"));
  }
}
