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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive descent parser for regex patterns. Converts pattern strings into AST.
 *
 * <p>Grammar (simplified): alternation := concatenation ('|' concatenation)* concatenation :=
 * quantified* quantified := atom quantifier? atom := char | charClass | group | anchor | backref
 * quantifier := '*' | '+' | '?' | '{n}' | '{n,}' | '{n,m}'
 */
public class RegexParser {

  // Highest group number PCRE2 accepts in a subroutine call
  private static final int MAX_GROUP_NUMBER = 65535;

  private String pattern;
  private int pos;
  private int groupCount;
  private int totalGroupCount;
  private int maxCalledGroup; // Highest group number called by (?N)
  private Set<String> calledNames; // Group names called by (?&name), null if none
  private Map<String, Integer> groupNameMap; // Maps group names to group numbers
  private RegexModifiers currentModifiers;

  public RegexNode parse(String pattern) throws ParseException {
    // Note: We don't validate with JDK Pattern.compile() because we support
    // additional syntax like (?#...) comments that JDK doesn't support

    this.pattern = removeQeQuoting(pattern);
    this.pos = 0;
    this.groupCount = 0;
    this.totalGroupCount = countCapturingGroups(this.pattern);
    this.groupNameMap = new HashMap<>();
    this.currentModifiers = new RegexModifiers();
    this.maxCalledGroup = 0;
    this.calledNames = null;

    RegexNode result = parseAlternation();

    if (hasMore()) {
      throw new ParseException("Unexpected character at position " + pos + ": " + peek());
    }
    // Subroutine calls may refer forward, so they are checked once all groups are known
    if (maxCalledGroup > groupCount) {
      throw new ParseException(
          "Subroutine call (?" + maxCalledGroup + ") refers to non-existent group");
    }
    if (calledNames != null) {
      for (String name : calledNames) {
        if (!groupNameMap.containsKey(name)) {
          throw new ParseException("Subroutine call (?&" + name + ") refers to undefined group");
        }
      }
    }

    return result;
  }

  /**
   * Returns the named capture group map built during the last {@link #parse} call.
   *
   * @return an unmodifiable map from group name to 1-based capture group index, or an empty map if
   *     the pattern contains no named groups or {@code parse} has not yet been called
   */
  public Map<String, Integer> getGroupNameMap() {
    return groupNameMap != null
        ? Collections.unmodifiableMap(groupNameMap)
        : Collections.emptyMap();
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

    while (true) {
      // Skip first: a whitespace- or comment-only alternative is empty, as in java.util.regex.
      skipExtendedWhitespace();
      if (!hasMore() || isAlternationOrGroupEnd()) {
        break;
      }
      RegexNode node = parseQuantified();
      // Filter out epsilon nodes (from global modifiers and comments)
      if (!(node instanceof EpsilonNode)) {
        items.add(node);
      }
    }

    if (items.isEmpty()) {
      // Empty concatenation (e.g., from "()")
      return EpsilonNode.INSTANCE; // Epsilon - handled specially
    }

    return items.size() == 1 ? items.get(0) : new ConcatNode(items);
  }

