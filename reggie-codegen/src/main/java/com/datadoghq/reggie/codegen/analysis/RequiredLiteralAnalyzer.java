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
package com.datadoghq.reggie.codegen.analysis;

import com.datadoghq.reggie.codegen.ast.AlternationNode;
import com.datadoghq.reggie.codegen.ast.AnchorNode;
import com.datadoghq.reggie.codegen.ast.AssertionNode;
import com.datadoghq.reggie.codegen.ast.BackreferenceNode;
import com.datadoghq.reggie.codegen.ast.BranchResetNode;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.ConcatNode;
import com.datadoghq.reggie.codegen.ast.EpsilonNode;
import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.QuantifierNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.ast.SubroutineNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sound required-literal extraction over the pattern AST.
 *
 * <p>Computes the longest literal string that must occur in <em>every</em> string the pattern's
 * language can match — a language-level property, independent of engine match-choice semantics
 * (greedy vs lazy, atomic vs not). Consumers use it as an {@code indexOf} rejection prefilter for
 * unanchored {@code find()}: if the literal does not occur in the (remaining) input, no match can
 * exist.
 *
 * <h4>Soundness rules</h4>
 *
 * <ul>
 *   <li><b>concat</b>: every child matches exactly once, so the union of children's facts is sound.
 *       Adjacent-run merges are allowed through <em>exact</em> intermediates only: {@code
 *       suffixRun(c_i) + exact(c_{i+1..j-1}) + prefixRun(c_j)} is a fact because suffixRun ends at
 *       the child's match end and prefixRun starts at the child's match start.
 *   <li><b>alternation</b>: intersection of branch fact sets, plus the longest common prefix and
 *       suffix runs of the branches.
 *   <li><b>quantifier</b> min=0: nothing is required. min&gt;=1: facts, prefixRun and suffixRun
 *       pass through (every match contains at least one child match; the first starts at the node
 *       start, the last ends at the node end); exact only when min==max==1.
 *   <li><b>groups</b> pass through; atomic groups pass facts through (the atomic language is a
 *       subset of the child language).
 *   <li><b>backreferences, subroutines, conditionals, assertions, char classes</b>: no facts; run
 *       chains break at them.
 *   <li><b>case-insensitive</b> patterns drop facts containing cased letters (conservative — only
 *       fold-stable facts survive).
 * </ul>
 *
 * <p>Validated by {@code RequiredLiteralAuditTest} against the JDK oracle over the 513-pattern real
 * logs-backend corpus: zero violations (every extracted literal occurs in every JDK-matched input),
 * 407/513 patterns yield a literal.
 */
public final class RequiredLiteralAnalyzer {

  private static final int MAX_FACTS_PER_NODE = 32;
  private static final int MAX_FACT_LEN = 64;
  private static final int MIN_USABLE_LEN = 2;

  private RequiredLiteralAnalyzer() {}

  /**
   * Longest literal that must occur in every match of the pattern, or {@code null} when no usable
   * (≥ {@value #MIN_USABLE_LEN} chars) literal exists.
   *
   * @param ast parsed pattern root (from {@code RegexParser})
   * @param caseInsensitive whether the pattern compiles case-insensitively
   */
  public static String longestLiteral(RegexNode ast, boolean caseInsensitive) {
    Analysis root = analyze(ast);
    String best = null;
    for (String fact : root.facts) {
      if (caseInsensitive && containsCasedLetter(fact)) {
        continue;
      }
      if (fact.length() >= MIN_USABLE_LEN && (best == null || fact.length() > best.length())) {
        best = fact;
      }
    }
    return best;
  }

  /**
   * Any single character that must occur in every match of the pattern, or {@code null} when none
   * is known. Sources the 1-char evidence that {@link #longestLiteral} drops: the first char of a
   * non-empty root {@code exact}/{@code prefixRun}/{@code suffixRun} (each is a "every match
   * contains/starts/ends with this" fact by construction).
   *
   * <p>Only meaningful when {@link #longestLiteral} returned {@code null} — a multi-char fact
   * strictly dominates. Under global case-insensitivity cased letters never appear in runs (the
   * parser folds them into char classes), so {@code caseInsensitive} skips cased chars defensively.
   */
  public static String requiredChar(RegexNode ast, boolean caseInsensitive) {
    return requiredCharRec(ast, caseInsensitive);
  }

