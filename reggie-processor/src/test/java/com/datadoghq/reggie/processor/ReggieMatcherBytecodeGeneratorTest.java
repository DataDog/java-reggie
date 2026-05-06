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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** End-to-end tests for bytecode generation. Tests that patterns compile to working matcher classes. */
class ReggieMatcherBytecodeGeneratorTest {

  private Object compile(String pattern, String className) throws Exception {
    byte[] bytecode =
        new ReggieMatcherBytecodeGenerator("test.generated", className, pattern).generate();
    assertNotNull(bytecode);
    assertTrue(bytecode.length > 0);
    Class<?> cls = new TestClassLoader().defineClass("test.generated." + className, bytecode);
    assertTrue(ReggieMatcher.class.isAssignableFrom(cls));
    return cls.getDeclaredConstructor().newInstance();
  }

  @Test
  void testSimplePhonePattern() throws Exception {
    Object matcher = compile("\\d{3}-\\d{3}-\\d{4}", "PhoneMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "123-456-7890"));
    assertTrue((Boolean) matches.invoke(matcher, "000-000-0000"));
    assertTrue((Boolean) matches.invoke(matcher, "999-999-9999"));
    assertFalse((Boolean) matches.invoke(matcher, "123-456-789"));
    assertFalse((Boolean) matches.invoke(matcher, "123-456-78901"));
    assertFalse((Boolean) matches.invoke(matcher, "abc-def-ghij"));
    assertFalse((Boolean) matches.invoke(matcher, "123 456 7890"));
    assertFalse((Boolean) matches.invoke(matcher, (String) null));
  }

  @Test
  void testSimpleLiteral() throws Exception {
    Object matcher = compile("abc", "LiteralMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, "ab"));
    assertFalse((Boolean) matches.invoke(matcher, "abcd"));
    assertFalse((Boolean) matches.invoke(matcher, "ABC"));
  }

  @Test
  void testCharacterClass() throws Exception {
    Object matcher = compile("[a-z]+", "LowerMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "a"));
    assertTrue((Boolean) matches.invoke(matcher, "abc"));
    assertTrue((Boolean) matches.invoke(matcher, "xyz"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertFalse((Boolean) matches.invoke(matcher, "ABC"));
    assertFalse((Boolean) matches.invoke(matcher, "123"));
    assertFalse((Boolean) matches.invoke(matcher, "a1b"));
  }

  @Test
  void testFindMethod() throws Exception {
    Object matcher = compile("\\d+", "DigitMatcher");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "abc123def"));
    assertTrue((Boolean) find.invoke(matcher, "123"));
    assertTrue((Boolean) find.invoke(matcher, "x9y"));
    assertFalse((Boolean) find.invoke(matcher, "abc"));
    assertFalse((Boolean) find.invoke(matcher, "xyz"));
    assertFalse((Boolean) find.invoke(matcher, ""));
  }

  @Test
  void testEmailPattern() throws Exception {
    Object matcher =
        compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "EmailMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "test@example.com"));
    assertTrue((Boolean) matches.invoke(matcher, "user.name+tag@domain.co.uk"));
    assertTrue((Boolean) matches.invoke(matcher, "a@b.cc"));
    assertFalse((Boolean) matches.invoke(matcher, "not-an-email"));
    assertFalse((Boolean) matches.invoke(matcher, "@example.com"));
    assertFalse((Boolean) matches.invoke(matcher, "test@"));
    assertFalse((Boolean) matches.invoke(matcher, "test@.com"));
  }

  @Test
  void testGreedyCharClassStrategy() throws Exception {
    Object matcher = compile("(\\d+)", "GreedyMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "123"));
    assertTrue((Boolean) matches.invoke(matcher, "9"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
    assertTrue((Boolean) find.invoke(matcher, "prefix123"));
    assertFalse((Boolean) find.invoke(matcher, "abc"));
  }

  @Test
  void testMultiGroupGreedyStrategy() throws Exception {
    Object matcher = compile("([a-z]+)@([a-z]+)\\.([a-z]+)", "MultiGroupMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "user@host.com"));
    assertFalse((Boolean) matches.invoke(matcher, "user@host"));
    assertFalse((Boolean) matches.invoke(matcher, "@host.com"));
    assertTrue((Boolean) find.invoke(matcher, "send to user@host.com ok"));
    assertFalse((Boolean) find.invoke(matcher, "no email"));
  }

  @Test
  void testBoundedQuantifiersStrategy() throws Exception {
    Object matcher =
        compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "IPv4Matcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "192.168.1.1"));
    assertTrue((Boolean) matches.invoke(matcher, "0.0.0.0"));
    assertTrue((Boolean) matches.invoke(matcher, "255.255.255.255"));
    assertFalse((Boolean) matches.invoke(matcher, "1.1.1"));
    assertTrue((Boolean) find.invoke(matcher, "host 10.0.0.1 port 80"));
    assertFalse((Boolean) find.invoke(matcher, "no ip here"));
  }

  @Test
  void testDfaUnrolledStrategy() throws Exception {
    Object matcher = compile("\\d+[a-z]", "DfaUnrolledMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "123a"));
    assertTrue((Boolean) matches.invoke(matcher, "9z"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, "123"));
    assertTrue((Boolean) find.invoke(matcher, "start 9x end"));
    assertFalse((Boolean) find.invoke(matcher, "no match here"));
  }

  @Test
  void testLiteralAlternationStrategy() throws Exception {
    Object matcher = compile("foo|bar|baz", "LiteralAltMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "foo"));
    assertTrue((Boolean) matches.invoke(matcher, "bar"));
    assertTrue((Boolean) matches.invoke(matcher, "baz"));
    assertFalse((Boolean) matches.invoke(matcher, "qux"));
    assertFalse((Boolean) matches.invoke(matcher, "fo"));
    assertTrue((Boolean) find.invoke(matcher, "I like foo a lot"));
    assertTrue((Boolean) find.invoke(matcher, "baz!"));
    assertFalse((Boolean) find.invoke(matcher, "qux"));
  }

  @Test
  void testVariableCaptureBackrefStrategy() throws Exception {
    Object matcher = compile("(\\w+)\\s+\\1", "VarCaptureBackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "hello hello"));
    assertTrue((Boolean) matches.invoke(matcher, "go go"));
    assertFalse((Boolean) matches.invoke(matcher, "hello world"));
    assertFalse((Boolean) matches.invoke(matcher, "hello"));
    assertTrue((Boolean) find.invoke(matcher, "the the fox"));
    assertFalse((Boolean) find.invoke(matcher, "no repeat here"));
  }

  @Test
  void testRecursiveDescentStrategy() throws Exception {
    Object matcher = compile("\\d+?", "RecursiveMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "1"));
    assertTrue((Boolean) matches.invoke(matcher, "5"));
    assertFalse((Boolean) matches.invoke(matcher, "a"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertTrue((Boolean) find.invoke(matcher, "abc1def"));
    assertFalse((Boolean) find.invoke(matcher, "abc"));
  }

  @Test
  void testSpecializedBackreferenceStrategy() throws Exception {
    Object matcher = compile("\\b(\\w+)\\s+\\1\\b", "BackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "hello hello"));
    assertFalse((Boolean) matches.invoke(matcher, "hello world"));
    assertTrue((Boolean) find.invoke(matcher, "the the quick fox"));
    assertFalse((Boolean) find.invoke(matcher, "no repeat here"));
  }

  @Test
  void testLinearBackreferenceStrategy() throws Exception {
    Object matcher = compile("(abc)\\1", "LinearBackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abcabc"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, "abcdef"));
    assertTrue((Boolean) find.invoke(matcher, "xabcabcy"));
    assertFalse((Boolean) find.invoke(matcher, "abcdef"));
  }

  @Test
  void testFixedRepetitionBackrefStrategy() throws Exception {
    Object matcher = compile("(\\w)\\1{2}", "FixedRepBackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    // (\w) + \1{2} = 3 repetitions of the same word char
    assertTrue((Boolean) matches.invoke(matcher, "aaa"));
    assertTrue((Boolean) matches.invoke(matcher, "zzz"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, "aa"));
    assertTrue((Boolean) find.invoke(matcher, "x aaa y"));
    assertFalse((Boolean) find.invoke(matcher, "abc"));
  }

  @Test
  void testOptionalGroupBackrefStrategy() throws Exception {
    Object matcher = compile("^(a)?(b)?\\1\\2$", "OptGroupBackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    // Group 1=(a)? and group 2=(b)? are optional; backrefs \1\2 match whatever they captured
    assertTrue((Boolean) matches.invoke(matcher, "abab"));
    assertTrue((Boolean) matches.invoke(matcher, "aa"));
    assertFalse((Boolean) matches.invoke(matcher, "abba"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
  }

  @Test
  void testMultiRangeAlpha() throws Exception {
    // CharSet sorts ranges by start char, so [A-Z] comes before [a-z]; the general fallback
    // path in MultiRangeOptimization uses only the first range [A-Z] for find-next scanning.
    Object matcher = compile("([a-zA-Z]+)", "AlphaMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abc"));
    assertTrue((Boolean) matches.invoke(matcher, "XYZ"));
    assertTrue((Boolean) matches.invoke(matcher, "Hello"));
    assertFalse((Boolean) matches.invoke(matcher, "123"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertTrue((Boolean) find.invoke(matcher, "123ABC456"));
    assertFalse((Boolean) find.invoke(matcher, "123456"));
  }

  @Test
  void testMultiRangeAlphaNum() throws Exception {
    // CharSet sorts ranges by start char: [0-9, A-Z, a-z]; general fallback uses first [0-9]
    Object matcher = compile("([a-zA-Z0-9]+)", "AlphaNumMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abc123"));
    assertTrue((Boolean) matches.invoke(matcher, "ABC"));
    assertTrue((Boolean) matches.invoke(matcher, "9"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertFalse((Boolean) matches.invoke(matcher, "!@#"));
    assertTrue((Boolean) find.invoke(matcher, "!123abc!"));
    assertFalse((Boolean) find.invoke(matcher, "!@#$"));
  }

  @Test
  void testMultiRangeGeneral() throws Exception {
    // CharSet sorted: [0-9, a-z]; general fallback uses first range [0-9] for find-next scanning
    Object matcher = compile("([a-z0-9]+)", "LowerNumMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abc123"));
    assertTrue((Boolean) matches.invoke(matcher, "abc"));
    assertTrue((Boolean) matches.invoke(matcher, "123"));
    assertFalse((Boolean) matches.invoke(matcher, "ABC"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertTrue((Boolean) find.invoke(matcher, "!123abc!"));
    assertFalse((Boolean) find.invoke(matcher, "ABC!"));
  }

  @Test
  void testGreedyBacktrackStrategyThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new ReggieMatcherBytecodeGenerator("test.generated", "GreedyBacktrackMatcher", "(.*)end")
                .generate());
  }

  @Test
  void testNestedQuantifiedGroupsStrategy() throws Exception {
    Object matcher = compile("((a+)+)", "NestedQuantMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "aaa"));
    assertTrue((Boolean) matches.invoke(matcher, "a"));
    assertFalse((Boolean) matches.invoke(matcher, "b"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
  }

  @Test
  void testDfaUnrolledWithAssertionsStrategy() throws Exception {
    Object matcher = compile("\\w+(?=-)", "DfaAssertionMatcher");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "word-"));
    assertFalse((Boolean) find.invoke(matcher, "word"));
  }

  @Test
  void testDfaSwitchWithAssertionsStrategy() throws Exception {
    Object matcher = compile("a{1,10}b{1,10}c{1,10}(?=d)", "DfaSwitchAssertionMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "aabbbcccd"));
    assertNotNull(matches.invoke(matcher, "aabbbcccd"));
  }

  @Test
  void testDfaSwitchWithGroupsStrategy() throws Exception {
    Object matcher = compile("(a{1,10})(b{1,10})(c{1,10})", "DfaSwitchGroupsMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "aaabbbccc"));
    assertTrue((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, "aaabbbb"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
  }

  @Test
  void testOnePassNfaStrategy() throws Exception {
    Object matcher = compile("(abc)(def)(ghi)", "OnePassNfaMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abcdefghi"));
    assertFalse((Boolean) matches.invoke(matcher, "abcdef"));
    assertFalse((Boolean) matches.invoke(matcher, "xyz"));
  }

  @Test
  void testSpecializedMultipleLookaheadsStrategy() throws Exception {
    Object matcher = compile("(?=.*[A-Z])(?=.*\\d).{8,}", "MultipleLookaheadsMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "Password1"));
    assertFalse((Boolean) matches.invoke(matcher, "password1"));
    assertFalse((Boolean) matches.invoke(matcher, "PASSW0R"));
  }

  @Test
  void testHybridDfaLookaheadStrategy() throws Exception {
    Object matcher = compile("(?=.*[A-Z])abc", "HybridLookaheadMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertNotNull(matches.invoke(matcher, "abc"));
    assertNotNull(matches.invoke(matcher, "abcZ"));
  }

  @Test
  void testHexDigitCharsetStrategy() throws Exception {
    Object matcher = compile("([0-9a-fA-F]+)", "HexDigitMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "deadbeef"));
    assertTrue((Boolean) matches.invoke(matcher, "0123456789abcdefABCDEF"));
    assertFalse((Boolean) matches.invoke(matcher, "xyz"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertTrue((Boolean) find.invoke(matcher, "color: #ff0000"));
    assertFalse((Boolean) find.invoke(matcher, "!@#$%"));
  }

  @Test
  void testNegatedCharsetStrategy() throws Exception {
    Object matcher = compile("([^a-z]+)", "NegatedCharMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "123"));
    assertTrue((Boolean) matches.invoke(matcher, "ABC"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
  }

  @Test
  void testOptimizedNfaWithBackrefsStrategy() throws Exception {
    Object matcher = compile("(\\w+).(\\w+).\\1", "NfaBackrefsMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertNotNull(matches.invoke(matcher, "aXaXa"));
    assertNotNull(matches.invoke(matcher, "aXbXa"));
    assertNotNull(find.invoke(matcher, "aXaXa more text"));
  }

  @Test
  void testOptimizedNfaWithLookaroundStrategy() throws Exception {
    assertNotNull(compile("(?=(\\w+))\\1", "NfaLookaroundMatcher"));
  }

  @Test
  void testDfaSwitchNegativeLookaheadStrategy() throws Exception {
    Object matcher = compile("a{1,10}b{1,10}c{1,10}(?!d)", "DfaSwitchNegLookaheadMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertNotNull(matches.invoke(matcher, "aabbbccc"));
    assertNotNull(find.invoke(matcher, "aabbbccc end"));
  }

  @Test
  void testDfaSwitchNegativeLookbehindStrategy() throws Exception {
    Object matcher = compile("(?<!a)b{1,10}c{1,10}d{1,10}", "DfaSwitchNegLookbehindMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertNotNull(matches.invoke(matcher, "bbccddd"));
  }

  @Test
  void testShortLiteralLookaheadStrategy() throws Exception {
    Object matcher = compile("ab(?=cd)", "ShortLiteralLookaheadMatcher");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "abcd"));
    assertFalse((Boolean) find.invoke(matcher, "abef"));
  }

  @Test
  void testShortLiteralNegativeLookaheadStrategy() throws Exception {
    Object matcher = compile("xy(?!z)", "ShortLiteralNegLookaheadMatcher");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "xya"));
    assertFalse((Boolean) find.invoke(matcher, "xyz"));
  }

  @Test
  void testSingleCharCharsetStrategy() throws Exception {
    Object matcher = compile("([a]+)", "SingleCharMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "aaa"));
    assertTrue((Boolean) matches.invoke(matcher, "a"));
    assertFalse((Boolean) matches.invoke(matcher, "b"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertTrue((Boolean) find.invoke(matcher, "baaab"));
    assertFalse((Boolean) find.invoke(matcher, "bbb"));
  }

  @Test
  void testLargeNegatedRangeCharsetStrategy() throws Exception {
    Object matcher = compile("([^A-z]+)", "LargeNegatedRangeMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "123"));
    assertTrue((Boolean) matches.invoke(matcher, "!@#"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
  }

  /** Custom ClassLoader for loading generated bytecode in tests. */
  private static class TestClassLoader extends ClassLoader {
    public Class<?> defineClass(String name, byte[] bytecode) {
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