  private boolean isAlternationOrGroupEnd() throws ParseException {
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
        {
          consume();
          int suffix = checkQuantifierSuffix();
          if (suffix == 2) return possessive(base, 0, -1);
          return new QuantifierNode(base, 0, -1, suffix != 1);
        }
      case '+':
        {
          consume();
          int suffix = checkQuantifierSuffix();
          if (suffix == 2) return possessive(base, 1, -1);
          return new QuantifierNode(base, 1, -1, suffix != 1);
        }
      case '?':
        consume();
        // Check if this is a quantifier or non-greedy marker
        if (base instanceof QuantifierNode) {
          if (hasMore() && peek() == '+') {
            consume();
            throw new UnsupportedPatternException(
                "Possessive quantifier on already-quantified expression is not supported");
          }
          return base;
        }
        {
          int suffix = checkQuantifierSuffix();
          if (suffix == 2) return possessive(base, 0, 1);
          return new QuantifierNode(base, 0, 1, suffix != 1);
        }
      case '{':
        return parseCountedQuantifier(base);
      default:
        return base;
    }
  }

  /**
   * Checks the suffix after a quantifier character. Returns {@code 1} for non-greedy ({@code ?}),
   * {@code 2} for possessive ({@code +}), or {@code 0} for greedy (no suffix).
   */
  private int checkQuantifierSuffix() throws ParseException {
    skipExtendedWhitespace();
    if (hasMore() && peek() == '?') {
      consume();
      return 1; // non-greedy
    }
    if (hasMore() && peek() == '+') {
      consume();
      return 2; // possessive
    }
    return 0; // greedy
  }

  private boolean checkNonGreedy() throws ParseException {
    return checkQuantifierSuffix() == 1;
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

    int suffix = checkQuantifierSuffix();
    if (suffix == 2) return possessive(base, min, max);
    return new QuantifierNode(base, min, max, suffix != 1);
  }

  /**
   * Possessive quantifier, built the way JDK runs one: the repeat never gives back, and when the
   * base is a group each iteration is atomic too ({@code (?:a+){2}+b} cannot match "aab"). Builds
   * {@code (?>(?>X){min,max})} for a non-atomic group base X, {@code (?>X{min,max})} otherwise.
   */
  private static RegexNode possessive(RegexNode base, int min, int max) {
    RegexNode iteration =
        base instanceof GroupNode && !((GroupNode) base).atomic
            ? new GroupNode(base, 0, false, null, true)
            : base;
    return new GroupNode(new QuantifierNode(iteration, min, max, true, true), 0, false, null, true);
  }

  private int parseNumber() throws ParseException {
    StringBuilder sb = new StringBuilder();
    while (hasMore() && Character.isDigit(peek())) {
      sb.append(consume());
    }
    if (sb.length() == 0) {
      throw new ParseException("Expected number at position " + pos);
    }
    try {
      return Integer.parseInt(sb.toString());
    } catch (NumberFormatException e) {
      throw new ParseException("Quantifier count overflow at position " + pos);
    }
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
    } else if (ch == '}') {
      // A bare '}' is always a literal, matching java.util.regex: '}' can only be meaningful
      // as the closing delimiter of a {n,m} quantifier spec, which is consumed by
      // parseCountedQuantifier. If a '}' survives to atom position it must be a literal —
      // patterns such as `\{([\w.]+)}`, `{{...}}` (mustache templates) and `a}b` rely on
      // this JDK leniency.
      consume();
      return literalNode(ch);
    } else if (isMetachar(ch)) {
      throw new ParseException("Unexpected metacharacter '" + ch + "' at position " + pos);
    } else {
      consume();
      return literalNode(ch);
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
    boolean isAtomic = false;

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
      } else if (peek() == '>') {
        // Atomic group: (?>X). Mark as atomic so that engines that implement backtracking
        // (e.g. PikeVM) can honour the backtracking-prevention semantics.
        consume();
        capturing = false;
        isAtomic = true;
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

    return new GroupNode(child, groupNum, capturing, groupName, isAtomic);
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

  /**
   * Parses a character class at {@code [} the way java.util.regex {@code clazz()} does: a {@code ]}
   * is a literal while the class is empty, a nested {@code [..]} is a union, {@code &&} intersects
   * with everything up to the next {@code ]} or {@code &&}, and under (?x) whitespace and comments
   * are skipped. A {@code ^} right after the {@code [} negates.
   */
  private RegexNode parseCharClass() throws ParseException {
    boolean negated = peekNext() == '^';
    return new CharClassNode(parseClassBody(true), negated);
  }

  /** Parses a class nested in another class, at its {@code [}; negation is a complement. */
  private CharSet parseNestedClass() throws ParseException {
    boolean negated = peekNext() == '^';
    CharSet set = parseClassBody(true);
    return negated ? set.complement() : set;
  }

  /**
   * A class operand. java.util.regex adds every single character below U+0100 to one mutable set
   * per class and reads it only when the class closes, so characters added after an {@code &&} also
   * join its left operand; an operand therefore takes the final set of those characters.
   */
  private interface ClassOperand {
    CharSet of(CharSet bits);
  }

  private static final ClassOperand BITS = bits -> bits;

  private static ClassOperand leaf(CharSet set) {
    return bits -> set;
  }

  private static ClassOperand union(ClassOperand a, ClassOperand b) {
    return bits -> a.of(bits).union(b.of(bits));
  }

  private static ClassOperand intersection(ClassOperand a, ClassOperand b) {
    return bits -> a.of(bits).intersection(b.of(bits));
  }

  /**
   * The body of java.util.regex {@code clazz(consume)}: with {@code consume} it starts at the
   * {@code [} and consumes the closing {@code ]}; without it, it parses the right operand of an
   * {@code &&} from the current position and stops at the {@code ]}. Returns the set without the
   * leading-{@code ^} negation, which the callers apply.
   */
  private CharSet parseClassBody(boolean consume) throws ParseException {
    ClassOperand prev = null;
    ClassOperand curr = null;
    List<CharSet.Range> bits = new ArrayList<>();
    boolean hasBits = false;
    if (consume) {
      pos++;
    }
    char ch = classPeek();
    if (consume && ch == '^' && pattern.charAt(pos - 1) == '[') {
      pos++;
      ch = classPeek();
    }
    for (; ; ) {
      switch (ch) {
        case '[':
          curr = leaf(parseNestedClass());
          prev = prev == null ? curr : union(prev, curr);
          ch = classPeek();
          continue;
        case '&':
          pos++;
          ch = classPeek();
          if (ch != '&') {
            // A literal '&'. Like the JDK, step back one character only: under (?x) whitespace
            // after the '&' lands the next item on the whitespace and the '&' is dropped.
            pos--;
            break;
          }
          pos++;
          ch = classPeek();
          ClassOperand right = null;
          while (ch != ']' && ch != '&') {
            ClassOperand operand = leaf(ch == '[' ? parseNestedClass() : parseClassBody(false));
            right = right == null ? operand : union(right, operand);
            ch = classPeek();
          }
          if (hasBits) {
            if (prev == null) {
              prev = curr = BITS;
            } else {
              prev = union(prev, BITS);
            }
            hasBits = false;
          }
          if (right != null) {
            curr = right;
          }
          if (prev == null) {
            if (right == null) {
              throw new ParseException("Bad class syntax");
            }
            prev = right;
          } else {
            if (curr == null) {
              throw new ParseException("Bad intersection syntax");
            }
            prev = intersection(prev, curr);
          }
          continue;
        case 0:
          if (!hasMore()) {
            throw new ParseException("Unclosed character class");
          }
          break;
        case ']':
          if (prev != null || hasBits) {
            if (consume) {
              pos++;
            }
            ClassOperand result = prev == null ? BITS : hasBits ? union(prev, BITS) : prev;
            return result.of(foldClassLeaf(CharSet.fromRanges(bits)));
          }
          break;
        default:
          break;
      }
      CharSet range = parseClassRange(bits);
      if (range == null) {
        hasBits = true;
        curr = null;
      } else {
        curr = leaf(range);
        prev = prev == null ? curr : union(prev, curr);
      }
      ch = classPeek();
    }
  }

  /**
   * java.util.regex {@code range(bits)}: parses one class item. A single character below U+0100 is
   * added to {@code bits} and null is returned; any other item is returned as a set. As in the JDK,
   * a {@code -} before {@code [} or {@code ]} is not a range.
   */
  private CharSet parseClassRange(List<CharSet.Range> bits) throws ParseException {
    if (classPeek() == '\\') {
      CharSet set = parseClassEscapeSet(peekNext());
      if (set != null) {
        return set;
      }
    }
    char start = parseCharClassChar();
    if (classPeek() == '-') {
      char endRange = peekNext();
      if (endRange != '[' && endRange != ']') {
        pos++;
        char end;
        if (classPeek() == '\\') {
          end = parseCharClassChar();
        } else {
          end = hasMore() ? consume() : 0;
        }
        if (end < start) {
          throw new ParseException("Illegal character range");
        }
        return foldClassLeaf(CharSet.range(start, end));
      }
    }
    if (start < 0x100) {
      bits.add(new CharSet.Range(start, start));
      return null;
    }
    return foldClassLeaf(CharSet.of(start));
  }

  /**
   * Consumes a set escape at a backslash in a class ({@code \d \D \w \W \s \S \h \H \V \p \P}, and
   * {@code \v} unless a {@code -} follows, which makes it the VT range bound) and returns its set,
   * or returns null without consuming for any other escape.
   */
  private CharSet parseClassEscapeSet(char e) throws ParseException {
    switch (e) {
      case 'd':
      case 'D':
      case 'w':
      case 'W':
      case 's':
      case 'S':
        pos += 2;
        return getCharSetForEscape(e);
      case 'v':
        if (pos + 2 < pattern.length() && pattern.charAt(pos + 2) == '-') {
          return null;
        }
      // fall through
      case 'h':
      case 'H':
      case 'V':
        pos += 2;
        CharSet ws = e == 'h' || e == 'H' ? CharSet.HORIZONTAL_WS : CharSet.VERTICAL_WS;
        return Character.isUpperCase(e) ? ws.complement() : ws;
      case 'p':
      case 'P':
        pos += 2;
        CharSet property = foldClassLeaf(parseUnicodeCategoryRanges(false));
        return e == 'P' ? property.complement() : property;
      default:
        return null;
    }
  }

  /** Under (?i), adds the case-folded characters of a literal, range or property set. */
  private CharSet foldClassLeaf(CharSet set) throws ParseException {
    if (!currentModifiers.isCaseInsensitive() || set.isEmpty()) {
      return set;
    }
    checkUnicodeCaseFold();
    return CharSet.fromRanges(applyCaseInsensitiveToRanges(set.getRanges()));
  }

  /**
   * java.util.regex {@code peek()} in a class: skips (?x) whitespace and comments; 0 at the end.
   */
  private char classPeek() {
    skipExtendedWhitespace();
    return hasMore() ? pattern.charAt(pos) : 0;
  }

  /**
   * Parse a Unicode property category from {Name} (the '\p' or '\P' has already been consumed) and
   * return the effective CharSet, applying negation when {@code negated} is true. Used when inside
   * a character class.
   */
  private CharSet parseUnicodeCategoryRanges(boolean negated) throws ParseException {
    if (!hasMore() || peek() != '{') {
      throw new ParseException("Expected '{' after \\p/\\P in character class at position " + pos);
    }
    consume('{');
    StringBuilder name = new StringBuilder();
    while (hasMore() && peek() != '}') {
      name.append(consume());
    }
    if (!hasMore()) {
      throw new ParseException("Unclosed \\p{...} in character class at position " + pos);
    }
    consume('}');
    String category = name.toString();
    CharSet cs =
        CharSet.ofUnicodeCategory(
            category, currentModifiers.has(RegexModifiers.Flag.UNICODE_CLASSES));
    if (cs == null) {
      throw new UnsupportedPatternException("Unsupported Unicode property: \\p{" + category + "}");
    }
    return negated ? cs.complement() : cs;
  }

  private CharSet getCharSetForEscape(char escapeChar) {
    boolean unicode = currentModifiers.has(RegexModifiers.Flag.UNICODE_CLASSES);
    switch (escapeChar) {
      case 'd':
        return unicode ? CharSet.UNICODE_CLASSES_DIGIT : CharSet.DIGIT;
      case 'D':
        return (unicode ? CharSet.UNICODE_CLASSES_DIGIT : CharSet.DIGIT).complement();
      case 'w':
        return unicode ? CharSet.UNICODE_CLASSES_WORD : CharSet.WORD;
      case 'W':
        return (unicode ? CharSet.UNICODE_CLASSES_WORD : CharSet.WORD).complement();
      case 's':
        return unicode ? CharSet.UNICODE_CLASSES_SPACE : CharSet.WHITESPACE;
      case 'S':
        return (unicode ? CharSet.UNICODE_CLASSES_SPACE : CharSet.WHITESPACE).complement();
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
        case 'b':
          // \b inside a character class is backspace (U+0008), not a word-boundary anchor
          return (char) 8;
        case 'e':
          return '\u001B';
        case 'a':
          return '\u0007';
        case 'c':
          return parseControlChar();
        case 'v':
          // Reached only as a range bound (parseCharClass takes any other \v as a set)
          return '\u000B';
        case 'd':
        case 'D':
        case 'w':
        case 'W':
        case 's':
        case 'S':
        case 'h':
        case 'H':
        case 'V':
        case 'p':
        case 'P':
          // parseCharClass consumes these as sets before a range start, so this is a range end
          throw new ParseException("Illegal character range");
        case 'R':
        case 'X':
          throw new ParseException("Illegal/unsupported escape sequence \\" + ch + " in class");
        case 'x':
          // Hex escape: \xhh
          return parseCharFromHexEscape();
        case 'u':
          return parseUnicodeEscape();
        case '0':
          return parseOctalAfterZero();
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
          // Octal escape: \nnn (up to 3 octal digits); java.util.regex rejects these in a class
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

    boolean unicode = currentModifiers.has(RegexModifiers.Flag.UNICODE_CLASSES);
    switch (ch) {
      case 'd':
        return new CharClassNode(unicode ? CharSet.UNICODE_CLASSES_DIGIT : CharSet.DIGIT, false);
      case 'D':
        return new CharClassNode(unicode ? CharSet.UNICODE_CLASSES_DIGIT : CharSet.DIGIT, true);
      case 'w':
        return new CharClassNode(unicode ? CharSet.UNICODE_CLASSES_WORD : CharSet.WORD, false);
      case 'W':
        return new CharClassNode(unicode ? CharSet.UNICODE_CLASSES_WORD : CharSet.WORD, true);
      case 's':
        return new CharClassNode(
            unicode ? CharSet.UNICODE_CLASSES_SPACE : CharSet.WHITESPACE, false);
      case 'S':
        return new CharClassNode(
            unicode ? CharSet.UNICODE_CLASSES_SPACE : CharSet.WHITESPACE, true);
      case 'b':
      case 'B':
        if (unicode) {
          // JDK \b with UNICODE_CHARACTER_CLASS uses the Unicode word set; every Reggie
          // engine's word-boundary evaluator is ASCII. Reject loudly rather than diverge.
          throw new UnsupportedPatternException(
              "\\b/\\B with UNICODE_CHARACTER_CLASS ((?U)): the Unicode word boundary is not"
                  + " supported - the pattern matches with the ASCII word set instead; use"
                  + " compileAllowingFallback or drop (?U) for this pattern");
        }
        return new AnchorNode(
            ch == 'b' ? AnchorNode.Type.WORD_BOUNDARY : AnchorNode.Type.NON_WORD_BOUNDARY);
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
      case 'e':
        return new LiteralNode('\u001B');
      case 'a':
        return new LiteralNode('\u0007');
      case 'c':
        return literalNode(parseControlChar());
      case 'h':
        return new CharClassNode(CharSet.HORIZONTAL_WS, false);
      case 'H':
        return new CharClassNode(CharSet.HORIZONTAL_WS, true);
      case 'v':
        return new CharClassNode(CharSet.VERTICAL_WS, false);
      case 'V':
        return new CharClassNode(CharSet.VERTICAL_WS, true);
      case 'R':
        {
          // java.util.regex: ordered, non-atomic (?:\r\n|[\n\x0B\f\r\x85 U+2028 U+2029])
          List<RegexNode> crlf = new ArrayList<>();
          crlf.add(new LiteralNode('\r'));
          crlf.add(new LiteralNode('\n'));
          List<RegexNode> alternatives = new ArrayList<>();
          alternatives.add(new ConcatNode(crlf));
          alternatives.add(new CharClassNode(CharSet.VERTICAL_WS, false));
          return new GroupNode(new AlternationNode(alternatives), 0, false, null, false);
        }
      case 'X':
        throw new UnsupportedPatternException("\\X (grapheme cluster) is not supported");
      case 'x':
        // Hex escape: \xhh
        return parseHexEscape();
      case 'u':
        return literalNode(parseUnicodeEscape());
      case '0':
        return literalNode(parseOctalAfterZero());
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '8':
      case '9':
        // java.util.regex.Pattern.ref(): \1-\9 always start a backreference. The number is
        // extended digit by digit while it stays within the groups opened so far, so \10 after
        // one group is backref \1 followed by literal '0'.
        {
          int refNum = ch - '0';
          while (hasMore() && peek() >= '0' && peek() <= '9') {
            int next = refNum * 10 + (peek() - '0');
            if (next > groupCount) break;
            refNum = next;
            consume();
          }
          if (refNum > totalGroupCount) {
            // A group that does not exist is never set, so the reference never matches.
            return new CharClassNode(CharSet.fromRanges(new ArrayList<>()), false);
          }
          return new BackreferenceNode(refNum);
        }
      case 'k':
        // Named backreference: \k<name> or \k'name'
        return parseNamedBackreference();
      case 'g':
        // PCRE backreference: \g{N}, \g{-N}, or \g{name}
        return parseGBackreference();
      case 'p':
        return parseUnicodeProperty(false);
      case 'P':
        return parseUnicodeProperty(true);
      default:
        // Escaped literal (e.g., \., \*, \+)
        return literalNode(ch);
    }
  }

  /**
   * Parse \p{Category} or \P{Category} (negated). The leading '\' and 'p'/'P' have already been
   * consumed.
   */
  private RegexNode parseUnicodeProperty(boolean negated) throws ParseException {
    if (!hasMore() || peek() != '{') {
      throw new ParseException("Expected '{' after \\p/\\P at position " + pos);
    }
    consume('{');
    StringBuilder name = new StringBuilder();
    while (hasMore() && peek() != '}') {
      name.append(consume());
    }
    if (!hasMore()) {
      throw new ParseException("Unclosed \\p{...} at position " + pos);
    }
    consume('}');
    String category = name.toString();
    CharSet cs =
        CharSet.ofUnicodeCategory(
            category, currentModifiers.has(RegexModifiers.Flag.UNICODE_CLASSES));
    if (cs == null) {
      throw new UnsupportedPatternException("Unsupported Unicode property: \\p{" + category + "}");
    }
    return new CharClassNode(cs, negated);
  }

  /**
   * java.util.regex.Pattern.o() for the digits after {@code \0} (already consumed): one to three
   * octal digits, a third only when the first is at most 3. With no octal digit this returns NUL,
   * where the JDK rejects the pattern.
   */
  private char parseOctalAfterZero() throws ParseException {
    int value = 0;
    int digits = 0;
    while (hasMore() && digits < 3 && peek() >= '0' && peek() <= '7') {
      if (digits == 2 && value > 3 * 8 + 7) {
        break; // the first digit is above 3
      }
      value = value * 8 + (consume() - '0');
      digits++;
    }
    return (char) value;
  }

  /** Parse the X of {@code \cX} (the '\' and 'c' have already been consumed): X ^ 64. */
  private char parseControlChar() throws ParseException {
    if (!hasMore()) {
      throw new ParseException("Illegal control escape sequence");
    }
    return (char) (consume() ^ 64);
  }

  /** Parse hex escape in character class context and return the character. */
  private char parseCharFromHexEscape() throws ParseException {
    RegexNode node = parseHexEscapeInternal();
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch;
    }
    throw new ParseException("Invalid hex escape in character class at position " + pos);
  }

  /**
   * Parse a Unicode escape {@code \}{@code uhhhh} (the '\' and 'u' have already been consumed):
   * exactly four hex digits, as in java.util.regex. Surrogate values are rejected because
   * java.util.regex pairs them into a supplementary code point, which Reggie does not support.
   */
  private char parseUnicodeEscape() throws ParseException {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      int digit = hasMore() ? Character.digit(peek(), 16) : -1;
      if (digit == -1) {
        throw new ParseException("Illegal Unicode escape sequence at position " + pos);
      }
      value = value * 16 + digit;
      consume();
    }
    if (Character.isSurrogate((char) value)) {
      throw new UnsupportedPatternException(
          "Surrogate Unicode escape \\u" + Integer.toHexString(value) + " is not supported");
    }
    return (char) value;
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
    RegexNode node = parseHexEscapeInternal();
    return node instanceof LiteralNode ? literalNode(((LiteralNode) node).ch) : node;
  }

  /** Internal implementation of hex escape parsing. Handles both \xhh and \x{NNNN} forms. */
  private RegexNode parseHexEscapeInternal() throws ParseException {
    // Already consumed '\x'
    if (!hasMore()) {
      throw new ParseException("Incomplete hex escape at position " + pos);
    }

    // Braced form: \x{NNNN}
    if (peek() == '{') {
      consume(); // consume '{'
      StringBuilder sb = new StringBuilder();
      while (hasMore() && peek() != '}') {
        char ch = peek();
        if (Character.digit(ch, 16) == -1) {
          throw new ParseException("Invalid character in hex escape at position " + pos);
        }
        sb.append(consume());
      }
      if (sb.length() == 0) {
        throw new ParseException("Empty hex escape \\x{} at position " + pos);
      }
      consume('}');
      try {
        int codePoint = Integer.parseInt(sb.toString(), 16);
        if (codePoint > 0xFFFF) {
          throw new ParseException(
              "Hex escape \\x{" + sb + "} is outside the BMP (> U+FFFF) at position " + pos);
        }
        return new LiteralNode((char) codePoint);
      } catch (NumberFormatException e) {
        throw new ParseException("Hex escape overflow \\x{" + sb + "} at position " + pos);
      }
    }

    // Plain form: \xhh — up to 2 hex digits
    int value = 0;
    int digitCount = 0;

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

  /**
   * Rejects case-insensitive matching under the Unicode-aware case folding of (?u) and (?U).
   * Reggie's case folding is Unicode simple-case; the JDK adds the special folds (k/K U+212A,
   * s/\u017F) when either UNICODE_CASE or UNICODE_CHARACTER_CLASS is active — accepting the
   * combination would diverge exactly where the JDK matches. Like \b under (?U): reject loudly
   * rather than diverge.
   */
  private void checkUnicodeCaseFold() throws ParseException {
    if (currentModifiers.has(RegexModifiers.Flag.UNICODE_CASE)
        || currentModifiers.has(RegexModifiers.Flag.UNICODE_CLASSES)) {
      throw new UnsupportedPatternException(
          "case-insensitive matching with Unicode-aware case folding ((?iu)/(?iU)): the special"
              + " Unicode case folds (k/U+212A, s/U+017F) are not supported - reggie would match"
              + " with simple case folding instead; drop the u/U flag for this pattern or use"
              + " compileAllowingFallback");
    }
  }

  private RegexNode literalNode(char ch) throws ParseException {
    if (currentModifiers.isCaseInsensitive() && Character.isLetter(ch)) {
      checkUnicodeCaseFold();
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

  private char peek() throws ParseException {
    if (!hasMore()) {
      throw new ParseException("Unexpected end of pattern at position " + pos);
    }
    return pattern.charAt(pos);
  }

  private char peekNext() {
    if (pos + 1 >= pattern.length()) {
      return '\0';
    }
    return pattern.charAt(pos + 1);
  }

  private char consume() throws ParseException {
    if (pos >= pattern.length()) {
      throw new ParseException("Unexpected end of pattern at position " + pos);
    }
    return pattern.charAt(pos++);
  }

  /**
   * Skip whitespace and comments if in extended mode (?x), as java.util.regex does, also inside
   * character classes: unescaped ASCII whitespace ({@code [ \t\n\x0B\f\r]}) and comments from
   * {@code #} up to, not including, a line terminator ({@code \n \r U+0085 U+2028 U+2029}) or NUL.
   */
  private void skipExtendedWhitespace() {
    if (!currentModifiers.isExtended()) {
      return;
    }
    while (hasMore()) {
      char ch = pattern.charAt(pos);
      if (PatternLexer.isSpace(ch)) {
        pos++;
      } else if (ch == '#') {
        pos = PatternLexer.skipComment(pattern, pos);
      } else {
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
    return c == 'i' || c == 'm' || c == 's' || c == 'x' || c == 'U' || c == 'u' || c == '-';
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
    return EpsilonNode.INSTANCE;
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
      return EpsilonNode.INSTANCE;

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
    if (calledNames == null) {
      calledNames = new HashSet<>();
    }
    calledNames.add(name.toString());
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
      if (number > MAX_GROUP_NUMBER) {
        throw new ParseException("Subroutine group number is too big at position " + pos);
      }
    }

    consume(')');
    maxCalledGroup = Math.max(maxCalledGroup, number);
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

    // Group 0 is not a capturing group (PCRE2 rejects (?(0)...))
    if (conditionGroup == 0) {
      throw new ParseException("Invalid condition (?(0) at position " + pos);
    }
    // Validate that the referenced group exists
    if (conditionGroup > groupCount) {
      throw new ParseException(
          "Backreference condition \\("
              + conditionGroup
              + ") refers to non-existent group at position "
              + pos);
    }

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

  /**
   * Port of java.util.regex.Pattern.RemoveQEQuoting: rewrites every {@code \Q..\E} span as plain
   * pattern text, so a quantifier after {@code \E} applies to the last quoted character only.
   * Inside a quote, ASCII letters, digits and non-ASCII characters are copied, a backslash becomes
   * an escaped backslash, and any other character gets a backslash. A digit directly after {@code
   * \Q} is written as {@code \x3} followed by the digit, so it cannot extend a preceding escape.
   * Returns {@code pat} itself when it contains no {@code \Q}.
   */
  private static String removeQeQuoting(String pat) {
    int len = pat.length();
    int i = 0;
    while (i < len - 1) {
      if (pat.charAt(i) != '\\') {
        i += 1;
      } else if (pat.charAt(i + 1) != 'Q') {
        i += 2;
      } else {
        break;
      }
    }
    if (i >= len - 1) {
      return pat;
    }
    StringBuilder sb = new StringBuilder(len + 16);
    sb.append(pat, 0, i);
    i += 2;
    boolean inQuote = true;
    boolean beginQuote = true;
    while (i < len) {
      char c = pat.charAt(i++);
      if (c > 0x7F || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
        sb.append(c);
      } else if (c >= '0' && c <= '9') {
        if (beginQuote) {
          sb.append("\\x3");
        }
        sb.append(c);
      } else if (c != '\\') {
        if (inQuote) {
          sb.append('\\');
        }
        sb.append(c);
      } else if (inQuote) {
        if (i < len && pat.charAt(i) == 'E') {
          i++;
          inQuote = false;
        } else {
          sb.append("\\\\");
        }
      } else {
        if (i < len && pat.charAt(i) == 'Q') {
          i++;
          inQuote = true;
          beginQuote = true;
          continue;
        }
        sb.append(c);
        if (i < len) {
          sb.append(pat.charAt(i++));
        }
      }
      beginQuote = false;
    }
    return sb.toString();
  }

  /**
   * Pre-scans the pattern to count the total number of capturing groups. A numeric backreference
   * above this count refers to a group that does not exist and never matches.
   */
  private static int countCapturingGroups(String pat) {
    return GroupCounter.count(pat);
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
