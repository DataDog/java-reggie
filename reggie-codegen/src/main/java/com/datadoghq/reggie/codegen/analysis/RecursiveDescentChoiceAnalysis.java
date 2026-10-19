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

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder.GroupFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether a pattern is "one-way": every choice the recursive-descent generator commits to
 * locally (first matching alternative, greedy run of a quantifier) is also the choice of the first
 * successful backtracking path. Such a pattern gives the same matches and group spans on the
 * RecursiveDescentBytecodeGenerator as on the backtracker.
 *
 * <p>A follow set is the set of symbols (chars and end of input) that may appear at a position on a
 * successful path, or {@code accept}: the continuation matches the empty string unconditionally. At
 * a choice point with follow F that is not accept, the symbols under which an earlier option
 * succeeds locally must be disjoint from the symbols a later option can see on a successful path.
 */
public final class RecursiveDescentChoiceAnalysis {

  // Upper bound on the call-context fixpoint iterations; reaching it rejects the pattern
  private static final int MAX_CONTEXT_ITERATIONS = 64;

  private static final CharSet LINE_TERMINATORS = CharSet.ANY_EXCEPT_NEWLINE.complement();

  private RecursiveDescentChoiceAnalysis() {}

  /** True when the RD generator's local choices agree with backtracking on every input. */
  public static boolean isOneWay(RegexNode ast) {
    List<GroupNode> targets = ast != null ? passesGlobalRules(ast) : null;
    if (targets == null) {
      return false;
    }
    // matches()/match() see end of input after the pattern; find()/findMatch() accept anywhere
    return new ChoiceCheck(ast, targets).passes(Follow.EOF)
        && new ChoiceCheck(ast, targets).passes(Follow.ACCEPT);
  }

  /** Symbol set at a position: chars, end of input, or accept (any position succeeds). */
  private static final class Follow {
    static final Follow EMPTY = new Follow(CharSet.empty(), false, false);
    static final Follow ALL = new Follow(CharSet.ANY, true, false);
    static final Follow EOF = new Follow(CharSet.empty(), true, false);
    static final Follow ACCEPT = new Follow(CharSet.ANY, true, true);

    final CharSet chars;
    final boolean eof;
    final boolean accept;

    Follow(CharSet chars, boolean eof, boolean accept) {
      this.chars = chars;
      this.eof = eof;
      this.accept = accept;
    }

    /** Symbols of either side; accept on either side succeeds anywhere. */
    Follow union(Follow other) {
      if (accept || other.accept) {
        return ACCEPT;
      }
      return new Follow(chars.union(other.chars), eof || other.eof, false);
    }

    /**
     * Context merge: accept only when both sides are accept. Otherwise an accept side counts as
     * every char plus end of input, since a later anchor or conditional can turn it into a real
     * constraint.
     */
    Follow merge(Follow other) {
      if (accept && other.accept) {
        return ACCEPT;
      }
      Follow a = withoutAccept();
      Follow b = other.withoutAccept();
      return new Follow(a.chars.union(b.chars), a.eof || b.eof, false);
    }

    Follow withoutAccept() {
      return accept ? ALL : this;
    }

