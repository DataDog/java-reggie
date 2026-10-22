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

import static com.datadoghq.reggie.runtime.DotLineTerminatorTest.escape;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import com.datadoghq.reggie.ReggieOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Patterns whose generated method exceeds the JIT size limit, compiled with JDK fallback allowed,
 * route to {@link OverJitSplitMatcher} when Laurikari-eligible and not case-insensitive. Every
 * routed method agrees with {@code java.util.regex}, including group spans and named groups.
 */
class OverJitSplitMatcherTest {

  private static final String SPLIT_ROUTE = "LAURIKARI find / JDK matches (over JIT limit)";

  private static final ReggieOptions FALLBACK = ReggieOptions.builder().allowJdkFallback().build();

  /** The patterns from {@code OverJitLimitRerouteBenchmark}. */
  private static final String[] BENCHMARK_PATTERNS = {
    "[^a]{3,30}b+(?:\\n{2}\\n{1,25}\\n$)\\d{1,25}",
    "[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$",
    "[0-9a-f]{4,40}[0-9]{4,40}\\Z",
    "[\\w.-]{1,40}@[\\w.-]{1,40}\\.[a-z]{2,6}$",
    "[A-Za-z ]{1,60}[A-Za-z]{1,40}:\\d{1,5}$",
    "\\w{1,30}[\\w.]{1,40}\\.(?:com|org|net)$"
  };

  /** Over-limit patterns with capturing groups; the last two use named groups. */
  private static final String[] CAPTURE_PATTERNS = {
    "(x)[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$",
    "^(x)[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$",
    "(?:[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30})(x)$",
    "[\\w.-]{1,40}(@)[\\w.-]{1,40}\\.[a-z]{2,6}$",
    "[A-Za-z ]{1,60}[A-Za-z]{1,40}(:)\\d{1,5}$",
    "(?<lead>[xy])[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$",
    "[\\w.-]{1,40}(?<at>@)[\\w.-]{1,40}\\.[a-z]{2,6}$"
  };

  private static final String[][] NAMES = {{}, {}, {}, {}, {}, {"lead"}, {"at"}};

  /** Texts that match one or more of the patterns. */
  private static final String[] TEXTS = {
    "xyzb\n\n\n\n1",
    "xyzbb\n\n\n\n12",
    "session7f3a9b2c1d0e12345",
    "xsession7f3a9b2c1d0e12345",
    "yab12",
    "abcdef123x",
    "deadbeef0123456789",
    "john.doe@mail.example.com",
    "a@b.cd",
    "Server Host Name:8080",
    "ab:1",
    "www.example.com",
    "a.b.net",
    "ab12"
  };

  private static final String[] PREFIXES = {"", "x ", "\n", "\r\n", "ab ", "@"};

  private static final String[] SUFFIXES = {
    "", "\n", "\r\n", "\n\n", "\r", "\u0085", "\u2028", "\u2029", " ", "!", "1", "\nx"
  };

  @Test
  void overJitLimitPatternsRouteToSplitAndAgreeWithJdk() {
    List<String> wrong = new ArrayList<>();
    List<Input> inputs = inputs();
    for (String p : BENCHMARK_PATTERNS) {
      check(p, new String[0], inputs, wrong);
    }
    for (int i = 0; i < CAPTURE_PATTERNS.length; i++) {
      check(CAPTURE_PATTERNS[i], NAMES[i], inputs, wrong);
    }
    assertEquals(List.of(), wrong);
  }

  /**
   * The Laurikari half writes its DFA caches, so compile() must not share a split matcher or its
   * find half.
   */
  @Test
  void compileReturnsFreshSplitMatcher() {
    String p = BENCHMARK_PATTERNS[1];
    RuntimeCompiler.clearCache();
    ReggieMatcher first = RuntimeCompiler.compile(p, FALLBACK);
    ReggieMatcher second = RuntimeCompiler.compile(p, FALLBACK);
    assertEquals(OverJitSplitMatcher.class, unwrap(first).getClass());
    assertNotSame(unwrap(first), unwrap(second));
    assertNotSame(
        ((OverJitSplitMatcher) unwrap(first)).findHalf,
        ((OverJitSplitMatcher) unwrap(second)).findHalf);
  }

