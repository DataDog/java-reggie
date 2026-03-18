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
package com.datadoghq.reggie.codegen.parsing;

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive descent parser for regex patterns. Converts pattern strings into AST.
 *
 * <p>Grammar (simplified): alternation := concatenation ('|' concatenation)* concatenation :=
 * quantified* quantified := atom quantifier? atom := char | charClass | group | anchor | backref
 * quantifier := '*' | '+' | '?' | '{n}' | '{n,}' | '{n,m}'
 */
public class RegexParser {

  private String pattern;
  private int pos;
  private int groupCount;
  private Map<String, Integer> groupNameMap; // Maps group names to group numbers
  private RegexModifiers currentModifiers;

  public RegexNode parse(String pattern) throws ParseException {
    // Note: We don't validate with JDK Pattern.compile() because we support
    // additional syntax like (?#...) comments that JDK doesn't support

    this.pattern = pattern;
    this.pos = 0;
    this.groupCount = 0;
    this.groupNameMap = new HashMap<>();
    this.currentModifiers = new RegexModifiers();

    RegexNode result = parseAlternation();

    if (hasMore()) {
      throw new ParseException("Unexpected character at position " + pos + ": " + peek());
    }

    return result;
  }

  // Parsing methods

  private RegexNode parseAlternation() throws ParseException {
    List<RegexNode> alternatives = new ArrayList<>();
    alternatives.add(parseConcatenation());

    while (hasMore() && peek() == '|') {
      consume();
      alternatives.add(parseConcatenation());
    }

    return alternatives.size() == 1 ? alternatives.get(0) : new AlternationNode(alternatives);
  }

  private RegexNode parseConcatenation() throws ParseException {
    List<RegexNode> items = new ArrayList<>();

    while (hasMore() && !isAlternationOrGroupEnd()) {
      RegexNode node = parseQuantified();
      // Filter out epsilon nodes (from global modifiers and comments)
      if (!(node instanceof LiteralNode && ((LiteralNode) node).ch == 0)) {
        items.add(node);
      }
    }

    if (items.isEmpty()) {
      // Empty concatenation (e.g., from "()")
      return new LiteralNode((char) 0); // Epsilon - handled specially
    }

    return items.size() == 1 ? items.get(0) : new ConcatNode(items);
  }

  private boolean isAlternationOrGroupEnd() {
    char ch = peek();
    return ch == '|' || ch == ')';
  }

  private RegexNode parseQuantified() throws ParseException {
    skipExtendedWhitespace(); // Skip whitespace before atom in extended mode
    RegexNode base = parseAtom();

    if (!hasMore()) return base;

    skipExtendedWhitespace(); // Skip whitespace after atom, before quantifier
    if (!hasMore()) return base; // After skipping whitespace, we might be at end

    char ch = peek();
    switch (ch) {
      case '*':
        consume();
        return new QuantifierNode(base, 0, -1, !checkNonGreedy());
      case '+':
        consume();
        return new QuantifierNode(base, 1, -1, !checkNonGreedy());
      case '?':
        consume();
        // Check if this is a quantifier or non-greedy marker
        if (base instanceof QuantifierNode) {
          // Already a quantifier, so this ? makes it non-greedy
          return base; // Handled in checkNonGreedy
        }
        return new QuantifierNode(base, 0, 1, !checkNonGreedy());
      case '{':
        return parseCountedQuantifier(base);
      default:
        return base;
    }
  }

  private boolean checkNonGreedy() {
    skipExtendedWhitespace();
    if (hasMore() && peek() == '?') {
      consume();
      return true;
    }
    return false;
  }

  private RegexNode parseCountedQuantifier(RegexNode base) throws ParseException {
    consume('{');
    skipWhitespaceInQuantifier();

    int min = parseNumber();
    int max = min;

    skipWhitespaceInQuantifier();
    if (peek() == ',') {
      consume();
      skipWhitespaceInQuantifier();
      if (peek() == '}') {
        max = -1; // {n,} means n or more
      } else {
        max = parseNumber();
        skipWhitespaceInQuantifier();
      }
    }

    consume('}');

    // Validate quantifier bounds
    if (max != -1 && min > max) {
      throw new ParseException("Invalid quantifier: min (" + min + ") > max (" + max + ")");
    }

    return new QuantifierNode(base, min, max, !checkNonGreedy());
  }

