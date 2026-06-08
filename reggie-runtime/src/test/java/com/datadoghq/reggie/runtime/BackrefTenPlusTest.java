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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests PCRE semantics for multi-digit backreferences (\10 and above).
 *
 * <p>PCRE rule: \10 and higher are backreferences when the pattern has at least that many groups;
 * otherwise they are parsed as octal escapes.
 */
public class BackrefTenPlusTest {

  @Test
  void backrefToGroupTen() {
    // Pattern with 10 groups: \10 must be a backreference to group 10 ('j')
    String pat = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\10";
    ReggieMatcher reg = Reggie.compile(pat);
    // \10 as backref to group 10 must match: 'j' captured by group 10, then matched again
    assertTrue(reg.matches("abcdefghijj"), "Reggie should match: \\10 must be backref to group 10");
    // \10 as octal \\n (8=backspace) + literal '0' would match different input — must not match
    assertFalse(reg.matches("abcdefghij\0100"), "Should not match octal interpretation");
  }

  @Test
  void backrefToGroupElevenInElevenGroupPattern() {
    // 12-group pattern: \11 is backref to group 11 ('k'), \12 is backref to group 12 ('cd')
    String pat = "^(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)\\11*(\\3\\4)\\12$";
    String input = "abcdefghijkcdcd";
    Matcher jdk = Pattern.compile(pat).matcher(input);
    assertTrue(jdk.matches(), "JDK should match");
    ReggieMatcher reg = Reggie.compile(pat);
    // \11 and \12 as backrefs: 'k'*1 (group 11), 'cd' (group 12 = \3\4), 'cd' (backref \12)
    assertTrue(reg.matches(input), "Reggie should match: \\11 and \\12 must be backrefs");
    // Verify non-match: if \11 were octal (9 = tab), this would require a different input
    assertFalse(reg.matches("abcdefghijkcdxy"), "Should not match: group 12 mismatch");
  }
}