  /** Case-insensitive over-limit patterns: inline at the start, inline later, scoped. */
  private static final String[] CASE_INSENSITIVE_PATTERNS = {
    "(?i)[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$",
    "[a-z]{2,30}(?i)[a-z0-9]{2,30}\\d{2,30}$",
    "[a-z]{2,30}(?i:[a-z0-9]{2,30})\\d{2,30}$"
  };

  /** Inputs with U+212A (Kelvin sign) and U+017F (long s), which fold to k and s in the JDK. */
  private static final String[] CASE_FOLD_TEXTS = {
    "aK\u017fK200",
    "ab\u212ak200",
    "\u017fs12",
    "AB\u212A12",
    "abK200",
    "ABcd12",
    "x \u212a\u212a99"
  };

  private static final String BITSTATE_ROUTE =
      "BITSTATE_CAPTURE (generated method over the JIT size limit)";

  /**
   * A case-insensitive over-limit pattern routes to BitState, not to the split: the JDK half folds
   * U+212A and U+017F, the parser does not, so the split would answer matches() and find()
   * differently. The BitState matcher agrees with the strict (generated) compile.
   */
  @Test
  void caseInsensitiveOverJitLimitRoutesToBitState() {
    List<String> wrong = new ArrayList<>();
    for (String p : CASE_INSENSITIVE_PATTERNS) {
      RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(p);
      checkCaseInsensitive(
          p, info.routing, info.matcher, RuntimeCompiler.compile(p, ReggieOptions.DEFAULT), wrong);
    }
    // ReggieFlags.CASE_INSENSITIVE instead of an inline modifier
    String p = "[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$";
    RuntimeCompiler.clearCache();
    RuntimeCompiler.ROUTING_NOTE.remove();
    ReggieMatcher flagged = RuntimeCompiler.compile(p, ReggieFlags.CASE_INSENSITIVE, FALLBACK);
    checkCaseInsensitive(
        "flags:" + p,
        RuntimeCompiler.ROUTING_NOTE.get(),
        flagged,
        RuntimeCompiler.compile(p, ReggieFlags.CASE_INSENSITIVE, ReggieOptions.DEFAULT),
        wrong);
    assertEquals(List.of(), wrong);
  }

  private static void checkCaseInsensitive(
      String p, String route, ReggieMatcher m, ReggieMatcher strict, List<String> wrong) {
    if (!BITSTATE_ROUTE.equals(route) || !(unwrap(m) instanceof BitStateMatcher)) {
      wrong.add(p + " -> " + route + " | " + unwrap(m).getClass().getSimpleName());
      return;
    }
    for (String in : CASE_FOLD_TEXTS) {
      boolean matches = m.matches(in);
      boolean find = m.find(in);
      if (matches && !find) {
        wrong.add(p + " on \"" + escape(in) + "\": matches() without find()");
      }
      if (matches != strict.matches(in) || find != strict.find(in)) {
        wrong.add(p + " on \"" + escape(in) + "\": differs from the strict compile");
      }
      MatchResult r = m.findMatch(in);
      MatchResult s = strict.findMatch(in);
      String rs = r == null ? "-" : r.start() + "," + r.end();
      String ss = s == null ? "-" : s.start() + "," + s.end();
      if (!rs.equals(ss)) {
        wrong.add(p + " on \"" + escape(in) + "\": findMatch " + rs + " strict " + ss);
      }
    }
  }

  /** Random 400-char strings over {@code [a-f0-9 ]}: they fill the Laurikari DFA cache. */
  private static List<String> denseInputs(int count) {
    Random random = new Random(42);
    String alphabet = "abcdef0123456789 ";
    List<String> out = new ArrayList<>(count);
    for (int n = 0; n < count; n++) {
      char[] c = new char[400];
      for (int i = 0; i < c.length; i++) {
        c[i] = alphabet.charAt(random.nextInt(alphabet.length()));
      }
      out.add(new String(c));
    }
    return out;
  }