  private int parseNumber() throws ParseException {
    StringBuilder sb = new StringBuilder();
    while (hasMore() && Character.isDigit(peek())) {
      sb.append(consume());
    }
    if (sb.length() == 0) {
      throw new ParseException("Expected number at position " + pos);
    }
    return Integer.parseInt(sb.toString());
  }

  private RegexNode parseAtom() throws ParseException {
    char ch = peek();

    if (ch == '(') {
      return parseGroup();
    } else if (ch == '[') {
      return parseCharClass();
    } else if (ch == '\\') {
      return parseEscape();
    } else if (ch == '^' || ch == '$') {
      return parseAnchor();
    } else if (ch == '.') {
      consume();
      // . matches any character
      // In dotall mode (?s), . matches newline
      // In standard mode, . does NOT match newline (\n)
      CharSet charSet = currentModifiers.isDotall() ? CharSet.ANY : CharSet.ANY_EXCEPT_NEWLINE;
      return new CharClassNode(charSet, false);
    } else if (isMetachar(ch)) {
      throw new ParseException("Unexpected metacharacter '" + ch + "' at position " + pos);
    } else {
      consume();
      // Apply case-insensitive modifier if active
      if (currentModifiers.isCaseInsensitive() && Character.isLetter(ch)) {
        // Convert to character class [aA]
        char lower = Character.toLowerCase(ch);
        char upper = Character.toUpperCase(ch);
        if (lower != upper) {
          List<CharSet.Range> ranges = new ArrayList<>();
          ranges.add(new CharSet.Range(lower, lower));
          ranges.add(new CharSet.Range(upper, upper));
          return new CharClassNode(CharSet.fromRanges(ranges), false);
        }
      }
      return new LiteralNode(ch);
    }
  }

  private boolean isMetachar(char ch) {
    return ch == '*' || ch == '+' || ch == '?' || ch == '{' || ch == '}' || ch == '|' || ch == ')';
  }

  private RegexNode parseGroup() throws ParseException {
    consume('(');

    boolean capturing = true;
    int groupNum = 0;
    String groupName = null;

    // Check for special constructs (?:...), (?=...), (?!...), (?<=...), (?<!...), (?i), (?i:...)
    if (peek() == '?') {
      consume();
      if (peek() == ':') {
        consume();
        capturing = false;
      } else if (peek() == '=' || peek() == '!') {
        // Lookahead: (?=...) or (?!...)
        return parseLookahead();
      } else if (peek() == '<') {
        // Could be lookbehind (?<=...) or named group (?<name>...)
        consume('<');
        if (peek() == '=' || peek() == '!') {
          // Lookbehind: (?<=...) or (?<!...)
          pos--; // backtrack to let parseLookbehind handle it
          return parseLookbehind();
        } else {
          // Named group: (?<name>...)
          groupName = parseGroupName('>');
          consume('>');
          capturing = true;
        }
      } else if (peek() == '\'') {
        // Named group with single quotes: (?'name'...)
        consume('\'');
        groupName = parseGroupName('\'');
        consume('\'');
        capturing = true;
      } else if (peek() == '#') {
        // Comment: (?#...) - skip until closing paren
        return parseComment();
      } else if (peek() == '(') {
        // Conditional: (?(1)yes|no) or (?(name)yes|no)
        return parseConditional();
      } else if (isModifierChar(peek())) {
        // Inline modifiers: (?i), (?-i), (?i:...), (?im), etc.
        return parseModifierGroup();
      } else if (peek() == 'R') {
        // Subroutine call to entire pattern: (?R)
        return parseSubroutineRecursive();
      } else if (peek() == '&') {
        // Named subroutine call: (?&name)
        return parseNamedSubroutine();
      } else if (Character.isDigit(peek())) {
        // Could be subroutine call (?1) or conditional (?(1)...)
        // Need to look ahead to distinguish
        return parseNumberedConstruct();
      } else if (peek() == '|') {
        // Branch reset: (?|alt1|alt2)
        return parseBranchReset();
      } else {
        throw new UnsupportedPatternException(
            "Unsupported special group construct at position " + pos);
      }
    }

    if (capturing) {
      groupNum = ++groupCount;
      // Register named group if present
      if (groupName != null) {
        if (groupNameMap.containsKey(groupName)) {
          throw new ParseException("Duplicate group name: " + groupName);
        }
        groupNameMap.put(groupName, groupNum);
      }
    }

    // Save modifiers before parsing child - inline modifiers like (?i) inside
    // a group should only affect that group, not the rest of the pattern
    RegexModifiers savedModifiers = currentModifiers;

    RegexNode child = parseAlternation();

    // Restore modifiers after parsing group
    currentModifiers = savedModifiers;

    consume(')');

    return new GroupNode(child, groupNum, capturing, groupName);
  }

