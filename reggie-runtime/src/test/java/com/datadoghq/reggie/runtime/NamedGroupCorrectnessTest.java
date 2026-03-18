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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Correctness tests for PCRE named groups. Verifies that (?<name>...) and (?'name'...) patterns
 * work correctly, along with named backreferences \k<name> and \k'name'.
 */
public class NamedGroupCorrectnessTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testSimpleNamedGroup_AngleBrackets() {
    // Pattern: (?<word>\w+)
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)");

    MatchResult result = m.match("hello");
    assertNotNull(result, "Should match 'hello'");
    assertEquals("hello", result.group(1), "Group 1 should be 'hello'");
  }

  @Test
  public void testSimpleNamedGroup_SingleQuotes() {
    // Pattern: (?'word'\w+)
    ReggieMatcher m = RuntimeCompiler.compile("(?'word'\\w+)");

    MatchResult result = m.match("world");
    assertNotNull(result, "Should match 'world'");
    assertEquals("world", result.group(1), "Group 1 should be 'world'");
  }

  @Test
  public void testMultipleNamedGroups() {
    // Pattern: (?<first>\w+)-(?<second>\w+)
    ReggieMatcher m = RuntimeCompiler.compile("(?<first>\\w+)-(?<second>\\w+)");

    MatchResult result = m.match("foo-bar");
    assertNotNull(result, "Should match 'foo-bar'");
    assertEquals("foo", result.group(1), "Group 1 should be 'foo'");
    assertEquals("bar", result.group(2), "Group 2 should be 'bar'");
  }

  @Test
  public void testNamedBackreference_AngleBrackets() {
    // Pattern: (?<word>\w+)-\k<word>
    // Should match repeated words separated by hyphen
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)-\\k<word>");

    assertTrue(m.matches("foo-foo"), "Should match 'foo-foo'");
    assertTrue(m.matches("bar-bar"), "Should match 'bar-bar'");
    assertFalse(m.matches("foo-bar"), "Should NOT match 'foo-bar'");
  }

  @Test
  public void testNamedBackreference_SingleQuotes() {
    // Pattern: (?'word'\w+)-\k'word'
    ReggieMatcher m = RuntimeCompiler.compile("(?'word'\\w+)-\\k'word'");

    assertTrue(m.matches("test-test"), "Should match 'test-test'");
    assertFalse(m.matches("test-best"), "Should NOT match 'test-best'");
  }

  @Test
  public void testMixedNamedAndNumberedGroups() {
    // Pattern: (?<name>\w+):(\d+)
    // Named group followed by numbered group
    ReggieMatcher m = RuntimeCompiler.compile("(?<name>\\w+):(\\d+)");

    MatchResult result = m.match("age:25");
    assertNotNull(result, "Should match 'age:25'");
    assertEquals("age", result.group(1), "Group 1 (name) should be 'age'");
    assertEquals("25", result.group(2), "Group 2 should be '25'");
  }

  @Test
  public void testNumberedBackrefWithNamedGroup() {
    // Pattern: (?<word>\w+)-\1
    // Named group with numbered backreference
    ReggieMatcher m = RuntimeCompiler.compile("(?<word>\\w+)-\\1");

    assertTrue(m.matches("abc-abc"), "Should match 'abc-abc'");
    assertFalse(m.matches("abc-def"), "Should NOT match 'abc-def'");
  }

  @Test
  public void testHTMLTagMatching() {
    // Pattern: <(?<tag>\w+)>.*?</\k<tag>>
    // Match opening and closing HTML tags
    ReggieMatcher m = RuntimeCompiler.compile("<(?<tag>\\w+)>.*?</\\k<tag>>");

    assertTrue(m.matches("<div>content</div>"), "Should match '<div>content</div>'");
    assertTrue(m.matches("<span>text</span>"), "Should match '<span>text</span>'");
    assertFalse(m.matches("<div>content</span>"), "Should NOT match mismatched tags");
  }

  @Test
  public void testNestedNamedGroups() {
    // Pattern: (?<outer>(?<inner>\w+)-)
    // Nested named groups
    ReggieMatcher m = RuntimeCompiler.compile("(?<outer>(?<inner>\\w+)-)");

    MatchResult result = m.match("hello-");
    assertNotNull(result, "Should match 'hello-'");
    assertEquals("hello-", result.group(1), "Group 1 (outer) should be 'hello-'");
    assertEquals("hello", result.group(2), "Group 2 (inner) should be 'hello'");
  }

  @Test
  public void testMultipleNamedBackreferences() {
    // Pattern: (?<a>\w)-(?<b>\w)-\k<a>-\k<b>
    // Multiple named backreferences
    ReggieMatcher m = RuntimeCompiler.compile("(?<a>\\w)-(?<b>\\w)-\\k<a>-\\k<b>");

    assertTrue(m.matches("x-y-x-y"), "Should match 'x-y-x-y'");
    assertFalse(m.matches("x-y-y-x"), "Should NOT match 'x-y-y-x'");
  }

  @Test
  public void testNamedGroupWithQuantifier() {
    // Pattern: (?<digits>\d+)
    ReggieMatcher m = RuntimeCompiler.compile("(?<digits>\\d+)");

    MatchResult result = m.match("12345");
    assertNotNull(result, "Should match '12345'");
    assertEquals("12345", result.group(1), "Group 1 should be '12345'");
  }

  @Test
  public void testComplexPattern() {
    // Pattern: (?<protocol>\w+)://(?<domain>[\w.]+)(?<path>/\w+)?
    // URL pattern with named groups
    ReggieMatcher m =
        RuntimeCompiler.compile("(?<protocol>\\w+)://(?<domain>[\\w.]+)(?<path>/\\w+)?");

    MatchResult result = m.match("https://example.com/path");
    assertNotNull(result, "Should match 'https://example.com/path'");
    assertEquals("https", result.group(1), "Group 1 (protocol) should be 'https'");
    assertEquals("example.com", result.group(2), "Group 2 (domain) should be 'example.com'");
    assertEquals("/path", result.group(3), "Group 3 (path) should be '/path'");
  }
}