  /**
   * Once the Laurikari half's DFA cache is full, the split matcher sends every search call to the
   * JDK half; results stay equal to the JDK before, during and after the switch.
   */
  @Test
  void saturatedFindHalfRoutesSearchToWholeHalf() {
    String p = BENCHMARK_PATTERNS[1];
    Pattern j = Pattern.compile(p);
    List<String> dense = denseInputs(400);

    // Real split matcher: results equal the JDK throughout
    RuntimeCompiler.clearCache();
    OverJitSplitMatcher split = (OverJitSplitMatcher) unwrap(RuntimeCompiler.compile(p, FALLBACK));
    LaurikariDfaMatcher laurikari = (LaurikariDfaMatcher) split.findHalf;
    List<String> wrong = new ArrayList<>();
    for (String in : dense) {
      Matcher m = j.matcher(in);
      boolean jf = m.find();
      MatchResult r = split.findMatch(in);
      if ((r != null) != jf || (jf && (r.start() != m.start() || r.end() != m.end()))) {
        wrong.add("findMatch on \"" + in + "\" saturated=" + laurikari.saturated());
      }
    }
    assertEquals(List.of(), wrong);
    assertTrue(laurikari.saturated(), "dense inputs did not fill the Laurikari DFA cache");

    // Same saturated find half with a recording whole half: search calls reach the whole half
    Recorder wholeHalf = new Recorder("whole");
    LaurikariDfaMatcher fresh =
        (LaurikariDfaMatcher)
            ((OverJitSplitMatcher) unwrap(RuntimeCompiler.compile(p, FALLBACK))).findHalf;
    ReggieMatcher m = new OverJitSplitMatcher(p, fresh, wholeHalf);
    m.find(dense.get(0));
    assertEquals(List.of(), wholeHalf.calls);
    for (int n = 0; !fresh.saturated() && n < dense.size(); n++) {
      m.find(dense.get(n));
    }
    assertTrue(fresh.saturated());
    assertEquals(List.of(), wholeHalf.calls);
    int[] a = new int[1];
    m.find("");
    m.findFrom("", 0);
    m.findBoundsFrom("", 0, a);
    m.findMatch("");
    m.findMatchFrom("", 0);
    m.findMatchInto("", a, a);
    m.findMatchInto("", 0, a, a);
    m.replaceFirst("", "");
    m.replaceAll("", "");
    m.replaceAll("", r -> "");
    m.split("");
    m.split("", 0);
    m.findAll("");
    m.cursor("");
    assertEquals(
        List.of(
            "whole.find",
            "whole.findFrom",
            "whole.findBoundsFrom",
            "whole.findMatch",
            "whole.findMatchFrom",
            "whole.findMatchInto3",
            "whole.findMatchInto4",
            "whole.replaceFirst",
            "whole.replaceAllString",
            "whole.replaceAllFunction",
            "whole.split1",
            "whole.split2",
            "whole.findAll",
            "whole.cursor"),
        wholeHalf.calls);
  }

  /** Over-limit pattern with a named group (1) and an unnamed group (2) that named-only drops. */
  private static final String NAMED_ONLY_PATTERN =
      "(?<lead>[xy])(x)[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$";

  private static final ReggieOptions NAMED_ONLY_FALLBACK =
      ReggieOptions.builder().namedOnly().allowJdkFallback().build();

  private static final ReggieOptions NAMED_ONLY_STRICT =
      ReggieOptions.builder().namedOnly().build();

  /** Compiles {@code p} on a fresh cache and returns the routing note of that compile. */
  private static String freshRoute(String p, ReggieOptions options, ReggieMatcher[] out) {
    RuntimeCompiler.clearCache();
    RuntimeCompiler.ROUTING_NOTE.remove();
    out[0] = RuntimeCompiler.compile(p, options);
    return RuntimeCompiler.ROUTING_NOTE.get();
  }

  /** findMatch/findMatchInto and match/matchInto report the same spans for every group. */
  private static void assertFindAndMatchAgree(ReggieMatcher m, String in, int groupCount) {
    MatchResult f = m.findMatch(in);
    MatchResult w = m.match(in);
    assertNotNull(f, "findMatch on " + in);
    assertNotNull(w, "match on " + in);
    for (int g = 0; g <= groupCount; g++) {
      assertEquals(w.start(g), f.start(g), "group " + g + " start on " + in);
      assertEquals(w.end(g), f.end(g), "group " + g + " end on " + in);
    }
    int[] fs = new int[groupCount + 1];
    int[] fe = new int[groupCount + 1];
    int[] ws = new int[groupCount + 1];
    int[] we = new int[groupCount + 1];
    assertTrue(m.findMatchInto(in, fs, fe));
    assertTrue(m.matchInto(in, ws, we));
    assertArrayEquals(ws, fs, "group starts on " + in);
    assertArrayEquals(we, fe, "group ends on " + in);
  }