  /**
   * Parse a group name until the given terminator character. Group names must start with a letter
   * and contain only letters, digits, and underscores.
   */
  private String parseGroupName(char terminator) throws ParseException {
    StringBuilder name = new StringBuilder();

    if (!hasMore() || !Character.isLetter(peek())) {
      throw new ParseException("Group name must start with a letter at position " + pos);
    }

    while (hasMore() && peek() != terminator) {
      char ch = peek();
      if (Character.isLetterOrDigit(ch) || ch == '_') {
        name.append(consume());
      } else {
        throw new ParseException("Invalid character in group name at position " + pos);
      }
    }

    if (name.length() == 0) {
      throw new ParseException("Empty group name at position " + pos);
    }

    return name.toString();
  }

  private RegexNode parseCharClass() throws ParseException {
    consume('[');

    boolean negated = false;
    if (peek() == '^') {
      consume();
      negated = true;
    }

    List<CharSet.Range> ranges = new ArrayList<>();

    while (hasMore() && peek() != ']') {
      // Check for character class escapes like \d, \w, \s
      if (peek() == '\\' && hasMore() && pos + 1 < pattern.length()) {
        char nextChar = pattern.charAt(pos + 1);
        if (nextChar == 'd'
            || nextChar == 'D'
            || nextChar == 'w'
            || nextChar == 'W'
            || nextChar == 's'
            || nextChar == 'S') {
          // Handle character class escape
          consume(); // consume '\'
          consume(); // consume the class character
          CharSet charSet = getCharSetForEscape(nextChar);
          ranges.addAll(charSet.getRanges());
          continue;
        }
      }

      char start = parseCharClassChar();

      if (peek() == '-' && peekNext() != ']') {
        consume('-');
        char end = parseCharClassChar();
        ranges.add(new CharSet.Range(start, end));
      } else {
        ranges.add(new CharSet.Range(start, start));
      }
    }

    consume(']');

    // Apply case-insensitive modifier if active
    if (currentModifiers.isCaseInsensitive()) {
      ranges = applyCaseInsensitiveToRanges(ranges);
    }

    CharSet charset = CharSet.fromRanges(ranges);
    return new CharClassNode(charset, negated);
  }

  private CharSet getCharSetForEscape(char escapeChar) {
    switch (escapeChar) {
      case 'd':
        return CharSet.DIGIT;
      case 'D':
        return CharSet.DIGIT.complement();
      case 'w':
        return CharSet.WORD;
      case 'W':
        return CharSet.WORD.complement();
      case 's':
        return CharSet.WHITESPACE;
      case 'S':
        return CharSet.WHITESPACE.complement();
      default:
        throw new IllegalArgumentException("Not a character class escape: " + escapeChar);
    }
  }

  private char parseCharClassChar() throws ParseException {
    char ch = peek();
    if (ch == '\\') {
      consume();
      ch = consume();
      // Handle escapes in character class
      switch (ch) {
        case 'n':
          return '\n';
        case 't':
          return '\t';
        case 'r':
          return '\r';
        case 'f':
          return '\f';
        case 'x':
          // Hex escape: \xhh
          return parseCharFromHexEscape();
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
          // Octal escape: \nnn (up to 3 octal digits)
          pos--; // Back up to the digit
          return parseCharFromOctalEscape();
        default:
          return ch; // Escaped literal
      }
    } else {
      return consume();
    }
  }

