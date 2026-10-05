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

import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Differential parity suite pinning the replacement/collection ops of {@link ReggieMatcher} —
 * {@code replaceAll(String,String)}, {@code replaceFirst(String,String)}, {@code
 * replaceAll(String,Function)}, and {@code split(String,int)} — against {@link
 * java.util.regex.Matcher}/{@link java.util.regex.Pattern} as the oracle.
 *
 * <p>Two sections:
 *
 * <ul>
 *   <li><b>Parity</b> — the ops must match the JDK exactly: literal replacements, single-digit
 *       group references ({@code $0..$9}, including references to groups that did not participate
 *       in the match, which append the empty string), multi-digit references that truncate to the
 *       largest defined group ({@code $12} with two groups = group 1 + literal "2"), zero-width
 *       matches, empty inputs, and all {@code split} limit semantics (positive, zero, negative —
 *       including the JDK-8 zero-width-at-start rule and trailing-empty discarding at limit 0).
 *   <li><b>Divergence</b> — where reggie intentionally differs from the JDK. Each divergence is
 *       pinned on both engines (reggie's documented behavior AND the JDK's observed behavior, so
 *       JDK drift fails the test too) and documented in the test's javadoc:
 *       <ol>
 *         <li>reggie does not process backslash escapes in replacement strings: {@code \\} stays
 *             two characters (JDK collapses it to one) and {@code \$} does not suppress {@code $n}
 *             expansion (JDK makes the {@code $} literal); {@code $$} is accepted as a literal
 *             {@code $} (JDK throws — only {@code \$} is a literal-dollar escape).
 *         <li>out-of-range group references (e.g. {@code $3} with two groups) and a trailing {@code
 *             $} at the end of the replacement are appended literally by reggie, while the JDK
 *             throws {@code IndexOutOfBoundsException} / {@code IllegalArgumentException}.
 *         <li>functional {@code replaceAll} uses the replacer's returned string verbatim, while the
 *             JDK expands {@code $n}/escape sequences in it (appendReplacement semantics,
 *             documented since JDK 9).
 *       </ol>
 * </ul>
 *
 * <p>Note: the replacement-string {@code $n} expansion itself is NOT a divergence — {@code
 * ReggieMatcher.replaceAll(String,String)} expands {@code $n} exactly like the JDK. The randomized
 * parity section restricts replacements to strings without {@code $}/{@code \} (where the engines
 * trivially agree) so it exercises match mechanics, not replacement parsing.
 */