  /**
   * A named-only over-limit pattern routes to BitState, not to the split: the JDK half compiles the
   * unprojected pattern and would report the unnamed group that the find half drops. Every API then
   * reports the projected layout.
   */
  @Test
  void namedOnlyOverJitLimitRoutesToBitState() {
    // Non-vacuity: without named-only the pattern is split-admitted
    RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(NAMED_ONLY_PATTERN);
    assertEquals(SPLIT_ROUTE, info.routing);
    assertTrue(info.engineChain.endsWith("OverJitSplitMatcher"), info.engineChain);

    ReggieMatcher[] out = new ReggieMatcher[1];
    assertEquals(BITSTATE_ROUTE, freshRoute(NAMED_ONLY_PATTERN, NAMED_ONLY_FALLBACK, out));
    ReggieMatcher m = out[0];
    assertFalse(unwrap(m) instanceof OverJitSplitMatcher, unwrap(m).getClass().getName());

    // (x) participates in the JDK at [1,2) on both inputs; named-only must still drop it
    for (String in : new String[] {"xxabcd12", "yxabcd12"}) {
      assertFindAndMatchAgree(m, in, 2);
      MatchResult r = m.findMatch(in);
      assertEquals(2, r.groupCount(), "group indexes are preserved under named-only");
      assertEquals(0, r.start(1));
      assertEquals(1, r.end(1));
      assertEquals(-1, r.start(2), "unnamed group is projected away");
      assertEquals(-1, r.end(2), "unnamed group is projected away");
    }
  }

  /** The structural-cache-hit call site of overJitNative applies the named-only exclusion too. */
  @Test
  void namedOnlyExclusionAppliesOnStructuralCacheHit() {
    RuntimeCompiler.clearCache();
    assertFalse(Reggie.compile(NAMED_ONLY_PATTERN, NAMED_ONLY_STRICT).isJdkFallback());
    RuntimeCompiler.ROUTING_NOTE.remove();
    RuntimeCompiler.cached("named-only-overjit-hit", NAMED_ONLY_PATTERN, NAMED_ONLY_FALLBACK);
    assertEquals(BITSTATE_ROUTE, RuntimeCompiler.ROUTING_NOTE.get());

    // Control: the same hit path without named-only reaches the split
    RuntimeCompiler.clearCache();
    assertFalse(Reggie.compile(NAMED_ONLY_PATTERN).isJdkFallback());
    RuntimeCompiler.ROUTING_NOTE.remove();
    RuntimeCompiler.cached("overjit-hit-control", NAMED_ONLY_PATTERN, FALLBACK);
    assertEquals(SPLIT_ROUTE, RuntimeCompiler.ROUTING_NOTE.get());
  }

  /** Split and named-only entries for the same pattern do not alias each other in the caches. */
  @Test
  void namedOnlyAndSplitCompilationsDoNotAlias() {
    String in = "xxabcd12";
    RuntimeCompiler.clearCache();
    ReggieMatcher split = RuntimeCompiler.compile(NAMED_ONLY_PATTERN, FALLBACK);
    assertEquals(OverJitSplitMatcher.class, unwrap(split).getClass());

    RuntimeCompiler.ROUTING_NOTE.remove();
    ReggieMatcher named = RuntimeCompiler.compile(NAMED_ONLY_PATTERN, NAMED_ONLY_FALLBACK);
    assertEquals(BITSTATE_ROUTE, RuntimeCompiler.ROUTING_NOTE.get());
    assertFalse(unwrap(named) instanceof OverJitSplitMatcher);
    assertFindAndMatchAgree(named, in, 2);
    assertEquals(-1, named.match(in).start(2));

    ReggieMatcher again = RuntimeCompiler.compile(NAMED_ONLY_PATTERN, FALLBACK);
    assertEquals(OverJitSplitMatcher.class, unwrap(again).getClass());
    Matcher j = Pattern.compile(NAMED_ONLY_PATTERN).matcher(in);
    assertTrue(j.matches());
    MatchResult r = again.match(in);
    assertEquals(j.start(2), r.start(2));
    assertEquals(j.end(2), r.end(2));
    assertFindAndMatchAgree(again, in, 2);
  }

