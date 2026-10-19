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
package com.datadoghq.reggie.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Annotation-processor counterpart of the runtime {@code TrailingLookaheadDifferentialTest}:
 * trailing lookaheads, lookarounds combined with {@code \b}/{@code \B} (evaluated by the NFA
 * closure), and LINEAR_BACKREFERENCE {@code findBoundsFrom}. Patterns the processor refuses at
 * compile time ({@link UnsupportedOperationException}) are skipped. Every input up to the given
 * length is checked against JDK across
 * matches/find/findFrom/findMatchFrom/findBoundsFrom/matchesBounded/matchInto/
 * findMatchInto/match/replaceAll/split.
 */
public class TrailingLookaheadProcessorDifferentialTest {

  static final String[] DFA_ADMITTED = {
    "a+(?=b)",
    "a+(?!b)",
    "a+(?=b)|c",
    "a+(?!a)",
    "[ab]+(?!b)",
    "[ab]*a[ab]{3}(?=c)",
    "ab(?=c)|a(?=b)"
  };

  /** Trailing-lookahead shapes the assertion DFA must not take. */
  static final String[] DFA_REFUSED = {
    "(?:a|ab)(?=c)",
    "a+(?=b)|a+(?=c)",
    "a+(?=b)|[ab]+(?!a)",
    "a*(?=b)",
    "a(?=b)[a-c]*",
    "[ab]*a[ab]{5}(?=c)",
    "[ab]*a[ab]{5}(?!c)"
  };

  static final String[] TRAILING = {
    "a*b(?=c)",
    "[ab]+(?=c)",
    "a+(?=bc)",
    "a+(?=[bc]a)",
    "(?:ab)+(?=a)",
    "a+b*(?=c)",
    "a+(?=b)|a",
    "a|b+(?=c)",
    "a{2,3}(?=b)",
    "ab+(?=b)",
    "(?:a|b)+(?=c)",
    "a+(?=a)",
    "b(?:a+(?=b)|c)",
    "(?:a+|b)(?=c)",
    "[ab]*a(?=b)",
    "a+(?=b)$",
    "^a+(?=b)",
    "\\w+(?=\\sc)",
    "a+(?=(?:b))",
    "[ab]*a[ab]{3}(?!c)",
    "[ab]*b[ab]{3}(?=a)|c",
    "a+(?=b)c",
    "a(?=b)(?=\\w)",
    "a+(?=b)\\w",
    "a+(?=[bc])[bc]"
  };

  static final String[] WORD_BOUNDARY = {
    "a+(?=b)\\b",
    "a(?=b)\\b",
    "a+(?=b)\\B",
    "(?=b)\\b",
    "a+\\b(?=b)",
    "(?:a+|b)(?=c)\\b",
    "a(?!b)\\b",
    "a\\b",
    "\\ba+\\b",
    "\\Ba",
    "(?:a|ab)\\b",
    "a*\\B",
    "(a|b)+\\b",
    "\\b(a)\\b",
    "(?:ab|a)\\Bb",
    "a{1,3}\\b",
    "(a+)\\b \\1",
    "(a|b)\\b.\\1\\b",
    "(a*)\\b\\1"
  };

  static final String[] LINEAR_BACKREF = {"(a)\\1", "(\\w)\\1", "(\\w)\\B\\1"};

  @Test
  void trailingLookaheadsAgreeWithJdk() {
    assertAgrees(DFA_ADMITTED, new char[] {'a', 'b', 'c', ' '}, 6);
    assertAgrees(DFA_REFUSED, new char[] {'a', 'b', 'c'}, 8);
    assertAgrees(TRAILING, new char[] {'a', 'b', 'c', ' '}, 6);
  }

  @Test
  void wordBoundaryWithLookaroundAgreesWithJdk() {
    assertAgrees(WORD_BOUNDARY, new char[] {'a', 'b', 'c', ' '}, 6);
  }

  @Test
  void linearBackrefBoundsAgreeWithJdk() {
    assertAgrees(LINEAR_BACKREF, new char[] {'a', 'b', ' '}, 6);
  }

  private static void assertAgrees(String[] patterns, char[] alphabet, int maxLen) {
    List<String> inputs = inputs(alphabet, maxLen);
    List<String> failures = new ArrayList<>();
    for (String p : patterns) {
      ReggieMatcher r = compile(p);
      if (r == null) continue;
      Pattern jdk = Pattern.compile(p);
      for (String in : inputs) {
        String f = divergence(r, jdk, in);
        if (f != null) {
          failures.add("/" + p + "/ on \"" + in + "\": " + f);
          break;
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  static List<String> inputs(char[] alphabet, int maxLen) {
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    for (int i = 0; i < inputs.size() && inputs.get(i).length() < maxLen; i++) {
      for (char ch : alphabet) inputs.add(inputs.get(i) + ch);
    }
    return inputs;
  }

  /** First API whose result diverges from JDK, or null. */
  static String divergence(ReggieMatcher r, Pattern jdk, String in) {
    if (jdk.matcher(in).matches() != r.matches(in)) return "matches";
    Matcher jm = jdk.matcher(in);
    boolean jf = jm.find();
    if (jf != r.find(in)) return "find";
    int[] bounds = new int[2];
    for (int s = 0; s <= in.length(); s++) {
      Matcher m = jdk.matcher(in);
      boolean ok = m.find(s);
      int fs = r.findFrom(in, s);
      if ((ok ? m.start() : -1) != fs) return "findFrom(" + s + ") " + fs;
      String exp = ok ? m.start() + ".." + m.end() : "null";
      MatchResult mr = r.findMatchFrom(in, s);
      String got = mr == null ? "null" : mr.start() + ".." + mr.end();
      if (!exp.equals(got)) return "findMatchFrom(" + s + ") exp " + exp + " got " + got;
      String gotB = r.findBoundsFrom(in, s, bounds) ? bounds[0] + ".." + bounds[1] : "null";
      if (!exp.equals(gotB)) return "findBoundsFrom(" + s + ") exp " + exp + " got " + gotB;
      for (int e = s; e <= in.length(); e++) {
        if (jdk.matcher(in.substring(s, e)).matches() != r.matchesBounded(in, s, e)) {
          return "matchesBounded(" + s + "," + e + ")";
        }
      }
    }
    int[] gs = new int[jm.groupCount() + 1];
    int[] ge = new int[jm.groupCount() + 1];
    if (r.matchInto(in, gs, ge) != jdk.matcher(in).matches()) return "matchInto";
    boolean fi = r.findMatchInto(in, gs, ge);
    if (fi != jf || (jf && (gs[0] != jm.start() || ge[0] != jm.end()))) return "findMatchInto";
    if ((r.match(in) != null) != jdk.matcher(in).matches()) return "match";
    if (!jdk.matcher(in).replaceAll("<$0>").equals(r.replaceAll(in, "<$0>"))) return "replaceAll";
    if (!Arrays.equals(jdk.split(in), r.split(in))) return "split";
    return null;
  }

  private static int seq;

  /** Processor-generated matcher, or null when the processor refuses the pattern. */
  private static ReggieMatcher compile(String pattern) {
    String name = "TrailingLookahead" + (seq++);
    byte[] bytecode;
    try {
      bytecode = new ReggieMatcherBytecodeGenerator("test.generated", name, pattern).generate();
    } catch (UnsupportedOperationException refused) {
      return null;
    } catch (Exception e) {
      throw new AssertionError(pattern, e);
    }
    try {
      Class<?> cls = new TestClassLoader().defineClass("test.generated." + name, bytecode);
      return (ReggieMatcher) cls.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(pattern, e);
    }
  }

  private static class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return super.defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
