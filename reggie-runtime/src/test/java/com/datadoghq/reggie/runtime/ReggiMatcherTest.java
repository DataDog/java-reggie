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

import org.junit.jupiter.api.Test;

/** Base test class for generated regex matchers. */
public class ReggiMatcherTest {

  @Test
  public void testReggiMatcherClass() {
    ReggieMatcher matcher = new TestMatcher();
    assertEquals("test", matcher.pattern());
    assertTrue(matcher.matches("test"));
    assertFalse(matcher.matches("other"));
  }

  private static class TestMatcher extends ReggieMatcher {
    public TestMatcher() {
      super("test");
    }

    @Override
    public boolean matches(String input) {
      return "test".equals(input);
    }

    @Override
    public boolean find(String input) {
      return input != null && input.contains("test");
    }

    @Override
    public int findFrom(String input, int start) {
      return input == null ? -1 : input.indexOf("test", start);
    }

    @Override
    public MatchResult match(String input) {
      if (matches(input)) {
        return new MatchResultImpl(input, new int[] {0}, new int[] {input.length()}, 0);
      }
      return null;
    }

    @Override
    public boolean matchesBounded(CharSequence input, int start, int end) {
      if (input == null || start < 0 || end > input.length() || start > end) {
        return false;
      }
      String bounded = input.subSequence(start, end).toString();
      return matches(bounded);
    }

    @Override
    public MatchResult matchBounded(CharSequence input, int start, int end) {
      if (input == null || start < 0 || end > input.length() || start > end) {
        return null;
      }
      String bounded = input.subSequence(start, end).toString();
      if (matches(bounded)) {
        return new MatchResultImpl(input.toString(), new int[] {start}, new int[] {end}, 0);
      }
      return null;
    }

    @Override
    public MatchResult findMatch(String input) {
      int pos = findFrom(input, 0);
      if (pos >= 0) {
        return new MatchResultImpl(input, new int[] {pos}, new int[] {pos + 4}, 0);
      }
      return null;
    }

    @Override
    public MatchResult findMatchFrom(String input, int start) {
      int pos = findFrom(input, start);
      if (pos >= 0) {
        return new MatchResultImpl(input, new int[] {pos}, new int[] {pos + 4}, 0);
      }
      return null;
    }
  }
}
