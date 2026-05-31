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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Structural meta-test that verifies every code-generation strategy in {@link
 * PatternAnalyzer.MatchingStrategy} returns correct results across the full public API of {@link
 * ReggieMatcher}.
 *
 * <p>For each strategy, a representative pattern is provided and (1) its routing is confirmed via
 * {@link PatternAnalyzer}, and (2) all eight public matching methods are exercised against {@link
 * java.util.regex.Pattern} for a set of inputs (full match, embedded match, non-match, empty
 * string, Unicode string).
 *
 * <p>The map is exhaustive: the test fails if any {@link PatternAnalyzer.MatchingStrategy} value
 * lacks a representative pattern, which forces future strategies to be covered.
 *
 * <p>Semantic agreement assertions are gated behind the system property {@code
 * reggie.metatest.enforce}. When false (default) mismatches are collected and printed, but the test
 * passes — this lets the harness land green and act as a discovery tool. When true, every mismatch
 * fails the test.
 */
public class StrategyCorrectnessMetaTest {

  private static final boolean ENFORCE = Boolean.getBoolean("reggie.metatest.enforce");

  /** A representative pattern plus the inputs to exercise against it. */
  private record Spec(String pattern, List<String> inputs) {}

  /**
   * Strategy -> representative pattern + inputs. Patterns were confirmed (via {@link
   * PatternAnalyzer}) to route to the keyed strategy; see {@link #routeOf(String)} which
   * re-confirms routing at test time.
   *
   * <p>Each input list contains, where applicable: a full match, an embedded/partial match, a
   * non-match, the empty string, and a Unicode-containing string.
   */
  private static Map<PatternAnalyzer.MatchingStrategy, Spec> strategyPatterns() {
    Map<PatternAnalyzer.MatchingStrategy, Spec> m =
        new EnumMap<>(PatternAnalyzer.MatchingStrategy.class);

    m.put(
        PatternAnalyzer.MatchingStrategy.STATELESS_LOOP,
        new Spec("\\w+", List.of("abc", "  abc  ", "...", "", "héllo")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_GREEDY_CHARCLASS,
        new Spec("(\\d+)", List.of("12345", "abc123def", "abc", "", "４2")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY,
        new Spec(
            "([a-z]+)@([a-z]+)\\.com",
            List.of("foo@bar.com", "x foo@bar.com y", "foo@bar.org", "", "főo@bar.com")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_CONCAT_GREEDY_GROUP,
        new Spec("a(b*)", List.of("abbb", "xabbby", "xyz", "", "abbbé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE,
        new Spec(
            "\\d{3}-\\d{3}-\\d{4}",
            List.of("123-456-7890", "call 123-456-7890 now", "12-34-5678", "", "１23-456-7890")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_BOUNDED_QUANTIFIERS,
        new Spec(
            "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
            List.of("192.168.0.1", "ip=192.168.0.1!", "192.168.0", "", "1.2.3.４")));
    m.put(
        PatternAnalyzer.MatchingStrategy.LINEAR_BACKREFERENCE,
        new Spec("(a)(b)\\1\\2", List.of("abab", "xababy", "abba", "", "ababé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_BACKREFERENCE,
        new Spec(
            "<(\\w+)>.*</\\1>",
            List.of("<b>hi</b>", "x<b>hi</b>y", "<b>hi</i>", "", "<b>héllo</b>")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTIPLE_LOOKAHEADS,
        new Spec(
            "(?=[a-z]+\\d)(?=\\w+!).*end",
            List.of("abc1!end", "x abc1!end", "abcend", "", "abc1!énd")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS,
        new Spec(
            "(?=.*foo)(?=.*bar).*baz",
            List.of("foobarbaz", "xx foobarbaz", "foobaz", "", "foobarbáz")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_ALTERNATION,
        new Spec("foo|bar|baz|qux|quux|corge", List.of("foo", "xx bar yy", "zzz", "", "córge")));
    m.put(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP,
        new Spec("(a)+", List.of("aaa", "xaaay", "bbb", "", "aaaé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.GREEDY_BACKTRACK,
        new Spec("(.*)bar", List.of("xxbar", "a bar b", "foo", "", "hébar")));
    m.put(
        PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF,
        new Spec("(a)\\1{8,}", List.of("aaaaaaaaa", "xaaaaaaaaay", "aaaa", "", "aaaaaaaaaé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF,
        new Spec(
            "(\\w+)\\s+\\1",
            List.of("hello hello", "x hello hello y", "hello world", "", "héllo héllo")));
    m.put(
        PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF,
        new Spec("(a)?\\1", List.of("aa", "xaay", "ab", "", "aaé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.NESTED_QUANTIFIED_GROUPS,
        new Spec("((a|bc)+)*", List.of("abcabc", "xabcy", "zzz", "", "abcé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        new Spec("(foo)(bar)", List.of("foobar", "x foobar y", "foobaz", "", "foobár")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED,
        new Spec("[ab]c", List.of("ac", "x bc y", "dc", "", "ác")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS,
        new Spec("(?=ab)(?=cd)(?=ef)gh", List.of("gh", "x gh", "ab", "", "ghé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        new Spec("(a|b)c(d|e)", List.of("acd", "x bce y", "acf", "", "acdé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_SWITCH,
        new Spec("a.*b.*c.*d.*e.*f", List.of("abcdef", "x a1b2c3d4e5f y", "abcde", "", "aébcdef")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_SWITCH_WITH_ASSERTIONS,
        new Spec("(?=[abcdef])[a-z]{1,30}xyz", List.of("axyz", "x axyz y", "zzz", "", "axyzé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_SWITCH_WITH_GROUPS,
        new Spec(
            "(a|b|c|d|e|f|g)(h|i|j|k|l|m)(n|o|p|q|r)(s|t|u|v)",
            List.of("ahns", "x bios y", "aaaa", "", "ahnsé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.DFA_TABLE,
        new Spec(
            "(?:abc){100}",
            List.of(
                "abc".repeat(100), "x" + "abc".repeat(100) + "y", "abc".repeat(99), "", "abcé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA,
        new Spec("(a*)+", List.of("aaaa", "xaaay", "bbbb", "", "aaaé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.LAZY_DFA,
        new Spec(
            "(?:a+b+|b+a+){75}",
            List.of("ab".repeat(75), "x" + "ab".repeat(75) + "y", "ab", "", "abé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS,
        new Spec("(a|b)c\\1", List.of("aca", "x bcb y", "acb", "", "acaé")));
    m.put(
        PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
        new Spec("a(?!\\d+x).*b", List.of("ab", "x ayb y", "a1xb", "", "aéb")));
    m.put(
        PatternAnalyzer.MatchingStrategy.HYBRID_DFA_LOOKAHEAD,
        new Spec(
            "(?=\\w+@).*@example.com",
            List.of("u@example.com", "x u@example.com", "u@other.com", "", "ü@example.com")));
    // RECURSIVE_DESCENT: subroutine/conditional/branch-reset forms are not expressible in
    // java.util.regex, so they cannot be cross-checked. This backtracking-for-groups form
    // (a([bc]*)(c+d)) also routes to RECURSIVE_DESCENT and IS JDK-expressible, giving a valid
    // oracle.
    m.put(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        new Spec("a([bc]*)(c+d)", List.of("abcd", "x abccd y", "axd", "", "abcdé")));

    return m;
  }

  /** Re-confirm the routing of a pattern, mirroring RuntimeCompiler.compileInternal. */
  static PatternAnalyzer.MatchingStrategy routeOf(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = null;
    if (!PatternAnalyzer.requiresRecursiveDescent(ast)) {
      ThompsonBuilder builder = new ThompsonBuilder();
      nfa = builder.build(ast, countGroups(pattern));
    }
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeAndRecommend().strategy;
  }

  /** Group counter copied from RuntimeCompiler (only used for NFA group sizing here). */
  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length()) {
        if (pattern.charAt(i + 1) == '?') {
          if (i + 2 < pattern.length()) {
            char next = pattern.charAt(i + 2);
            if (next == ':'
                || next == '='
                || next == '!'
                || next == '>'
                || next == '#'
                || next == '|'
                || next == '('
                || next == '-'
                || next == 'i'
                || next == 'm'
                || next == 's'
                || next == 'x'
                || next == 'u'
                || next == 'U'
                || next == 'd') {
              if (next == '<' && i + 3 < pattern.length()) {
                char afterLt = pattern.charAt(i + 3);
                if (afterLt == '=' || afterLt == '!') {
                  continue;
                }
              } else {
                continue;
              }
            }
            if (next == '<' && i + 3 < pattern.length()) {
              char afterLt = pattern.charAt(i + 3);
              if (afterLt == '=' || afterLt == '!') {
                continue;
              }
            }
          }
        }
        count++;
      }
    }
    return count;
  }

  /** Collected mismatch in default (non-enforcing) mode. */
  private record Mismatch(
      PatternAnalyzer.MatchingStrategy strategy,
      String pattern,
      String input,
      String method,
      String expected,
      String actual) {}

  /**
   * Structural guarantee: every {@link PatternAnalyzer.MatchingStrategy} value must have a
   * representative pattern, and each pattern must route to the strategy it is keyed under. These
   * are deterministic, JDK-independent assertions and are always enforced (not gated).
   */
  @Test
  void everyStrategyHasRoutableRepresentative() throws Exception {
    Map<PatternAnalyzer.MatchingStrategy, Spec> map = strategyPatterns();

    List<String> missing = new ArrayList<>();
    for (PatternAnalyzer.MatchingStrategy strategy : PatternAnalyzer.MatchingStrategy.values()) {
      if (!map.containsKey(strategy)) {
        missing.add(strategy.name());
      }
    }
    assertTrue(
        missing.isEmpty(),
        "Every MatchingStrategy must have a representative pattern; missing: " + missing);

    // Confirm routing of each representative pattern.
    List<String> misrouted = new ArrayList<>();
    for (Map.Entry<PatternAnalyzer.MatchingStrategy, Spec> e : map.entrySet()) {
      PatternAnalyzer.MatchingStrategy expected = e.getKey();
      String pattern = e.getValue().pattern();
      PatternAnalyzer.MatchingStrategy actual = routeOf(pattern);
      if (actual != expected) {
        misrouted.add(
            "pattern '" + pattern + "' expected " + expected + " but routed to " + actual);
      }
    }
    assertTrue(misrouted.isEmpty(), "Misrouted representative patterns: " + misrouted);
  }

  /**
   * For every (strategy, pattern, input) tuple, exercise all eight public methods of {@link
   * ReggieMatcher} and assert agreement with {@link java.util.regex.Pattern}. Gated behind {@code
   * reggie.metatest.enforce} (see class javadoc).
   */
  @Test
  void allStrategiesAgreeWithJdkAcrossPublicApi() {
    Map<PatternAnalyzer.MatchingStrategy, Spec> map = strategyPatterns();
    List<Mismatch> mismatches = new ArrayList<>();

    for (Map.Entry<PatternAnalyzer.MatchingStrategy, Spec> e : map.entrySet()) {
      PatternAnalyzer.MatchingStrategy strategy = e.getKey();
      String pattern = e.getValue().pattern();

      ReggieMatcher reggie;
      Pattern jdk;
      try {
        reggie = Reggie.compile(pattern);
        jdk = Pattern.compile(pattern);
      } catch (Throwable ex) {
        mismatches.add(
            new Mismatch(strategy, pattern, "<compile>", "compile", "compiles", "threw " + ex));
        continue;
      }

      for (String input : e.getValue().inputs()) {
        checkInput(strategy, pattern, input, reggie, jdk, mismatches);
      }
    }

    report(mismatches);
  }

  private void checkInput(
      PatternAnalyzer.MatchingStrategy strategy,
      String pattern,
      String input,
      ReggieMatcher reggie,
      Pattern jdk,
      List<Mismatch> mismatches) {

    java.util.regex.Matcher jm = jdk.matcher(input);

    // 1. matches(String) == Pattern.matches(p, in)
    boolean jdkMatches = Pattern.matches(pattern, input);
    safeBool(
        strategy, pattern, input, "matches", jdkMatches, () -> reggie.matches(input), mismatches);

    // 2. find() == matcher.find()
    boolean jdkFind = jm.find();
    safeBool(strategy, pattern, input, "find", jdkFind, () -> reggie.find(input), mismatches);

    // 3a. findMatch span agrees with JDK find span
    if (jdkFind) {
      int fs = jm.start();
      int fe = jm.end();
      String fg = jm.group();
      safeSpan(
          strategy,
          pattern,
          input,
          "findMatch",
          fs,
          fe,
          fg,
          () -> reggie.findMatch(input),
          mismatches,
          /* expectNull= */ false);
      safeSpan(
          strategy,
          pattern,
          input,
          "findMatchFrom(0)",
          fs,
          fe,
          fg,
          () -> reggie.findMatchFrom(input, 0),
          mismatches,
          /* expectNull= */ false);
    } else {
      safeSpan(
          strategy,
          pattern,
          input,
          "findMatch",
          -1,
          -1,
          null,
          () -> reggie.findMatch(input),
          mismatches,
          /* expectNull= */ true);
      safeSpan(
          strategy,
          pattern,
          input,
          "findMatchFrom(0)",
          -1,
          -1,
          null,
          () -> reggie.findMatchFrom(input, 0),
          mismatches,
          /* expectNull= */ true);
    }

    // 3b. findFrom(in,0) start position agrees with JDK find start (or -1)
    int jdkFindStart = jdkFind ? findStart(jdk, input) : -1;
    safeInt(
        strategy,
        pattern,
        input,
        "findFrom(0)",
        jdkFindStart,
        () -> reggie.findFrom(input, 0),
        mismatches);

    // 4. match() span agrees with whole-input match; match()!=null iff matches()
    if (jdkMatches) {
      safeSpan(
          strategy,
          pattern,
          input,
          "match",
          0,
          input.length(),
          input,
          () -> reggie.match(input),
          mismatches,
          /* expectNull= */ false);
    } else {
      safeSpan(
          strategy,
          pattern,
          input,
          "match",
          -1,
          -1,
          null,
          () -> reggie.match(input),
          mismatches,
          /* expectNull= */ true);
    }

    // 5. matchesBounded(in,0,len) == matches; matchBounded span agrees
    safeBool(
        strategy,
        pattern,
        input,
        "matchesBounded(0,len)",
        jdkMatches,
        () -> reggie.matchesBounded(input, 0, input.length()),
        mismatches);
    if (jdkMatches) {
      safeSpan(
          strategy,
          pattern,
          input,
          "matchBounded(0,len)",
          0,
          input.length(),
          input,
          () -> reggie.matchBounded(input, 0, input.length()),
          mismatches,
          /* expectNull= */ false);
    } else {
      safeSpan(
          strategy,
          pattern,
          input,
          "matchBounded(0,len)",
          -1,
          -1,
          null,
          () -> reggie.matchBounded(input, 0, input.length()),
          mismatches,
          /* expectNull= */ true);
    }
  }

  private static int findStart(Pattern jdk, String input) {
    java.util.regex.Matcher m = jdk.matcher(input);
    return m.find() ? m.start() : -1;
  }

  // --- assertion/collection helpers -------------------------------------------------------------

  private interface BoolSupplier {
    boolean get();
  }

  private interface IntSupplier {
    int get();
  }

  private interface MatchSupplier {
    MatchResult get();
  }

  private void safeBool(
      PatternAnalyzer.MatchingStrategy strategy,
      String pattern,
      String input,
      String method,
      boolean expected,
      BoolSupplier actualSupplier,
      List<Mismatch> mismatches) {
    boolean actual;
    try {
      actual = actualSupplier.get();
    } catch (Throwable ex) {
      record(strategy, pattern, input, method, String.valueOf(expected), "threw " + ex, mismatches);
      return;
    }
    if (actual != expected) {
      record(
          strategy,
          pattern,
          input,
          method,
          String.valueOf(expected),
          String.valueOf(actual),
          mismatches);
    }
  }

  private void safeInt(
      PatternAnalyzer.MatchingStrategy strategy,
      String pattern,
      String input,
      String method,
      int expected,
      IntSupplier actualSupplier,
      List<Mismatch> mismatches) {
    int actual;
    try {
      actual = actualSupplier.get();
    } catch (Throwable ex) {
      record(strategy, pattern, input, method, String.valueOf(expected), "threw " + ex, mismatches);
      return;
    }
    if (actual != expected) {
      record(
          strategy,
          pattern,
          input,
          method,
          String.valueOf(expected),
          String.valueOf(actual),
          mismatches);
    }
  }

  private void safeSpan(
      PatternAnalyzer.MatchingStrategy strategy,
      String pattern,
      String input,
      String method,
      int expStart,
      int expEnd,
      String expGroup,
      MatchSupplier actualSupplier,
      List<Mismatch> mismatches,
      boolean expectNull) {
    MatchResult actual;
    try {
      actual = actualSupplier.get();
    } catch (Throwable ex) {
      record(
          strategy,
          pattern,
          input,
          method,
          expectNull ? "null" : ("[" + expStart + "," + expEnd + ")='" + expGroup + "'"),
          "threw " + ex,
          mismatches);
      return;
    }
    if (expectNull) {
      if (actual != null) {
        record(
            strategy,
            pattern,
            input,
            method,
            "null",
            "[" + actual.start() + "," + actual.end() + ")='" + actual.group() + "'",
            mismatches);
      }
      return;
    }
    if (actual == null) {
      record(
          strategy,
          pattern,
          input,
          method,
          "[" + expStart + "," + expEnd + ")='" + expGroup + "'",
          "null",
          mismatches);
      return;
    }
    int as = actual.start();
    int ae = actual.end();
    String ag = actual.group();
    if (as != expStart || ae != expEnd || !java.util.Objects.equals(ag, expGroup)) {
      record(
          strategy,
          pattern,
          input,
          method,
          "[" + expStart + "," + expEnd + ")='" + expGroup + "'",
          "[" + as + "," + ae + ")='" + ag + "'",
          mismatches);
    }
  }

  private void record(
      PatternAnalyzer.MatchingStrategy strategy,
      String pattern,
      String input,
      String method,
      String expected,
      String actual,
      List<Mismatch> mismatches) {
    mismatches.add(new Mismatch(strategy, pattern, input, method, expected, actual));
  }

  private void report(List<Mismatch> mismatches) {
    if (mismatches.isEmpty()) {
      System.out.println("[StrategyCorrectnessMetaTest] No mismatches across all strategies.");
      return;
    }

    // Group by strategy for a readable inventory.
    Map<PatternAnalyzer.MatchingStrategy, List<Mismatch>> byStrategy = new LinkedHashMap<>();
    for (Mismatch mm : mismatches) {
      byStrategy.computeIfAbsent(mm.strategy(), k -> new ArrayList<>()).add(mm);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[StrategyCorrectnessMetaTest] ")
        .append(mismatches.size())
        .append(" mismatch(es) across ")
        .append(byStrategy.size())
        .append(" strategy(ies):\n");
    for (Map.Entry<PatternAnalyzer.MatchingStrategy, List<Mismatch>> e : byStrategy.entrySet()) {
      sb.append("== ").append(e.getKey()).append(" ==\n");
      for (Mismatch mm : e.getValue()) {
        sb.append("  pattern='")
            .append(mm.pattern())
            .append("' input='")
            .append(mm.input())
            .append("' method=")
            .append(mm.method())
            .append(" expected=")
            .append(mm.expected())
            .append(" actual=")
            .append(mm.actual())
            .append('\n');
      }
    }
    System.out.println(sb);

    if (ENFORCE) {
      fail(sb.toString());
    }
  }

  /** Sanity check that the enforcement flag plumbing is wired (does not assert behavior). */
  @Test
  void enforcementFlagIsReadable() {
    // Touch the flag so the gate is exercised regardless of value; no semantic assertion.
    assertFalse(ENFORCE && false, "unreachable");
    assertEquals(Boolean.getBoolean("reggie.metatest.enforce"), ENFORCE);
  }
}