  /**
   * Soundness by construction: a returned char is required in every match of {@code node}.
   *
   * <ul>
   *   <li>literal: its char (a cased letter is skipped under {@code caseInsensitive} — the parser
   *       folds those into char classes, so this is defensive only)
   *   <li>concat: every child participates in every match (possibly matching empty), so a char
   *       required by any child is required overall
   *   <li>alternation/branch-reset: only a char required by EVERY branch qualifies
   *   <li>quantifier min ≥ 1: the body must match at least once
   *   <li>group: pass-through (group language ⊆ child language)
   *   <li>everything else (char classes, backrefs, assertions, conditionals): null
   * </ul>
   */
  private static String requiredCharRec(RegexNode node, boolean ci) {
    if (node instanceof LiteralNode lit) {
      if (node instanceof EpsilonNode) {
        return null;
      }
      char c = lit.ch;
      if (ci && Character.isLetter(c)) {
        return null;
      }
      return String.valueOf(c);
    }
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        String r = requiredCharRec(child, ci);
        if (r != null) {
          return r;
        }
      }
      return null;
    }
    if (node instanceof AlternationNode a) {
      String common = null;
      for (RegexNode alt : a.alternatives) {
        String r = requiredCharRec(alt, ci);
        if (r == null) {
          return null;
        }
        if (common == null) {
          common = r;
        } else if (!common.equals(r)) {
          return null;
        }
      }
      return a.alternatives.isEmpty() ? null : common;
    }
    if (node instanceof BranchResetNode b) {
      String common = null;
      for (RegexNode alt : b.alternatives) {
        String r = requiredCharRec(alt, ci);
        if (r == null) {
          return null;
        }
        if (common == null) {
          common = r;
        } else if (!common.equals(r)) {
          return null;
        }
      }
      return b.alternatives.isEmpty() ? null : common;
    }
    if (node instanceof QuantifierNode q) {
      return q.min >= 1 ? requiredCharRec(q.child, ci) : null;
    }
    if (node instanceof GroupNode g) {
      return requiredCharRec(g.child, ci);
    }
    return null;
  }

  /** Per-node extraction result. */
  private record Analysis(
      Set<String> facts, // every match of this node's language contains each fact
      String exact, // all matches are exactly this string (null if unknown)
      String prefixRun, // every match STARTS with this ("" if none)
      String suffixRun) { // every match ENDS with this ("" if none)
    static Analysis none() {
      return new Analysis(new HashSet<>(), null, "", "");
    }
  }

  private static Analysis analyze(RegexNode node) {
    if (node instanceof LiteralNode lit) {
      if (node instanceof EpsilonNode) {
        // matches only the empty string: contributes nothing but chains may extend through it
        return new Analysis(new HashSet<>(), "", "", "");
      }
      String s = String.valueOf(lit.ch);
      return new Analysis(new HashSet<>(Set.of(s)), s, s, s);
    }
    if (node instanceof AnchorNode) {
      // zero-width; the language is exactly {""}
      return new Analysis(new HashSet<>(), "", "", "");
    }
    if (node instanceof CharClassNode
        || node instanceof BackreferenceNode
        || node instanceof SubroutineNode
        || node instanceof AssertionNode) {
      return Analysis.none();
    }
    if (node instanceof ConcatNode c) {
      return analyzeConcat(c);
    }
    if (node instanceof AlternationNode a) {
      return analyzeAlternation(a.alternatives);
    }
    if (node instanceof BranchResetNode b) {
      return analyzeAlternation(b.alternatives);
    }
    if (node instanceof QuantifierNode q) {
      Analysis in = analyze(q.child);
      if (q.min == 0) {
        return Analysis.none();
      }
      String exact = (q.min == 1 && q.max == 1) ? in.exact() : null;
      return new Analysis(in.facts(), exact, in.prefixRun(), in.suffixRun());
    }
    if (node instanceof GroupNode g) {
      // capturing/non-capturing pass through; atomic language ⊆ child language: facts hold
      return analyze(g.child);
    }
    // ConditionalNode and unknown node types: conservative — no facts
    return Analysis.none();
  }

  private static Analysis analyzeConcat(ConcatNode c) {
    List<RegexNode> children = c.children;
    if (children.isEmpty()) {
      return Analysis.none();
    }

    // Every child is required (matches once, in order): union of child facts is sound.
    Set<String> facts = new HashSet<>();
    for (RegexNode child : children) {
      facts.addAll(analyze(child).facts());
    }

    // prefixRun: chain through children with exact content, extend into the first inexact child
    StringBuilder pre = new StringBuilder();
    String prefixRun = "";
    boolean sawInexact = false;
    for (RegexNode child : children) {
      Analysis a = analyze(child);
      if (a.exact() != null && !sawInexact) {
        pre.append(a.exact());
      } else if (!sawInexact) {
        pre.append(a.prefixRun());
        sawInexact = true;
      }
    }
    prefixRun = pre.toString();

    // suffixRun: symmetric from the right
    StringBuilder suf = new StringBuilder();
    boolean sawInexactSuffix = false;
    for (int i = children.size() - 1; i >= 0; i--) {
      Analysis a = analyze(children.get(i));
      if (a.exact() != null && !sawInexactSuffix) {
        suf.insert(0, a.exact());
      } else if (!sawInexactSuffix) {
        suf.insert(0, a.suffixRun());
        sawInexactSuffix = true;
      }
    }
    String suffixRun = suf.toString();

    // exact: only when every child has exact content
    String exact = null;
    StringBuilder ex = new StringBuilder();
    boolean allExact = true;
    for (RegexNode child : children) {
      Analysis a = analyze(child);
      if (a.exact() == null) {
        allExact = false;
        break;
      }
      ex.append(a.exact());
    }
    if (allExact) {
      exact = ex.toString();
    }

    // Boundary merges over maximal chains of children with exact content:
    // 1. the chain text itself — a contiguous substring of every match (each child
    //    contributes its exact content, in order);
    // 2. left extension by the preceding child's suffixRun (it ends at that child's
    //    match end, immediately before the chain);
    // 3. right extension by the following child's prefixRun (it starts at that
    //    child's match start, immediately after the chain).
    // Zero-width exacts (anchors, epsilon) keep chains connected through them.
    for (int i = 0; i < children.size(); i++) {
      if (analyze(children.get(i)).exact() == null) {
        continue; // not a chain start
      }
      StringBuilder chain = new StringBuilder();
      int j = i;
      while (j < children.size() && analyze(children.get(j)).exact() != null) {
        chain.append(analyze(children.get(j)).exact());
        j++;
      }
      addFact(facts, chain.toString());
      String leftSuf = i > 0 ? analyze(children.get(i - 1)).suffixRun() : "";
      String rightPre = j < children.size() ? analyze(children.get(j)).prefixRun() : "";
      if (!leftSuf.isEmpty()) {
        addFact(facts, leftSuf + chain);
        if (!rightPre.isEmpty()) {
          addFact(facts, leftSuf + chain + rightPre);
        }
      }
      if (!rightPre.isEmpty()) {
        addFact(facts, chain + rightPre);
      }
      i = j; // skip past the chain
    }

    addFact(facts, prefixRun);
    addFact(facts, suffixRun);
    if (exact != null) {
      addFact(facts, exact);
    }
    return new Analysis(facts, exact, prefixRun, suffixRun);
  }

  private static Analysis analyzeAlternation(List<RegexNode> branches) {
    if (branches.isEmpty()) {
      return Analysis.none();
    }
    List<Analysis> analyses = new ArrayList<>();
    for (RegexNode b : branches) {
      analyses.add(analyze(b));
    }
    // facts: intersection — each fact must hold in EVERY branch
    Set<String> facts = new HashSet<>(analyses.get(0).facts());
    for (int i = 1; i < analyses.size() && !facts.isEmpty(); i++) {
      facts.retainAll(analyses.get(i).facts());
    }
    String prefixRun = analyses.get(0).prefixRun();
    String suffixRun = analyses.get(0).suffixRun();
    for (int i = 1; i < analyses.size(); i++) {
      prefixRun = commonPrefix(prefixRun, analyses.get(i).prefixRun());
      suffixRun = commonSuffix(suffixRun, analyses.get(i).suffixRun());
    }
    String exact = analyses.get(0).exact();
    for (int i = 1; i < analyses.size() && exact != null; i++) {
      if (!exact.equals(analyses.get(i).exact())) {
        exact = null;
      }
    }
    addFact(facts, prefixRun);
    addFact(facts, suffixRun);
    if (exact != null) {
      addFact(facts, exact);
    }
    return new Analysis(facts, exact, prefixRun, suffixRun);
  }

  private static void addFact(Set<String> facts, String fact) {
    // Only multi-char facts are stored: consumers require >= 2 chars, and admitting 1-char
    // facts would flood the set (one per literal child) and evict the long chain facts that
    // actually matter. 1-char prefix/suffix runs still flow through the merge machinery via
    // the prefixRun/suffixRun/exact fields; they just never become standalone facts.
    if (fact == null || fact.length() < MIN_USABLE_LEN || fact.length() > MAX_FACT_LEN) {
      return;
    }
    if (facts.size() < MAX_FACTS_PER_NODE || facts.contains(fact)) {
      facts.add(fact);
      return;
    }
    // Set full: evict the shortest stored fact if this one is longer — long facts dominate
    // rejection selectivity, so they must never lose to a cap.
    String shortest = null;
    for (String f : facts) {
      if (shortest == null || f.length() < shortest.length()) {
        shortest = f;
      }
    }
    if (shortest != null && fact.length() > shortest.length()) {
      facts.remove(shortest);
      facts.add(fact);
    }
  }

  private static boolean containsCasedLetter(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
        return true;
      }
    }
    return false;
  }

  private static String commonPrefix(String a, String b) {
    int n = Math.min(a.length(), b.length());
    int i = 0;
    while (i < n && a.charAt(i) == b.charAt(i)) {
      i++;
    }
    return a.substring(0, i);
  }

  private static String commonSuffix(String a, String b) {
    int n = Math.min(a.length(), b.length());
    int i = 0;
    while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
      i++;
    }
    return a.substring(a.length() - i);
  }
}
