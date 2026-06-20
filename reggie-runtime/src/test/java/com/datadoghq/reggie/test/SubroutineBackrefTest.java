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
package com.datadoghq.reggie.test;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

public class SubroutineBackrefTest {
  @Test
  public void testSubroutineWithPlusAndBackref() {
    // Pattern: ^(a)(?1)+ab  with input "aaaab"
    // Expected: group 1 = "a"
    // The (?1)+ means "call group 1 one or more times"
    // So it should match: (a) + (a) + (a) + ab = "aaaab"
    ReggieMatcher m = Reggie.compile("^(a)(?1)+ab");

    System.out.println("Testing: ^(a)(?1)+ab on 'aaaab'");
    boolean matches = m.matches("aaaab");
    System.out.println("matches(): " + matches);

    MatchResult result = m.match("aaaab");
    System.out.println("match() result: " + result);
    if (result != null) {
      System.out.println("  group(0): " + result.group(0));
      System.out.println("  group(1): " + result.group(1));
      System.out.println("  groupCount: " + result.groupCount());
    }

    assertNotNull(result, "Should match");
    assertEquals("a", result.group(1), "Group 1 should capture 'a'");
  }

  @Test
  public void testPalindromePattern() {
    // Pattern: ^((.)(?1)\2|.?)$  should match palindromes
    // abba: (a)(.)(?1)\2 where inner (.) captures 'b', recursion matches 'bb', outer captures 'a'
    ReggieMatcher m;
    try {
      m = Reggie.compileAllowingFallback("^((.)(?1)\\2|.?)$");
    } catch (PatternSyntaxException e) {
      // JDK java.util.regex does not support PCRE subroutines
      throw new TestAbortedException(
          "Skipping test: JDK java.util.regex does not support PCRE subroutine syntax (?1)");
    }

    System.out.println("\nTesting palindrome pattern: ^((.)(?1)\\2|.?)$");

    assertTrue(m.matches("abba"), "Should match 'abba'");
    assertFalse(m.matches("abc"), "Should NOT match 'abc'");
  }
}