  private RegexNode parseEscape() throws ParseException {
    consume('\\');
    char ch = consume();

    switch (ch) {
      case 'd':
        return new CharClassNode(CharSet.DIGIT, false);
      case 'D':
        return new CharClassNode(CharSet.DIGIT, true);
      case 'w':
        return new CharClassNode(CharSet.WORD, false);
      case 'W':
        return new CharClassNode(CharSet.WORD, true);
      case 's':
        return new CharClassNode(CharSet.WHITESPACE, false);
      case 'S':
        return new CharClassNode(CharSet.WHITESPACE, true);
      case 'b':
        return new AnchorNode(AnchorNode.Type.WORD_BOUNDARY);
      case 'A':
        return new AnchorNode(AnchorNode.Type.STRING_START);
      case 'Z':
        return new AnchorNode(AnchorNode.Type.STRING_END);
      case 'z':
        return new AnchorNode(AnchorNode.Type.STRING_END_ABSOLUTE);
      case 'K':
        return new AnchorNode(AnchorNode.Type.RESET_MATCH);
      case 'n':
        return new LiteralNode('\n');
      case 't':
        return new LiteralNode('\t');
      case 'r':
        return new LiteralNode('\r');
      case 'f':
        return new LiteralNode('\f');
      case 'x':
        // Hex escape: \xhh
        return parseHexEscape();
      case '0':
        // \0 followed by octal digits is always octal
        pos--; // Back up to the digit
        return parseOctalEscape();
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
        // Could be backreference (\1) or octal (\100)
        // Check if followed by another digit
        if (hasMore() && Character.isDigit(peek())) {
          // Multi-digit: \1x where x is digit → octal escape
          pos--; // Back up to the digit
          return parseOctalEscape();
        } else {
          // Single digit: \1 → backreference
          return new BackreferenceNode(ch - '0');
        }
      case '8':
      case '9':
        // \8 and \9 are backreferences (or invalid if no such group exists)
        return new BackreferenceNode(ch - '0');
      case 'k':
        // Named backreference: \k<name> or \k'name'
        return parseNamedBackreference();
      case 'g':
        // PCRE backreference: \g{N}, \g{-N}, or \g{name}
        return parseGBackreference();
      default:
        // Escaped literal (e.g., \., \*, \+)
        return new LiteralNode(ch);
    }
  }

