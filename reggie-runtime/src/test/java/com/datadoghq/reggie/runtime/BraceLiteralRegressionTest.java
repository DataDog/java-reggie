/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for JDK brace leniency: a bare {@code }} is a literal.
 *
 * <p>History: {@code parseAtom} threw "Unexpected metacharacter '}'" for any {@code }} that was
 * not part of a valid {@code {n,m}} quantifier spec, while {@code java.util.regex} treats such a
 * {@code }} as a literal character. This hard-rejected 13 patterns from consumer repositories —
 * notably every {@code {{ ... }}} mustache-template parsing pattern in logs-backend and
 * profiling-backend's {@code \{([\w.]+)}}. Invalid quantifier specs ({@code a{}, {@code a{,2},
 * {@code a{2,1}, unclosed) must keep erroring, matching JDK.
 */
class BraceLiteralRegressionTest {

  @Test
  void bareClosingBraceIsLiteral() {
    assertTrue(Reggie.compile("}").matches("}"));
    assertFalse(Reggie.compile("}").matches("a"));
    assertTrue(Reggie.compile("a}b").matches("a}b"));
    assertTrue(Reggie.compile("}}").matches("}}"));
    assertTrue(Reggie.compile("[}]").find("x}y"));
  }

  @Test
  void consumerPatterns() {
    // profiling-backend: \{([\w.]+)}
    var placeholder = Reggie.compile("\\{([\\w.]+)}");
    assertTrue(placeholder.find("a {foo.bar} b"));
    assertEquals("foo.bar", placeholder.findMatch("a {foo.bar} b").group(1));

    // logs-backend mustache family: {{ ... }}
    assertTrue(Reggie.compile("\\{\\{\\s*urlencode\\s+\"([^\\s]*?)\"}}").find("{{ urlencode \"x\"}}"));
    assertTrue(
        Reggie.compile("\\{\\{\\s*\\^is_match(\\s+[^{}]*)}}(.*?)\\{\\{/is_match")
            .find("{{ ^is_match a}}x{{/is_match"));
    assertTrue(Reggie.compile("~<lambda>|~\\{closure}|^_M_(destroy|manager)$").find("~{closure}"));
    assertTrue(Reggie.compile(".*?\\{\\{([^}{]*\\s)?(\")?this(\\.|\\s|(}})|\").*").find("{{ this }}"));
  }

  @Test
  void quantifierSemanticsUnchanged() {
    assertTrue(Reggie.compile("a{2}").matches("aa"));
    assertFalse(Reggie.compile("a{2}").matches("a"));
    assertTrue(Reggie.compile("a{2,3}").matches("aaa"));
    assertTrue(Reggie.compile("a{2,}").matches("aaaa"));
    assertTrue(Reggie.compile("(ab){1,2}").matches("abab"));
    assertTrue(Reggie.compile("a{0,1}b").matches("b"));
    // escaped braces remain literals
    assertTrue(Reggie.compile("a\\{b").matches("a{b"));
  }

  @Test
  void invalidQuantifierSpecsStillError() {
    // JDK parity: these are PatternSyntaxException in java.util.regex and must stay errors.
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("{"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("a{"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("a{b"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("a{,2}"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("a{2,1}"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("a{2,3"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("a)b"));
  }
}