public class ReplacementOpsParityTest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static ReggieMatcher reggie(String pattern) {
    return RuntimeCompiler.compile(pattern);
  }

  // ------------------------------------------------------------------
  // Parity: literal and group-reference replacement strings
  // ------------------------------------------------------------------

  /** {pattern, input, replacement} triples where reggie must equal the JDK. */
  private static final String[][] PARITY_CASES = {
    // plain literal replacements
    {"o", "foobar", "-"},
    {"o", "foobar", ""},
    {"[0-9]+", "a1b22c333d", "<N>"},
    {"\\s+", "a b  c   d", "_"},
    {"foo|bar", "foobar foobar", "X"},
    // group references: single digit, $0, unmatched group, $$, multi-digit truncation
    {"(a)(b)?", "a a", "[$1]"},
    {"(a)(b)?", "a a", "[$2]"}, // group 2 did not participate -> JDK appends empty
    {"(a)(b)?", "a a", "<$0>"},
    {"(a)(b)?", "a a", "<a0>"},
    {"(a)(b)?", "a a", "[x$12y]"}, // 12 > groupCount 2 -> group 1 + literal "2"
    {"(\\w+)=(\\d+)", "x=1 y=22 z=333", "$2:$1"},
    {"(\\w+)=(\\d+)", "x=1 y=22 z=333", "($1)($2)"},
    // zero-width matches
    {"a*", "bab", "-"},
    {"a*", "", "-"},
    {"x*", "abc", "-"},
    // empty input / no match
    {"o", "", "-"},
    {"zzz", "foobar", "-"},
    // GO_PATTERN-shaped site from the profiling-backend migration
    {"^(.+?)\\.([^/]+)", "example.com/res", "host=$1"},
  };

  @Test
  public void replaceAllLiteralParity() {
    for (String[] c : PARITY_CASES) {
      String expected = Pattern.compile(c[0]).matcher(c[1]).replaceAll(c[2]);
      assertEquals(
          expected, reggie(c[0]).replaceAll(c[1], c[2]), c[0] + " / " + c[1] + " / " + c[2]);
    }
  }

  @Test
  public void replaceFirstLiteralParity() {
    for (String[] c : PARITY_CASES) {
      String expected = Pattern.compile(c[0]).matcher(c[1]).replaceFirst(c[2]);
      assertEquals(
          expected, reggie(c[0]).replaceFirst(c[1], c[2]), c[0] + " / " + c[1] + " / " + c[2]);
    }
  }

  // ------------------------------------------------------------------
  // Parity: functional replaceAll
  // ------------------------------------------------------------------

  @Test
  public void functionalReplaceAllParity() {
    String[] patterns = {"(a)(b)?", "[0-9]+", "o", "a*", "foo|bar"};
    String[] inputs = {"a ab abc", "a1b22c333d", "foobar", "bab", ""};
    for (String p : patterns) {
      for (String input : inputs) {
        StringBuilder jdkSeen = new StringBuilder();
        String expected =
            Pattern.compile(p)
                .matcher(input)
                .replaceAll(
                    mr -> {
                      jdkSeen
                          .append(mr.group())
                          .append('@')
                          .append(mr.start())
                          .append(':')
                          .append(mr.groupCount())
                          .append(';');
                      return "<" + mr.group() + ">";
                    });
        StringBuilder reggieSeen = new StringBuilder();
        String actual =
            reggie(p)
                .replaceAll(
                    input,
                    mr -> {
                      reggieSeen
                          .append(mr.group())
                          .append('@')
                          .append(mr.start())
                          .append(':')
                          .append(mr.groupCount())
                          .append(';');
                      return "<" + mr.group() + ">";
                    });
        assertEquals(expected, actual, p + " / " + input + " result");
        assertEquals(jdkSeen.toString(), reggieSeen.toString(), p + " / " + input + " match view");
      }
    }
  }

  /**
   * Divergence 5: {@code $$} as a literal-dollar escape. The JDK's replacement-string grammar has
   * no {@code $$} escape — a literal {@code $} is written {@code \$}, so {@code $$} throws {@code
   * IllegalArgumentException}. Reggie accepts {@code $$} and appends a single {@code $}.
   */
  @Test
  public void divergenceDoubleDollar() {
    assertThrows(
        Exception.class, () -> Pattern.compile("(a)(b)?").matcher("a a").replaceAll("[$$]"));
    assertEquals("[$] [$]", reggie("(a)(b)?").replaceAll("a a", "[$$]"));
  }

  /**
   * Divergence 6: functional {@code replaceAll}. Reggie uses the replacer's returned string
   * verbatim. The JDK treats the returned string as a replacement string with appendReplacement
   * semantics — {@code $n} group references and backslash escapes ARE expanded in it. Pin both.
   */
  @Test
  public void divergenceFunctionalReplaceAllExpansion() {
    // reggie: verbatim.
    assertEquals("b<$1>n<$1>n<$1>", reggie("(a)").replaceAll("banana", mr -> "<$1>"));
    // JDK: expands $1 in the replacer's result.
    assertEquals("b<a>n<a>n<a>", Pattern.compile("(a)").matcher("banana").replaceAll(mr -> "<$1>"));
  }

  /** Functional replacers returning expansion-free strings agree with the JDK (parity path). */
  @Test
  public void functionalReplaceAllParityOnExpansionFreeResults() {
    assertEquals(
        Pattern.compile("(a)").matcher("banana").replaceAll(mr -> "[" + mr.group() + "]"),
        reggie("(a)").replaceAll("banana", mr -> "[" + mr.group() + "]"));
  }

  /** An exception thrown by the replacer propagates out of replaceAll. */
  @Test
  public void functionalReplaceAllExceptionPropagates() {
    ReggieMatcher m = reggie("(a)");
    assertThrows(
        IllegalStateException.class,
        () ->
            m.replaceAll(
                "banana",
                mr -> {
                  throw new IllegalStateException("boom");
                }));
  }

  // ------------------------------------------------------------------
  // Parity: split
  // ------------------------------------------------------------------

  private static final String[][] SPLIT_CASES = {
    {"o", "foobar"},
    {"o", "foo"},
    {":", ":::"},
    {",", ""},
    {",", ","},
    {",", ",a,"},
    {"a*", "bab"},
    {"a*", "aaab"},
    {"a*", "aaa"},
    {"a*", ""},
    {"x*", "abc"},
    {"\\d+", "a1b22c333d"},
    {"\\d+", "123"},
    {"\\b", "one two three"},
  };

  @Test
  public void splitParity() {
    int[] limits = {0, 1, 2, 3, -1};
    for (String[] c : SPLIT_CASES) {
      Pattern jdk = Pattern.compile(c[0]);
      ReggieMatcher rm = reggie(c[0]);
      for (int limit : limits) {
        String[] expected = jdk.split(c[1], limit);
        String[] actual = rm.split(c[1], limit);
        assertArrayEquals(expected, actual, c[0] + " / \"" + c[1] + "\" / limit " + limit);
      }
      assertArrayEquals(
          jdk.split(c[1]), rm.split(c[1]), c[0] + " / \"" + c[1] + "\" / limit 0 (one-arg)");
    }
  }

  // ------------------------------------------------------------------
  // Parity: randomized differential (seeded)
  // ------------------------------------------------------------------

  /**
   * Seeded randomized differential: patterns from a restricted construct grammar (literals, char
   * classes, alternation, groups, bounded/unbounded quantifiers, lazy variants — no backrefs,
   * lookaround, or named groups), inputs over a small alphabet with separators, replacement strings
   * without {@code $}/{@code \}. Both engines must agree on replaceAll, replaceFirst, and split for
   * every limit.
   */
  @Test
  public void randomizedDifferentialParity() {
    Random rnd = new Random(20261002L);
    int compared = 0;
    for (int i = 0; i < 400; i++) {
      String pattern = randomPattern(rnd, 0);
      String input = randomInput(rnd);
      // Replacement without $ or \: both engines treat it literally.
      String replacement = randomReplacement(rnd);
      int[] limits = {0, 1, 2, 3, -1};

      Pattern jdk = Pattern.compile(pattern);
      ReggieMatcher rm;
      try {
        rm = reggie(pattern);
      } catch (UnsupportedPatternException e) {
        continue; // reggie refuses the construct; nothing to compare
      }
      compared++;

      assertEquals(
          jdk.matcher(input).replaceAll(replacement),
          rm.replaceAll(input, replacement),
          "replaceAll pattern=" + pattern + " input=" + input + " repl=" + replacement);
      assertEquals(
          jdk.matcher(input).replaceFirst(replacement),
          rm.replaceFirst(input, replacement),
          "replaceFirst pattern=" + pattern + " input=" + input + " repl=" + replacement);
      for (int limit : limits) {
        assertArrayEquals(
            jdk.split(input, limit),
            rm.split(input, limit),
            "split(" + limit + ") pattern=" + pattern + " input=" + input);
      }
    }
    // Non-vacuity floor: if a future codegen regression makes reggie refuse most of this
    // grammar, the loop above would pass while comparing almost nothing.
    assertTrue(
        compared >= 300, "randomized differential compared only " + compared + "/400 patterns");
  }

  private static final String ALPHABET = "abc";
  private static final String SEPARATORS = " =/.";

  private static String randomPattern(Random rnd, int depth) {
    int parts = 1 + rnd.nextInt(3);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts; i++) {
      if (i > 0 && rnd.nextBoolean()) {
        sb.append('|');
      }
      sb.append(randomAtom(rnd, depth));
    }
    return sb.toString();
  }

  private static String randomAtom(Random rnd, int depth) {
    switch (rnd.nextInt(6)) {
      case 0 -> {
        int len = 1 + rnd.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
          sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
      }
      case 1 -> {
        return switch (rnd.nextInt(4)) {
          case 0 -> "[abc]";
          case 1 -> "[a-c]";
          case 2 -> "[^a]";
          default -> "\\d";
        };
      }
      case 2 -> {
        char base = ALPHABET.charAt(rnd.nextInt(ALPHABET.length()));
        char q = "*+?".charAt(rnd.nextInt(3));
        return "" + base + q + (rnd.nextBoolean() ? "?" : "");
      }
      case 3 -> {
        int n = rnd.nextInt(3);
        int m = n + rnd.nextInt(3);
        return ALPHABET.charAt(rnd.nextInt(ALPHABET.length())) + "{" + n + "," + m + "}";
      }
      case 4 -> {
        // group with quantifier, bounded nesting
        String inner = depth < 2 ? randomPattern(rnd, depth + 1) : "a";
        String q = rnd.nextBoolean() ? "?" : "";
        return "(" + inner + ")" + q;
      }
      default -> {
        return String.valueOf(SEPARATORS.charAt(rnd.nextInt(SEPARATORS.length())));
      }
    }
  }

  private static String randomInput(Random rnd) {
    int len = rnd.nextInt(13);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      String pool = rnd.nextInt(3) == 0 ? SEPARATORS : ALPHABET;
      sb.append(pool.charAt(rnd.nextInt(pool.length())));
    }
    return sb.toString();
  }

  private static String randomReplacement(Random rnd) {
    int len = rnd.nextInt(4);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      String pool = rnd.nextBoolean() ? ALPHABET : "<>";
      sb.append(pool.charAt(rnd.nextInt(pool.length())));
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------
  // Divergence (documented): backslash escapes are not processed
  // ------------------------------------------------------------------

  /**
   * Divergence 1: replacement {@code \\} — the JDK's replacement-string grammar collapses it to a
   * single backslash; reggie appends the two characters literally. Pinned on both engines.
   */
  @Test
  public void divergenceBackslashEscapeStaysLiteral() {
    // JDK: \\ is an escape for a literal backslash.
    assertEquals("[\\] [\\]", Pattern.compile("(a)(b)?").matcher("a a").replaceAll("[\\\\]"));
    // reggie: no escape processing, both characters are appended.
    assertEquals("[\\\\] [\\\\]", reggie("(a)(b)?").replaceAll("a a", "[\\\\]"));
  }

  /**
   * Divergence 2: replacement {@code \$1} — the JDK makes {@code \$} a literal {@code $} (so the
   * group reference does NOT expand); reggie leaves the backslash in place and expands {@code $1}.
   */
  @Test
  public void divergenceEscapedDollarDoesNotSuppressExpansion() {
    assertEquals("[$1] [$1]", Pattern.compile("(a)(b)?").matcher("a a").replaceAll("[\\$1]"));
    assertEquals("[\\a] [\\a]", reggie("(a)(b)?").replaceAll("a a", "[\\$1]"));
  }

  // ------------------------------------------------------------------
  // Divergence (documented): error cases append literally instead of throwing
  // ------------------------------------------------------------------

  /**
   * Divergence 3: a group reference beyond {@code groupCount} (e.g. {@code $3} with two groups).
   * The JDK throws {@code IndexOutOfBoundsException}; reggie appends the reference literally.
   */
  @Test
  public void divergenceOutOfRangeGroupRefAppendsLiterally() {
    assertThrows(
        Exception.class, () -> Pattern.compile("(a)(b)?").matcher("a a").replaceAll("[$3]"));
    assertEquals("[$3] [$3]", reggie("(a)(b)?").replaceAll("a a", "[$3]"));
  }

  /**
   * Divergence 4: a trailing {@code $} at the end of the replacement (no group number after it).
   * The JDK throws {@code IllegalArgumentException} ("Illegal group reference"); reggie appends the
   * {@code $} literally.
   */
  @Test
  public void divergenceTrailingDollarAppendsLiterally() {
    assertThrows(
        Exception.class, () -> Pattern.compile("(a)(b)?").matcher("a a").replaceFirst("x$"));
    assertEquals("x$ a", reggie("(a)(b)?").replaceFirst("a a", "x$"));
  }

  // ------------------------------------------------------------------
  // Parity: greedy longest-end on the split/replaceAll bounds path
  // ------------------------------------------------------------------

  /**
   * Regression pin for the DFA_UNROLLED inline greedy scan used by {@code findBoundsFrom} (the
   * bounds source for {@code split}/{@code replaceAll(String,String)}): the scan must report the
   * LONGEST accepting position, including when it terminates at a non-first accepting state —
   * bounded quantifiers whose match needs greedy backtracking (e.g. {@code b{0,2}b} on "bb" is
   * [0,2), not [0,1)). The former skippable-accepting-state optimization dropped the record at any
   * accepting state the scan could terminate on and truncated these matches. The {@code
   * cbb|}-prefixed rows provably route to DFA_UNROLLED (plain {@code b{0,2}b} alone can route
   * elsewhere); the {@code a{m,n}} rows pin end-to-end behavior wherever they route.
   */
  @Test
  public void findBoundsLongestEndParity() {
    String[][] cases = {
      {"cbb|b{0,2}b", "c= c==b=bbcb"},
      {"cbb|b{0,2}b", "bb"},
      {"cbb|b{0,2}b", "bbcb"},
      {"cbb|b{0,2}b", "abba"},
      {"cbb|b{0,2}b", "bbb"},
      {"a{2,4}", "aaa"},
      {"a{2,4}", "aaaa"},
      {"a{2,4}", "aab"},
      {"a{2,}", "aaaa"},
      {"(ab){1,3}x", "abababx"},
    };
    for (String[] c : cases) {
      Pattern jdk = Pattern.compile(c[0]);
      ReggieMatcher rm = reggie(c[0]);
      String replacement = "<M>";
      assertEquals(
          jdk.matcher(c[1]).replaceAll(replacement),
          rm.replaceAll(c[1], replacement),
          c[0] + " / " + c[1] + " replaceAll");
      assertArrayEquals(jdk.split(c[1], 0), rm.split(c[1], 0), c[0] + " / " + c[1] + " split");
    }
  }

  // ------------------------------------------------------------------
  // Match-cursor consistency for the streaming path used by rich-API callers
  // ------------------------------------------------------------------

  /** findAll match spans must agree with a JDK find() walk for the parity patterns. */
  @Test
  public void findAllSpansParity() {
    String[] patterns = {"(a)(b)?", "o", "a*", "[0-9]+", "foo|bar", "x*"};
    String[] inputs = {"a ab abc", "foobar", "bab", "a1b22c333d", "foobar foobar", "abc", ""};
    for (String p : patterns) {
      for (String input : inputs) {
        List<int[]> expected = new ArrayList<>();
        Matcher jdk = Pattern.compile(p).matcher(input);
        while (jdk.find()) {
          expected.add(new int[] {jdk.start(), jdk.end()});
        }
        List<int[]> actual = new ArrayList<>();
        for (var mr : reggie(p).findAll(input)) {
          actual.add(new int[] {mr.start(), mr.end()});
        }
        assertEquals(expected.size(), actual.size(), p + " / " + input + " match count");
        for (int i = 0; i < expected.size(); i++) {
          assertArrayEquals(expected.get(i), actual.get(i), p + " / " + input + " span " + i);
        }
      }
    }
  }

  /**
   * {@code split("")} — both engines return a single-element array containing the empty string, for
   * every limit (JDK 26 {@code Arrays.toString} renders it as {@code []}, which is easy to misread
   * — hence the explicit element assertions).
   */
  @Test
  public void splitDegenerateInputs() {
    for (int limit : new int[] {0, 1, 2, -1}) {
      String[] expected = Pattern.compile(",").split("", limit);
      String[] actual = reggie(",").split("", limit);
      assertEquals(1, expected.length, "jdk sanity, limit " + limit);
      assertEquals(1, actual.length, "limit " + limit);
      assertEquals("", actual[0], "limit " + limit);
    }
    assertEquals(1, reggie("a*").split("").length);
  }
}
