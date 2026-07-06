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
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for nullable-group backreference patterns in OPTIMIZED_NFA_WITH_BACKREFS.
 *
 * <p>Bug: The parallel NFA simulation uses a shared group array across all active NFA paths. For
 * nullable groups (e.g., {@code (a|)}), both the empty-capture path and the non-empty-capture path
 * run simultaneously. The last writer to groupEnds[] wins, causing the backref check to see the
 * wrong capture span.
 *
 * <p>Fix: Enable per-state group tracking ({@code usePosixLastMatch=true}) for
 * OPTIMIZED_NFA_WITH_BACKREFS patterns where a backref refers to a nullable group. Each NFA path
 * carries its own group capture array, eliminating shared-array contamination.
 */
class NullableBackrefTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Native routing assertions ──────────────────────────────────────────────

  @Test
  void nullableBackref_optionalAlt_notFallback() {
    assertFalse(
        Reggie.compile("^(a|)\\1+b") instanceof JavaRegexFallbackMatcher,
        "^(a|)\\1+b must not fall back to JDK after the fix");
  }

  @Test
  void nullableBackref_optionalQuantifier_notFallback() {
    assertFalse(
        Reggie.compile("^(a?)\\1+b") instanceof JavaRegexFallbackMatcher,
        "^(a?)\\1+b must not fall back to JDK after the fix");
  }

  // ── Correctness: ^(a|)\1+b ────────────────────────────────────────────────

  @Test
  void optionalAlt_nonEmptyCapture_matches() {
    // group(1)="a", \1+="a", then "b" → match at "aab"
    String pat = "^(a|)\\1+b";
    MatchResult r = Reggie.compile(pat).findMatch("aab");
    assertNotNull(r, "^(a|)\\1+b must match 'aab'");
    assertEquals("a", r.group(1), "group(1) must be 'a'");
  }

  @Test
  void optionalAlt_emptyCapture_matches() {
    // group(1)="", \1+ matches empty (once), then "b"
    String pat = "^(a|)\\1+b";
    MatchResult r = Reggie.compile(pat).findMatch("b");
    assertNotNull(r, "^(a|)\\1+b must match 'b' (empty group capture)");
    assertEquals("", r.group(1), "group(1) must be empty string");
  }

  @Test
  void optionalAlt_repeatedCapture_matches() {
    // group(1)="a", \1+="aaaa" (4 repetitions), then "b"
    String pat = "^(a|)\\1+b";
    MatchResult r = Reggie.compile(pat).findMatch("aaaab");
    assertNotNull(r, "^(a|)\\1+b must match 'aaaab'");
    assertEquals("a", r.group(1), "group(1) must be 'a'");
  }

  @Test
  void optionalAlt_noMatch() {
    // "ab" — group(1) would be 'a', then \1+ needs 'a' but only 'b' remains
    assertNull(Reggie.compile("^(a|)\\1+b").findMatch("ab"), "^(a|)\\1+b must NOT match 'ab'");
  }

  // ── Correctness: matches JDK for ^(a|)\1+b ────────────────────────────────

  @Test
  void optionalAlt_matchesJdk_aab() {
    String pat = "^(a|)\\1+b";
    MatchResult r = Reggie.compile(pat).findMatch("aab");
    Matcher jdk = Pattern.compile(pat).matcher("aab");
    assertTrue(jdk.find(), "JDK must find match");
    assertNotNull(r, "Reggie must find match");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
  }

  @Test
  void optionalAlt_matchesJdk_b() {
    String pat = "^(a|)\\1+b";
    MatchResult r = Reggie.compile(pat).findMatch("b");
    Matcher jdk = Pattern.compile(pat).matcher("b");
    assertTrue(jdk.find(), "JDK must find match");
    assertNotNull(r, "Reggie must find match");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
  }

  // ── Correctness: ^(a?)\1+b ────────────────────────────────────────────────

  @Test
  void optionalQuantifier_nonEmptyCapture_matchesJdk() {
    String pat = "^(a?)\\1+b";
    String input = "aab";
    MatchResult r = Reggie.compile(pat).findMatch(input);
    Matcher jdk = Pattern.compile(pat).matcher(input);
    boolean jdkFound = jdk.find();
    if (jdkFound) {
      assertNotNull(r, "Reggie must match when JDK matches");
      assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    } else {
      assertNull(r, "Reggie must not match when JDK does not match");
    }
  }

  @Test
  void optionalQuantifier_emptyCapture_matchesJdk() {
    String pat = "^(a?)\\1+b";
    String input = "b";
    MatchResult r = Reggie.compile(pat).findMatch(input);
    Matcher jdk = Pattern.compile(pat).matcher(input);
    boolean jdkFound = jdk.find();
    if (jdkFound) {
      assertNotNull(r, "Reggie must match when JDK matches");
      assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    } else {
      assertNull(r, "Reggie must not match when JDK does not match");
    }
  }

  // ── Correctness: ((.*))\d+\1 ──────────────────────────────────────────────

  @Test
  void nestedNullableGroup_matchesJdk_abc123abc() {
    // ((.*))\d+\1 — inner group is nullable (.*), backref \1 → same content
    String pat = "((.*))\\d+\\1";
    String input = "abc123abc";
    MatchResult r = Reggie.compile(pat).findMatch(input);
    Matcher jdk = Pattern.compile(pat).matcher(input);
    boolean jdkFound = jdk.find();
    if (jdkFound) {
      assertNotNull(r, "Reggie must match when JDK matches");
      assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    } else {
      assertNull(r, "Reggie must not match when JDK does not match");
    }
  }

  @Test
  void nestedNullableGroup_matchesJdk_abc123bc() {
    String pat = "((.*))\\d+\\1";
    String input = "abc123bc";
    MatchResult r = Reggie.compile(pat).findMatch(input);
    Matcher jdk = Pattern.compile(pat).matcher(input);
    boolean jdkFound = jdk.find();
    if (jdkFound) {
      assertNotNull(r, "Reggie must match when JDK matches");
      assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    } else {
      assertNull(r, "Reggie must not match when JDK does not match");
    }
  }

  // ── Correctness: ^(cow|)\1(bell) ─────────────────────────────────────────

  @Test
  void cowbell_withCapture_matchesJdk() {
    String pat = "^(cow|)\\1(bell)";
    String input = "cowcowbell";
    MatchResult r = Reggie.compile(pat).findMatch(input);
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.find(), "JDK must match 'cowcowbell'");
    assertNotNull(r, "Reggie must match 'cowcowbell'");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    assertEquals(jdk.group(2), r.group(2), "group(2) must match JDK");
  }

  @Test
  void cowbell_emptyCapture_matchesJdk() {
    String pat = "^(cow|)\\1(bell)";
    String input = "bell";
    MatchResult r = Reggie.compile(pat).findMatch(input);
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.find(), "JDK must match 'bell'");
    assertNotNull(r, "Reggie must match 'bell'");
    assertEquals(jdk.group(1), r.group(1), "group(1) must match JDK");
    assertEquals(jdk.group(2), r.group(2), "group(2) must match JDK");
  }

  // ── PINNED_BACKREFERENCE interaction: hasAmbiguouslyNullableBackrefGroup guard ────────────

  /**
   * {@code (a{0,3})\d+\1} is pinned-eligible (group content charset {@code a} is disjoint from the
   * {@code \d+} suffix that follows it), so it would route to {@code PINNED_BACKREFERENCE} if the
   * B7 danger-condition guard did not also cover that strategy. Group 1 is nullable ({@code a{0,3}}
   * can match empty) and can capture strings of length &gt; 1, so {@code
   * hasAmbiguouslyNullableBackrefGroup} must still exclude it, routing to JDK fallback instead.
   */
  @Test
  void pinnedEligibleAmbiguouslyNullableGroup_routesToFallback_notPinnedBackreference() {
    ReggieOptions withFallback = ReggieOptions.builder().allowJdkFallback().build();
    assertTrue(
        Reggie.compile("(a{0,3})\\d+\\1", withFallback) instanceof JavaRegexFallbackMatcher,
        "(a{0,3})\\d+\\1 must fall back to JDK: pinned-eligible boundary but ambiguously "
            + "nullable backref group (B7 guard) must still apply");
  }
}
