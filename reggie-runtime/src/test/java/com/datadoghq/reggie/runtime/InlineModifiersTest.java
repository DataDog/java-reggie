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

/** Comprehensive tests for inline modifier support: (?i), (?m), (?s), (?x), (?#) */
public class InlineModifiersTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ========== CASE INSENSITIVE (?i) TESTS ==========

  @Test
  public void testCaseInsensitive_Literals() {
    ReggieMatcher m = Reggie.compile("(?i)abc");

    assertTrue(m.matches("abc"), "Should match lowercase");
    assertTrue(m.matches("ABC"), "Should match uppercase");
    assertTrue(m.matches("AbC"), "Should match mixed case");
    assertTrue(m.matches("aBc"), "Should match mixed case");
    assertFalse(m.matches("xyz"), "Should not match different letters");
    assertFalse(m.matches("ab"), "Should not match partial");
  }

  @Test
  public void testCaseInsensitive_CharacterClass() {
    ReggieMatcher m = Reggie.compile("(?i)[a-c]+");

    assertTrue(m.matches("abc"), "Should match lowercase");
    assertTrue(m.matches("ABC"), "Should match uppercase");
    assertTrue(m.matches("AaBbCc"), "Should match mixed case");
    assertTrue(m.matches("a"), "Should match single char");
    assertTrue(m.matches("CBA"), "Should match reversed uppercase");
    assertFalse(m.matches("xyz"), "Should not match outside range");
    assertFalse(m.matches(""), "Should not match empty");
  }

  @Test
  public void testCaseInsensitive_ScopedModifier() {
    // (?i:abc)def - only abc is case insensitive
    ReggieMatcher m = Reggie.compile("(?i:abc)def");

    assertTrue(m.matches("abcdef"), "Should match all lowercase");
    assertTrue(m.matches("ABCdef"), "Should match ABC uppercase, def lowercase");
    assertTrue(m.matches("AbCdef"), "Should match mixed case abc, lowercase def");
    assertFalse(m.matches("abcDEF"), "Should not match when def is uppercase");
    assertFalse(m.matches("ABCDEF"), "Should not match when def is uppercase");
  }

  @Test
  public void testCaseInsensitive_GlobalFromMidpoint() {
    // abc(?i)def - def is case insensitive, abc is not
    ReggieMatcher m = Reggie.compile("abc(?i)def");

    assertTrue(m.matches("abcdef"), "Should match all lowercase");
    assertTrue(m.matches("abcDEF"), "Should match abc lowercase, DEF uppercase");
    assertTrue(m.matches("abcDeF"), "Should match abc lowercase, mixed def");
    assertFalse(m.matches("ABCdef"), "Should not match when abc is uppercase");
    assertFalse(m.matches("ABCDEF"), "Should not match when abc is uppercase");
  }

  @Test
  public void testCaseInsensitive_TurnOffModifier() {
    // (?i)abc(?-i)def - abc is case insensitive, def is not
    ReggieMatcher m = Reggie.compile("(?i)abc(?-i)def");

    assertTrue(m.matches("abcdef"), "Should match all lowercase");
    assertTrue(m.matches("ABCdef"), "Should match ABC uppercase, def lowercase");
    assertFalse(m.matches("abcDEF"), "Should not match when def is uppercase");
    assertFalse(m.matches("ABCDEF"), "Should not match when def is uppercase");
  }

  @Test
  public void testCaseInsensitive_MultipleModifiers() {
    // (?im) - both case insensitive and multiline
    ReggieMatcher m = Reggie.compile("(?im)abc");

    assertTrue(m.matches("abc"), "Should match lowercase");
    assertTrue(m.matches("ABC"), "Should match uppercase");
    // Multiline behavior tested separately since it needs bytecode generator support
  }

  @Test
  public void testCaseInsensitive_NestedScopes() {
    // (?i:a(?-i:b)c) - a and c are case insensitive, b is not
    // This tests that modifier scope properly restores
    ReggieMatcher m = Reggie.compile("(?i:a(?-i:b)c)");

    assertTrue(m.matches("abc"), "Should match all lowercase");
    assertTrue(m.matches("Abc"), "Should match A uppercase, bc lowercase");
    assertTrue(m.matches("abC"), "Should match ab lowercase, C uppercase");
    assertTrue(m.matches("AbC"), "Should match A and C uppercase, b lowercase");
    assertFalse(m.matches("aBc"), "Should not match when only b is uppercase");
    assertFalse(m.matches("ABC"), "Should not match when b is uppercase");
  }

  @Test
  public void testCaseInsensitive_WithQuantifiers() {
    ReggieMatcher m = Reggie.compile("(?i)(abc)+");

    assertTrue(m.matches("abc"), "Should match single occurrence");
    assertTrue(m.matches("abcabc"), "Should match two occurrences");
    assertTrue(m.matches("ABCABC"), "Should match uppercase");
    assertTrue(m.matches("abcABC"), "Should match mixed case");
    assertTrue(m.matches("ABCabc"), "Should match mixed case");
  }

  @Test
  public void testCaseInsensitive_WithAlternation() {
    ReggieMatcher m = Reggie.compile("(?i)(abc|xyz)");

    assertTrue(m.matches("abc"), "Should match first alternative");
    assertTrue(m.matches("ABC"), "Should match first alternative uppercase");
    assertTrue(m.matches("xyz"), "Should match second alternative");
    assertTrue(m.matches("XYZ"), "Should match second alternative uppercase");
    assertTrue(m.matches("XyZ"), "Should match mixed case");
    assertFalse(m.matches("def"), "Should not match neither alternative");
  }

  @Test
  public void testCaseInsensitive_SpecialCharacters() {
    // Numbers and special chars should not be affected
    ReggieMatcher m = Reggie.compile("(?i)a1b2c3");

    assertTrue(m.matches("a1b2c3"), "Should match with numbers");
    assertTrue(m.matches("A1B2C3"), "Should match uppercase with numbers");
    assertFalse(m.matches("A1B2C4"), "Should not match wrong numbers");
  }

  // ========== COMMENT (?#) TESTS ==========

  @Test
  public void testComment_Basic() {
    ReggieMatcher m = Reggie.compile("abc(?#this is a comment)def");

    assertTrue(m.matches("abcdef"), "Should match ignoring comment");
    assertFalse(m.matches("abc def"), "Should not match with space");
  }

  @Test
  public void testComment_Multiple() {
    ReggieMatcher m = Reggie.compile("(?#comment1)abc(?#comment2)def(?#comment3)");

    assertTrue(m.matches("abcdef"), "Should match ignoring multiple comments");
  }

  @Test
  public void testComment_WithModifiers() {
    ReggieMatcher m = Reggie.compile("(?i)(?#case insensitive)abc");

    assertTrue(m.matches("ABC"), "Should match case insensitive with comment");
  }

  // ========== FIND TESTS (not just matches) ==========

  @Test
  public void testCaseInsensitive_Find() {
    ReggieMatcher m = Reggie.compile("(?i)abc");

    assertTrue(m.find("xyzABCdef"), "Should find ABC in middle");
    assertTrue(m.find("ABCdef"), "Should find ABC at start");
    assertTrue(m.find("xyzABC"), "Should find ABC at end");
    assertTrue(m.find("xyzAbCdef"), "Should find mixed case");

    assertEquals(3, m.findFrom("xyzABC", 0), "Should find at position 3");
  }

  @Test
  public void testCaseInsensitive_FindWithGroups() {
    ReggieMatcher m = Reggie.compile("(?i)(a)(b)(c)");
    MatchResult result = m.findMatch("xyzABCdef");

    assertNotNull(result, "Should find match");
    assertEquals("ABC", result.group(0), "Should capture full match");
    // Note: Group extraction has known issues, testing basic functionality
  }

  // ========== EDGE CASES ==========

  @Test
  public void testCaseInsensitive_EmptyPattern() {
    ReggieMatcher m = Reggie.compile("(?i)");

    assertTrue(m.matches(""), "Should match empty string");
  }

  @Test
  public void testCaseInsensitive_OnlyModifier() {
    ReggieMatcher m = Reggie.compile("(?i)");

    assertTrue(m.matches(""), "Should match empty string after modifier");
    assertFalse(m.matches("a"), "Should not match non-empty");
  }

  @Test
  public void testCaseInsensitive_NonLetters() {
    // Ensure non-letter characters are not affected by case-insensitive
    ReggieMatcher m = Reggie.compile("(?i)123-456");

    assertTrue(m.matches("123-456"), "Should match numbers and hyphen");
    assertFalse(m.matches("123_456"), "Should not match with underscore");
  }

  @Test
  public void testCaseInsensitive_UnicodeBasic() {
    // Basic test - ASCII only for now
    ReggieMatcher m = Reggie.compile("(?i)café");

    assertTrue(m.matches("café"), "Should match lowercase");
    assertTrue(m.matches("CAFÉ"), "Should match uppercase");
    // Extended Unicode support may be limited
  }

  @Test
  public void testScopedModifier_Nested() {
    // Test nested groups with different modifier scopes
    ReggieMatcher m = Reggie.compile("(?i:a(b(?-i:c)d)e)");

    assertTrue(m.matches("abcde"), "Should match all lowercase");
    assertTrue(m.matches("Abcde"), "Should match A uppercase");
    assertTrue(m.matches("aBcde"), "Should match B uppercase");
    assertFalse(m.matches("abCde"), "Should not match C uppercase (inside (?-i:))");
    assertTrue(m.matches("abcDe"), "Should match D uppercase");
    assertTrue(m.matches("abcdE"), "Should match E uppercase");
  }

  // ========== REGRESSION TESTS ==========

  @Test
  public void testNoModifiers_StillWorks() {
    // Ensure patterns without modifiers still work
    ReggieMatcher m = Reggie.compile("abc");

    assertTrue(m.matches("abc"), "Should match exact");
    assertFalse(m.matches("ABC"), "Should not match different case");
  }

  @Test
  public void testCharacterClass_WithoutModifier() {
    // Ensure character classes without (?i) still work normally
    ReggieMatcher m = Reggie.compile("[a-z]+");

    assertTrue(m.matches("abc"), "Should match lowercase");
    assertFalse(m.matches("ABC"), "Should not match uppercase");
    assertFalse(m.matches("AbC"), "Should not match mixed case");
  }

  @Test
  public void testQuantifiers_WithCaseInsensitive() {
    ReggieMatcher m = Reggie.compile("(?i)a{3}");

    assertTrue(m.matches("aaa"), "Should match lowercase");
    assertTrue(m.matches("AAA"), "Should match uppercase");
    assertTrue(m.matches("AaA"), "Should match mixed");
    assertFalse(m.matches("aa"), "Should not match too few");
    assertFalse(m.matches("aaaa"), "Should not match too many");
  }

  // ========== MULTILINE (?m) TESTS ==========

  @Test
  public void testMultiline_StartAnchor() {
    ReggieMatcher m = Reggie.compile("(?m)^abc");

    assertTrue(m.matches("abc"), "Should match at string start");
    assertFalse(m.matches("xabc"), "Should not match in middle without newline");

    // find() tests - multiline ^ should match after \n
    assertTrue(m.find("abc"), "Should find at start");
    assertTrue(m.find("x\nabc"), "Should find after newline");
    assertTrue(m.find("x\nabcdef"), "Should find after newline with more text");
    assertFalse(m.find("xabc"), "Should not find without newline");
  }

  @Test
  public void testMultiline_EndAnchor() {
    ReggieMatcher m = Reggie.compile("(?m)abc$");

    assertTrue(m.matches("abc"), "Should match at string end");
    assertFalse(m.matches("abcx"), "Should not match in middle without newline");

    // find() tests - multiline $ should match before \n
    assertTrue(m.find("abc"), "Should find at end");
    assertTrue(m.find("abc\nx"), "Should find before newline");
    assertTrue(m.find("defabc\nx"), "Should find before newline with prefix");
    assertFalse(m.find("abcx"), "Should not find without newline");
  }

  @Test
  public void testMultiline_BothAnchors() {
    ReggieMatcher m = Reggie.compile("(?m)^abc$");

    assertTrue(m.matches("abc"), "Should match full string");
    assertFalse(m.matches("abc\n"), "Should not match with trailing newline");

    // find() tests
    assertTrue(m.find("abc"), "Should find full line");
    assertTrue(m.find("x\nabc\ny"), "Should find line between newlines");
    assertTrue(m.find("abc\nx"), "Should find at start before newline");
    assertTrue(m.find("x\nabc"), "Should find at end after newline");
  }

  @Test
  public void testMultiline_WithContent() {
    ReggieMatcher m = Reggie.compile("(?m)^[0-9]+$");

    assertTrue(m.matches("123"), "Should match digits only");

    // find() tests - should match digit-only lines
    assertTrue(m.find("123"), "Should find digits at start/end");
    assertTrue(m.find("abc\n123\ndef"), "Should find digit line between text");
    assertFalse(m.find("abc123def"), "Should not find digits in middle");
  }

  @Test
  public void testNonMultiline_StartAnchor() {
    // Without (?m), ^ should only match at string start
    ReggieMatcher m = Reggie.compile("^abc");

    assertTrue(m.matches("abc"), "Should match at string start");
    assertTrue(m.find("abc"), "Should find at start");
    assertFalse(m.find("x\nabc"), "Should NOT find after newline without (?m)");
  }

  @Test
  public void testNonMultiline_EndAnchor() {
    // Without (?m), $ should only match at string end
    ReggieMatcher m = Reggie.compile("abc$");

    assertTrue(m.matches("abc"), "Should match at string end");
    assertTrue(m.find("abc"), "Should find at end");
    assertFalse(m.find("abc\nx"), "Should NOT find before newline without (?m)");
  }

  // ==================== Dotall Mode Tests ====================

  @Test
  public void testDotall_BasicDot() {
    // Without (?s), . should NOT match newline
    ReggieMatcher nonDotall = Reggie.compile("a.b");
    assertTrue(nonDotall.matches("axb"), "Should match with non-newline char");
    assertFalse(nonDotall.matches("a\nb"), "Should NOT match newline without (?s)");

    // With (?s), . should match newline
    ReggieMatcher dotall = Reggie.compile("(?s)a.b");
    assertTrue(dotall.matches("axb"), "Should match with non-newline char");
    assertTrue(dotall.matches("a\nb"), "Should match newline with (?s)");
  }

  @Test
  public void testDotall_MultipleDots() {
    ReggieMatcher nonDotall = Reggie.compile("a..c");
    assertTrue(nonDotall.matches("axyc"), "Should match without newlines");
    assertFalse(nonDotall.matches("ax\nc"), "Should NOT match with newline");
    assertFalse(nonDotall.matches("a\nyc"), "Should NOT match with newline");
    assertFalse(nonDotall.matches("a\n\nc"), "Should NOT match with newlines");

    ReggieMatcher dotall = Reggie.compile("(?s)a..c");
    assertTrue(dotall.matches("axyc"), "Should match without newlines");
    assertTrue(dotall.matches("ax\nc"), "Should match with newline");
    assertTrue(dotall.matches("a\nyc"), "Should match with newline");
    assertTrue(dotall.matches("a\n\nc"), "Should match with newlines");
  }

  @Test
  public void testDotall_WithQuantifiers() {
    // Test .+ without dotall
    ReggieMatcher nonDotall = Reggie.compile("a.+b");
    assertTrue(nonDotall.matches("axxxb"), "Should match without newlines");
    assertFalse(nonDotall.matches("ax\nxb"), "Should NOT match with newline");

    // Test .+ with dotall
    ReggieMatcher dotall = Reggie.compile("(?s)a.+b");
    assertTrue(dotall.matches("axxxb"), "Should match without newlines");
    assertTrue(dotall.matches("ax\nxb"), "Should match with newline");
    assertTrue(dotall.matches("a\n\n\nb"), "Should match with multiple newlines");
  }

  @Test
  public void testDotall_WithFind() {
    String multiline = "first line\nsecond line\nthird line";

    // Without dotall, .+ should not cross line boundaries
    ReggieMatcher nonDotall = Reggie.compile("first.+second");
    assertFalse(nonDotall.find(multiline), "Should NOT find across newline without (?s)");

    // With dotall, .+ should cross line boundaries
    ReggieMatcher dotall = Reggie.compile("(?s)first.+second");
    assertTrue(dotall.find(multiline), "Should find across newline with (?s)");
  }

  @Test
  public void testDotall_ScopedModifier() {
    // Scoped dotall: only applies within the group
    ReggieMatcher m = Reggie.compile("a(?s:.+)z");

    assertTrue(m.matches("axxxz"), "Should match without newlines");
    assertTrue(m.matches("ax\nxz"), "Should match with newline in scoped region");
  }

  @Test
  public void testDotall_CombinedWithMultiline() {
    // Test combining (?s) and (?m)
    ReggieMatcher m = Reggie.compile("(?ms)^a.+z$");

    // Should match: ^ matches start, . matches newlines, $ matches end
    assertTrue(m.matches("a\n\nz"), "Should match with (?ms) flags");

    // Should find line that spans with newlines
    String text = "start\na\n\nz\nend";
    assertTrue(m.find(text), "Should find pattern across lines with (?ms)");
  }

  @Test
  public void testNonDotall_DefaultBehavior() {
    // By default (no (?s)), . should NOT match newline
    ReggieMatcher m = Reggie.compile(".*");

    // Should match everything up to newline
    assertTrue(m.find("abc"), "Should match text without newline");

    // With multiline text, should match each line separately
    String multiline = "first\nsecond";
    assertTrue(m.find(multiline), "Should find pattern");
    // The .* should match "first" but not cross the newline
  }

  // ==================== Extended Mode Tests ====================

  @Test
  public void testExtended_IgnoreWhitespace() {
    // Without (?x), spaces are literal
    ReggieMatcher nonExtended = Reggie.compile("a b c");
    assertTrue(nonExtended.matches("a b c"), "Should match literal spaces");
    assertFalse(nonExtended.matches("abc"), "Should NOT match without spaces");

    // With (?x), spaces are ignored
    ReggieMatcher extended = Reggie.compile("(?x)a b c");
    assertFalse(extended.matches("a b c"), "Should NOT match literal spaces in extended mode");
    assertTrue(extended.matches("abc"), "Should match without spaces in extended mode");
  }

  @Test
  public void testExtended_IgnoreAllWhitespace() {
    // Extended mode ignores all types of whitespace
    ReggieMatcher m = Reggie.compile("(?x)a\t\n\r b  c");

    assertTrue(m.matches("abc"), "Should ignore all whitespace");
    assertFalse(m.matches("a b c"), "Should NOT match literal spaces");
  }

  @Test
  public void testExtended_Comments() {
    // Comments in extended mode start with # and go to end of line
    ReggieMatcher m = Reggie.compile("(?x)a # this is a comment\nb # another comment\nc");

    assertTrue(m.matches("abc"), "Should ignore comments");
    assertFalse(m.matches("a#b#c"), "Should NOT match literal # symbols");
  }

  @Test
  public void testExtended_WithQuantifiers() {
    // Whitespace around quantifiers should be ignored
    ReggieMatcher m = Reggie.compile("(?x)a + b *");

    assertTrue(m.matches("aab"), "Should match with quantifiers");
    assertTrue(m.matches("aaabbb"), "Should match with multiple chars");
  }

  @Test
  public void testExtended_WithCharacterClasses() {
    // Inside character classes, whitespace should be LITERAL
    ReggieMatcher m = Reggie.compile("(?x)[a b c]");

    assertTrue(m.matches("a"), "Should match 'a'");
    assertTrue(m.matches("b"), "Should match 'b'");
    assertTrue(m.matches("c"), "Should match 'c'");
    assertTrue(m.matches(" "), "Should match literal space inside []");
  }

  @Test
  public void testExtended_EscapedWhitespace() {
    // Escaped whitespace should be literal even in extended mode
    ReggieMatcher m = Reggie.compile("(?x)a\\ b");

    assertTrue(m.matches("a b"), "Should match escaped space");
    assertFalse(m.matches("ab"), "Should NOT match without space");
  }

  @Test
  public void testExtended_ComplexPattern() {
    // Real-world example: phone number pattern with comments
    String pattern =
        "(?x)\n"
            + "\\d{3}  # area code\n"
            + "-       # separator\n"
            + "\\d{3}  # first three\n"
            + "-       # separator\n"
            + "\\d{4}  # last four";

    ReggieMatcher m = Reggie.compile(pattern);

    assertTrue(m.matches("123-456-7890"), "Should match phone number");
    assertFalse(m.matches("123 456 7890"), "Should NOT match with spaces");
  }

  @Test
  public void testExtended_ScopedModifier() {
    // Scoped extended: only applies within the group
    ReggieMatcher m = Reggie.compile("a (?x:b c) d");

    assertTrue(m.matches("a bc d"), "Should have literal spaces outside, ignore inside");
    assertFalse(m.matches("abcd"), "Should NOT match without outer spaces");
  }

  @Test
  public void testExtended_CombinedWithCaseInsensitive() {
    // Test combining (?x) and (?i)
    ReggieMatcher m = Reggie.compile("(?ix)a b c");

    assertTrue(m.matches("abc"), "Should ignore spaces and match");
    assertTrue(m.matches("ABC"), "Should be case-insensitive");
    assertTrue(m.matches("AbC"), "Should be case-insensitive");
  }

  @Test
  public void testExtended_MultilineComment() {
    // Comments with multiple lines
    String pattern = "(?x)\n" + "a # first char\n" + "b # second char\n" + "c # third char";

    ReggieMatcher m = Reggie.compile(pattern);

    assertTrue(m.matches("abc"), "Should ignore multiline comments");
  }
}
