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

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TaggedDFASimpleTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testSimpleAlternation() {
    ReggieMatcher matcher = Reggie.compile("(fo|foo)");

    // Test "fo"
    MatchResult result = matcher.match("fo");
    assertNotNull(result);
    assertEquals("fo", result.group(0));
    assertEquals("fo", result.group(1));
    assertEquals(0, result.start(1));
    assertEquals(2, result.end(1));

    // Test "foo"
    result = matcher.match("foo");
    assertNotNull(result);
    assertEquals("foo", result.group(0));
    assertEquals("foo", result.group(1));
    assertEquals(0, result.start(1));
    assertEquals(3, result.end(1));
  }

  @Test
  public void testDigitsGroup() {
    ReggieMatcher matcher = Reggie.compile("(\\d+)");

    MatchResult result = matcher.match("123");
    assertNotNull(result);
    assertEquals("123", result.group(0));
    assertEquals("123", result.group(1));
    assertEquals(0, result.start(1));
    assertEquals(3, result.end(1));
  }

  @Test
  public void testEmailWithGroups() {
    ReggieMatcher matcher = Reggie.compile("([a-z]+)@([a-z]+)\\.com");

    MatchResult result = matcher.match("test@example.com");
    assertNotNull(result);
    assertEquals("test@example.com", result.group(0));
    assertEquals("test", result.group(1));
    assertEquals("example", result.group(2));
  }
}