  /** Parse hex escape in character class context and return the character. */
  private char parseCharFromHexEscape() throws ParseException {
    RegexNode node = parseHexEscapeInternal();
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch;
    }
    throw new ParseException("Invalid hex escape in character class at position " + pos);
  }

  /** Parse octal escape in character class context and return the character. */
  private char parseCharFromOctalEscape() throws ParseException {
    RegexNode node = parseOctalEscapeInternal();
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch;
    }
    throw new ParseException("Invalid octal escape in character class at position " + pos);
  }

  /**
   * Parse hex escape sequence: \xhh where h is a hex digit. Returns a LiteralNode with the
   * character represented by the hex value.
   */
  private RegexNode parseHexEscape() throws ParseException {
    return parseHexEscapeInternal();
  }

  /** Internal implementation of hex escape parsing. */
  private RegexNode parseHexEscapeInternal() throws ParseException {
    // Already consumed '\x', now parse up to 2 hex digits
    if (!hasMore()) {
      throw new ParseException("Incomplete hex escape at position " + pos);
    }

    int value = 0;
    int digitCount = 0;

    // Parse up to 2 hex digits
    while (hasMore() && digitCount < 2) {
      char ch = peek();
      int digit = Character.digit(ch, 16);
      if (digit == -1) {
        break; // Not a hex digit
      }
      value = value * 16 + digit;
      consume();
      digitCount++;
    }

    if (digitCount == 0) {
      throw new ParseException("Invalid hex escape at position " + pos);
    }

    return new LiteralNode((char) value);
  }

  /**
   * Parse octal escape sequence: \nnn where n is an octal digit (0-7). Handles up to 3 octal
   * digits.
   *
   * <p>PCRE rules: - \0nn is always octal (starts with 0) - \1nn to \7nn could be backreference or
   * octal: - If result <= 99 and within group count, it's a backreference - Otherwise, it's octal
   */
  private RegexNode parseOctalEscape() throws ParseException {
    return parseOctalEscapeInternal();
  }

  /** Internal implementation of octal escape parsing. */
  private RegexNode parseOctalEscapeInternal() throws ParseException {
    // Position is at the first octal digit
    int value = 0;
    int digitCount = 0;
    int startPos = pos;

    // Parse up to 3 octal digits
    while (hasMore() && digitCount < 3) {
      char ch = peek();
      if (ch < '0' || ch > '7') {
        break; // Not an octal digit
      }
      value = value * 8 + (ch - '0');
      consume();
      digitCount++;
    }

    if (digitCount == 0) {
      throw new ParseException("Invalid octal escape at position " + startPos);
    }

    // Special case: if we have 1-2 digits and the value is 1-9,
    // it might be a backreference instead of octal
    // But we treat \0 through \7 as octal, \8 and \9 as backreferences
    // Since we're in parseOctalEscape, we know first digit is 0-7

    // If value >= 8 (e.g., \10 = octal 8), it's definitely octal not backref
    // If value is 1-7 with multiple digits (e.g., \10, \100), it's octal
    // If value is 1-7 with single digit, caller should have treated as backref

    // For simplicity: in octal context, always return octal literal
    return new LiteralNode((char) value);
  }

  /** Parse named backreference: \k<name> or \k'name' */
  private RegexNode parseNamedBackreference() throws ParseException {
    char delimiter;
    if (peek() == '<') {
      delimiter = '>';
      consume('<');
    } else if (peek() == '\'') {
      delimiter = '\'';
      consume('\'');
    } else {
      throw new ParseException(
          "Named backreference must use <> or '' delimiters at position " + pos);
    }

    String name = parseGroupName(delimiter);
    consume(delimiter);

    // Look up group number by name
    Integer groupNum = groupNameMap.get(name);
    if (groupNum == null) {
      throw new ParseException(
          "Reference to undefined group name '" + name + "' at position " + pos);
    }

    return new BackreferenceNode(groupNum, name);
  }

  /**
   * Parse PCRE-style backreference: \g{N}, \g{-N}, or \g{name}
   *
   * <p>Syntax: \g{N} - backreference to group N (1-based) \g{-N} - relative backreference (N groups
   * back from current) \g{name} - named backreference (equivalent to \k'name')
   */
  private RegexNode parseGBackreference() throws ParseException {
    if (peek() != '{') {
      throw new ParseException("\\g must be followed by {N}, {-N}, or {name} at position " + pos);
    }
    consume('{');

    // Check if it's a number (positive or negative) or a name
    boolean isNegative = false;
    if (peek() == '-') {
      isNegative = true;
      consume('-');
    }

    if (Character.isDigit(peek())) {
      // Numeric backref: \g{N} or \g{-N}
      int num = parseNumber();
      consume('}');

      int groupNum;
      if (isNegative) {
        // Relative backref: \g{-N} means Nth group counting back from most recent
        // \g{-1} = most recent group = groupCount
        // \g{-2} = second most recent = groupCount - 1
        groupNum = groupCount - num + 1;
        if (groupNum < 1) {
          throw new ParseException(
              "Relative backreference \\g{-" + num + "} is out of range at position " + pos);
        }
      } else {
        groupNum = num;
      }

      return new BackreferenceNode(groupNum);
    } else {
      // Named backref: \g{name}
      if (isNegative) {
        throw new ParseException("Invalid \\g{-name} syntax at position " + pos);
      }

      String name = parseGroupName('}');
      consume('}');

      // Look up group number by name
      Integer groupNum = groupNameMap.get(name);
      if (groupNum == null) {
        throw new ParseException(
            "Reference to undefined group name '" + name + "' at position " + pos);
      }

      return new BackreferenceNode(groupNum, name);
    }
  }

  private RegexNode parseAnchor() throws ParseException {
    char ch = consume();
    boolean isMultiline = currentModifiers.isMultiline();

    if (ch == '^') {
      return new AnchorNode(AnchorNode.Type.START, isMultiline);
    } else if (ch == '$') {
      return new AnchorNode(AnchorNode.Type.END, isMultiline);
    } else {
      throw new ParseException("Unexpected anchor: " + ch);
    }
  }

  private RegexNode parseLookahead() throws ParseException {
    // At this point we've consumed "(?" and peeked '=' or '!'
    boolean positive = peek() == '=';
    consume(); // consume '=' or '!'

    // Save modifiers - inline modifiers inside assertion shouldn't affect rest of pattern
    RegexModifiers savedModifiers = currentModifiers;

    // Parse sub-pattern
    RegexNode subPattern = parseAlternation();

    // Restore modifiers
    currentModifiers = savedModifiers;

    // Check for nested assertions (not supported initially)
    if (containsAssertions(subPattern)) {
      throw new UnsupportedPatternException("Nested assertions are not supported");
    }

    consume(')');

    AssertionNode.Type type =
        positive ? AssertionNode.Type.POSITIVE_LOOKAHEAD : AssertionNode.Type.NEGATIVE_LOOKAHEAD;

    return new AssertionNode(type, subPattern, -1); // -1 = not lookbehind
  }

  private RegexNode parseLookbehind() throws ParseException {
    // At this point we've consumed "(?" and peeked '<'
    consume('<'); // consume '<'

    boolean positive = peek() == '=';
    if (peek() != '=' && peek() != '!') {
      throw new ParseException("Expected '=' or '!' after '(?<' at position " + pos);
    }
    consume(); // consume '=' or '!'

    // Save modifiers - inline modifiers inside assertion shouldn't affect rest of pattern
    RegexModifiers savedModifiers = currentModifiers;

    // Parse sub-pattern
    RegexNode subPattern = parseAlternation();

    // Restore modifiers
    currentModifiers = savedModifiers;

    // Check for nested assertions
    if (containsAssertions(subPattern)) {
      throw new UnsupportedPatternException("Nested assertions are not supported");
    }

    // CRITICAL: Validate fixed-width
    int width = computeFixedWidth(subPattern);
    if (width == -1) {
      throw new UnsupportedPatternException(
          "Lookbehind assertions must be fixed-width (no *, +, ?, {n,m})");
    }

    consume(')');

    AssertionNode.Type type =
        positive ? AssertionNode.Type.POSITIVE_LOOKBEHIND : AssertionNode.Type.NEGATIVE_LOOKBEHIND;

    return new AssertionNode(type, subPattern, width);
  }

  private boolean containsAssertions(RegexNode node) {
    AssertionDetector detector = new AssertionDetector();
    return node.accept(detector);
  }

  private int computeFixedWidth(RegexNode node) {
    FixedWidthCalculator calculator = new FixedWidthCalculator();
    return node.accept(calculator);
  }

  private static class AssertionDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return true; // Found an assertion!
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false; // Subroutines are not assertions
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches for assertions
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      // Check all alternatives for assertions
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  private static class FixedWidthCalculator implements RegexVisitor<Integer> {
    @Override
    public Integer visitLiteral(LiteralNode node) {
      return 1;
    }

    @Override
    public Integer visitCharClass(CharClassNode node) {
      return 1;
    }

    @Override
    public Integer visitConcat(ConcatNode node) {
      int total = 0;
      for (RegexNode child : node.children) {
        int childWidth = child.accept(this);
        if (childWidth == -1) return -1; // Not fixed-width
        total += childWidth;
      }
      return total;
    }

    @Override
    public Integer visitAlternation(AlternationNode node) {
      if (node.alternatives.isEmpty()) return 0;

      int firstWidth = node.alternatives.get(0).accept(this);
      if (firstWidth == -1) return -1;

      // All alternatives must have same width
      for (int i = 1; i < node.alternatives.size(); i++) {
        int altWidth = node.alternatives.get(i).accept(this);
        if (altWidth != firstWidth) return -1; // Different widths
      }

      return firstWidth;
    }

    @Override
    public Integer visitQuantifier(QuantifierNode node) {
      // Only {n} (exact count) is fixed-width
      if (node.min == node.max && node.max > 0) {
        int childWidth = node.child.accept(this);
        if (childWidth == -1) return -1;
        return childWidth * node.min;
      }
      return -1; // Variable-width quantifier
    }

    @Override
    public Integer visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Integer visitAnchor(AnchorNode node) {
      return 0; // Anchors are zero-width
    }

    @Override
    public Integer visitBackreference(BackreferenceNode node) {
      return -1; // Backreferences are NOT fixed-width
    }

    @Override
    public Integer visitAssertion(AssertionNode node) {
      return 0; // Assertions are zero-width
    }

    @Override
    public Integer visitSubroutine(SubroutineNode node) {
      return -1; // Subroutines are variable-width (dynamic calls)
    }

    @Override
    public Integer visitConditional(ConditionalNode node) {
      // Both branches must have same fixed width
      int thenWidth = node.thenBranch.accept(this);
      if (thenWidth == -1) return -1;

      if (node.elseBranch != null) {
        int elseWidth = node.elseBranch.accept(this);
        if (elseWidth != thenWidth) return -1; // Different widths
      }

      return thenWidth;
    }

    @Override
    public Integer visitBranchReset(BranchResetNode node) {
      // All alternatives must have same fixed width
      if (node.alternatives.isEmpty()) return 0;

      int firstWidth = node.alternatives.get(0).accept(this);
      if (firstWidth == -1) return -1;

      for (int i = 1; i < node.alternatives.size(); i++) {
        int altWidth = node.alternatives.get(i).accept(this);
        if (altWidth != firstWidth) return -1; // Different widths
      }

      return firstWidth;
    }
  }

  // Helper methods

  private boolean hasMore() {
    return pos < pattern.length();
  }

  private char peek() {
    if (!hasMore()) {
      throw new IllegalStateException("Unexpected end of pattern");
    }
    return pattern.charAt(pos);
  }

  private char peekNext() {
    if (pos + 1 >= pattern.length()) {
      return '\0';
    }
    return pattern.charAt(pos + 1);
  }

  private char consume() {
    return pattern.charAt(pos++);
  }

  /**
   * Skip whitespace and comments if in extended mode (?x). Extended mode ignores: - Unescaped
   * whitespace characters (space, tab, newline, etc.) - Comments starting with # until end of line
   * This does NOT apply inside character classes [].
   */
  private void skipExtendedWhitespace() {
    if (!currentModifiers.isExtended()) {
      return;
    }

    while (hasMore()) {
      char ch = pattern.charAt(pos);

      if (Character.isWhitespace(ch)) {
        // Skip whitespace
        pos++;
      } else if (ch == '#') {
        // Skip comment until end of line
        pos++;
        while (hasMore() && pattern.charAt(pos) != '\n') {
          pos++;
        }
        // Skip the newline too if present
        if (hasMore() && pattern.charAt(pos) == '\n') {
          pos++;
        }
      } else {
        // Not whitespace or comment, stop skipping
        break;
      }
    }
  }

  /**
   * Skips whitespace inside quantifiers {n,m}. PCRE allows whitespace in quantifiers even outside
   * extended mode. Examples: { 3, 5 }, { 3, }, { 3 }
   */
  private void skipWhitespaceInQuantifier() {
    while (hasMore() && Character.isWhitespace(pattern.charAt(pos))) {
      pos++;
    }
  }

  private void consume(char expected) throws ParseException {
    char actual = consume();
    if (actual != expected) {
      throw new ParseException(
          "Expected '" + expected + "' but got '" + actual + "' at position " + (pos - 1));
    }
  }

  // Modifier parsing helpers

  /**
   * Applies case-insensitive transformation to character class ranges. For each letter in the
   * ranges, adds the opposite case version. Example: [a-z] becomes [a-zA-Z]
   */
  private List<CharSet.Range> applyCaseInsensitiveToRanges(List<CharSet.Range> ranges) {
    List<CharSet.Range> result = new ArrayList<>(ranges);

    for (CharSet.Range range : ranges) {
      // For each character in the range, add the case-flipped version
      for (char ch = range.start; ch <= range.end; ch++) {
        if (Character.isLetter(ch)) {
          char lower = Character.toLowerCase(ch);
          char upper = Character.toUpperCase(ch);

          // Add the opposite case if it's different
          if (lower != upper) {
            // Add both to ensure we cover all cases
            result.add(new CharSet.Range(lower, lower));
            result.add(new CharSet.Range(upper, upper));
          }
        }

        // Safety check to prevent infinite loop on large ranges
        if (ch == Character.MAX_VALUE) break;
      }
    }

    return result;
  }

  /** Checks if the character is a valid modifier flag (i, m, s, x, -) */
  private boolean isModifierChar(char c) {
    return c == 'i' || c == 'm' || c == 's' || c == 'x' || c == '-';
  }

  /** Parses (?#...) comment - consumes everything until ) */
  private RegexNode parseComment() throws ParseException {
    consume('#');

    // Skip everything until closing paren
    while (hasMore() && peek() != ')') {
      consume();
    }

    if (!hasMore()) {
      throw new ParseException("Unclosed comment at position " + pos);
    }

    consume(')');

    // Comments are no-ops, return empty literal (epsilon)
    return new LiteralNode((char) 0);
  }

  /** Parses (?i), (?-i), (?im), (?i:...) style modifier groups */
  private RegexNode parseModifierGroup() throws ParseException {
    // Parse modifiers: (?imsx) or (?-i) or combinations
    RegexModifiers newModifiers = new RegexModifiers(currentModifiers);
    boolean negating = false;

    while (hasMore() && isModifierChar(peek())) {
      char c = consume();

      if (c == '-') {
        negating = true;
        continue;
      }

      RegexModifiers.Flag flag = RegexModifiers.Flag.fromChar(c);
      if (flag == null) {
        throw new ParseException("Unknown modifier flag: " + c);
      }

      if (negating) {
        newModifiers.remove(flag);
      } else {
        newModifiers.add(flag);
      }
    }

    // Check if it's scoped (?i:...) or global (?i)
    if (peek() == ':') {
      // Scoped modifiers: (?i:pattern)
      consume(':');

      // Save current modifiers
      RegexModifiers savedModifiers = currentModifiers;
      currentModifiers = newModifiers;

      // Parse the child pattern with new modifiers
      RegexNode child = parseAlternation();

      // Restore original modifiers
      currentModifiers = savedModifiers;

      consume(')');

      // Return a non-capturing group with the child
      // The modifiers have already affected parsing of the child
      return new GroupNode(child, 0, false);

    } else if (peek() == ')') {
      // Global modifiers: (?i) - affects rest of pattern
      consume(')');
      currentModifiers = newModifiers;

      // Return epsilon (no-op in the AST)
      return new LiteralNode((char) 0);

    } else {
      throw new ParseException("Expected ':' or ')' after modifiers at position " + pos);
    }
  }

  /** Parse (?R) - subroutine call to entire pattern. */
  private RegexNode parseSubroutineRecursive() throws ParseException {
    consume('R');
    consume(')');
    return new SubroutineNode(-1); // -1 indicates entire pattern recursion
  }

  /** Parse (?&name) - named subroutine call. */
  private RegexNode parseNamedSubroutine() throws ParseException {
    consume('&');

    // Parse the name (alphanumeric characters)
    StringBuilder name = new StringBuilder();
    while (hasMore() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
      name.append(consume());
    }

    if (name.length() == 0) {
      throw new ParseException("Expected subroutine name after (?& at position " + pos);
    }

    consume(')');
    return new SubroutineNode(name.toString());
  }

  /**
   * Parse constructs starting with (?digit... This is always a subroutine call: (?1), (?2), etc.
   */
  private RegexNode parseNumberedConstruct() throws ParseException {
    // Parse the number
    int number = 0;
    while (hasMore() && Character.isDigit(peek())) {
      number = number * 10 + (consume() - '0');
    }

    consume(')');
    return new SubroutineNode(number);
  }

  /**
   * Parse (?(condition)yes-pattern|no-pattern) or (?(condition)yes-pattern). The (? has already
   * been consumed, and peek() is '('.
   */
  private RegexNode parseConditional() throws ParseException {
    consume('(');

    // Parse the condition - for now only support numeric group references
    // Future: could support named groups, assertions, etc.
    int conditionGroup = 0;

    if (Character.isDigit(peek())) {
      // Numeric group reference: (?(1)...)
      while (hasMore() && Character.isDigit(peek())) {
        conditionGroup = conditionGroup * 10 + (consume() - '0');
      }
    } else {
      throw new UnsupportedPatternException(
          "Only numeric group conditions supported in conditionals at position " + pos);
    }

    consume(')');

    // Save modifiers - inline modifiers inside conditional shouldn't affect rest of pattern
    RegexModifiers savedModifiers = currentModifiers;

    // Parse the then-branch
    RegexNode thenBranch = parseConcatenation();

    // Check if there's an else-branch
    RegexNode elseBranch = null;
    if (peek() == '|') {
      consume('|');
      elseBranch = parseConcatenation();
    }

    // Restore modifiers
    currentModifiers = savedModifiers;

    consume(')');

    return new ConditionalNode(conditionGroup, thenBranch, elseBranch);
  }

  /** Parse (?|alt1|alt2) - branch reset group. */
  private RegexNode parseBranchReset() throws ParseException {
    consume('|');

    // Save modifiers - inline modifiers inside branch reset shouldn't affect rest of pattern
    RegexModifiers savedModifiers = currentModifiers;

    // Parse alternatives (same as alternation)
    List<RegexNode> alternatives = new ArrayList<>();

    // Track max group number across all alternatives
    int savedGroupCount = groupCount;
    int maxGroupNumber = savedGroupCount;

    // Parse first alternative
    alternatives.add(parseConcatenation());
    maxGroupNumber = Math.max(maxGroupNumber, groupCount);

    while (peek() == '|') {
      consume('|');

      // Reset group count to saved value for each alternative
      groupCount = savedGroupCount;

      alternatives.add(parseConcatenation());
      maxGroupNumber = Math.max(maxGroupNumber, groupCount);
    }

    // Restore modifiers
    currentModifiers = savedModifiers;

    consume(')');

    // Set final group count to max across all alternatives
    groupCount = maxGroupNumber;

    return new BranchResetNode(alternatives, maxGroupNumber);
  }

  // Exception classes

  public static class ParseException extends Exception {
    public ParseException(String message) {
      super(message);
    }
  }

  public static class UnsupportedPatternException extends ParseException {
    public UnsupportedPatternException(String message) {
      super(message);
    }
  }
}