  private static final String SATURATING_PATTERN = "(?s)[0-9a-f]{4,40}[0-9]{4,40}.*(?m:$)";

  private static final String SATURATING_INPUT = "abcd1234\n" + "x".repeat(10000);

  /** A fresh split matcher for {@link #SATURATING_PATTERN} with an unsaturated find half. */
  private static OverJitSplitMatcher freshSaturatingSplit() {
    RuntimeCompiler.clearCache();
    OverJitSplitMatcher split =
        (OverJitSplitMatcher) unwrap(RuntimeCompiler.compile(SATURATING_PATTERN, FALLBACK));
    assertFalse(((LaurikariDfaMatcher) split.findHalf).saturated());
    return split;
  }

  /**
   * The call that fills the Laurikari DFA cache returns the full JDK span, the same as every later
   * call (which the split serves from the JDK half): results do not depend on cache state.
   */
  @Test
  void saturatingCallReturnsFullSpan() {
    assertEquals(SPLIT_ROUTE, RuntimeCompiler.describeRouting(SATURATING_PATTERN).routing);
    Matcher j = Pattern.compile(SATURATING_PATTERN).matcher(SATURATING_INPUT);
    assertTrue(j.find());
    int js = j.start();
    int je = j.end();
    assertEquals(SATURATING_INPUT.length(), je, "JDK sanity check");

    OverJitSplitMatcher split = freshSaturatingSplit();
    LaurikariDfaMatcher lk = (LaurikariDfaMatcher) split.findHalf;
    MatchResult r1 = split.findMatch(SATURATING_INPUT);
    assertTrue(lk.saturated(), "the first call must fill the Laurikari DFA cache");
    assertNotNull(r1);
    assertEquals(js, r1.start());
    assertEquals(je, r1.end());
    MatchResult r2 = split.findMatch(SATURATING_INPUT);
    assertNotNull(r2);
    assertEquals(r1.start(), r2.start());
    assertEquals(r1.end(), r2.end());

    split = freshSaturatingSplit();
    int[] gs = new int[1];
    int[] ge = new int[1];
    assertTrue(split.findMatchInto(SATURATING_INPUT, gs, ge));
    assertTrue(((LaurikariDfaMatcher) split.findHalf).saturated());
    assertEquals(js, gs[0]);
    assertEquals(je, ge[0]);

    split = freshSaturatingSplit();
    int[] b = new int[2];
    assertTrue(split.findBoundsFrom(SATURATING_INPUT, 0, b));
    assertTrue(((LaurikariDfaMatcher) split.findHalf).saturated());
    assertEquals(js, b[0]);
    assertEquals(je, b[1]);

    split = freshSaturatingSplit();
    assertTrue(split.find(SATURATING_INPUT));
    assertTrue(((LaurikariDfaMatcher) split.findHalf).saturated());
    assertTrue(split.findBoundsFrom(SATURATING_INPUT, 0, b));
    assertEquals(js, b[0]);
    assertEquals(je, b[1]);
  }

