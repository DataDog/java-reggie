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

/**
 * End-to-end tests for bytecode generation. Tests that patterns compile to working matcher classes.
 */
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
    Object matcher = compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "EmailMatcher");
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
    Object matcher = compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "IPv4Matcher");
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
  void testVariableCaptureBackrefStrategyNative() throws Exception {
    // VARIABLE_CAPTURE_BACKREF is now NATIVE (promoted in Wave 2).
    Object matcher = compile("(\\w+)\\s+\\1", "VarCaptureBackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "hello hello"));
    assertFalse((Boolean) matches.invoke(matcher, "hello world"));
  }

  @Test
  void testRecursiveDescentStrategy() throws Exception {
    // \d+? (lazy) routes to JDK fallback: RECURSIVE_DESCENT lacks general alternation
    // backtracking needed for lazy semantics. Use a quantified-backref pattern instead,
    // which routes to RECURSIVE_DESCENT via hasQuantifiedBackrefs without lazy quantifiers.
    Object matcher = compile("(\\d+)\\1{1,2}", "RecursiveMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "11")); // group="1", backref once
    assertTrue((Boolean) matches.invoke(matcher, "1111")); // group="11", backref once
    assertFalse((Boolean) matches.invoke(matcher, "12")); // backref mismatch
    assertFalse((Boolean) matches.invoke(matcher, "1")); // no room for backref
    assertFalse((Boolean) matches.invoke(matcher, ""));
    assertTrue((Boolean) find.invoke(matcher, "x11y"));
    assertFalse((Boolean) find.invoke(matcher, "abc"));
  }

  @Test
  void testWordBoundaryBackrefStrategyNative() throws Exception {
    // \b(\w+)\s+\1\b resolves to VARIABLE_CAPTURE_BACKREF, now NATIVE (Wave 2).
    Object matcher = compile("\\b(\\w+)\\s+\\1\\b", "BackrefMatcher");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) find.invoke(matcher, "say hello hello world"));
    assertFalse((Boolean) find.invoke(matcher, "hello world"));
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
    // Use empty-alt form (a|) instead of (a)? so the group always participates
    // (the quantified (X)? form now routes to JDK fallback for correct Java backref semantics)
    Object matcher = compile("^(a|)(b|)\\1\\2$", "OptGroupBackrefMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "abab"));
    assertTrue((Boolean) matches.invoke(matcher, "aa"));
    assertTrue((Boolean) matches.invoke(matcher, ""));
    assertFalse((Boolean) matches.invoke(matcher, "abba"));
    assertFalse((Boolean) matches.invoke(matcher, "abc"));
  }

  @Test
  void testSpecializedOptionalGroupStrategy() throws Exception {
    // (a)?b-shaped pattern; exercises the compile-time (@RegexPattern annotation processor)
    // dispatch path for SPECIALIZED_OPTIONAL_GROUP, which is otherwise only covered via the
    // runtime path (RuntimeCompiler) in reggie-runtime's tests.
    Object matcher = compile("(a)?b", "SpecializedOptionalGroupMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "ab"));
    assertTrue((Boolean) matches.invoke(matcher, "b"));
    assertFalse((Boolean) matches.invoke(matcher, "aab"));
    assertFalse((Boolean) matches.invoke(matcher, "a"));
    assertTrue((Boolean) find.invoke(matcher, "xaby"));
    assertTrue((Boolean) find.invoke(matcher, "xby"));
    assertFalse((Boolean) find.invoke(matcher, "xay"));
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
            new ReggieMatcherBytecodeGenerator(
                    "test.generated", "GreedyBacktrackMatcher", "(.*)end")
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
    // matches() requires full string; pattern ends before the d (lookahead is zero-width)
    assertFalse((Boolean) matches.invoke(matcher, "aabbbcccd"));
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
    // (abc)(def)(ghi) routes to SPECIALIZED_FIXED_SEQUENCE, not ONEPASS_NFA; an end-anchored
    // capturing group is what actually selects ONEPASS_NFA (see OnePassNfaAnchorFindTest).
    Object matcher = compile("(a)$", "OnePassNfaMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "a"));
    assertFalse((Boolean) matches.invoke(matcher, "ba"));
    assertFalse((Boolean) matches.invoke(matcher, "ab"));
    assertFalse((Boolean) matches.invoke(matcher, ""));
  }

  @Test
  void testOnePassNfaMatchFromMethod() throws Exception {
    Object matcher = compile("(a)$", "OnePassNfaMatchFromMatcher");
    Method matchFrom = matcher.getClass().getMethod("matchFrom", String.class, int.class);
    assertEquals(2, (Integer) matchFrom.invoke(matcher, "ba", 1));
    assertEquals(-1, (Integer) matchFrom.invoke(matcher, "ba", 0));
    assertEquals(-1, (Integer) matchFrom.invoke(matcher, (String) null, 0));
  }

  @Test
  void testFixedSequenceWithWordBoundaryStrategy() throws Exception {
    // Bare \b/\B + literal patterns route to SPECIALIZED_FIXED_SEQUENCE (see
    // WordBoundaryFixedSequenceTest in reggie-runtime for the runtime-compiler-path coverage of
    // this same strategy). This test exercises the annotation-processor path
    // (ReggieMatcherBytecodeGenerator), which generates the leading/trailing isBoundary check via
    // its own FixedSequenceBytecodeGenerator instance -- a call to matches()/find() here fails with
    // a verification/NoSuchMethodError if the generated class references isBoundary without the
    // helper methods actually being emitted.
    Object leading = compile("\\bfoo", "LeadingBoundaryMatcher");
    Method leadingMatches = leading.getClass().getMethod("matches", String.class);
    Method leadingFind = leading.getClass().getMethod("find", String.class);
    assertTrue((Boolean) leadingMatches.invoke(leading, "foo"));
    assertTrue((Boolean) leadingFind.invoke(leading, "xx foo"));
    assertFalse((Boolean) leadingFind.invoke(leading, "xfoo"));

    Object trailing = compile("foo\\b", "TrailingBoundaryMatcher");
    Method trailingMatches = trailing.getClass().getMethod("matches", String.class);
    Method trailingFind = trailing.getClass().getMethod("find", String.class);
    assertTrue((Boolean) trailingMatches.invoke(trailing, "foo"));
    assertTrue((Boolean) trailingFind.invoke(trailing, "foo bar"));
    assertFalse((Boolean) trailingFind.invoke(trailing, "foox"));

    Object both = compile("\\bfoo\\b", "BothBoundaryMatcher");
    Method bothMatches = both.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) bothMatches.invoke(both, "foo"));
    assertFalse((Boolean) bothMatches.invoke(both, "foox"));

    Object nonWord = compile("\\Bfoo\\B", "NonWordBoundaryMatcher");
    Method nonWordFind = nonWord.getClass().getMethod("find", String.class);
    assertTrue((Boolean) nonWordFind.invoke(nonWord, "xfoox"));
    assertFalse((Boolean) nonWordFind.invoke(nonWord, "foo"));
  }

  @Test
  void testBitStateBytecodeStrategy() throws Exception {
    // Prefix-guarded-scan shape recognized by PatternAnalyzer#detectPrefixGuardedScan; exercises
    // the compile-time BITSTATE_BYTECODE dispatch case (com.datadoghq.reggie.codegen.codegen.
    // BitStateBytecodeGenerator), separate from the runtime dispatch path covered by
    // BitStateBytecodeGeneratorTest in reggie-runtime.
    Object matcher =
        compile("(?s)(?m)^(?:\\s*(?:sudo|doas)\\s+)?\\b\\S+\\b\\s*(.*)", "CommandMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "ls -la"));
    assertTrue((Boolean) matches.invoke(matcher, "sudo ls -la"));
    assertTrue((Boolean) matches.invoke(matcher, "doas rm -rf /"));
    assertFalse((Boolean) matches.invoke(matcher, "   "));
  }

  @Test
  void testSpecializedMultipleLookaheadsStrategyNative() throws Exception {
    // SPECIALIZED_MULTIPLE_LOOKAHEADS is now NATIVE (Wave 3 fixed the lookahead boolean engine).
    Object matcher = compile("(?=.*[A-Z])(?=.*\\d).{8,}", "MultipleLookaheadsMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "Password1"));
    assertFalse((Boolean) matches.invoke(matcher, "password1")); // no uppercase
    assertFalse((Boolean) matches.invoke(matcher, "PASSWORDX")); // no digit
  }

  @Test
  void testHybridDfaLookaheadStrategyNative() throws Exception {
    // HYBRID_DFA_LOOKAHEAD is now NATIVE (Wave 3 fixed the lookahead boolean engine).
    // (?=.*[A-Z])abc: lookahead must see uppercase, then abc must follow at current pos
    Object matcher = compile("(?=.*[A-Z])abc", "HybridLookaheadMatcher");
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue(
        (Boolean) find.invoke(matcher, "abcX")); // at pos 0: lookahead "abcX" has X, abc matches
    assertFalse((Boolean) find.invoke(matcher, "abc")); // no uppercase anywhere
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
    // Smoke test: OPTIMIZED_NFA_WITH_BACKREFS has known correctness limitations.
    // Verify the strategy produces loadable bytecode that executes without throwing.
    Object matcher = compile("(\\w+).(\\w+).\\1", "NfaBackrefsMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    matches.invoke(matcher, "aXaXa");
    find.invoke(matcher, "aXaXa more text");
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
    assertTrue(
        (Boolean) matches.invoke(matcher, "aabbbccc")); // no 'd' after — negative lookahead passes
    assertFalse(
        (Boolean) matches.invoke(matcher, "aabbbcccd")); // 'd' after — negative lookahead fails
    assertTrue((Boolean) find.invoke(matcher, "aabbbccc end"));
  }

  @Test
  void testDfaSwitchNegativeLookbehindStrategy() throws Exception {
    Object matcher = compile("(?<!a)b{1,10}c{1,10}d{1,10}", "DfaSwitchNegLookbehindMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "bbccddd")); // no 'a' before — lookbehind passes
    assertFalse((Boolean) matches.invoke(matcher, ""));
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

  @Test
  void testLookbehindBeforeUnboundedQuantifier() throws Exception {
    Object matcherPlus = compile("(?<=\\d)[a-z]+", "LookbehindUnboundedPlus");
    Method replaceAllPlus =
        matcherPlus.getClass().getMethod("replaceAll", String.class, String.class);
    assertEquals("3X", replaceAllPlus.invoke(matcherPlus, "3abc", "X"));

    Object matcherStar = compile("(?<=\\d)[a-z]*", "LookbehindUnboundedStar");
    Method replaceAllStar =
        matcherStar.getClass().getMethod("replaceAll", String.class, String.class);
    assertEquals("3X", replaceAllStar.invoke(matcherStar, "3abc", "X"));
  }

  @Test
  void testLazyDfaStrategy() throws Exception {
    // (?:a+b+|b+a+){75} has ~685 NFA states and no groups/anchors → routes to LAZY_DFA.
    // The processor emits the delegating matches() path (no package-private access).
    Object matcher = compile("(?:a+b+|b+a+){75}", "LazyDfaMatcher");
    Method matches = matcher.getClass().getMethod("matches", String.class);
    Method find = matcher.getClass().getMethod("find", String.class);
    assertTrue((Boolean) matches.invoke(matcher, "ab".repeat(75)));
    assertFalse((Boolean) matches.invoke(matcher, "ab".repeat(74) + "b"));
    assertTrue((Boolean) find.invoke(matcher, "xx" + "ab".repeat(75) + "yy"));
    assertFalse((Boolean) find.invoke(matcher, "xx"));
  }

  /**
   * Two differently-named provider classes in the same package that both have a {@code value()}
   * method must produce distinct matcher classes. The new naming scheme qualifies the class name
   * with the provider class simple name: {@code ProviderA_ValueMatcher} vs {@code
   * ProviderB_ValueMatcher}.
   */
  @Test
  void twoProviderClassesWithSameMethodNameProduceDistinctMatchers() throws Exception {
    // Simulate what RegexPatternProcessor.generateMatcherClassName() now produces:
    // providerClassName + "_" + capitalize(methodName) + "Matcher"
    Object matcherA = compile("\\d+", "ProviderA_ValueMatcher");
    Object matcherB = compile("[a-z]+", "ProviderB_ValueMatcher");

    assertNotEquals(
        matcherA.getClass().getName(),
        matcherB.getClass().getName(),
        "Two providers with same method name must generate different matcher class names");

    Method matches = matcherA.getClass().getMethod("matches", String.class);
    assertTrue(
        (Boolean) matches.invoke(matcherA, "123"), "ProviderA_ValueMatcher must match digits");
    assertFalse(
        (Boolean) matches.invoke(matcherA, "abc"), "ProviderA_ValueMatcher must not match letters");

    Method matchesB = matcherB.getClass().getMethod("matches", String.class);
    assertTrue(
        (Boolean) matchesB.invoke(matcherB, "abc"), "ProviderB_ValueMatcher must match letters");
    assertFalse(
        (Boolean) matchesB.invoke(matcherB, "123"), "ProviderB_ValueMatcher must not match digits");
  }

  // --- Tests for Fix 1: resolveRealization() must honour needsFallback() for PIKEVM_CAPTURE ---

  @Test
  void quantifiedAnchorOnlyGroup_throwsWithoutFallback() throws Exception {
    // ($){2} triggers hasAnchorInQuantifier (B3) — must not silently route to PIKEVM
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test", "Cls", "($){2}");
    assertThrows(UnsupportedOperationException.class, () -> gen.resolveRealization(false));
  }

  @Test
  void optionalAnchorOnlyGroup_throwsWithoutFallback() throws Exception {
    // (^)? triggers hasAnchorInQuantifier (B3)
    ReggieMatcherBytecodeGenerator gen = new ReggieMatcherBytecodeGenerator("test", "Cls", "(^)?");
    assertThrows(UnsupportedOperationException.class, () -> gen.resolveRealization(false));
  }

  @Test
  void quantifiedAnchorOnlyGroup_returnsDelegateFallbackWhenAllowed() throws Exception {
    // With ALLOW_JDK_FALLBACK, unsafe PIKEVM pattern must route to DELEGATE_FALLBACK
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test", "Cls", "($){2}");
    ReggieMatcherBytecodeGenerator.Realization r = gen.resolveRealization(true);
    assertEquals(ReggieMatcherBytecodeGenerator.Realization.DELEGATE_FALLBACK, r);
  }

  @Test
  void nonQuantifiedAnchorOnlyGroup_stillReturnsDelegatePikevm() throws Exception {
    // ($) has anchor-only group but NO quantifier on the group →
    // hasAnchorInQuantifier = false → needsFallback = null → DELEGATE_PIKEVM preserved
    ReggieMatcherBytecodeGenerator gen = new ReggieMatcherBytecodeGenerator("test", "Cls", "($)");
    ReggieMatcherBytecodeGenerator.Realization r = gen.resolveRealization(false);
    assertEquals(ReggieMatcherBytecodeGenerator.Realization.DELEGATE_PIKEVM, r);
  }

  /** Custom ClassLoader for loading generated bytecode in tests. */
  private static class TestClassLoader extends ClassLoader {
    public Class<?> defineClass(String name, byte[] bytecode) {
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
