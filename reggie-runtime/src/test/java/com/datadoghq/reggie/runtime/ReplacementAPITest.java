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

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for replacement APIs (replaceFirst, replaceAll, split). Uses RuntimeCompiler to test actual
 * Reggie-generated matchers.
 */
public class ReplacementAPITest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testReplaceFirstSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    String result = matcher.replaceFirst("test test test", "REPLACED");

    assertEquals("REPLACED test test", result, "Should replace first occurrence");
  }

  @Test
  public void testReplaceFirstNoMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    String result = matcher.replaceFirst("nothing here", "REPLACED");

    assertEquals("nothing here", result, "Should return original string when no match");
  }

  @Test
  public void testReplaceAllSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    String result = matcher.replaceAll("test and test and test", "REPLACED");

    assertEquals("REPLACED and REPLACED and REPLACED", result, "Should replace all occurrences");
  }

  @Test
  public void testReplaceAllNoMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    String result = matcher.replaceAll("nothing here", "REPLACED");

    assertEquals("nothing here", result, "Should return original string when no match");
  }

  @Test
  public void testReplaceAllWithFunction() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");
    AtomicInteger counter = new AtomicInteger(0);

    String result =
        matcher.replaceAll(
            "test and test",
            match -> {
              int n = counter.incrementAndGet();
              return "MATCH" + n;
            });

    assertEquals("MATCH1 and MATCH2", result, "Should replace with function results");
  }

  @Test
  public void testReplaceAllFunctionReceivesMatchResult() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");

    String result =
        matcher.replaceAll(
            "prefix test suffix",
            match -> {
              return "[" + match.start() + "-" + match.end() + ":" + match.group() + "]";
            });

    assertEquals(
        "prefix [7-11:test] suffix", result, "Function should receive correct MatchResult");
  }

  @Test
  public void testSplitSuccess() {
    ReggieMatcher matcher = RuntimeCompiler.compile(",");
    String[] parts = matcher.split("a,b,c,d");

    assertArrayEquals(new String[] {"a", "b", "c", "d"}, parts, "Should split on delimiter");
  }

  @Test
  public void testSplitNoMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile(",");
    String[] parts = matcher.split("no delimiter");

    assertArrayEquals(
        new String[] {"no delimiter"}, parts, "Should return single element when no match");
  }

  @Test
  public void testSplitWithEmptyParts() {
    ReggieMatcher matcher = RuntimeCompiler.compile(",");
    String[] parts = matcher.split("a,,b");

    assertArrayEquals(new String[] {"a", "", "b"}, parts, "Should include empty parts");
  }

  @Test
  public void testSplitAtStart() {
    ReggieMatcher matcher = RuntimeCompiler.compile(",");
    String[] parts = matcher.split(",a,b");

    assertArrayEquals(new String[] {"", "a", "b"}, parts, "Should handle delimiter at start");
  }

  @Test
  public void testSplitAtEnd() {
    ReggieMatcher matcher = RuntimeCompiler.compile(",");
    String[] parts = matcher.split("a,b,");

    assertArrayEquals(new String[] {"a", "b", ""}, parts, "Should handle delimiter at end");
  }

  @Test
  public void testBackreferenceExpansion() {
    // Test capturing group backreference
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");

    String result = matcher.replaceFirst("value: 123", "[$1]");

    assertEquals("value: [123]", result, "Should expand $1 backreference");
  }

  @Test
  public void testBackreferenceGroup0() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");

    String result = matcher.replaceFirst("value: 123", "[$0]");

    assertEquals("value: [123]", result, "Should expand $0 to entire match");
  }

  @Test
  public void testBackreferenceEscape() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");

    String result = matcher.replaceFirst("test", "$$replacement");

    assertEquals("$replacement", result, "Should expand $$ to single $");
  }

  @Test
  public void testBackreferenceInvalidGroup() {
    ReggieMatcher matcher = RuntimeCompiler.compile("test");

    String result = matcher.replaceFirst("test", "$9");

    assertEquals("$9", result, "Should leave invalid backreference as-is");
  }

  @Test
  public void testSplitOnWhitespace() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\s+");
    String[] parts = matcher.split("hello   world\tfoo");

    assertArrayEquals(
        new String[] {"hello", "world", "foo"}, parts, "Should split on whitespace pattern");
  }

  @Test
  public void testReplaceDigits() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
    String result = matcher.replaceAll("abc123def456", "NUM");

    assertEquals("abcNUMdefNUM", result, "Should replace digit sequences");
  }
}