  /**
   * Each method reaches its half: the find family and the API derived from it reach the find half,
   * the whole-input family reaches the whole half, and setNameToIndex reaches only the find half
   * (the whole half is shared across compiles). The JDK differential above cannot tell the halves
   * apart, since both return JDK-equal results.
   */
  @Test
  void everyMethodReachesItsHalf() {
    Recorder findHalf = new Recorder("find");
    Recorder wholeHalf = new Recorder("whole");
    ReggieMatcher m = new OverJitSplitMatcher("p", findHalf, wholeHalf);
    List<String> calls = new ArrayList<>();
    findHalf.calls = calls;
    wholeHalf.calls = calls;
    int[] a = new int[1];
    m.matches("");
    m.matchesBounded("", 0, 0);
    m.match("");
    m.matchBounded("", 0, 0);
    m.matchInto("", a, a);
    m.find("");
    m.findFrom("", 0);
    m.findBoundsFrom("", 0, a);
    m.findMatch("");
    m.findMatchFrom("", 0);
    m.findMatchInto("", a, a);
    m.findMatchInto("", 0, a, a);
    m.replaceFirst("", "");
    m.replaceAll("", "");
    m.replaceAll("", r -> "");
    m.split("");
    m.split("", 0);
    m.findAll("");
    m.cursor("");
    m.setNameToIndex(Map.of("n", 1));
    assertEquals(
        List.of(
            "whole.matches",
            "whole.matchesBounded",
            "whole.match",
            "whole.matchBounded",
            "whole.matchInto",
            "find.find",
            "find.findFrom",
            "find.findBoundsFrom",
            "find.findMatch",
            "find.findMatchFrom",
            "find.findMatchInto3",
            "find.findMatchInto4",
            "find.replaceFirst",
            "find.replaceAllString",
            "find.replaceAllFunction",
            "find.split1",
            "find.split2",
            "find.findAll",
            "find.cursor",
            "find.setNameToIndex"),
        calls);
    assertFalse(m.isJdkFallback());
  }

  /** Records each call as {@code <half>.<method>}; returns no-match values. */
  private static final class Recorder extends ReggieMatcher {
    private final String half;
    List<String> calls = new ArrayList<>();

    Recorder(String half) {
      super("p");
      this.half = half;
    }

    private void rec(String method) {
      calls.add(half + "." + method);
    }

    @Override
    protected void setNameToIndex(Map<String, Integer> map) {
      rec("setNameToIndex");
    }

    @Override
    public boolean matches(String input) {
      rec("matches");
      return false;
    }

    @Override
    public boolean matchesBounded(CharSequence input, int start, int end) {
      rec("matchesBounded");
      return false;
    }

    @Override
    public MatchResult match(String input) {
      rec("match");
      return null;
    }

    @Override
    public MatchResult matchBounded(CharSequence input, int start, int end) {
      rec("matchBounded");
      return null;
    }

    @Override
    public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
      rec("matchInto");
      return false;
    }

    @Override
    public boolean find(String input) {
      rec("find");
      return false;
    }

    @Override
    public int findFrom(String input, int start) {
      rec("findFrom");
      return -1;
    }

    @Override
    public boolean findBoundsFrom(String input, int start, int[] bounds) {
      rec("findBoundsFrom");
      return false;
    }

    @Override
    public MatchResult findMatch(String input) {
      rec("findMatch");
      return null;
    }

    @Override
    public MatchResult findMatchFrom(String input, int start) {
      rec("findMatchFrom");
      return null;
    }

    @Override
    public boolean findMatchInto(String input, int[] groupStarts, int[] groupEnds) {
      rec("findMatchInto3");
      return false;
    }

    @Override
    public boolean findMatchInto(String input, int start, int[] groupStarts, int[] groupEnds) {
      rec("findMatchInto4");
      return false;
    }

    @Override
    public String replaceFirst(String input, String replacement) {
      rec("replaceFirst");
      return input;
    }

    @Override
    public String replaceAll(String input, String replacement) {
      rec("replaceAllString");
      return input;
    }

    @Override
    public String replaceAll(String input, Function<MatchResult, String> replacer) {
      rec("replaceAllFunction");
      return input;
    }

    @Override
    public String[] split(String input) {
      rec("split1");
      return new String[0];
    }

    @Override
    public String[] split(String input, int limit) {
      rec("split2");
      return new String[0];
    }

    @Override
    public List<MatchResult> findAll(String input) {
      rec("findAll");
      return List.of();
    }

