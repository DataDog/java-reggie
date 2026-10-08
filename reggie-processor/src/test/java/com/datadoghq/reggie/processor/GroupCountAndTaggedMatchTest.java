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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.runtime.HybridMatcher;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.PooledMatcher;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group-count and {@code match()} parity with the JDK for annotation-processor generated matchers.
 *
 * <p>The processor's group counter treated {@code [(]}, inline flags ({@code (?i)}), branch resets
 * and conditionals as capturing groups, so group-less patterns reported a non-zero group count. The
 * DFA-unrolled {@code match()} for tagged DFAs also calls the private {@code findMatchFromForMatch}
 * helper, which only {@link com.datadoghq.reggie.runtime.RuntimeCompiler} emitted. Quantified
 * capturing groups ({@code (.c)+}) must report the last iteration's span; they are realized as a
 * DFA/NFA hybrid.
 */
class GroupCountAndTaggedMatchTest {
  private static final List<String> PATTERNS =
      List.of(
          "[(]x",
          "a[(]b",
          "(?i)abc",
          "(?-i)abc",
          "(fo|foo)",
          "(b)|b",
          "b|(b)",
          ".|([^c])",
          "(.c)+",
          ".+(0)",
          "(ab)+");

  private static final List<String> INPUTS =
      List.of(
          "", "x", "(x", "a(b", "abc", "ABC", "fo", "foo", "b", "a", "c", "acbc", "10", "x00",
          "abab", "abca", "bcabc");

  static List<String> patterns() {
    return PATTERNS;
  }

  private ReggieMatcher compile(String pattern) throws Exception {
    String className = "G" + Integer.toHexString(pattern.hashCode());
    String fqcn = "test.generated." + className;
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test.generated", className, pattern);
    TestClassLoader loader = new TestClassLoader();
    if (gen.resolveRealization(false) == ReggieMatcherBytecodeGenerator.Realization.HYBRID) {
      ReggieMatcherBytecodeGenerator.HybridClasses halves = gen.generateHybrid();
      Class<?> dfa =
          loader.defineClass(
              fqcn + ReggieMatcherBytecodeGenerator.DFA_HALF_SUFFIX, halves.dfaHalf());
      Class<?> nfa =
          loader.defineClass(
              fqcn + ReggieMatcherBytecodeGenerator.NFA_HALF_SUFFIX, halves.nfaHalf());
      return new HybridMatcher(
          pattern,
          (ReggieMatcher) dfa.getDeclaredConstructor().newInstance(),
          new PooledMatcher(
              MethodHandles.publicLookup().findConstructor(nfa, MethodType.methodType(void.class))),
          halves.lazyFind());
    }
    byte[] bytecode = gen.generate();
    assertNotNull(bytecode);
    Class<?> cls = loader.defineClass(fqcn, bytecode);
    return (ReggieMatcher) cls.getDeclaredConstructor().newInstance();
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void matchParity(String pattern) throws Exception {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher matcher = compile(pattern);
    for (String input : INPUTS) {
      Matcher m = jdk.matcher(input);
      MatchResult r = matcher.match(input);
      if (!m.matches()) {
        assertNull(r, "match() should fail for /" + pattern + "/ input: " + input);
        continue;
      }
      assertNotNull(r, "match() should succeed for /" + pattern + "/ input: " + input);
      assertEquals(
          m.groupCount(), r.groupCount(), "groupCount for /" + pattern + "/ input: " + input);
      for (int g = 0; g <= m.groupCount(); g++) {
        assertEquals(m.start(g), r.start(g), "start(" + g + ") /" + pattern + "/ " + input);
        assertEquals(m.end(g), r.end(g), "end(" + g + ") /" + pattern + "/ " + input);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void findMatchGroupCountParity(String pattern) throws Exception {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher matcher = compile(pattern);
    for (String input : INPUTS) {
      Matcher m = jdk.matcher(input);
      MatchResult r = matcher.findMatch(input);
      if (!m.find()) {
        assertNull(r, "findMatch() should fail for /" + pattern + "/ input: " + input);
        continue;
      }
      assertNotNull(r, "findMatch() should succeed for /" + pattern + "/ input: " + input);
      assertEquals(
          m.groupCount(), r.groupCount(), "groupCount for /" + pattern + "/ input: " + input);
    }
  }

  private static class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return super.defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