    boolean intersects(Follow other) {
      return (eof && other.eof) || chars.intersects(other.chars);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Follow)) {
        return false;
      }
      Follow f = (Follow) o;
      return eof == f.eof && accept == f.accept && chars.equals(f.chars);
    }

    @Override
    public int hashCode() {
      return Objects.hash(chars, eof, accept);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Global rules
  // ---------------------------------------------------------------------------------------------

  /**
   * Rules independent of the choice model: constructs the RD generator gets wrong (lookaround,
   * \b/\B/\K, backreferences, branch reset or nullable capturing group inside a repeated body),
   * recursion, and calls to a group that does not exist. Returns the call targets, or null when a
   * rule fails.
   */
  private static List<GroupNode> passesGlobalRules(RegexNode ast) {
    GlobalRules rules = new GlobalRules(ast);
    ast.accept(rules);
    if (!rules.ok) {
      return null;
    }
    for (GroupNode target : rules.targets) {
      if (reachesItself(ast, target, target, new ArrayList<>())) {
        return null;
      }
    }
    // C2 runs after the cycle check: isNullable follows calls into their targets
    for (GroupNode group : rules.variableCaptures) {
      if (isNullable(ast, group.child)) {
        return null;
      }
    }
    return rules.targets;
  }

  /** True when a call inside {@code from}'s body reaches {@code target} again. */
  private static boolean reachesItself(
      RegexNode ast, GroupNode target, GroupNode from, List<GroupNode> visited) {
    List<SubroutineNode> calls = new ArrayList<>();
    collectCalls(from.child, calls);
    for (SubroutineNode call : calls) {
      GroupNode next = callTarget(ast, call);
      if (next == target) {
        return true;
      }
      if (next != null && !containsIdentity(visited, next)) {
        visited.add(next);
        if (reachesItself(ast, target, next, visited)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsIdentity(List<GroupNode> list, GroupNode node) {
    for (GroupNode g : list) {
      if (g == node) {
        return true;
      }
    }
    return false;
  }

  /** True for a call to the whole pattern: (?R) or (?0). */
  private static boolean isRootCall(SubroutineNode node) {
    return node.name == null && node.groupNumber <= 0;
  }

  /** The group a call invokes, resolved as ThompsonBuilder and the RD generator resolve it. */
  private static GroupNode callTarget(RegexNode ast, SubroutineNode node) {
    GroupNode group = ast.accept(new GroupFinder(node.groupNumber, node.name));
    if (group != null && node.name != null) {
      // A name stands for its number: in a branch reset (?&n) calls the first group numbered as n
      group = ast.accept(new GroupFinder(group.groupNumber, null));
    }
    return group;
  }

  private static final class GlobalRules implements RegexVisitor<Void> {
    private final RegexNode ast;
    final List<GroupNode> targets = new ArrayList<>();
    // Capturing groups inside a variable quantifier body, for C2
    final List<GroupNode> variableCaptures = new ArrayList<>();
    boolean ok = true;
    // Nesting depth of quantifiers with min < max
    private int variableDepth;
    // Nesting depth of quantifiers with max > 1 or unbounded
    private int repeatDepth;

    GlobalRules(RegexNode ast) {
      this.ast = ast;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      for (RegexNode child : node.children) {
        child.accept(this);
      }
      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      for (RegexNode alt : node.alternatives) {
        alt.accept(this);
      }
      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      boolean variable = isVariable(node);
      boolean repeat = node.max < 0 || node.max > 1;
      if (variable) variableDepth++;
      if (repeat) repeatDepth++;
      node.child.accept(this);
      if (variable) variableDepth--;
      if (repeat) repeatDepth--;
      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      // C2: a nullable capturing group in a variable body can complete, fail later and leave
      // stale spans for the exit path
      if (node.capturing && variableDepth > 0) {
        variableCaptures.add(node);
      }
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      switch (node.type) {
        case WORD_BOUNDARY:
        case NON_WORD_BOUNDARY:
        case RESET_MATCH:
          ok = false;
          break;
        default:
          break;
      }
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      ok = false; // C4
      return null;
    }

    @Override
    public Void visitAssertion(AssertionNode node) {
      ok = false;
      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      if (isRootCall(node)) {
        ok = false; // recursion
        return null;
      }
      GroupNode target = callTarget(ast, node);
      if (target == null) {
        ok = false;
      } else if (!containsIdentity(targets, target)) {
        targets.add(target);
      }
      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      node.thenBranch.accept(this);
      if (node.elseBranch != null) {
        node.elseBranch.accept(this);
      }
      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      // C3: visitBranchReset clears its groups before every alternative of a new iteration
      if (repeatDepth > 0) {
        ok = false;
      }
      for (RegexNode alt : node.alternatives) {
        alt.accept(this);
      }
      return null;
    }
  }

  private static boolean isVariable(QuantifierNode node) {
    return node.max < 0 || node.min < node.max;
  }

  /** Every call in the subtree, not following calls into their targets. */
  private static void collectCalls(RegexNode node, List<SubroutineNode> out) {
    node.accept(
        new RegexVisitor<Void>() {
          @Override
          public Void visitLiteral(LiteralNode n) {
            return null;
          }

          @Override
          public Void visitCharClass(CharClassNode n) {
            return null;
          }

          @Override
          public Void visitConcat(ConcatNode n) {
            for (RegexNode c : n.children) {
              c.accept(this);
            }
            return null;
          }

          @Override
          public Void visitAlternation(AlternationNode n) {
            for (RegexNode c : n.alternatives) {
              c.accept(this);
            }
            return null;
          }

          @Override
          public Void visitQuantifier(QuantifierNode n) {
            return n.child.accept(this);
          }

          @Override
          public Void visitGroup(GroupNode n) {
            return n.child.accept(this);
          }

          @Override
          public Void visitAnchor(AnchorNode n) {
            return null;
          }

          @Override
          public Void visitBackreference(BackreferenceNode n) {
            return null;
          }

          @Override
          public Void visitAssertion(AssertionNode n) {
            return n.subPattern.accept(this);
          }

          @Override
          public Void visitSubroutine(SubroutineNode n) {
            out.add(n);
            return null;
          }

          @Override
          public Void visitConditional(ConditionalNode n) {
            n.thenBranch.accept(this);
            return n.elseBranch != null ? n.elseBranch.accept(this) : null;
          }

          @Override
          public Void visitBranchReset(BranchResetNode n) {
            for (RegexNode c : n.alternatives) {
              c.accept(this);
            }
            return null;
          }
        });
  }

  /** True when the subtree has a capturing group or a branch reset, not following calls. */
  private static boolean writesGroups(RegexNode node) {
    return node.accept(
        new RegexVisitor<Boolean>() {
          @Override
          public Boolean visitLiteral(LiteralNode n) {
            return false;
          }

          @Override
          public Boolean visitCharClass(CharClassNode n) {
            return false;
          }

          @Override
          public Boolean visitConcat(ConcatNode n) {
            for (RegexNode c : n.children) {
              if (c.accept(this)) return true;
            }
            return false;
          }

          @Override
          public Boolean visitAlternation(AlternationNode n) {
            for (RegexNode c : n.alternatives) {
              if (c.accept(this)) return true;
            }
            return false;
          }

          @Override
          public Boolean visitQuantifier(QuantifierNode n) {
            return n.child.accept(this);
          }

          @Override
          public Boolean visitGroup(GroupNode n) {
            return n.capturing || n.child.accept(this);
          }

          @Override
          public Boolean visitAnchor(AnchorNode n) {
            return false;
          }

          @Override
          public Boolean visitBackreference(BackreferenceNode n) {
            return false;
          }

          @Override
          public Boolean visitAssertion(AssertionNode n) {
            return n.subPattern.accept(this);
          }

          @Override
          public Boolean visitSubroutine(SubroutineNode n) {
            return false; // the call restores the groups
          }

          @Override
          public Boolean visitConditional(ConditionalNode n) {
            return n.thenBranch.accept(this) || (n.elseBranch != null && n.elseBranch.accept(this));
          }

          @Override
          public Boolean visitBranchReset(BranchResetNode n) {
            return true;
          }
        });
  }

  /** True when the subtree can match the empty string. Call targets must be acyclic. */
  private static boolean isNullable(RegexNode ast, RegexNode node) {
    return node.accept(
        new RegexVisitor<Boolean>() {
          // Per call target: keeps repeated calls of the same target linear
          private final Map<GroupNode, Boolean> targetMemo = new IdentityHashMap<>();

          @Override
          public Boolean visitLiteral(LiteralNode n) {
            return n instanceof EpsilonNode;
          }

          @Override
          public Boolean visitCharClass(CharClassNode n) {
            return false;
          }

          @Override
          public Boolean visitConcat(ConcatNode n) {
            for (RegexNode c : n.children) {
              if (!c.accept(this)) return false;
            }
            return true;
          }

          @Override
          public Boolean visitAlternation(AlternationNode n) {
            return anyNullable(n.alternatives);
          }

          @Override
          public Boolean visitQuantifier(QuantifierNode n) {
            return n.min == 0 || n.child.accept(this);
          }

          @Override
          public Boolean visitGroup(GroupNode n) {
            return n.child.accept(this);
          }

          @Override
          public Boolean visitAnchor(AnchorNode n) {
            return true;
          }

          @Override
          public Boolean visitBackreference(BackreferenceNode n) {
            return true;
          }

          @Override
          public Boolean visitAssertion(AssertionNode n) {
            return true;
          }

          @Override
          public Boolean visitSubroutine(SubroutineNode n) {
            // Root calls and calls without a target are rejected by GlobalRules before any
            // isNullable call, so a null target is not expected here; treat it as nullable
            GroupNode target = isRootCall(n) ? null : callTarget(ast, n);
            if (target == null) {
              return true;
            }
            Boolean nullable = targetMemo.get(target);
            if (nullable == null) {
              nullable = target.child.accept(this);
              targetMemo.put(target, nullable);
            }
            return nullable;
          }

          @Override
          public Boolean visitConditional(ConditionalNode n) {
            return n.elseBranch == null || n.thenBranch.accept(this) || n.elseBranch.accept(this);
          }

          @Override
          public Boolean visitBranchReset(BranchResetNode n) {
            return anyNullable(n.alternatives);
          }

          private boolean anyNullable(List<RegexNode> alternatives) {
            for (RegexNode c : alternatives) {
              if (c.accept(this)) return true;
            }
            return alternatives.isEmpty();
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Choice check
  // ---------------------------------------------------------------------------------------------

  /**
   * Computes S(n, F), the symbols that can appear at n's position on a successful path through n
   * and then F, and checks every choice point on the way. Call targets are checked at their own
   * position under ctx(T): the merge of every call-site follow and the in-place follow, iterated to
   * a fixpoint.
   */
  private static final class ChoiceCheck implements RegexVisitor<Follow> {
    private final RegexNode ast;
    private final List<GroupNode> targets;
    // Follows recorded for each call target in the previous / current walk
    private Map<GroupNode, Follow> previousContexts = new IdentityHashMap<>();
    private Map<GroupNode, Follow> contexts = new IdentityHashMap<>();
    // S(n, F) computed without checking: independent of the contexts, so kept across iterations
    private final Map<RegexNode, Map<Follow, Follow>> sideMemo = new IdentityHashMap<>();
    private Follow follow;
    // Main walk: checks choice points and records contexts. Side computations only compute S.
    private boolean main;
    private boolean ok;

    ChoiceCheck(RegexNode ast, List<GroupNode> targets) {
      this.ast = ast;
      this.targets = targets;
    }

    boolean passes(Follow top) {
      for (int i = 0; i < MAX_CONTEXT_ITERATIONS; i++) {
        ok = true;
        main = true;
        contexts = new IdentityHashMap<>();
        walk(ast, top);
        // Contexts only grow, so a failed walk stays failed in later walks
        if (!ok || sameContexts()) {
          return ok;
        }
        previousContexts = contexts;
      }
      return false;
    }

    // IdentityHashMap.equals compares the values by reference too; the Follows need equals()
    private boolean sameContexts() {
      if (contexts.size() != previousContexts.size()) {
        return false;
      }
      for (Map.Entry<GroupNode, Follow> e : contexts.entrySet()) {
        if (!e.getValue().equals(previousContexts.get(e.getKey()))) {
          return false;
        }
      }
      return true;
    }

    private Follow walk(RegexNode node, Follow f) {
      Follow saved = follow;
      follow = f;
      Follow result = node.accept(this);
      follow = saved;
      return result;
    }

    /**
     * S(node, f) without checking or recording. Without accept, every step is a union of fixed
     * symbols and f, so S(node, f) = S(node, EMPTY), plus f when f passes through node (seen as end
     * of input in S(node, EOF): with the global rules met, only f contributes it). Memoizing those
     * two keeps the number of walks per node constant; keyed by f alone, distinct follows grow
     * exponentially with the call depth.
     */
    private Follow side(RegexNode node, Follow f) {
      if (f.accept) {
        return sideWalk(node, f);
      }
      Follow fixed = sideWalk(node, Follow.EMPTY);
      return sideWalk(node, Follow.EOF).eof ? fixed.union(f) : fixed;
    }

    private Follow sideWalk(RegexNode node, Follow f) {
      Map<Follow, Follow> memo = sideMemo.computeIfAbsent(node, n -> new HashMap<>());
      Follow result = memo.get(f);
      if (result == null) {
        boolean savedMain = main;
        main = false;
        result = walk(node, f);
        main = savedMain;
        memo.put(f, result);
      }
      return result;
    }

    /** S(node, ALL): the symbols under which node can succeed locally. */
    private Follow local(RegexNode node) {
      return side(node, Follow.ALL);
    }

    private Follow sEmpty(RegexNode node) {
      return side(node, Follow.EMPTY);
    }

    private void record(GroupNode target, Follow f) {
      Follow old = contexts.get(target);
      contexts.put(target, old == null ? f : old.merge(f));
    }

    @Override
    public Follow visitLiteral(LiteralNode node) {
      // EpsilonNode extends LiteralNode: test it first
      if (node instanceof EpsilonNode) {
        return follow;
      }
      return new Follow(CharSet.of(node.ch), false, false);
    }

    @Override
    public Follow visitCharClass(CharClassNode node) {
      return new Follow(node.negated ? node.chars.complement() : node.chars, false, false);
    }

    @Override
    public Follow visitConcat(ConcatNode node) {
      Follow f = follow;
      for (int i = node.children.size() - 1; i >= 0; i--) {
        f = walk(node.children.get(i), f);
      }
      return f;
    }

    @Override
    public Follow visitAlternation(AlternationNode node) {
      return choice(node.alternatives);
    }

    @Override
    public Follow visitBranchReset(BranchResetNode node) {
      return choice(node.alternatives);
    }

    /** Options in priority order: an earlier option's local success must exclude later options. */
    private Follow choice(List<RegexNode> options) {
      Follow f = follow;
      Follow result = Follow.EMPTY;
      List<Follow> sets = new ArrayList<>(options.size());
      for (RegexNode option : options) {
        Follow s = walk(option, f);
        sets.add(s);
        result = result.union(s);
      }
      if (main && !f.accept) {
        // Intersecting the union of the earlier options' local sets is the same as intersecting
        // each of them (no accept on either side)
        Follow earlier = local(options.get(0));
        for (int j = 1; j < options.size(); j++) {
          if (earlier.intersects(sets.get(j))) {
            ok = false;
          }
          earlier = earlier.union(local(options.get(j)));
        }
      }
      return result;
    }

    @Override
    public Follow visitQuantifier(QuantifierNode node) {
      Follow f = follow;
      if (node.max == 0) {
        // Never entered here, but a call can reach the body
        if (main) {
          walk(node.child, f);
        }
        return f;
      }
      if (main) {
        boolean variable = isVariable(node);
        // C1: a failed iteration leaves its group writes behind, so accept gives no freedom
        Follow g = variable && writesGroups(node.child) ? f.withoutAccept() : f;
        if (variable) {
          if (!node.greedy && !node.possessive) {
            ok = false; // lazy
          } else if (isNullable(ast, node.child)) {
            ok = false;
          } else if (!g.accept && local(node.child).intersects(g)) {
            ok = false; // continue vs stop
          }
        }
        Follow bodyFollow = node.max == 1 ? g : g.merge(side(node.child, g));
        walk(node.child, bodyFollow);
      }
      Follow s = side(node.child, f.merge(sEmpty(node.child)));
      return node.min == 0 ? s.union(f) : s;
    }

    @Override
    public Follow visitGroup(GroupNode node) {
      Follow f = follow;
      if (main && containsIdentity(targets, node)) {
        record(node, f);
        Follow previous = previousContexts.get(node);
        walk(node.child, previous == null ? f : previous.merge(f));
        return side(node.child, f);
      }
      return walk(node.child, f);
    }

    @Override
    public Follow visitAnchor(AnchorNode node) {
      Follow f = follow;
      if (!f.accept) {
        return f; // keeps all of F: the artificial-end lemma relies on this over-approximation
      }
      switch (node.type) {
        case END:
        case STRING_END:
          return new Follow(LINE_TERMINATORS, true, false);
        case STRING_END_ABSOLUTE:
          return Follow.EOF;
        default:
          return Follow.ALL;
      }
    }

    @Override
    public Follow visitBackreference(BackreferenceNode node) {
      return Follow.ALL; // rejected by C4
    }

    @Override
    public Follow visitAssertion(AssertionNode node) {
      return Follow.ALL; // rejected by the global rules
    }

    @Override
    public Follow visitSubroutine(SubroutineNode node) {
      Follow f = follow;
      GroupNode target = callTarget(ast, node);
      if (main) {
        record(target, f);
      }
      return side(target.child, f);
    }

    @Override
    public Follow visitConditional(ConditionalNode node) {
      Follow f = follow;
      Follow then = walk(node.thenBranch, f);
      Follow otherwise = node.elseBranch != null ? walk(node.elseBranch, f) : f;
      // The branch depends on the group state, so accept passes only when both branches pass it
      return then.merge(otherwise);
    }
  }
}