    @Override
    public MatchCursor cursor(String input) {
      rec("cursor");
      return null;
    }
  }

  private static ReggieMatcher unwrap(ReggieMatcher m) {
    return m instanceof PrefilteringMatcher ? ((PrefilteringMatcher) m).delegate() : m;
  }

  private static void check(String p, String[] names, List<Input> inputs, List<String> wrong) {
    RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(p);
    if (!SPLIT_ROUTE.equals(info.routing) || !info.engineChain.endsWith("OverJitSplitMatcher")) {
      wrong.add(p + " -> " + info.routing + " | " + info.engineChain);
      return;
    }
    // A second compile takes the cached-entry path (fresh matcher from OVER_JIT_SPLIT_CACHE)
    ReggieMatcher[] matchers = {info.matcher, RuntimeCompiler.compile(p, FALLBACK)};
    Pattern j = Pattern.compile(p);
    for (ReggieMatcher r : matchers) {
      for (Input in : inputs) {
        String diff = diff(r, j, in, names);
        if (diff != null) {
          wrong.add(p + " on \"" + escape(in.text) + "\": " + diff);
          break;
        }
      }
    }
  }

  private record Input(String text, int[][] regions) {}

  private static List<Input> inputs() {
    Set<String> seen = new LinkedHashSet<>();
    List<Input> out = new ArrayList<>();
    for (String t : TEXTS) {
      for (String pre : PREFIXES) {
        for (String suf : SUFFIXES) {
          String s = pre + t + suf;
          if (!seen.add(s)) {
            continue;
          }
          int len = s.length();
          int core = pre.length() + t.length();
          out.add(
              new Input(
                  s,
                  new int[][] {
                    {0, len},
                    {pre.length(), len},
                    {0, core},
                    {pre.length(), core},
                    {1, len},
                    {0, len - 1},
                    {len, len}
                  }));
        }
      }
    }
    return out;
  }

  /** First disagreement with the JDK across every routed method, or {@code null}. */
  private static String diff(ReggieMatcher r, Pattern j, Input input, String[] names) {
    String in = input.text;
    int gc = j.matcher("").groupCount();

    // ---- whole-input family ----
    Matcher m = j.matcher(in);
    boolean jm = m.matches();
    String jSpans = jm ? spans(m, names) : "-";
    if (r.matches(in) != jm) {
      return "matches jdk=" + jm;
    }
    MatchResult mr = r.match(in);
    if (!jSpans.equals(mr == null ? "-" : spans(mr, gc, names))) {
      return "match jdk=" + jSpans + " reggie=" + (mr == null ? "-" : spans(mr, gc, names));
    }
    int[] gs = new int[gc + 1];
    int[] ge = new int[gc + 1];
    boolean into = r.matchInto(in, gs, ge);
    if (into != jm || (jm && !arraySpans(m, gs, ge))) {
      return "matchInto jdk=" + jSpans + " reggie=" + Arrays.toString(gs) + Arrays.toString(ge);
    }
    for (int[] reg : input.regions) {
      if (reg[0] < 0 || reg[1] < reg[0] || reg[1] > in.length()) {
        continue;
      }
      m = j.matcher(in);
      m.region(reg[0], reg[1]);
      boolean jb = m.matches();
      String jbSpans = jb ? spans(m, names) : "-";
      if (r.matchesBounded(in, reg[0], reg[1]) != jb) {
        return "matchesBounded" + Arrays.toString(reg) + " jdk=" + jb;
      }
      MatchResult br = r.matchBounded(in, reg[0], reg[1]);
      String rbSpans = br == null ? "-" : spans(br, gc, names);
      if (!jbSpans.equals(rbSpans)) {
        return "matchBounded" + Arrays.toString(reg) + " jdk=" + jbSpans + " reggie=" + rbSpans;
      }
    }

    // ---- find family ----
    m = j.matcher(in);
    boolean jf = m.find();
    String jfSpans = jf ? spans(m, names) : "-";
    if (r.find(in) != jf) {
      return "find jdk=" + jf;
    }
    MatchResult fr = r.findMatch(in);
    String rfSpans = fr == null ? "-" : spans(fr, gc, names);
    if (!jfSpans.equals(rfSpans)) {
      return "findMatch jdk=" + jfSpans + " reggie=" + rfSpans;
    }
    gs = new int[gc + 1];
    ge = new int[gc + 1];
    if (r.findMatchInto(in, gs, ge) != jf || (jf && !arraySpans(m, gs, ge))) {
      return "findMatchInto jdk=" + jfSpans;
    }
    for (int start = 0; start <= in.length(); start++) {
      m = j.matcher(in);
      boolean found = m.find(start);
      String jFrom = found ? spans(m, names) : "-";
      int rStart = r.findFrom(in, start);
      if (rStart != (found ? m.start() : -1)) {
        return "findFrom(" + start + ") jdk=" + jFrom + " reggie=" + rStart;
      }
      MatchResult fm = r.findMatchFrom(in, start);
      String rFrom = fm == null ? "-" : spans(fm, gc, names);
      if (!jFrom.equals(rFrom)) {
        return "findMatchFrom(" + start + ") jdk=" + jFrom + " reggie=" + rFrom;
      }
      int[] bounds = {-7, -7};
      boolean rb = r.findBoundsFrom(in, start, bounds);
      if (rb != found || (found && (bounds[0] != m.start() || bounds[1] != m.end()))) {
        return "findBoundsFrom(" + start + ") jdk=" + jFrom + " reggie=" + Arrays.toString(bounds);
      }
      gs = new int[gc + 1];
      ge = new int[gc + 1];
      if (r.findMatchInto(in, start, gs, ge) != found || (found && !arraySpans(m, gs, ge))) {
        return "findMatchInto(" + start + ") jdk=" + jFrom;
      }
    }

    // ---- API derived from the find family ----
    List<String> jAll = new ArrayList<>();
    m = j.matcher(in);
    while (m.find()) {
      jAll.add(spans(m, names));
    }
    List<String> rAll = new ArrayList<>();
    for (MatchResult a : r.findAll(in)) {
      rAll.add(spans(a, gc, names));
    }
    if (!jAll.equals(rAll)) {
      return "findAll jdk=" + jAll + " reggie=" + rAll;
    }
    String jRep = j.matcher(in).replaceAll("<$0>");
    String rRep = r.replaceAll(in, "<$0>");
    if (!jRep.equals(rRep)) {
      return "replaceAll jdk=" + escape(jRep) + " reggie=" + escape(rRep);
    }
    jRep = j.matcher(in).replaceFirst("<$0>");
    rRep = r.replaceFirst(in, "<$0>");
    if (!jRep.equals(rRep)) {
      return "replaceFirst jdk=" + escape(jRep) + " reggie=" + escape(rRep);
    }
    jRep = j.matcher(in).replaceAll(x -> "<" + x.start() + "," + x.end() + ">");
    rRep = r.replaceAll(in, (MatchResult x) -> "<" + x.start() + "," + x.end() + ">");
    if (!jRep.equals(rRep)) {
      return "replaceAll(Function) jdk=" + escape(jRep) + " reggie=" + escape(rRep);
    }
    if (!Arrays.equals(j.split(in), r.split(in))) {
      return "split jdk="
          + Arrays.toString(j.split(in))
          + " reggie="
          + Arrays.toString(r.split(in));
    }
    if (!Arrays.equals(j.split(in, -1), r.split(in, -1))) {
      return "split(-1) jdk="
          + Arrays.toString(j.split(in, -1))
          + " reggie="
          + Arrays.toString(r.split(in, -1));
    }
    List<String> rCursor = new ArrayList<>();
    MatchCursor cursor = r.cursor(in);
    for (MatchResult c = cursor.findNext(); c != null; c = cursor.findNext()) {
      rCursor.add(spans(c, gc, names));
    }
    if (!jAll.equals(rCursor)) {
      return "cursor jdk=" + jAll + " reggie=" + rCursor;
    }
    return null;
  }

  private static boolean arraySpans(Matcher m, int[] gs, int[] ge) {
    for (int g = 0; g <= m.groupCount(); g++) {
      if (gs[g] != m.start(g) || ge[g] != m.end(g)) {
        return false;
      }
    }
    return true;
  }

  private static String spans(Matcher m, String[] names) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append(m.start(g)).append(',').append(m.end(g)).append(';');
    }
    for (String n : names) {
      sb.append(n).append('=').append(m.start(n)).append(',').append(m.end(n));
      sb.append(':').append(m.group(n)).append(';');
    }
    return sb.toString();
  }

  private static String spans(MatchResult r, int gc, String[] names) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= gc; g++) {
      sb.append(r.start(g)).append(',').append(r.end(g)).append(';');
    }
    for (String n : names) {
      sb.append(n).append('=').append(r.start(n)).append(',').append(r.end(n));
      sb.append(':').append(r.group(n)).append(';');
    }
    return sb.toString();
  }
}
