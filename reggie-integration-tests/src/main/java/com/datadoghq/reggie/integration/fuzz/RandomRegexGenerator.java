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
package com.datadoghq.reggie.integration.fuzz;

import java.util.Random;

/**
 * Grammar-driven random regex generator. Produces syntactically valid patterns over a small
 * alphabet, bounded by a depth parameter, suitable for property-based testing against an oracle
 * (typically {@link java.util.regex.Pattern}). Deterministic given a seed.
 *
 * <p>Scope is deliberately narrow: simple atoms (literal chars, character classes), quantifiers,
 * concat/alternation, anchors, groups, and a single backreference when a group is in scope. No
 * lookarounds, no PCRE-only features — those have their own existing test corpora and would widen
 * the surface beyond what we can usefully oracle-check.
 */
public final class RandomRegexGenerator {

  /** Characters available to literals and character classes. Small alphabet keeps inputs dense. */
  private static final char[] ALPHABET = {'a', 'b', 'c', '0', '1', '-', '_'};

  private final Random rnd;
  private final int maxDepth;

  /**
   * @param rnd the random source; pass a seeded {@link Random} for reproducibility.
   * @param maxDepth maximum recursion depth for nested groups/alternation/concat. 3 is a sensible
   *     default — deeper trees produce patterns that strain the JDK parser more than they reveal
   *     Reggie bugs.
   */
  public RandomRegexGenerator(Random rnd, int maxDepth) {
    this.rnd = rnd;
    this.maxDepth = maxDepth;
  }

  /** Generate a top-level pattern. */
  public String generate() {
    StringBuilder sb = new StringBuilder();
    // 0 groups in scope yet; the genAlt path opens groups as it recurses.
    genAlt(sb, maxDepth, 0);
    return sb.toString();
  }

  /** Alternation: child[|child]* */
  private int genAlt(StringBuilder sb, int depth, int groupsInScope) {
    int branches = depth <= 0 ? 1 : 1 + rnd.nextInt(2); // 1 or 2 branches when depth allows
    for (int i = 0; i < branches; i++) {
      if (i > 0) sb.append('|');
      groupsInScope = genConcat(sb, depth - 1, groupsInScope);
    }
    return groupsInScope;
  }

  /** Concatenation: 1-3 atoms in sequence. */
  private int genConcat(StringBuilder sb, int depth, int groupsInScope) {
    int parts = 1 + rnd.nextInt(3);
    for (int i = 0; i < parts; i++) {
      groupsInScope = genAtom(sb, depth, groupsInScope);
    }
    return groupsInScope;
  }

  /**
   * A single atom, possibly with a quantifier. Atoms: literal, char class, dot, anchor, group,
   * backreference. Depth gates whether group/backref can recurse.
   */
  private int genAtom(StringBuilder sb, int depth, int groupsInScope) {
    int kind = rnd.nextInt(100);
    // Probabilities are eyeballed to keep generated patterns mostly satisfiable.
    if (kind < 30) {
      sb.append(literal());
    } else if (kind < 50) {
      sb.append(charClass());
    } else if (kind < 60) {
      sb.append('.');
    } else if (kind < 75) {
      sb.append(anchor());
    } else if (depth > 0 && kind < 90) {
      // Group: capturing 70% of the time, non-capturing 30%.
      // Children inside the group can reference outer groups (groupsInScope) but NOT this
      // group itself — a backref to a group that encloses the backref site is semantically
      // pathological (the group hasn't captured yet), and JDK / Reggie disagree on its
      // meaning. The fuzz oracle treats this as accepted divergence; stop generating it.
      boolean capturing = rnd.nextInt(10) < 7;
      sb.append(capturing ? "(" : "(?:");
      int after = genAlt(sb, depth - 1, groupsInScope);
      sb.append(')');
      // A capturing group adds one to the count from the caller's perspective.
      groupsInScope = capturing ? Math.max(groupsInScope + 1, after) : after;
    } else if (groupsInScope > 0 && kind < 95) {
      // Backreference to an already-opened group.
      int target = 1 + rnd.nextInt(groupsInScope);
      sb.append('\\').append(target);
    } else {
      // Fallback: another literal.
      sb.append(literal());
    }
    // Apply a quantifier sometimes. Anchors and backrefs at the end already wrote their own
    // representation; quantifying them is legal in Java regex syntax even if degenerate.
    int qkind = rnd.nextInt(10);
    if (qkind < 4) {
      sb.append(quantifier());
    }
    return groupsInScope;
  }

  /** A single literal char from {@link #ALPHABET}, regex-escaped where necessary. */
  private String literal() {
    char c = ALPHABET[rnd.nextInt(ALPHABET.length)];
    // '-' is fine outside character classes; '_' too. Nothing in our alphabet needs escaping
    // at the top level, but be explicit so a future alphabet expansion does not silently break.
    return String.valueOf(c);
  }

  /** A character class like {@code [abc]} / {@code [a-c]} / {@code [^abc]}. */
  private String charClass() {
    StringBuilder cls = new StringBuilder("[");
    if (rnd.nextInt(4) == 0) cls.append('^'); // negated 25% of the time
    int items = 1 + rnd.nextInt(3);
    for (int i = 0; i < items; i++) {
      if (rnd.nextInt(3) == 0) {
        // Range
        char a = ALPHABET[rnd.nextInt(ALPHABET.length)];
        char b = ALPHABET[rnd.nextInt(ALPHABET.length)];
        char low = (char) Math.min(a, b);
        char high = (char) Math.max(a, b);
        cls.append(low).append('-').append(high);
      } else {
        cls.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
      }
    }
    cls.append(']');
    return cls.toString();
  }

  /** A zero-width anchor: ^, $, \A, \Z, \z. */
  private String anchor() {
    switch (rnd.nextInt(5)) {
      case 0:
        return "^";
      case 1:
        return "$";
      case 2:
        return "\\A";
      case 3:
        return "\\Z";
      default:
        return "\\z";
    }
  }

  /** A quantifier suffix: ?, *, +, {n}, {n,}, {n,m}, with an optional lazy modifier. */
  private String quantifier() {
    String base;
    switch (rnd.nextInt(6)) {
      case 0:
        base = "?";
        break;
      case 1:
        base = "*";
        break;
      case 2:
        base = "+";
        break;
      case 3:
        base = "{" + rnd.nextInt(5) + "}";
        break;
      case 4:
        base = "{" + rnd.nextInt(4) + ",}";
        break;
      default:
        int lo = rnd.nextInt(4);
        int hi = lo + rnd.nextInt(4);
        base = "{" + lo + "," + hi + "}";
        break;
    }
    // 20% chance to make it lazy.
    return rnd.nextInt(5) == 0 ? base + "?" : base;
  }
}
