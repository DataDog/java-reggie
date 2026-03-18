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

/** Tests for branch reset groups combined with subroutines. */
public class BranchResetSubroutineTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testSimpleBranchReset() {
    // Simple branch reset without subroutine
    ReggieMatcher m = Reggie.compile("(?|(abc)|(xyz))");

    assertTrue(m.matches("abc"), "Should match 'abc'");
    assertTrue(m.matches("xyz"), "Should match 'xyz'");

    MatchResult r1 = m.match("abc");
    assertNotNull(r1);
    assertEquals("abc", r1.group(1), "Group 1 should be 'abc'");

    MatchResult r2 = m.match("xyz");
    assertNotNull(r2);
    assertEquals("xyz", r2.group(1), "Group 1 should be 'xyz'");
  }

  @Test
  void testSimpleSubroutine() {
    // Simple subroutine without branch reset
    ReggieMatcher m = Reggie.compile("(abc)(?1)");

    assertTrue(m.matches("abcabc"), "Should match 'abcabc'");
    assertFalse(m.matches("abcxyz"), "Should not match 'abcxyz'");

    MatchResult r = m.match("abcabc");
    assertNotNull(r);
    assertEquals("abc", r.group(1), "Group 1 should be 'abc'");
  }

  @Test
  void testBranchResetWithSubroutine() {
    // Branch reset combined with subroutine
    // PCRE semantics: (?1) calls the FIRST alternative's pattern for group 1
    ReggieMatcher m = Reggie.compile("(?|(abc)|(xyz))(?1)");

    // First alternative followed by subroutine call to first alternative
    assertTrue(m.matches("abcabc"), "Should match 'abcabc'");

    // Second alternative followed by subroutine call to FIRST alternative (abc)
    // (?1) always calls the FIRST alternative's pattern, not the one that matched
    assertTrue(m.matches("xyzabc"), "Should match 'xyzabc' - (?1) calls first alt (abc)");

    // These should NOT match because (?1) calls (abc), not (xyz)
    assertFalse(m.matches("abcxyz"), "Should not match 'abcxyz' - (?1) calls (abc)");
    assertFalse(m.matches("xyzxyz"), "Should not match 'xyzxyz' - (?1) calls (abc)");
  }

  @Test
  void testBranchResetWithSubroutineFind() {
    ReggieMatcher m = Reggie.compile("(?|(abc)|(xyz))(?1)");

    // Test findMatch - (?1) always calls first alternative (abc)
    MatchResult r1 = m.findMatch("abcabc");
    assertNotNull(r1, "Should find match in 'abcabc'");
    assertEquals("abcabc", r1.group());
    assertEquals("abc", r1.group(1));

    RuntimeCompiler.clearCache();

    // xyzabc should match: xyz matches second alt, (?1) calls (abc)
    MatchResult r2 = m.findMatch("xyzabc");
    assertNotNull(r2, "Should find match in 'xyzabc'");
    assertEquals("xyzabc", r2.group());
    assertEquals("xyz", r2.group(1));

    RuntimeCompiler.clearCache();

    // xyzxyz should NOT match as a full pattern
    MatchResult r3 = m.findMatch("xyzxyz");
    assertNull(r3, "Should not find match in 'xyzxyz' - (?1) calls (abc)");
  }

  @Test
  void testBranchResetWithSubroutineMixed() {
    // PCRE semantics: (?1) always calls the FIRST alternative's pattern
    // In (?|(abc)|(xyz))(?1), (?1) always calls (abc)
    ReggieMatcher m = Reggie.compile("(?|(abc)|(xyz))(?1)");

    // Matches: first/second alt followed by first alt pattern (abc)
    assertTrue(m.matches("abcabc"), "Should match 'abcabc'");
    assertTrue(m.matches("xyzabc"), "Should match 'xyzabc'");

    // Does not match: (?1) calls (abc), not (xyz)
    assertFalse(m.matches("abcxyz"), "Should not match 'abcxyz'");
    assertFalse(m.matches("xyzxyz"), "Should not match 'xyzxyz'");

    // Should not match if pattern doesn't match
    assertFalse(m.matches("abcdef"), "Should not match 'abcdef'");
  }

  @Test
  void testNestedBranchResetWithSubroutine() {
    // Pattern: ^X(?|(a)|(b))(c)(?1)$
    // (?1) calls first alternative (a)
    ReggieMatcher m = Reggie.compile("^X(?|(a)|(b))(c)(?1)$");

    // Matches: either alt followed by c followed by (a)
    assertTrue(m.matches("Xaca"), "Should match 'Xaca'");
    assertTrue(m.matches("Xbca"), "Should match 'Xbca' - (?1) calls (a)");

    // Does not match: (?1) calls (a), not (b)
    assertFalse(m.matches("Xacb"), "Should not match 'Xacb'");
    assertFalse(m.matches("Xbcb"), "Should not match 'Xbcb'");
  }
}
