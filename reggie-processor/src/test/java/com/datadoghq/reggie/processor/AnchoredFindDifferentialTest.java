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

import java.lang.reflect.Method;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the annotation-processor generated {@code find()}/{@code findFrom()} on
 * start-anchored patterns (issue: generated find() ignored a leading {@code ^}).
 *
 * <p>The bug: the APT pipeline constructed {@code DFASwitchBytecodeGenerator} without the NFA, so
 * {@code requiresStartAnchor}/{@code hasMultilineStart} were false and the generated findFrom
 * scanned every position, matching mid-input where the JDK (position 0 only, no MULTILINE) does
 * not. Expected values are computed from {@link Pattern} at runtime (JDK-differential style) —
 * never hand-derived.
 */
class AnchoredFindDifferentialTest {

  private Object compile(String pattern, String className) throws Exception {
    byte[] bytecode =
        new ReggieMatcherBytecodeGenerator("test.generated", className, pattern).generate();
    assertNotNull(bytecode);
    Class<?> cls = new TestClassLoader().defineClass("test.generated." + className, bytecode);
    return cls.getDeclaredConstructor().newInstance();
  }

  private static boolean aptFind(Object matcher, String input) throws Exception {
    Method find = matcher.getClass().getMethod("find", String.class);
    return (Boolean) find.invoke(matcher, input);
  }

  private static int aptFindFrom(Object matcher, String input, int start) throws Exception {
    Method findFrom = matcher.getClass().getMethod("findFrom", String.class, int.class);
    return (Integer) findFrom.invoke(matcher, input, start);
  }

  private void assertFindParity(String pattern, String input) throws Exception {
    Object matcher = compile(pattern, "M" + Integer.toHexString(pattern.hashCode()));
    boolean expected = Pattern.compile(pattern).matcher(input).find();
    boolean actual = aptFind(matcher, input);
    assertEquals(
        expected,
        actual,
        () -> "APT find('" + input + "') diverges from JDK for /" + pattern + "/");
    int expectedFrom =
        expected ? indexOrMinusOne(Pattern.compile(pattern).matcher(input), input) : -1;
    // findFrom(input, 0) reports the match start when a match exists.
    assertEquals(expectedFrom, aptFindFrom(matcher, input, 0), pattern);
    // With a leading anchor, findFrom(1) must agree with JDK Matcher.find(1) — computed
    // from the JDK, never hand-derived (multiline ^ legitimately matches post-'\n').
    if (input.length() > 1) {
      boolean from1 = Pattern.compile(pattern).matcher(input).find(1);
      assertEquals(from1, aptFindFrom(matcher, input, 1) >= 0, pattern + " findFrom(1)");
    }
  }

  private static int indexOrMinusOne(java.util.regex.Matcher m, String input) {
    m.reset();
    return m.find() ? m.start() : -1;
  }

  @Test
  void anchoredLongLiteralDfaSwitch() throws Exception {
    // 26-state DFA: routes to DFA_SWITCH in both pipelines.
    assertFindParity("^abcdefghijklmnopqrstuvwxy", "Xabcdefghijklmnopqrstuvwxy");
    assertFindParity("^abcdefghijklmnopqrstuvwxy", "abcdefghijklmnopqrstuvwxy");
    assertFindParity("^java\\.lang\\.Throwable\\.<init>", "Xjava.lang.Throwable.<init>");
    assertFindParity("^java\\.lang\\.Throwable\\.<init>", "java.lang.Throwable.<init>()V");
  }

  @Test
  void anchoredFixedLengthCharClassDfaSwitch() throws Exception {
    // The profiling-backend VALID_COMMIT_SHA site: ^[0-9a-fA-F]{40}$. The 41-char input
    // admits a full 40-char match at offset 1 (whose end also satisfies $) — only the
    // dropped ^ constraint makes that a match.
    String p41 = "0123456789abcdef0123456789abcdef012345678";
    assertEquals(41, p41.length());
    assertFindParity("^[0-9a-fA-F]{40}$", p41);
    assertFindParity("^[0-9a-fA-F]{40}$", "X" + p41.substring(1));
    assertFindParity("^[0-9a-fA-F]{40}$", "0123456789abcdef0123456789abcdef0123456789");
  }

  @Test
  void anchoredShortPatternsOtherStrategies() throws Exception {
    // Small DFAs route to DFA_UNROLLED / DFA_UNROLLED_WITH_GROUPS; they were correct via
    // per-transition guards but must stay correct after the call-site alignment.
    assertFindParity("^abc", "Xabc");
    assertFindParity("^aaa\\.aaa\\.aaa", "Xaaa.aaa.aaa");
    assertFindParity("^(a|b)c", "Xbc");
    assertFindParity("^\\d{3}-\\d{3}-\\d{4}", "123-456-7890 call");
  }

  @Test
  void multilineAnchoredDfaSwitch() throws Exception {
    // (?m)^ allows position 0 and post-'\n' positions only.
    assertFindParity("(?m)^abcdefghijklmnopqrstuvwxy", "Xabcdefghijklmnopqrstuvwxy");
    assertFindParity("(?m)^abcdefghijklmnopqrstuvwxy", "x\nabcdefghijklmnopqrstuvwxy");
  }

  @Test
  void unanchoredControlUnaffected() throws Exception {
    Object matcher = compile("abcdefghijklmnopqrstuvwxy", "Unanchored" + Integer.toHexString(7));
    boolean expected =
        Pattern.compile("abcdefghijklmnopqrstuvwxy").matcher("Xabcdefghijklmnopqrstuvwxy").find();
    assertEquals(expected, aptFind(matcher, "Xabcdefghijklmnopqrstuvwxy"));
    assertEquals(true, aptFind(matcher, "Xabcdefghijklmnopqrstuvwxy"));
  }

  /** Custom ClassLoader for loading generated bytecode in tests. */
  private static class TestClassLoader extends ClassLoader {
    public Class<?> defineClass(String name, byte[] bytecode) {
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
