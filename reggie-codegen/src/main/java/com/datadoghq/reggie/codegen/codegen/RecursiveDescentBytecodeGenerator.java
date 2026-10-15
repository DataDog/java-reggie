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
package com.datadoghq.reggie.codegen.codegen;

import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.invokeMultilineEnd;
import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.invokeMultilineStart;
import static org.objectweb.asm.Opcodes.*;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder.GroupFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates specialized recursive descent parser bytecode for context-free patterns.
 *
 * <h3>Pattern Types (Beyond Regular Languages)</h3>
 *
 * <ul>
 *   <li><b>Subroutines</b>: {@code (?R)}, {@code (?1)}, {@code (?&name)}
 *   <li><b>Conditionals</b>: {@code (?(1)yes|no)}
 *   <li><b>Branch reset</b>: {@code (?|alt1|alt2)}
 * </ul>
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern a(?R)?b (recursive balanced a...b)
 * // Generated methods: parse_0() for the entire pattern
 *
 * int parse_0(String input, int pos, int[] groups, int depth) {
 *     if (depth > MAX_DEPTH) return -1;  // Recursion limit
 *
 *     // Match 'a'
 *     if (pos >= input.length() || input.charAt(pos) != 'a') return -1;
 *     pos++;
 *
 *     // Optional recursive call: (?R)?
 *     int savedPos = pos;
 *     int newPos = parse_0(input, pos, groups, depth + 1);  // Recursive call
 *     if (newPos >= 0) {
 *         pos = newPos;
 *     } else {
 *         pos = savedPos;  // Backtrack (optional failed)
 *     }
 *
 *     // Match 'b'
 *     if (pos >= input.length() || input.charAt(pos) != 'b') return -1;
 *     pos++;
 *
 *     return pos;
 * }
 *
 * boolean matches(String input) {
 *     int[] groups = new int[groupCount * 2];
 *     int result = parse_0(input, 0, groups, 0);
 *     return result == input.length();
 * }
 * }</pre>
 *
 * <h3>Implementation Phases</h3>
 *
 * <ul>
 *   <li>Phase 3: Basic framework (method generation infrastructure)
 *   <li>Phase 4: Subroutine implementation with recursion depth and memoization
 *   <li>Phase 5-7: Conditionals, branch reset, integration
 * </ul>
 */
public class RecursiveDescentBytecodeGenerator {

  // Maximum recursion depth before throwing StackOverflowError
  private static final int MAX_RECURSION_DEPTH = 100;

  // Parse-method calls a non-one-way attempt may make per character of its region, on top of
  // BacktrackConfig.MAX_RD_STEPS: linear matches make a few calls per character and stay inside it
  private static final int RD_STEPS_PER_CHAR = 16;

  // Children per range helper when a concat or alternation is split: a call or an inline leaf
  // takes at most about 57 bytes, which keeps each helper under HotSpot's 8000-byte
  // HugeMethodLimit (and the 64 KB limit)
  private static final int RANGE_CHUNK = 128;

  // Capture groups copied out of the packed groups array by straight-line code; above, a loop
  private static final int UNROLLED_GROUP_COPY_MAX = 16;

  // First-char sets with more ranges skip the findBoundsFrom prefilter: its range bisection
  // would push findBoundsFrom over the JIT size limit
  private static final int FIRST_CHAR_PREFILTER_MAX_RANGES = 128;

  // Nested backtracking levels generated in one method; deeper levels get their own method
  private static final int NESTED_LEVELS_PER_METHOD = 8;
  // Longer child runs in a backtracking concat call range helpers: one method can hold many runs
  // (prefix, suffix, nested levels, both optional-group paths), so each inline run stays small
  private static final int INLINE_CHILD_RUN_MAX = 8;
  // Longer runs of consecutive branch-reset groups are cleared with one Arrays.fill call
  private static final int UNROLLED_RESET_RUN_MAX = 4;

  // Char-class ranges up to which a class leaf is checked inline: the range bisection grows with
  // the
  // number of ranges, and an inline leaf must cost about as much as a call
  private static final int INLINE_CLASS_RANGES = 2;

  private final RegexNode ast;
  private final NFA nfa;
  private final int groupCount;
  private final Map<RegexNode, String> nodeToMethodName;
  private final Map<SubroutineNode, Integer> subroutineNodeIds;
  // True when the pattern is one-way (RecursiveDescentChoiceAnalysis): no backtrack caps
  private final boolean oneWay;
  // Non-one-way root concat followed by \z: matches(), match() and matchInto() parse it, so the
  // end-of-input check runs inside the concat's give-back. Null otherwise.
  private final ConcatNode wholeRoot;
  private int methodCounter = 0;
  private int nodeIdCounter = 0;

  public RecursiveDescentBytecodeGenerator(RegexNode ast, NFA nfa) {
    this(ast, nfa, false);
  }

  public RecursiveDescentBytecodeGenerator(RegexNode ast, NFA nfa, boolean oneWay) {
    this.ast = ast;
    this.oneWay = oneWay;
    this.wholeRoot = !oneWay && ast instanceof ConcatNode ? endAnchored((ConcatNode) ast) : null;
    this.nfa = nfa;
    this.groupCount = nfa != null ? nfa.getGroupCount() : countGroups(ast);
    this.nodeToMethodName = new HashMap<>();
    this.subroutineNodeIds = new HashMap<>();
  }

  private static ConcatNode endAnchored(ConcatNode root) {
    List<RegexNode> children = new ArrayList<>(root.children);
    children.add(new AnchorNode(AnchorNode.Type.STRING_END_ABSOLUTE));
    return new ConcatNode(children);
  }

  /** Count the number of capturing groups in the AST. */
  private int countGroups(RegexNode node) {
    if (node == null) {
      return 0;
    }
    return node.accept(
        new RegexVisitor<Integer>() {
          @Override
          public Integer visitLiteral(LiteralNode node) {
            return 0;
          }

          @Override
          public Integer visitCharClass(CharClassNode node) {
            return 0;
          }

          @Override
          public Integer visitGroup(GroupNode node) {
            int count = node.groupNumber > 0 ? 1 : 0;
            return count + node.child.accept(this);
          }

          @Override
          public Integer visitQuantifier(QuantifierNode node) {
            return node.child.accept(this);
          }

          @Override
          public Integer visitAlternation(AlternationNode node) {
            int total = 0;
            for (RegexNode child : node.alternatives) {
              total += child.accept(this);
            }
            return total;
          }

          @Override
          public Integer visitConcat(ConcatNode node) {
            int total = 0;
            for (RegexNode child : node.children) {
              total += child.accept(this);
            }
            return total;
          }

          @Override
          public Integer visitAnchor(AnchorNode node) {
            return 0;
          }

          @Override
          public Integer visitAssertion(AssertionNode node) {
            return node.subPattern != null ? node.subPattern.accept(this) : 0;
          }

          @Override
          public Integer visitBackreference(BackreferenceNode node) {
            return 0;
          }

          @Override
          public Integer visitSubroutine(SubroutineNode node) {
            return 0;
          }

          @Override
          public Integer visitConditional(ConditionalNode node) {
            int total = node.thenBranch.accept(this);
            if (node.elseBranch != null) {
              total += node.elseBranch.accept(this);
            }
            return total;
          }

          @Override
          public Integer visitBranchReset(BranchResetNode node) {
            int max = 0;
            for (RegexNode alt : node.alternatives) {
              int count = alt.accept(this);
              if (count > max) {
                max = count;
              }
            }
            return max;
          }
        });
  }

  /**
   * Collect all group numbers that appear in a branch reset node's alternatives. These are the
   * groups that should be reset before trying each alternative.
   *
   * @param node The branch reset node to analyze
   * @return Set of group numbers found in the branch reset alternatives
   */
  private Set<Integer> collectBranchResetGroupNumbers(BranchResetNode node) {
    Set<Integer> groupNumbers = new HashSet<>();
    for (RegexNode alt : node.alternatives) {
      collectGroupNumbersFromNode(alt, groupNumbers);
    }
    return groupNumbers;
  }

  /**
   * Recursively collect group numbers from a node tree.
   *
   * @param node The node to analyze
   * @param result The set to populate with discovered group numbers
   */
  private void collectGroupNumbersFromNode(RegexNode node, Set<Integer> result) {
    if (node == null) {
      return;
    }

    node.accept(
        new RegexVisitor<Void>() {
          @Override
          public Void visitLiteral(LiteralNode node) {
            return null;
          }

          @Override
          public Void visitCharClass(CharClassNode node) {
            return null;
          }

          @Override
          public Void visitGroup(GroupNode node) {
            if (node.groupNumber > 0) {
              result.add(node.groupNumber);
            }
            node.child.accept(this);
            return null;
          }

          @Override
          public Void visitQuantifier(QuantifierNode node) {
            node.child.accept(this);
            return null;
          }

          @Override
          public Void visitAlternation(AlternationNode node) {
            for (RegexNode child : node.alternatives) {
              child.accept(this);
            }
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
          public Void visitAnchor(AnchorNode node) {
            return null;
          }

          @Override
          public Void visitAssertion(AssertionNode node) {
            if (node.subPattern != null) {
              node.subPattern.accept(this);
            }
            return null;
          }

          @Override
          public Void visitBackreference(BackreferenceNode node) {
            return null;
          }

          @Override
          public Void visitSubroutine(SubroutineNode node) {
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
            // Don't recurse into nested branch resets
            return null;
          }
        });
  }

  /** True for a call to the whole pattern: (?R) or (?0). */
  /**
   * Children per range-helper call when {@code size} children are split: {@code chunk} times a
   * power of RANGE_CHUNK, the smallest that leaves at most RANGE_CHUNK calls in one method. The
   * result is below {@code size} unless it is {@code chunk}.
   */
  private static int rangeSpan(int size, int chunk) {
    long span = chunk;
    while (size > span * RANGE_CHUNK) {
      span *= RANGE_CHUNK;
    }
    return (int) span;
  }

  /**
   * A single-char literal or a char class with at most INLINE_CLASS_RANGES ranges: concats and
   * alternations check it inline instead of calling a parser method, so a pattern does not need one
   * method (and its constant-pool entries) per character.
   */
  private static boolean isInlineLeaf(RegexNode node) {
    if (node instanceof EpsilonNode) {
      return false;
    }
    if (node instanceof LiteralNode) {
      return true;
    }
    if (!(node instanceof CharClassNode)) {
      return false;
    }
    CharClassNode cc = (CharClassNode) node;
    return (cc.negated ? cc.chars.complement() : cc.chars).getRanges().size()
        <= INLINE_CLASS_RANGES;
  }

  private static boolean isRootCall(SubroutineNode node) {
    return node.name == null && node.groupNumber <= 0;
  }

  /**
   * Body of the group a non-root call invokes: the first capturing group in pattern order with the
   * call's number, or with the named group's number when the call is named (as ThompsonBuilder
   * resolves calls). Null when no such group exists.
   */
  private RegexNode callTarget(SubroutineNode node) {
    GroupNode group = ast.accept(new GroupFinder(node.groupNumber, node.name));
    if (group != null && node.name != null) {
      // A name stands for its number: in a branch reset (?&n) calls the first group numbered as n
      group = ast.accept(new GroupFinder(group.groupNumber, null));
    }
    return group != null ? group.child : null;
  }

  /**
   * True when parsing the node leaves the groups array as it found it: no capturing group anywhere
   * inside (lookaround bodies included, since Java captures inside lookaheads). Subroutine calls
   * qualify because visitSubroutine restores the groups after the call; backreferences only read
   * them. Conditionals and branch resets are refused conservatively. Given unchanged groups, such a
   * node's result depends only on (pos, end, groups, depth), which is what the fast give-back path
   * and the skipped group snapshots rely on.
   */
  private static boolean isGroupsNeutral(RegexNode node) {
    return node == null || node.accept(GroupsNeutral.INSTANCE);
  }

  /** {@link #isGroupsNeutral} for every concat child from {@code from} on. */
  private static boolean isGroupsNeutralFrom(ConcatNode node, int from) {
    for (int i = from; i < node.children.size(); i++) {
      if (!isGroupsNeutral(node.children.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static final class GroupsNeutral implements RegexVisitor<Boolean> {
    static final GroupsNeutral INSTANCE = new GroupsNeutral();

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return true;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return true;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return isGroupsNeutralFrom(node, 0);
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      for (RegexNode alt : node.alternatives) {
        if (!isGroupsNeutral(alt)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return isGroupsNeutral(node.child);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.groupNumber <= 0 && isGroupsNeutral(node.child);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return true;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return true;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return isGroupsNeutral(node.subPattern);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return true;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      return false;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return false;
    }
  }

  /**
   * True when every match must start at position 0: the leftmost mandatory spine of the AST begins
   * with {\@code \A}, or with {\@code ^} in non-multiline mode. Conservative — optional
   * quantifiers, alternations and consuming nodes on the spine stop the walk, so this can only miss
   * opportunities, never claim a false anchor.
   */
  private static boolean isStartAnchoredAtZero(RegexNode node) {
    while (true) {
      if (node instanceof ConcatNode c && !c.children.isEmpty()) {
        node = c.children.get(0);
        continue;
      }
      if (node instanceof GroupNode g) {
        node = g.child;
        continue;
      }
      if (node instanceof QuantifierNode q) {
        if (q.min >= 1) {
          node = q.child;
          continue;
        }
        return false; // optional content on the spine: the anchor is not guaranteed
      }
      if (node instanceof AnchorNode a) {
        return a.type == AnchorNode.Type.STRING_START
            || (a.type == AnchorNode.Type.START && !a.multiline);
      }
      return false; // consuming node, alternation, backref, assertion: not anchored-at-zero
    }
  }

  /**
   * Compute the set of characters that can start a match for this pattern. Used for first-character
   * optimization in findBoundsFrom. Returns {@link CharSet#ANY} when the optimization does not
   * apply: the pattern can match the empty string, or a starting char cannot be bounded.
   */
  private CharSet computeFirstCharSet(RegexNode node) {
    if (node == null) {
      return CharSet.ANY; // Conservative: any character
    }
    FirstChars first = new FirstChars();
    boolean nullable = node.accept(first);
    return nullable ? CharSet.ANY : first.chars;
  }

  /**
   * Collects into {@link #chars} the chars that can start a match of the visited node; returns true
   * when the node can match the empty string. A concat continues into the next child while the
   * children so far are nullable.
   */
  private final class FirstChars implements RegexVisitor<Boolean> {
    CharSet chars = CharSet.empty();
    // Call targets being visited: a call that re-enters one is unbounded
    private final Set<RegexNode> activeTargets = new HashSet<>();

    private Boolean any() {
      chars = CharSet.ANY;
      return true;
    }

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      // EpsilonNode extends LiteralNode: test it first
      if (node instanceof EpsilonNode) {
        return true;
      }
      chars = chars.union(CharSet.of(node.ch));
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      chars = node.negated ? CharSet.ANY : chars.union(node.chars);
      return false;
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      boolean childNullable = node.child.accept(this);
      return node.min == 0 || childNullable;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      for (RegexNode child : node.children) {
        if (!child.accept(this)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return anyNullable(node.alternatives);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return true; // zero-width
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return true; // zero-width
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      // Content unknown at analysis time; may be empty
      return any();
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      RegexNode target = isRootCall(node) ? ast : callTarget(node);
      if (target == null || !activeTargets.add(target)) {
        return any();
      }
      boolean nullable = target.accept(this);
      activeTargets.remove(target);
      return nullable;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean nullable = node.thenBranch.accept(this);
      // A missing else branch matches empty
      if (node.elseBranch == null) {
        return true;
      }
      return node.elseBranch.accept(this) | nullable;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return anyNullable(node.alternatives);
    }

    private boolean anyNullable(List<RegexNode> alternatives) {
      boolean nullable = alternatives.isEmpty();
      for (RegexNode alt : alternatives) {
        nullable |= alt.accept(this);
      }
      return nullable;
    }
  }

  /** Main entry point: Generate matcher class with all parsing methods. */
  public byte[] generate(String className) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    cw.visit(
        V21,
        ACC_PUBLIC | ACC_SUPER,
        className.replace('.', '/'),
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        null);

    // Generate constructor
    generateConstructor(cw, className);

    // Generate all parser methods
    generateAllParserMethods(cw, className);

    // Generate matches() method
    generateMatchesMethod(cw, className);

    // Generate findBoundsFrom() method
    generateFindBoundsFromMethod(cw, className);

    // Generate match() method that returns MatchResult
    generateMatchMethod(cw, className);

    // Generate find() and findFrom() methods
    generateFindMethod(cw, className);
    generateFindFromMethod(cw, className);

    // Generate findMatch() and findMatchFrom() methods
    generateFindMatchMethod(cw, className);
    generateFindMatchFromMethod(cw, className);

    cw.visitEnd();
    return cw.toByteArray();
  }

  /**
   * Generate all parser methods (parseRoot and AST node parsers). This must be called before
   * generating the public API methods.
   */
  public void generateAllParserMethods(ClassWriter cw, String className) {
    // Flag set to true while executing find(); false (default) during matches().
    // Lazy quantifiers use this to return the minimum match for find() vs the
    // greedy (full-coverage) match for matches().
    cw.visitField(ACC_PRIVATE, "lazyFindMode", "Z", null, null).visitEnd();
    // Parse-method calls left in the current match attempt (non-one-way only; see emitStepCheck)
    if (!oneWay) {
      cw.visitField(ACC_PRIVATE, "stepsLeft", "J", null, null).visitEnd();
    }

    // IMPORTANT: Generate parser methods for AST nodes FIRST
    // This must happen before generateParseRootMethod, because parseRoot
    // calls getMethodNameForNode(ast) which adds ast to the map,
    // preventing generateParserMethod from generating it
    generateParserMethod(cw, className, ast);

    // Generate parseRoot method (entry point that delegates to ast parser)
    generateParseRootMethod(cw, className, "parseRoot", ast);
    if (wholeRoot != null) {
      generateParserMethod(cw, className, wholeRoot);
      generateParseRootMethod(cw, className, "parseWholeRoot", wholeRoot);
    }
  }

  /** The parseRoot variant for an attempt that must consume the whole input. */
  private String wholeInputRootMethod() {
    return wholeRoot != null ? "parseWholeRoot" : "parseRoot";
  }

  /** Generate constructor that calls super(). */
  public void generateConstructor(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(
        INVOKESPECIAL, "com/datadoghq/reggie/runtime/ReggieMatcher", "<init>", "()V", false);
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }

  /** Generate matches() method implementation. Signature: public boolean matches(String input) */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    emitWholeInputStepReset(mv, className);

    // Local vars: 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int iVar = allocator.allocate();

    // int[] groups = new int[(groupCount + 1) * 2]
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize all group positions to -1 (unset)
    // for (int i = 0; i < groups.length; i++) groups[i] = -1;
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitInsn(ICONST_0); // i = 0
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar); // i
    mv.visitVarInsn(ALOAD, groupsVar); // groups
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd); // if i >= length, exit loop
    // groups[i] = -1
    mv.visitVarInsn(ALOAD, groupsVar); // groups
    mv.visitVarInsn(ILOAD, iVar); // i
    mv.visitInsn(ICONST_M1); // -1
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // When the root AST is an alternation, try each branch separately and require that the
    // chosen branch consumes the entire input.  A branch that matches only a prefix of the
    // input (result != length) is treated as a failure and the next branch is tried.  This
    // mirrors JDK semantics: Pattern.matches() requires the pattern to span the whole string,
    // so an alternation branch that stops early must be discarded in favour of a later branch
    // that reaches the end.
    Label matchSuccess = new Label();
    if (ast instanceof AlternationNode) {
      emitRootAlternatives(
          cw, mv, className, (AlternationNode) ast, groupsVar, resultVar, iVar, matchSuccess);
    } else {
      // Non-alternation root: call the single root parser and check for full match
      String rootParserMethod = getMethodNameForNode(wholeRoot != null ? wholeRoot : ast);
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitInsn(ICONST_0); // pos = 0
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitVarInsn(ALOAD, groupsVar); // groups
      mv.visitInsn(ICONST_0); // depth = 0
      mv.visitMethodInsn(
          INVOKESPECIAL, className, rootParserMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, resultVar); // result

      // Check if result == input.length() (full match)
      mv.visitVarInsn(ILOAD, resultVar);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitJumpInsn(IF_ICMPEQ, matchSuccess);
    }

    mv.visitInsn(ICONST_0); // false
    mv.visitInsn(IRETURN);

    mv.visitLabel(matchSuccess);
    mv.visitInsn(ICONST_1); // true
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Gives a non-one-way matcher a fresh step budget for an attempt over the whole input. matches()
   * and match() call root parsers directly, bypassing parseRoot's reset: matches() always, match()
   * when the root is an alternation.
   */
  private void emitWholeInputStepReset(MethodVisitor mv, String className) {
    if (!oneWay) {
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      emitStepReset(mv, className);
    }
  }

  /**
   * Non-one-way only. Consumes the attempt's region length (int) from the stack and sets {@code
   * this.stepsLeft = BacktrackConfig.MAX_RD_STEPS + RD_STEPS_PER_CHAR * (long) length}.
   */
  private static void emitStepReset(MethodVisitor mv, String className) {
    mv.visitInsn(I2L);
    mv.visitLdcInsn((long) RD_STEPS_PER_CHAR);
    mv.visitInsn(LMUL);
    mv.visitFieldInsn(
        GETSTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "MAX_RD_STEPS", "I");
    mv.visitInsn(I2L);
    mv.visitInsn(LADD);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(DUP_X2);
    mv.visitInsn(POP);
    mv.visitFieldInsn(PUTFIELD, className, "stepsLeft", "J");
  }

  /**
   * Parse-method prologue of a non-one-way matcher: {@code if (--this.stepsLeft < 0) throw
   * BacktrackConfig.rdStepBudgetExceeded();}. Every repeated unit of work (a quantifier iteration,
   * retry or replay, a subroutine call) makes at least one parse-method call, so the budget bounds
   * the work of an attempt. A one-way matcher emits nothing: its work is linear in the region.
   */
  private void emitStepCheck(MethodVisitor mv, String className) {
    if (oneWay) {
      return;
    }
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(DUP);
    mv.visitFieldInsn(GETFIELD, className, "stepsLeft", "J");
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LSUB);
    mv.visitInsn(DUP2_X1);
    mv.visitFieldInsn(PUTFIELD, className, "stepsLeft", "J");
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    Label ok = new Label();
    mv.visitJumpInsn(IFGE, ok);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/BacktrackConfig",
        "rdStepBudgetExceeded",
        "()Lcom/datadoghq/reggie/runtime/MatchBudgetExceededException;",
        false);
    mv.visitInsn(ATHROW);
    mv.visitLabel(ok);
  }

  /**
   * Generate findBoundsFrom() implementation with first-character optimization. Signature:
   * protected int findBoundsFrom(CharSequence input, int fromIndex, int[] bounds)
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    // Compute first-character set for optimization
    CharSet firstCharSet = computeFirstCharSet(ast);
    boolean canOptimize =
        firstCharSet != null
            && !firstCharSet.equals(CharSet.ANY)
            && firstCharSet.getRanges().size() <= FIRST_CHAR_PREFILTER_MAX_RANGES;
    // R2: an ^/\A-anchored pattern (non-multiline) can only match at position 0 — searching
    // from a later offset cannot succeed, and scanning forward position by position is the
    // O(n^2) that dominates .*-prefix anchored rules (e.g. ^(.*)-([0-9]{1,4})$).
    boolean startAnchoredAtZero = isStartAnchoredAtZero(ast);

    MethodVisitor mv =
        cw.visitMethod(
            ACC_PROTECTED, "findBoundsFrom", "(Ljava/lang/CharSequence;I[I)I", null, null);
    mv.visitCode();

    // lazyFindMode = true: lazy quantifiers return minimum matches during find()
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ICONST_1);
    mv.visitFieldInsn(PUTFIELD, className, "lazyFindMode", "Z");

    // Local vars: 0=this, 1=charSeq, 2=fromIndex, 3=bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int stringInputVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int groupsVar = allocator.allocate();
    int posVar = allocator.allocate();
    int iVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int charVar = canOptimize ? allocator.allocate() : -1;

    // Cast CharSequence to String for our parser (limitation for now)
    // S: []
    mv.visitVarInsn(ALOAD, 1); // input (CharSequence)
    // S: [A:CharSequence]
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    // S: [A:String]
    mv.visitVarInsn(ASTORE, stringInputVar);

    // int len = input.length()
    // S: []
    mv.visitVarInsn(ALOAD, stringInputVar);
    // S: [A:String]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I]
    mv.visitVarInsn(ISTORE, lenVar);

    // Allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    // S: []
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    // S: [I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I]]
    mv.visitVarInsn(ASTORE, groupsVar);

    // Try matching from each position
    Label findMatchPositionLoop = new Label();
    Label findMatchPositionLoopEnd = new Label();
    Label foundMatch = new Label();
    Label firstCharOptimizationSkip = new Label();

    // if (fromIndex < 0) fromIndex = 0;
    // S: []
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, 2); // fromIndex
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startNotNeg);

    // R2 anchor-start: only position 0 can match, so a search from any later offset fails.
    if (startAnchoredAtZero) {
      Label anchoredAtZero = new Label();
      mv.visitVarInsn(ILOAD, 2); // fromIndex (clamped >= 0)
      mv.visitJumpInsn(IFLE, anchoredAtZero); // fromIndex == 0 -> continue
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(anchoredAtZero);
    }

    // pos starts at fromIndex (clamped)
    // S: []
    mv.visitVarInsn(ILOAD, 2); // fromIndex
    // S: [I]
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitLabel(findMatchPositionLoop);
    // if (pos > len) goto findMatchPositionLoopEnd
    // S: []
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPGT, findMatchPositionLoopEnd);

    // First-character optimization: skip positions that can't match
    if (canOptimize) {
      // if (pos < len) {
      //     char c = input.charAt(pos);
      //     if (!firstCharSet.contains(c)) goto firstCharOptimizationSkip;
      // }

      // S: []
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I]
      mv.visitVarInsn(ILOAD, lenVar);
      // S: [I, I]
      mv.visitJumpInsn(IF_ICMPGE, firstCharOptimizationSkip); // if pos >= len, skip check

      // Get character at position: char c = input.charAt(pos)
      // S: []
      mv.visitVarInsn(ALOAD, stringInputVar);
      // S: [A:String]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [A:String, I]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      // S: [C]
      mv.visitVarInsn(ISTORE, charVar);

      // Check if character is in firstCharSet
      // We need to generate code for CharSet.contains(c)
      // For now, generate a simple range check for common cases
      generateFirstCharSetCheck(mv, firstCharSet, charVar, firstCharOptimizationSkip);
    }

    // Initialize groups to -1
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    // S: []
    mv.visitInsn(ICONST_0); // i = 0
    // S: [I]
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    // S: []
    mv.visitVarInsn(ILOAD, iVar);
    // S: [I]
    mv.visitVarInsn(ALOAD, groupsVar);
    // S: [I, A:[I]]
    mv.visitInsn(ARRAYLENGTH);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);

    // groups[i] = -1
    // S: []
    mv.visitVarInsn(ALOAD, groupsVar);
    // S: [A:[I]]
    mv.visitVarInsn(ILOAD, iVar);
    // S: [A:[I], I]
    mv.visitInsn(ICONST_M1); // -1
    // S: [A:[I], I, I]
    mv.visitInsn(IASTORE);
    // S: []
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Try to match at this position: result = parseRoot(input, pos, len, groups)
    // S: []
    mv.visitVarInsn(ALOAD, 0); // this
    // S: [A:this]
    mv.visitVarInsn(ALOAD, stringInputVar);
    // S: [A:this, A:String]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [A:this, A:String, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [A:this, A:String, I, I]
    mv.visitVarInsn(ALOAD, groupsVar);
    // S: [A:this, A:String, I, I, A:[I]]
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(INVOKESPECIAL, className, "parseRoot", "(Ljava/lang/String;II[II)I", false);
    // S: [I]
    mv.visitVarInsn(ISTORE, resultVar);

    // If result != -1, we found a match
    // S: []
    mv.visitVarInsn(ILOAD, resultVar);
    // S: [I]
    mv.visitInsn(ICONST_M1);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPNE, foundMatch);

    // No match at this position, try next
    // S: []
    mv.visitLabel(firstCharOptimizationSkip); // Landing point for first-char optimization
    if (startAnchoredAtZero) {
      // R2 anchor-start: position 0 was the only candidate — fail immediately.
      mv.visitJumpInsn(GOTO, findMatchPositionLoopEnd);
    } else {
      mv.visitIincInsn(posVar, 1); // pos++
      mv.visitJumpInsn(GOTO, findMatchPositionLoop);
    }

    mv.visitLabel(foundMatch);
    // Set bounds[0] = pos (start), bounds[1] = result (end)
    // S: []
    mv.visitVarInsn(ALOAD, 3); // bounds
    // S: [A:[I]]
    mv.visitInsn(ICONST_0);
    // S: [A:[I], I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [A:[I], I, I]
    mv.visitInsn(IASTORE);

    // S: []
    mv.visitVarInsn(ALOAD, 3); // bounds
    // S: [A:[I]]
    mv.visitInsn(ICONST_1);
    // S: [A:[I], I]
    mv.visitVarInsn(ILOAD, resultVar);
    // S: [A:[I], I, I]
    mv.visitInsn(IASTORE);

    // Reset lazyFindMode before returning (match found path)
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ICONST_0);
    mv.visitFieldInsn(PUTFIELD, className, "lazyFindMode", "Z");
    // Return start position
    // S: []
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I]
    mv.visitInsn(IRETURN);

    mv.visitLabel(findMatchPositionLoopEnd);
    // No match found anywhere
    // Reset lazyFindMode before returning (no match path)
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ICONST_0);
    mv.visitFieldInsn(PUTFIELD, className, "lazyFindMode", "Z");
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate bytecode to check if a character is in a CharSet. If the character is NOT in the set,
   * jumps to notInSetLabel.
   *
   * @param mv MethodVisitor for bytecode generation
   * @param charSet The CharSet to check against
   * @param charVarSlot Local variable slot containing the char to check
   * @param notInSetLabel Label to jump to if char is NOT in the set
   */
  private void generateFirstCharSetCheck(
      MethodVisitor mv, CharSet charSet, int charVarSlot, Label notInSetLabel) {
    if (charSet.isSingleChar()) {
      // Single character: if (c != char) goto notInSet
      // S: []
      mv.visitVarInsn(ILOAD, charVarSlot); // load char c
      // S: [C]
      BytecodeUtil.pushInt(mv, charSet.getSingleChar());
      // S: [C, C]
      mv.visitJumpInsn(IF_ICMPNE, notInSetLabel); // if c != target, skip
      // S: []
    } else if (charSet.isSimpleRange()) {
      // Single range: if (c < start || c > end) goto notInSet
      CharSet.Range range = charSet.getSimpleRange();

      // Check c < start: if true, goto notInSet
      // S: []
      mv.visitVarInsn(ILOAD, charVarSlot); // c
      // S: [C]
      BytecodeUtil.pushInt(mv, range.start);
      // S: [C, C]
      mv.visitJumpInsn(IF_ICMPLT, notInSetLabel);
      // S: []

      // Check c > end: if true, goto notInSet
      mv.visitVarInsn(ILOAD, charVarSlot); // c
      // S: [C]
      BytecodeUtil.pushInt(mv, range.end);
      // S: [C, C]
      mv.visitJumpInsn(IF_ICMPGT, notInSetLabel);
      // S: []
    } else {
      BytecodeUtil.emitCharSetCheck(mv, charSet, charVarSlot, notInSetLabel);
    }
  }

  /**
   * Generate match() method that returns MatchResult with capturing groups. Signature: public
   * MatchResult match(String input)
   */
  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    emitWholeInputStepReset(mv, className);

    // Local vars: 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();
    int iVar = allocator.allocate();

    // Allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize groups to -1
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1);
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Call the root parser, trying each alternation branch separately when the root is an
    // alternation (same logic as generateMatchesMethod — see comment there).
    Label matchFailed = new Label();
    if (ast instanceof AlternationNode) {
      AlternationNode altNode = (AlternationNode) ast;
      Label matchOk = new Label();
      emitRootAlternatives(cw, mv, className, altNode, groupsVar, resultVar, iVar, matchOk);
      mv.visitJumpInsn(GOTO, matchFailed);
      mv.visitLabel(matchOk);
      // parseRoot normally sets groups[0]=0 and groups[1]=result.  Since we called the
      // alt parser directly (bypassing parseRoot), we must set group 0 manually here.
      mv.visitVarInsn(ALOAD, groupsVar);
      mv.visitInsn(ICONST_0); // index = 0
      mv.visitInsn(ICONST_0); // value = 0 (match start)
      mv.visitInsn(IASTORE);
      mv.visitVarInsn(ALOAD, groupsVar);
      mv.visitInsn(ICONST_1); // index = 1
      mv.visitVarInsn(ILOAD, resultVar); // value = result (match end = input.length())
      mv.visitInsn(IASTORE);
    } else {
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitInsn(ICONST_0); // start = 0
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitVarInsn(ALOAD, groupsVar);
      mv.visitInsn(ICONST_0); // depth = 0
      mv.visitMethodInsn(
          INVOKESPECIAL, className, wholeInputRootMethod(), "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, resultVar);

      mv.visitVarInsn(ILOAD, resultVar);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitJumpInsn(IF_ICMPNE, matchFailed);
    }

    // Create MatchResult: starts and ends arrays
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // Copy from groups to starts/ends
    emitUnpackGroups(mv, groupsVar, startsVar, endsVar, iVar);

    // new MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    BytecodeUtil.pushInt(mv, groupCount); // groupCount
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchFailed);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate matchInto() method that writes capture boundaries directly into caller-provided
   * arrays. Signature: public boolean matchInto(String input, int[] groupStarts, int[] groupEnds)
   */
  public void generateMatchIntoMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchInto", "(Ljava/lang/String;[I[I)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=groupStarts, 3=groupEnds
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int iVar = allocator.allocate();
    int requiredGroups = groupCount + 1;

    // Objects.requireNonNull(input/groupStarts/groupEnds)
    mv.visitVarInsn(ALOAD, 1);
    mv.visitLdcInsn("input");
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
        false);
    mv.visitInsn(POP);
    mv.visitVarInsn(ALOAD, 2);
    mv.visitLdcInsn("groupStarts");
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
        false);
    mv.visitInsn(POP);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitLdcInsn("groupEnds");
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
        false);
    mv.visitInsn(POP);

    // int[] groups = this.recursiveGroups;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(
        GETFIELD, "com/datadoghq/reggie/runtime/ReggieMatcher", "recursiveGroups", "[I");
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize packed groups to -1. Caller arrays remain unchanged until success.
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1);
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // int result = parseRoot(input, 0, input.length(), groups, 0)
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKESPECIAL, className, wholeInputRootMethod(), "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // Full-match check.
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    Label matchFailed = new Label();
    mv.visitJumpInsn(IF_ICMPNE, matchFailed);

    // Undersized caller arrays throw only on a match; a failed match returns false.
    Label startsLengthOk = new Label();
    mv.visitVarInsn(ALOAD, 2);
    mv.visitInsn(ARRAYLENGTH);
    BytecodeUtil.pushInt(mv, requiredGroups);
    mv.visitJumpInsn(IF_ICMPGE, startsLengthOk);
    generateGroupArrayTooSmallThrow(mv, requiredGroups);
    mv.visitLabel(startsLengthOk);

    Label endsLengthOk = new Label();
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ARRAYLENGTH);
    BytecodeUtil.pushInt(mv, requiredGroups);
    mv.visitJumpInsn(IF_ICMPGE, endsLengthOk);
    generateGroupArrayTooSmallThrow(mv, requiredGroups);
    mv.visitLabel(endsLengthOk);

    // Copy packed groups into caller arrays
    emitUnpackGroups(mv, groupsVar, 2, 3, iVar);

    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(matchFailed);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * starts[i] = groups[2 * i], ends[i] = groups[2 * i + 1] for i in 0..groupCount: unrolled up to
   * {@link #UNROLLED_GROUP_COPY_MAX} groups, else a loop over {@code iVar} (constant size).
   */
  private void emitUnpackGroups(
      MethodVisitor mv, int groupsVar, int startsVar, int endsVar, int iVar) {
    if (groupCount <= UNROLLED_GROUP_COPY_MAX) {
      for (int i = 0; i <= groupCount; i++) {
        mv.visitVarInsn(ALOAD, startsVar);
        BytecodeUtil.pushInt(mv, i);
        mv.visitVarInsn(ALOAD, groupsVar);
        BytecodeUtil.pushInt(mv, i * 2);
        mv.visitInsn(IALOAD);
        mv.visitInsn(IASTORE);

        mv.visitVarInsn(ALOAD, endsVar);
        BytecodeUtil.pushInt(mv, i);
        mv.visitVarInsn(ALOAD, groupsVar);
        BytecodeUtil.pushInt(mv, i * 2 + 1);
        mv.visitInsn(IALOAD);
        mv.visitInsn(IASTORE);
      }
      return;
    }
    Label loop = new Label();
    Label done = new Label();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);
    mv.visitLabel(loop);
    mv.visitVarInsn(ILOAD, iVar);
    BytecodeUtil.pushInt(mv, groupCount);
    mv.visitJumpInsn(IF_ICMPGT, done);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISHL);
    mv.visitInsn(IALOAD);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISHL);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitInsn(IALOAD);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1);
    mv.visitJumpInsn(GOTO, loop);
    mv.visitLabel(done);
  }

  private void generateGroupArrayTooSmallThrow(MethodVisitor mv, int requiredGroups) {
    mv.visitTypeInsn(NEW, "java/lang/IndexOutOfBoundsException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn(
        "group arrays must have length at least " + requiredGroups + " for this pattern");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/IndexOutOfBoundsException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
  }

  /**
   * Generate find method - searches for pattern in the input string. Signature: public boolean
   * find(String input)
   */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Delegate to findFrom(input, 0)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "findFrom", "(Ljava/lang/String;I)I", false);

    // Return true if result != -1
    mv.visitInsn(ICONST_M1);
    Label notFound = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, notFound);
    mv.visitInsn(ICONST_1); // true
    mv.visitInsn(IRETURN);

    mv.visitLabel(notFound);
    mv.visitInsn(ICONST_0); // false
    mv.visitInsn(IRETURN);

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /**
   * Generate findFrom method - searches for pattern starting at the given position. Signature:
   * public int findFrom(String input, int start)
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int boundsVar = allocator.allocate();

    // int[] bounds = new int[2]
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, boundsVar);

    // Call findBoundsFrom(input, start, bounds) - returns int (start position or -1)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findBoundsFrom", "(Ljava/lang/CharSequence;I[I)I", false);

    // findBoundsFrom returns int (position or -1), just return it directly
    mv.visitInsn(IRETURN);

    mv.visitMaxs(4, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate findMatch() method - finds and returns MatchResult. Signature: public MatchResult
   * findMatch(String input)
   */
  public void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Delegate to findMatchFrom(input, 0)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className,
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /**
   * Generate findMatchFrom() method - finds pattern starting at position and returns MatchResult.
   * Signature: public MatchResult findMatchFrom(String input, int start)
   */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int boundsVar = allocator.allocate();
    int matchStartVar = allocator.allocate();
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();
    int iVar = allocator.allocate();

    // if (input == null) return null
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int[] bounds = new int[2]
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, boundsVar);

    // int matchStart = findBoundsFrom(input, start, bounds)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findBoundsFrom", "(Ljava/lang/CharSequence;I[I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart == -1) return null
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(ICONST_M1);
    Label notFound = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, notFound);

    // Match found - allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize groups to -1
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);

    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);

    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Call parseRoot at the matched position to get all group captures:
    // parseRoot(input, matchStart, bounds[1], groups, 0). A non-one-way matcher reparses up to
    // input.length(), as the search did: a narrower end changes $, \z and the give-back.
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, matchStartVar); // matchStart (pos)
    if (oneWay) {
      mv.visitVarInsn(ALOAD, boundsVar);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IALOAD); // bounds[1] (end)
    } else {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    }
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(INVOKESPECIAL, className, "parseRoot", "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // if (result < 0) return null: the re-parse under the found end failed
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitJumpInsn(IFLT, notFound);

    // Create MatchResult: allocate starts and ends arrays
    BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // Copy from groups to starts/ends
    emitUnpackGroups(mv, groupsVar, startsVar, endsVar, iVar);

    // new MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    BytecodeUtil.pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Not found - return null
    mv.visitLabel(notFound);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(7, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate parseRoot method - entry point that delegates to the root parser. Signature: private
   * int parseRoot(String input, int pos, int end, int[] groups)
   */
  private void generateParseRootMethod(
      ClassWriter cw, String className, String name, RegexNode root) {
    String rootMethod = getMethodNameForNode(root);

    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, name, "(Ljava/lang/String;II[II)I", null, null);
    mv.visitCode();
    // Each parseRoot call is one match attempt: a fresh budget for the region [pos, end)
    if (!oneWay) {
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitInsn(ISUB);
      emitStepReset(mv, className);
    }
    emitStepCheck(mv, className);

    // Local vars: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
    LocalVarAllocator allocator = new LocalVarAllocator(6);
    int resultVar = allocator.allocate();

    // Save starting position for group 0
    // groups[0] = pos
    mv.visitVarInsn(ALOAD, 4); // groups
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // pos
    mv.visitInsn(IASTORE);

    // Delegate to the root parser method
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // pos
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitVarInsn(ALOAD, 4); // groups
    mv.visitVarInsn(ILOAD, 5); // depth
    mv.visitMethodInsn(INVOKESPECIAL, className, rootMethod, "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // If match succeeded, set group 0 end position
    // if (result != -1) groups[1] = result
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitInsn(ICONST_M1);
    Label failed = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, failed);

    // Success: set group 0 end
    mv.visitVarInsn(ALOAD, 4); // groups
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitInsn(IASTORE);

    mv.visitLabel(failed);
    // Return result
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate parser method for a specific AST node. Signature: private int parse_X_N(String input,
   * int pos, int end, int[] groups, int depth)
   */
  /**
   * Tries each alternative of a root alternation on the whole input, resetting the groups to -1
   * before every alternative but the first, and jumps to {@code success} with {@code resultVar} =
   * input.length() when one consumes it all. Over {@link #RANGE_CHUNK} alternatives, range helpers
   * try them, as in visitAlternation; else they are tried inline.
   */
  private void emitRootAlternatives(
      ClassWriter cw,
      MethodVisitor mv,
      String className,
      AlternationNode altNode,
      int groupsVar,
      int resultVar,
      int iVar,
      Label success) {
    List<RegexNode> alternatives = altNode.alternatives;
    int n = alternatives.size();
    if (n > RANGE_CHUNK) {
      int span = rangeSpan(n, RANGE_CHUNK);
      for (int lo = 0; lo < n; ) {
        int hi = n - lo <= span ? n : lo + span;
        String helper = generateRootRangeMethod(cw, className, alternatives, lo, hi);
        lo = hi;
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitInsn(ICONST_0); // pos = 0
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitVarInsn(ALOAD, groupsVar); // groups
        mv.visitInsn(ICONST_0); // depth = 0
        mv.visitMethodInsn(INVOKESPECIAL, className, helper, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, resultVar);
        // The helper returns input.length() or -1
        mv.visitVarInsn(ILOAD, resultVar);
        mv.visitInsn(ICONST_M1);
        mv.visitJumpInsn(IF_ICMPNE, success);
      }
      return;
    }
    for (int altIdx = 0; altIdx < n; altIdx++) {
      RegexNode alt = alternatives.get(altIdx);
      generateParserMethod(cw, className, alt);
      String altMethod = getMethodNameForNode(alt);

      // Reset groups to -1 before each alternative (groups may have been dirtied by a
      // previous alternative that partially matched).
      if (altIdx > 0) {
        Label resetLoopStart = new Label();
        Label resetLoopEnd = new Label();
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, iVar);
        mv.visitLabel(resetLoopStart);
        mv.visitVarInsn(ILOAD, iVar);
        mv.visitVarInsn(ALOAD, groupsVar);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitJumpInsn(IF_ICMPGE, resetLoopEnd);
        mv.visitVarInsn(ALOAD, groupsVar);
        mv.visitVarInsn(ILOAD, iVar);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IASTORE);
        mv.visitIincInsn(iVar, 1);
        mv.visitJumpInsn(GOTO, resetLoopStart);
        mv.visitLabel(resetLoopEnd);
      }

      // Call this alternative's parser
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitInsn(ICONST_0); // pos = 0
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitVarInsn(ALOAD, groupsVar); // groups
      mv.visitInsn(ICONST_0); // depth = 0
      mv.visitMethodInsn(INVOKESPECIAL, className, altMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, resultVar);

      // If this alternative consumed the whole input, we have a full match
      mv.visitVarInsn(ILOAD, resultVar);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitJumpInsn(IF_ICMPEQ, success);
      // Otherwise continue to the next alternative
    }
  }

  /**
   * Root range helper: returns {@code end} when one of {@code alternatives[from..to)} matches from
   * pos to end, else -1. Groups are reset to -1 before every alternative but the root's first.
   */
  private String generateRootRangeMethod(
      ClassWriter cw, String className, List<RegexNode> alternatives, int from, int to) {
    String name = "parse_RootRange_" + (methodCounter++);
    MethodVisitor m = cw.visitMethod(ACC_PRIVATE, name, "(Ljava/lang/String;II[II)I", null, null);
    m.visitCode();
    emitStepCheck(m, className);
    int span = to - from > RANGE_CHUNK ? rangeSpan(to - from, RANGE_CHUNK) : 1;
    for (int lo = from; lo < to; ) {
      int hi = to - lo <= span ? to : lo + span;
      String method;
      if (span == 1) {
        RegexNode alt = alternatives.get(lo);
        generateParserMethod(cw, className, alt);
        method = getMethodNameForNode(alt);
        if (lo > 0) {
          m.visitVarInsn(ALOAD, 4);
          m.visitInsn(ICONST_M1);
          m.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "fill", "([II)V", false);
        }
      } else {
        method = generateRootRangeMethod(cw, className, alternatives, lo, hi);
      }
      lo = hi;
      Label next = new Label();
      m.visitVarInsn(ALOAD, 0);
      m.visitVarInsn(ALOAD, 1);
      m.visitVarInsn(ILOAD, 2);
      m.visitVarInsn(ILOAD, 3);
      m.visitVarInsn(ALOAD, 4);
      m.visitVarInsn(ILOAD, 5);
      m.visitMethodInsn(INVOKESPECIAL, className, method, "(Ljava/lang/String;II[II)I", false);
      m.visitVarInsn(ISTORE, 6);
      m.visitVarInsn(ILOAD, 6);
      m.visitVarInsn(ILOAD, 3);
      m.visitJumpInsn(IF_ICMPNE, next);
      m.visitVarInsn(ILOAD, 6);
      m.visitInsn(IRETURN);
      m.visitLabel(next);
    }
    m.visitInsn(ICONST_M1);
    m.visitInsn(IRETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();
    return name;
  }

  private void generateParserMethod(ClassWriter cw, String className, RegexNode node) {
    // Skip if already generated
    if (nodeToMethodName.containsKey(node)) {
      return;
    }

    String methodName = getMethodNameForNode(node);

    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, methodName, "(Ljava/lang/String;II[II)I", null, null);
    mv.visitCode();
    emitStepCheck(mv, className);

    // Visit the node to generate parsing logic
    node.accept(new ParserMethodGenerator(cw, mv, className));

    mv.visitMaxs(0, 0); // Will be computed by ASM
    mv.visitEnd();
  }

  /** Get or create method name for a node. */
  private String getMethodNameForNode(RegexNode node) {
    return nodeToMethodName.computeIfAbsent(
        node,
        n -> {
          String prefix = "parse_" + n.getClass().getSimpleName().replace("Node", "");
          return prefix + "_" + (methodCounter++);
        });
  }

  /** Visitor that generates parser method body for each node type. */
  private class ParserMethodGenerator implements RegexVisitor<Void> {
    private final ClassWriter cw;
    private final MethodVisitor mv;
    private final String className;
    // Slot holding the depth parameter in the currently-generated method.
    // Stays 5 for normal methods; set to 2 by generateConcatWithBacktracking,
    // which repurposes slot 5 as currentPos and the now-dead pos slot (2) for depth.
    private int depthSlot = 5;

    public ParserMethodGenerator(ClassWriter cw, MethodVisitor mv, String className) {
      this.cw = cw;
      this.mv = mv;
      this.className = className;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
      // Check for epsilon (empty match) - represented as '\0'
      if (node instanceof EpsilonNode) {
        // Epsilon: match without consuming any input
        // Just return current position
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitInsn(IRETURN);
        return null;
      }

      // LiteralNode now represents a single character
      // Check if we have enough characters left
      // if (pos >= end) return -1;
      // S: []
      mv.visitVarInsn(ILOAD, 2); // pos
      // S: [I]
      mv.visitVarInsn(ILOAD, 3); // end
      // S: [I, I]
      Label lengthOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, lengthOk);
      // S: []
      mv.visitInsn(ICONST_M1);
      // S: [I]
      mv.visitInsn(IRETURN);

      mv.visitLabel(lengthOk);
      // S: []
      // Check character: if (input.charAt(pos) != node.ch) return -1;
      mv.visitVarInsn(ALOAD, 1); // input
      // S: [A:String]
      mv.visitVarInsn(ILOAD, 2); // pos
      // S: [A:String, I]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      // S: [C]
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, node.ch);
      // S: [C, I]
      Label charMatch = new Label();
      mv.visitJumpInsn(IF_ICMPEQ, charMatch);
      // S: []
      mv.visitInsn(ICONST_M1);
      // S: [I]
      mv.visitInsn(IRETURN);

      mv.visitLabel(charMatch);
      // S: []
      // Return new position (pos + 1)
      mv.visitVarInsn(ILOAD, 2); // pos
      // S: [I]
      mv.visitInsn(ICONST_1);
      // S: [I, I]
      mv.visitInsn(IADD);
      // S: [I]
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      // Check bounds: if (pos >= end) return -1;
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      Label boundsOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, boundsOk);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(boundsOk);

      // Get character: char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, 5); // c in slot 5

      // Check if character matches, applying negation: on success return pos + 1, else -1
      Label notInSet = new Label();
      BytecodeUtil.emitCharSetCheck(
          mv, node.negated ? node.chars.complement() : node.chars, 5, notInSet);
      mv.visitVarInsn(ILOAD, 2);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD);
      mv.visitInsn(IRETURN);

      mv.visitLabel(notInSet);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      // Generate parser for child
      generateParserMethod(cw, className, node.child);
      String childMethod = getMethodNameForNode(node.child);

      if (node.groupNumber > 0) {
        // Capturing group: record start/end positions
        int startIndex = node.groupNumber * 2;
        int endIndex = node.groupNumber * 2 + 1;
        boolean selfReferencingBackref = PatternAnalyzer.hasSelfReferencingBackref(node);

        // C-01: For self-referencing groups write partial-open sentinel before calling child.
        // This lets \N inside the group resolve to zero-length match on the first entry.
        if (selfReferencingBackref) {
          // Save prior groups[startIndex]/groups[endIndex] so they can be restored on child
          // failure.
          // Slot 7 = prior start, slot 8 = prior end. (Slots 0-6 hold
          // this/input/pos/end/groups/depth/result.)
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, startIndex);
          mv.visitInsn(IALOAD);
          mv.visitVarInsn(ISTORE, 7); // prior start
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, endIndex);
          mv.visitInsn(IALOAD);
          mv.visitVarInsn(ISTORE, 8); // prior end
          // groups[startIndex] = pos
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, startIndex);
          mv.visitVarInsn(ILOAD, 2); // pos
          mv.visitInsn(IASTORE);
          // groups[endIndex] = -1  (partial-open sentinel)
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, endIndex);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IASTORE);
        }

        // Call child parser
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 6); // result in slot 6 (slot 5 is depth parameter)

        // Check if child matched
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(ICONST_M1);
        Label childMatched = new Label();
        mv.visitJumpInsn(IF_ICMPNE, childMatched);

        // Child failed: restore the partial-open sentinel writes (if any) so we leave groups
        // unchanged on -1 return. Without this, an enclosing quantifier/alternation that retries
        // would observe the sentinel from this failed attempt.
        if (selfReferencingBackref) {
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, startIndex);
          mv.visitVarInsn(ILOAD, 7); // prior start
          mv.visitInsn(IASTORE);
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, endIndex);
          mv.visitVarInsn(ILOAD, 8); // prior end
          mv.visitInsn(IASTORE);
        }
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);

        // Child succeeded: set group boundaries
        mv.visitLabel(childMatched);

        // Save start position: groups[startIndex] = pos
        mv.visitVarInsn(ALOAD, 4); // groups
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
        mv.visitVarInsn(ILOAD, 2); // pos (start position before child)
        mv.visitInsn(IASTORE);

        // Save end position: groups[endIndex] = result
        mv.visitVarInsn(ALOAD, 4); // groups
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
        mv.visitVarInsn(ILOAD, 6); // result (end position after child)
        mv.visitInsn(IASTORE);

        // Return result
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IRETURN);
      } else {
        // Non-capturing group: just call child parser
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitInsn(IRETURN);
      }

      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      // Generate parser for child
      generateParserMethod(cw, className, node.child);
      String childMethod = getMethodNameForNode(node.child);

      // Quantifier matching strategy:
      // - Greedy: match min required, then as many as possible up to max
      // - Non-greedy: match min required, then return immediately (prefer minimum)
      // Local variables: currentPos (slot 6), matchCount (slot 7), result (slot 8)
      // Slot 5 is depth parameter

      mv.visitVarInsn(ILOAD, 2); // currentPos = pos
      mv.visitVarInsn(ISTORE, 6);
      mv.visitInsn(ICONST_0); // matchCount = 0
      mv.visitVarInsn(ISTORE, 7);

      // Match minimum required times (same for both greedy and non-greedy)
      if (node.min > 0) {
        Label minLoopStart = new Label();
        Label minLoopEnd = new Label();

        mv.visitLabel(minLoopStart);
        // if (matchCount >= min) goto minLoopEnd
        mv.visitVarInsn(ILOAD, 7);
        BytecodeUtil.pushInt(mv, node.min);
        mv.visitJumpInsn(IF_ICMPGE, minLoopEnd);

        // Try to match child
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 6); // currentPos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 6); // currentPos = result

        // If failed, return -1
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(ICONST_M1);
        Label matchSuccess = new Label();
        mv.visitJumpInsn(IF_ICMPNE, matchSuccess);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(matchSuccess);
        // matchCount++
        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(GOTO, minLoopStart);

        mv.visitLabel(minLoopEnd);
      }

      // Lazy (non-greedy) in find() mode: return the minimum match immediately.
      // In matches() mode the greedy extension below ensures the full input can be consumed
      // when a lazy quantifier is at the end of a concat with no following sibling.
      if (!node.greedy) {
        Label matchesModeLabel = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "lazyFindMode", "Z");
        mv.visitJumpInsn(IFEQ, matchesModeLabel); // lazyFindMode==false → matches mode, continue
        mv.visitVarInsn(ILOAD, 6); // currentPos after min matches
        mv.visitInsn(IRETURN);
        mv.visitLabel(matchesModeLabel);
      }

      // Match as many as possible up to max (used by matches() or greedy find)
      // PCRE semantics: capturing groups should contain values from LAST iteration
      Label greedyLoopStart = new Label();
      Label greedyLoopEnd = new Label();

      mv.visitLabel(greedyLoopStart);
      // if (matchCount >= max) goto greedyLoopEnd
      // Note: max=-1 means unbounded in the AST
      if (node.max != -1 && node.max != Integer.MAX_VALUE) {
        mv.visitVarInsn(ILOAD, 7);
        BytecodeUtil.pushInt(mv, node.max);
        mv.visitJumpInsn(IF_ICMPGE, greedyLoopEnd);
      }

      // Try to match child
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 6); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 8); // Store result in temporary slot 8

      // If failed (-1), stop matching
      mv.visitVarInsn(ILOAD, 8); // Load result
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, greedyLoopEnd);

      // Success: check if we made progress
      // If result == currentPos (no progress), we matched empty string
      // Accept this match (groups already updated by child parser) but stop looping
      mv.visitVarInsn(ILOAD, 8); // Load result
      mv.visitVarInsn(ILOAD, 6); // Load currentPos
      Label madeProgress = new Label();
      mv.visitJumpInsn(IF_ICMPNE, madeProgress);

      // No progress (empty match): update currentPos and break
      // The child parser already updated the groups, so this is the "last iteration"
      mv.visitVarInsn(ILOAD, 8); // Load successful result
      mv.visitVarInsn(ISTORE, 6); // currentPos = result
      mv.visitJumpInsn(GOTO, greedyLoopEnd);

      mv.visitLabel(madeProgress);
      // Made progress: update currentPos, matchCount++, and continue
      mv.visitVarInsn(ILOAD, 8); // Load successful result
      mv.visitVarInsn(ISTORE, 6); // currentPos = result
      mv.visitIincInsn(7, 1); // matchCount++
      mv.visitJumpInsn(GOTO, greedyLoopStart);

      mv.visitLabel(greedyLoopEnd);

      // Return final position
      mv.visitVarInsn(ILOAD, 6);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      // Alternation: try each alternative in order
      // If one succeeds, return its result
      // If one fails, restore groups and try the next
      // Local variables: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
      // Additional locals: 6=savedGroups, 7=result

      // Save groups array before trying alternatives
      // int[] savedGroups = groups.clone()
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, 6); // savedGroups in slot 6

      int n = node.alternatives.size();
      if (n > RANGE_CHUNK) {
        // Each range helper tries its alternatives in order and restores groups between them;
        // restoring between helpers gives the same sequence as the unsplit loop.
        emitAlternationRanges(mv, node.alternatives, null, 0, n, RANGE_CHUNK);
      } else {
        for (int i = 0; i < n; i++) {
          emitAlternative(mv, node.alternatives.get(i), i < n - 1);
        }
      }

      // All alternatives failed
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      return null;
    }

    /**
     * Calls one range helper per span of alternatives [from, to), restoring groups (slot 6) between
     * them.
     */
    private void emitAlternationRanges(
        MethodVisitor mv,
        List<RegexNode> alternatives,
        Set<Integer> resetGroups,
        int from,
        int to,
        int chunk) {
      int span = rangeSpan(to - from, chunk);
      for (int lo = from; lo < to; ) {
        int hi = to - lo <= span ? to : lo + span;
        emitAlternativeCall(
            mv, generateAlternationRangeMethod(alternatives, resetGroups, lo, hi, chunk), hi < to);
        lo = hi;
      }
    }

    /**
     * Range helper for an oversized alternation or branch reset: alternatives [from, to), -1 if all
     * fail. A non-null {@code resetGroups} clears those groups before each alternative. A range
     * larger than {@code chunk} calls nested range helpers instead.
     */
    private String generateAlternationRangeMethod(
        List<RegexNode> alternatives, Set<Integer> resetGroups, int from, int to, int chunk) {
      String name = "parse_AlternationRange_" + (methodCounter++);
      MethodVisitor m = cw.visitMethod(ACC_PRIVATE, name, "(Ljava/lang/String;II[II)I", null, null);
      m.visitCode();
      emitStepCheck(m, className);
      m.visitVarInsn(ALOAD, 4);
      m.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      m.visitTypeInsn(CHECKCAST, "[I");
      m.visitVarInsn(ASTORE, 6);
      if (to - from > chunk) {
        emitAlternationRanges(m, alternatives, resetGroups, from, to, chunk);
      } else {
        for (int i = from; i < to; i++) {
          if (resetGroups != null) {
            emitGroupReset(m, resetGroups);
          }
          emitAlternative(m, alternatives.get(i), i < to - 1);
        }
      }
      m.visitInsn(ICONST_M1);
      m.visitInsn(IRETURN);
      m.visitMaxs(0, 0);
      m.visitEnd();
      return name;
    }

    /**
     * Tries one alternative: an inline leaf returns pos + 1 when it matches, other nodes are called
     * through their parser method. A leaf writes no groups, so a failed leaf needs no restore.
     */
    private void emitAlternative(MethodVisitor mv, RegexNode alt, boolean restore) {
      if (isInlineLeaf(alt)) {
        Label nextAlt = new Label();
        emitLeafCheck(mv, alt, 2, nextAlt);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitLabel(nextAlt);
        return;
      }
      generateParserMethod(cw, className, alt);
      emitAlternativeCall(mv, getMethodNameForNode(alt), restore);
    }

    /**
     * Matches one concat child at currentPos (slot 6): an inline leaf advances currentPos or
     * returns -1, other nodes are called through their parser method.
     */
    private void emitSequenceChild(MethodVisitor mv, RegexNode child) {
      if (!isInlineLeaf(child)) {
        generateParserMethod(cw, className, child);
        emitSequenceCall(mv, getMethodNameForNode(child));
        return;
      }
      Label fail = new Label();
      Label matched = new Label();
      emitLeafCheck(mv, child, 6, fail);
      mv.visitIincInsn(6, 1);
      mv.visitJumpInsn(GOTO, matched);
      mv.visitLabel(fail);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(matched);
    }

    /**
     * Jumps to {@code fail} unless the leaf matches the char at the position in {@code posSlot};
     * uses slot 7 for the char. Same semantics as visitLiteral and visitCharClass.
     */
    private void emitLeafCheck(MethodVisitor mv, RegexNode leaf, int posSlot, Label fail) {
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitJumpInsn(IF_ICMPGE, fail);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      if (leaf instanceof LiteralNode) {
        BytecodeUtil.pushInt(mv, ((LiteralNode) leaf).ch);
        mv.visitJumpInsn(IF_ICMPNE, fail);
      } else {
        CharClassNode cc = (CharClassNode) leaf;
        mv.visitVarInsn(ISTORE, 7);
        BytecodeUtil.emitCharSetCheck(mv, cc.negated ? cc.chars.complement() : cc.chars, 7, fail);
      }
    }

    /**
     * result = method(input, pos, end, groups, depth); return it unless -1. With {@code restore}, a
     * failed call is followed by copying savedGroups (slot 6) back into groups.
     */
    private void emitAlternativeCall(MethodVisitor mv, String altMethod, boolean restore) {
      Label nextAlt = new Label();
      // Call alternative parser: result = parse_alt(input, pos, end, groups, depth)
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitMethodInsn(INVOKESPECIAL, className, altMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 7); // result in slot 7

      // If succeeded (result != -1), return result
      mv.visitVarInsn(ILOAD, 7);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, nextAlt); // If failed, try next alternative

      // Success: return result
      mv.visitVarInsn(ILOAD, 7);
      mv.visitInsn(IRETURN);

      // Failed: restore groups and try next
      mv.visitLabel(nextAlt);
      if (restore) {
        // Restore groups array: System.arraycopy(savedGroups, 0, groups, 0, groups.length)
        mv.visitVarInsn(ALOAD, 6); // savedGroups
        mv.visitInsn(ICONST_0); // srcPos
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitInsn(ICONST_0); // destPos
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitInsn(ARRAYLENGTH); // length
        mv.visitMethodInsn(
            INVOKESTATIC,
            "java/lang/System",
            "arraycopy",
            "(Ljava/lang/Object;ILjava/lang/Object;II)V",
            false);
      }
    }

    /**
     * groups[2g] = groups[2g + 1] = -1 for each g: two stores per group, or one Arrays.fill for a
     * run of more than UNROLLED_RESET_RUN_MAX consecutive group numbers.
     */
    private void emitGroupReset(MethodVisitor mv, Set<Integer> groupNumbers) {
      for (int g : groupNumbers) {
        int last = resetRunEnd(groupNumbers, g);
        if (last < 0) {
          continue;
        }
        if (last - g >= UNROLLED_RESET_RUN_MAX) {
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, g * 2);
          BytecodeUtil.pushInt(mv, last * 2 + 2);
          mv.visitInsn(ICONST_M1);
          mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "fill", "([IIII)V", false);
          continue;
        }
        for (int r = g; r <= last; r++) {
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, r * 2);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IASTORE);
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, r * 2 + 1);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IASTORE);
        }
      }
    }

    /**
     * Upper bound of the bytes emitGroupReset emits: at most 14 per stored group, at most 12 per
     * Arrays.fill.
     */
    private static int groupResetBytes(Set<Integer> groupNumbers) {
      int bytes = 0;
      for (int g : groupNumbers) {
        int last = resetRunEnd(groupNumbers, g);
        if (last >= 0) {
          bytes += last - g >= UNROLLED_RESET_RUN_MAX ? 12 : 14 * (last - g + 1);
        }
      }
      return bytes;
    }

    /** Last group of the run of consecutive group numbers starting at g, -1 if g starts no run. */
    private static int resetRunEnd(Set<Integer> groupNumbers, int g) {
      if (groupNumbers.contains(g - 1)) {
        return -1;
      }
      int last = g;
      while (groupNumbers.contains(last + 1)) {
        last++;
      }
      return last;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      // Concatenation: match each child in sequence
      // With backtracking support for quantifiers followed by potentially failing children

      // Check if we need backtracking: do we have a quantifier followed by more children?
      // Both greedy and non-greedy quantifiers may need backtracking
      boolean needsBacktracking = false;
      int backtrackChildIndex = -1;

      for (int i = 0; i < node.children.size() - 1; i++) {
        RegexNode child = node.children.get(i);
        if (containsBacktrackingQuantifier(child)) {
          needsBacktracking = true;
          backtrackChildIndex = i;
          break; // For now, handle only the first backtracking quantifier
        }
      }

      if (!needsBacktracking) {
        // Simple case: no backtracking needed
        // Local variable 6: currentPos (slot 5 is depth parameter)
        mv.visitVarInsn(ILOAD, 2); // currentPos = pos
        mv.visitVarInsn(ISTORE, 6);

        // Match each child in sequence; an oversized concat calls one range helper per chunk
        int n = node.children.size();
        if (n > RANGE_CHUNK) {
          emitConcatRanges(mv, node, 0, n);
        } else {
          for (RegexNode child : node.children) {
            emitSequenceChild(mv, child);
          }
        }

        // All children matched: return final position
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IRETURN);
      } else {
        // Complex case: backtracking needed
        // Strategy: save groups before greedy child, try it, then try remaining children
        // If remaining children fail, restore groups and have greedy child backtrack
        generateConcatWithBacktracking(node, backtrackChildIndex, 16);
      }

      return null;
    }

    /** Calls one range helper per span of children [from, to), threading currentPos (slot 6). */
    private void emitConcatRanges(MethodVisitor mv, ConcatNode node, int from, int to) {
      int span = rangeSpan(to - from, RANGE_CHUNK);
      for (int lo = from; lo < to; ) {
        int hi = to - lo <= span ? to : lo + span;
        emitSequenceCall(mv, generateConcatRangeMethod(node, lo, hi));
        lo = hi;
      }
    }

    /**
     * Range helper for an oversized simple concat: children [from, to) in sequence. A range larger
     * than RANGE_CHUNK calls nested range helpers instead.
     */
    private String generateConcatRangeMethod(ConcatNode node, int from, int to) {
      String name = "parse_ConcatRange_" + (methodCounter++);
      MethodVisitor m = cw.visitMethod(ACC_PRIVATE, name, "(Ljava/lang/String;II[II)I", null, null);
      m.visitCode();
      emitStepCheck(m, className);
      m.visitVarInsn(ILOAD, 2);
      m.visitVarInsn(ISTORE, 6);
      if (to - from > RANGE_CHUNK) {
        emitConcatRanges(m, node, from, to);
      } else {
        for (int i = from; i < to; i++) {
          emitSequenceChild(m, node.children.get(i));
        }
      }
      m.visitVarInsn(ILOAD, 6);
      m.visitInsn(IRETURN);
      m.visitMaxs(0, 0);
      m.visitEnd();
      return name;
    }

    /** currentPos (slot 6) = method(input, currentPos, end, groups, depth); return -1 on -1. */
    private void emitSequenceCall(MethodVisitor mv, String method) {
      Label successLabel = new Label();
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 6); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitMethodInsn(INVOKESPECIAL, className, method, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 6); // currentPos = result
      mv.visitVarInsn(ILOAD, 6);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPNE, successLabel);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(successLabel);
    }

    /**
     * Children [from, to) of {@code node} in sequence on currentPos (slot 5), with the depth in
     * depthSlot. A failed child leaves -1 in slot 5 and jumps to {@code fail}, or returns -1 when
     * {@code fail} is null. A run longer than INLINE_CHILD_RUN_MAX calls range helpers instead.
     */
    private void emitChildRun(ConcatNode node, int from, int to, Label fail) {
      if (to - from > INLINE_CHILD_RUN_MAX) {
        int span = rangeSpan(to - from, RANGE_CHUNK);
        for (int lo = from; lo < to; ) {
          int hi = to - lo <= span ? to : lo + span;
          emitChildCall(generateConcatRangeMethod(node, lo, hi), fail);
          lo = hi;
        }
        return;
      }
      for (int i = from; i < to; i++) {
        RegexNode child = node.children.get(i);
        generateParserMethod(cw, className, child);
        emitChildCall(getMethodNameForNode(child), fail);
      }
    }

    /** emitChildRun where a failed child backtracks, as {@link #emitBacktrack}. */
    private void emitChildRunOrBacktrack(
        ConcatNode node, int from, int to, int trySlot, int delta, Label loop) {
      if (from >= to) {
        return;
      }
      Label failed = new Label();
      Label done = new Label();
      emitChildRun(node, from, to, failed);
      mv.visitJumpInsn(GOTO, done);
      mv.visitLabel(failed);
      emitBacktrack(trySlot, delta, loop);
      mv.visitLabel(done);
    }

    /** tryMatchCount ({@code trySlot}, none when negative) += delta; goto loop. */
    private void emitBacktrack(int trySlot, int delta, Label loop) {
      if (trySlot >= 0) {
        mv.visitIincInsn(trySlot, delta);
      }
      mv.visitJumpInsn(GOTO, loop);
    }

    /**
     * currentPos (slot 5) = method(input, currentPos, end, groups, depth); -1 as in emitChildRun.
     */
    private void emitChildCall(String method, Label fail) {
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(INVOKESPECIAL, className, method, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(ICONST_M1);
      if (fail != null) {
        mv.visitJumpInsn(IF_ICMPEQ, fail);
        return;
      }
      Label ok = new Label();
      mv.visitJumpInsn(IF_ICMPNE, ok);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(ok);
    }

    /**
     * Check if a node contains a quantifier that might need backtracking. Both greedy and
     * non-greedy quantifiers need backtracking when followed by more children. A possessive
     * quantifier or an atomic group never gives back.
     */
    private boolean containsBacktrackingQuantifier(RegexNode node) {
      if (node instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) node;
        if (q.possessive) {
          return false;
        }
        // Non-greedy quantifiers always need backtracking when followed by more children
        // because they return after minimum, and need to expand if rest fails
        if (!q.greedy && q.min != q.max) {
          return true;
        }
        // Greedy quantifiers need backtracking if they can match multiple lengths.
        // This includes ?, +, *, {n,m} with n<m — any variable-length quantifier followed by
        // more concat children may need to give back characters on failure.
        return q.min != q.max;
      }
      if (node instanceof GroupNode) {
        GroupNode g = (GroupNode) node;
        if (g.atomic) {
          return false;
        }
        // A GroupNode whose child concat has a trailing optional backref needs backtracking
        // at the outer concat level (e.g. (a\1?) where \1 could match variable-length).
        // Only detect this for non-self-referencing groups (self-ref groups are handled by
        // partial-open sentinel and always return a fixed length).
        if (g.groupNumber > 0 && !PatternAnalyzer.hasSelfReferencingBackref(g)) {
          if (extractTrailingOptionalBackref(g.child) != null) {
            return true;
          }
          // Trailing optional greedy quantifier changes the group boundary, requiring
          // the same two-path backtracking as a trailing optional backref.
          if (hasTrailingOptionalQuantifier(g.child)) {
            return true;
          }
        }
        return containsBacktrackingQuantifier(g.child);
      }
      return false;
    }

    private boolean hasTrailingOptionalQuantifier(RegexNode node) {
      if (node instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) node;
        return q.min == 0 && q.max == 1 && q.greedy;
      }
      if (node instanceof ConcatNode) {
        ConcatNode concat = (ConcatNode) node;
        if (concat.children.isEmpty()) {
          return false;
        }
        RegexNode last = concat.children.get(concat.children.size() - 1);
        if (last instanceof QuantifierNode) {
          QuantifierNode q = (QuantifierNode) last;
          return q.min == 0 && q.max == 1 && q.greedy;
        }
      }
      return false;
    }

    /** Returns true if the subroutine's target pattern contains a variable-length quantifier. */
    private boolean hasVariableLengthTarget(SubroutineNode node) {
      RegexNode targetNode = isRootCall(node) ? ast : callTarget(node);
      return targetNode != null && containsVariableLengthQuantifier(targetNode);
    }

    private boolean containsVariableLengthQuantifier(RegexNode node) {
      if (node instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) node;
        return q.min != q.max;
      }
      if (node instanceof ConcatNode) {
        for (RegexNode child : ((ConcatNode) node).children) {
          if (containsVariableLengthQuantifier(child)) return true;
        }
      }
      if (node instanceof AlternationNode) {
        for (RegexNode alt : ((AlternationNode) node).alternatives) {
          if (containsVariableLengthQuantifier(alt)) return true;
        }
      }
      if (node instanceof GroupNode) {
        return containsVariableLengthQuantifier(((GroupNode) node).child);
      }
      return false;
    }

    /**
     * Extract the trailing optional backref quantifier from a concat or single node, if present.
     * Returns the QuantifierNode if the node ends with a greedy Quant(Backref, min=0, max=1) (i.e.,
     * exactly {@code \1?}), otherwise null.
     */
    private QuantifierNode extractTrailingOptionalBackref(RegexNode node) {
      if (node instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) node;
        if (q.min == 0 && q.max == 1 && q.greedy && q.child instanceof BackreferenceNode) {
          return q;
        }
        return null;
      }
      if (node instanceof ConcatNode) {
        ConcatNode concat = (ConcatNode) node;
        if (concat.children.isEmpty()) {
          return null;
        }
        RegexNode last = concat.children.get(concat.children.size() - 1);
        if (last instanceof QuantifierNode) {
          QuantifierNode q = (QuantifierNode) last;
          if (q.min == 0 && q.max == 1 && q.greedy && q.child instanceof BackreferenceNode) {
            return q;
          }
        }
        return null;
      }
      return null;
    }

    /**
     * Generate bytecode to save groups array by cloning. Generates: savedGroups = groups.clone()
     *
     * @param groupsVar Local variable slot containing groups array
     * @param savedGroupsVar Local variable slot to store cloned array
     */
    private void generateGroupArraySave(int groupsVar, int savedGroupsVar) {
      mv.visitVarInsn(ALOAD, groupsVar); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, savedGroupsVar); // savedGroups
    }

    /**
     * Generate bytecode to restore groups array from saved copy. Generates:
     * System.arraycopy(savedGroups, 0, groups, 0, savedGroups.length)
     *
     * @param savedGroupsVar Local variable slot containing saved groups array
     * @param groupsVar Local variable slot containing current groups array
     */
    private void generateGroupArrayRestore(int savedGroupsVar, int groupsVar) {
      mv.visitVarInsn(ALOAD, savedGroupsVar); // savedGroups (src)
      mv.visitInsn(ICONST_0); // srcPos = 0
      mv.visitVarInsn(ALOAD, groupsVar); // groups (dest)
      mv.visitInsn(ICONST_0); // destPos = 0
      mv.visitVarInsn(ALOAD, savedGroupsVar); // savedGroups
      mv.visitInsn(ARRAYLENGTH); // length = savedGroups.length
      mv.visitMethodInsn(
          INVOKESTATIC,
          "java/lang/System",
          "arraycopy",
          "(Ljava/lang/Object;ILjava/lang/Object;II)V",
          false);
    }

    /**
     * Generate concat with backtracking for quantifiers (both greedy and non-greedy).
     *
     * <p>Greedy quantifiers (a+b): Start from maxMatches, decrement to min on failure. Non-greedy
     * quantifiers (a+?b): Start from min, increment to maxMatches on failure.
     */
    private void generateConcatWithBacktracking(
        ConcatNode node, int backtrackChildIndex, int localBase) {
      // Local variable allocation:
      // slot 2: depth (repurposed from pos — pos is dead once moved to slot 5)
      // slot 5: currentPos (repurposed from depth parameter)
      // slot 6: savedGroups (int[])
      // slot 7: quantifierStartPos
      // slot 8: maxMatches (greedy match count)
      // slot 9: tryMatchCount (backtracking loop variable)
      // slot 10: matchCount (for quantifier matching loop)
      // slot 11: result (temporary for method calls)
      // localBase: R2 fast give-back iterEnd (int[], or int when max == 1), else the int
      //            greedyClean flag; nested backtracking starts above it

      // Preserve depth before repurposing slot 5 as currentPos.
      // Push pos then depth in order; store depth into the now-dead pos slot (2),
      // then store pos into slot 5 (currentPos = pos).
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitVarInsn(ISTORE, 2); // depth → slot 2 (pos slot repurposed)
      mv.visitVarInsn(ISTORE, 5); // slot 5 = currentPos (= original pos)
      depthSlot = 2;

      // Process children before the backtracking point
      emitChildRun(node, 0, backtrackChildIndex, null);

      RegexNode greedyChild = node.children.get(backtrackChildIndex);
      QuantifierNode quantNode = extractQuantifier(greedyChild);

      // Save groups array AFTER matching children before the backtracking point
      // This preserves any group captures set by those children during backtracking.
      // When nothing from the backtracking point on can write groups, the snapshot and its
      // restores are no-ops and are skipped (the two-path group handling always needs slot 6).
      boolean saveGroups = quantNode == null || !isGroupsNeutralFrom(node, backtrackChildIndex);
      if (saveGroups) {
        generateGroupArraySave(4, 6); // savedGroups = groups.clone()
      }

      // Save position before quantifier
      mv.visitVarInsn(ILOAD, 5); // quantifierStartPos = currentPos
      mv.visitVarInsn(ISTORE, 7);

      // Initialize last iteration tracking (slots 12, 13)
      // For POSIX last-match semantics: capture positions from last successful iteration
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, 12); // lastIterationStart = -1
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = -1

      // Check if this is a capturing group (we need to set group boundaries)
      int captureGroupNumber = -1;
      if (greedyChild instanceof GroupNode) {
        GroupNode groupNode = (GroupNode) greedyChild;
        if (groupNode.groupNumber > 0) {
          captureGroupNumber = groupNode.groupNumber;
        }
      }

      if (quantNode == null) {
        // Generate two-path backtracking: try full group first, then mandatory-only.
        if (greedyChild instanceof GroupNode) {
          GroupNode backtrackGroup = (GroupNode) greedyChild;
          QuantifierNode trailingOpt = extractTrailingOptionalBackref(backtrackGroup.child);
          boolean needsTwoPathBacktrack =
              trailingOpt != null || hasTrailingOptionalQuantifier(backtrackGroup.child);
          if (needsTwoPathBacktrack) {
            // Emit a local fail label that returns -1
            Label localFail = new Label();
            generateGroupWithOptionalBacktracking(
                node, backtrackChildIndex, backtrackGroup, 11, 6, 7, localFail);
            // On success, slot 5 (currentPos) is up-to-date; return it
            mv.visitVarInsn(ILOAD, 5);
            mv.visitInsn(IRETURN);
            // Emit the fail path
            mv.visitLabel(localFail);
            mv.visitInsn(ICONST_M1);
            mv.visitInsn(IRETURN);
            return;
          }
        }
        // Not a quantifier and not a handled group type, fall back to simple concat
        generateSimpleConcat(node, backtrackChildIndex);
        return;
      }

      // Generate parser for quantifier's child
      generateParserMethod(cw, className, quantNode.child);
      String quantChildMethod = getMethodNameForNode(quantNode.child);

      // R2 fast give-back: when the quantifier body is groups-neutral, re-matching it k times
      // from the start on every backtracking level is pure waste (deterministic body, no group
      // writes): record each iteration's end position during the greedy consume and look it up
      // instead. This turns .*-prefix give-back from O(n^2) into O(n), and a quantified
      // subroutine call from O(2^depth) into O(depth). localBase: iterEnd[k] = position after k
      // iterations; for max == 1 only iterEnd[1] is needed and it lives in an int slot.
      boolean fastGiveBack = isGroupsNeutral(quantNode.child);
      boolean singleIter = fastGiveBack && quantNode.max == 1;
      if (singleIter) {
        // iterEnd1 = quantifierStartPos (overwritten by the one greedy iteration, if any)
        mv.visitVarInsn(ILOAD, 7);
        mv.visitVarInsn(ISTORE, localBase);
      } else if (fastGiveBack) {
        // iterEnd = new int[(end - quantifierStartPos) + min + 2]
        // (progress iterations <= end-start; empty iterations counted only while below min)
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ILOAD, 7); // quantifierStartPos
        mv.visitInsn(ISUB);
        BytecodeUtil.pushInt(mv, quantNode.min + 2);
        mv.visitInsn(IADD);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, localBase);
        // iterEnd[0] = quantifierStartPos (read as the last iteration's start when k == 1)
        mv.visitVarInsn(ALOAD, localBase);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitInsn(IASTORE);
      }
      // Greedy body that may write groups: the first backtracking step (k == maxMatches) can
      // keep the greedy pass's position and groups instead of replaying it, unless the greedy
      // loop ended on a failed child call, which may have left partial captures behind.
      // localBase: greedyClean flag.
      boolean skipFirstReplay = quantNode.greedy && !fastGiveBack;
      if (skipFirstReplay) {
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, localBase); // greedyClean = true
      }

      // First, do a greedy match to find maxMatches
      // matchCount = 0 (slot 10)
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, 10);

      // Match greedily
      Label greedyLoop = new Label();
      Label greedyEnd = new Label();

      mv.visitLabel(greedyLoop);

      // Check max limit
      if (quantNode.max != -1 && quantNode.max != Integer.MAX_VALUE) {
        mv.visitVarInsn(ILOAD, 10);
        BytecodeUtil.pushInt(mv, quantNode.max);
        mv.visitJumpInsn(IF_ICMPGE, greedyEnd);
      }

      // Try to match
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, quantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 11); // result in slot 11

      // If failed, stop
      Label greedyFailed = new Label();
      mv.visitVarInsn(ILOAD, 11);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, skipFirstReplay ? greedyFailed : greedyEnd);

      if (skipFirstReplay) {
        // Last iteration bounds, as the replay loop would record them (POSIX slots 12/13)
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ISTORE, 12); // lastIterationStart = currentPos
        mv.visitVarInsn(ILOAD, 11);
        mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = result
      }

      // Check for empty match (no progress) - prevents infinite loop on (a?)+
      mv.visitVarInsn(ILOAD, 11); // result
      mv.visitVarInsn(ILOAD, 5); // currentPos (before update)
      Label madeProgressGreedy = new Label();
      mv.visitJumpInsn(IF_ICMPNE, madeProgressGreedy);
      // Zero-width match: count it. For patterns like \A{3,} the anchor is always zero-width;
      // we must keep counting until matchCount reaches min before stopping, otherwise the
      // minimum repetition requirement won't be satisfied.
      mv.visitIincInsn(10, 1); // matchCount++
      emitIterEndStore(fastGiveBack, singleIter, localBase, 10);
      if (quantNode.min > 1) {
        // If matchCount is still below min, continue counting (safe: pos doesn't change)
        mv.visitVarInsn(ILOAD, 10); // matchCount
        BytecodeUtil.pushInt(mv, quantNode.min);
        mv.visitJumpInsn(IF_ICMPLT, greedyLoop); // matchCount < min → keep going
      }
      mv.visitJumpInsn(GOTO, greedyEnd);

      mv.visitLabel(madeProgressGreedy);
      // Success: update currentPos and matchCount
      mv.visitVarInsn(ILOAD, 11);
      mv.visitVarInsn(ISTORE, 5); // currentPos = result
      mv.visitIincInsn(10, 1); // matchCount++
      emitIterEndStore(fastGiveBack, singleIter, localBase, 10);
      mv.visitJumpInsn(GOTO, greedyLoop);

      mv.visitLabel(greedyFailed);
      if (skipFirstReplay) {
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, localBase); // greedyClean = false
      }
      mv.visitLabel(greedyEnd);

      // Check if we matched minimum required
      if (quantNode.min > 0) {
        Label minOk = new Label();
        mv.visitVarInsn(ILOAD, 10); // matchCount
        BytecodeUtil.pushInt(mv, quantNode.min);
        mv.visitJumpInsn(IF_ICMPGE, minOk);
        // Failed to match minimum
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(minOk);
      }

      // Save maxMatches (slot 8)
      mv.visitVarInsn(ILOAD, 10);
      mv.visitVarInsn(ISTORE, 8);

      // Now try rest of pattern with backtracking
      // Greedy: tryMatchCount = maxMatches, decrement on failure
      // Non-greedy: tryMatchCount = min, increment on failure
      if (quantNode.greedy) {
        mv.visitVarInsn(ILOAD, 8); // tryMatchCount = maxMatches
      } else {
        BytecodeUtil.pushInt(mv, quantNode.min); // tryMatchCount = min
      }
      mv.visitVarInsn(ISTORE, 9);

      Label backtrackLoop = new Label();
      Label backtrackEnd = new Label();

      mv.visitLabel(backtrackLoop);

      // Check loop termination:
      // Greedy: exit when tryMatchCount < min
      // Non-greedy: exit when tryMatchCount > maxMatches
      mv.visitVarInsn(ILOAD, 9);
      if (quantNode.greedy) {
        BytecodeUtil.pushInt(mv, quantNode.min);
        mv.visitJumpInsn(IF_ICMPLT, backtrackEnd);
      } else {
        mv.visitVarInsn(ILOAD, 8); // maxMatches
        mv.visitJumpInsn(IF_ICMPGT, backtrackEnd);
      }

      Label matchEnd = new Label();
      if (skipFirstReplay) {
        // First step after a clean greedy pass: slot 5, slots 12/13 and the groups still hold
        // its k == maxMatches state.
        Label replay = new Label();
        mv.visitVarInsn(ILOAD, localBase); // greedyClean
        mv.visitJumpInsn(IFEQ, replay);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, localBase); // greedyClean = false
        mv.visitJumpInsn(GOTO, matchEnd);
        mv.visitLabel(replay);
      }

      // Restore groups by copying contents (not replacing reference)
      if (saveGroups) {
        generateGroupArrayRestore(
            6, 4); // System.arraycopy(savedGroups, 0, groups, 0, savedGroups.length)
      }

      Label matchEndFast = new Label();
      if (fastGiveBack) {
        // currentPos = (tryMatchCount == 0) ? quantifierStartPos : iterEnd[tryMatchCount]
        // POSIX last-iteration tracking (slots 12/13): only ever read when tryMatchCount == 1
        // (inner-shrink retry); k == 0 leaves them stale exactly like the re-match path.
        Label kIsPositive = new Label();
        mv.visitVarInsn(ILOAD, 9); // tryMatchCount
        mv.visitJumpInsn(IFGT, kIsPositive);
        mv.visitVarInsn(ILOAD, 7); // quantifierStartPos
        mv.visitVarInsn(ISTORE, 5);
        mv.visitJumpInsn(GOTO, matchEndFast);
        mv.visitLabel(kIsPositive);
        if (singleIter) {
          mv.visitVarInsn(ILOAD, localBase); // iterEnd1
          mv.visitVarInsn(ISTORE, 5);
          mv.visitVarInsn(ILOAD, 7);
          mv.visitVarInsn(ISTORE, 12); // lastIterationStart = quantifierStartPos
        } else {
          mv.visitVarInsn(ALOAD, localBase); // iterEnd
          mv.visitVarInsn(ILOAD, 9);
          mv.visitInsn(IALOAD);
          mv.visitVarInsn(ISTORE, 5);
          mv.visitVarInsn(ALOAD, localBase);
          mv.visitVarInsn(ILOAD, 9);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(ISUB);
          mv.visitInsn(IALOAD);
          mv.visitVarInsn(ISTORE, 12); // lastIterationStart = iterEnd[k-1]
        }
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = iterEnd[k]
        mv.visitJumpInsn(GOTO, matchEndFast);
      }

      // Reset position
      mv.visitVarInsn(ILOAD, 7); // quantifierStartPos
      mv.visitVarInsn(ISTORE, 5); // currentPos = quantifierStartPos

      // Match quantifier exactly tryMatchCount times
      // matchCount = 0
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, 10);

      Label matchLoop = new Label();

      mv.visitLabel(matchLoop);

      // if (matchCount >= tryMatchCount) goto matchEnd
      mv.visitVarInsn(ILOAD, 10);
      mv.visitVarInsn(ILOAD, 9);
      mv.visitJumpInsn(IF_ICMPGE, matchEnd);

      // Save iteration start position (for POSIX last-match semantics)
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ISTORE, 12); // lastIterationStart = currentPos

      // Try to match
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, quantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 11);

      // If match failed, this tryMatchCount won't work - backtrack immediately
      mv.visitVarInsn(ILOAD, 11);
      mv.visitInsn(ICONST_M1);
      Label matchSuccess = new Label();
      mv.visitJumpInsn(IF_ICMPNE, matchSuccess);
      // Failed - adjust tryMatchCount: decrement for greedy, increment for non-greedy
      mv.visitIincInsn(9, quantNode.greedy ? -1 : 1);
      mv.visitJumpInsn(GOTO, backtrackLoop);

      mv.visitLabel(matchSuccess);
      // Save iteration end position (for POSIX last-match semantics)
      mv.visitVarInsn(ILOAD, 11); // result position
      mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = result

      // Update position and count
      mv.visitVarInsn(ILOAD, 11);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitIincInsn(10, 1);
      mv.visitJumpInsn(GOTO, matchLoop);

      mv.visitLabel(matchEndFast); // fast give-back join (empty label when not emitted)
      mv.visitLabel(matchEnd);

      // If this is a capturing group, set boundaries for the ENTIRE quantifier match.
      // For pattern (a+), group 1 should capture "aaa", not just the last "a".
      // The quantifier is INSIDE the group, so we capture from quantifierStartPos to currentPos.
      // Note: POSIX last-match semantics ((a)+ capturing last 'a') is for quantifiers OUTSIDE the
      // group,
      // which is a separate issue handled in GroupCaptureLastMatchTest.
      // groups[captureGroupNumber * 2] = quantifierStartPos
      // groups[captureGroupNumber * 2 + 1] = currentPos
      if (captureGroupNumber > 0) {
        // Set group start from quantifier start position
        // S: [] -> [A:groups, I:index, I:quantifierStartPos]
        mv.visitVarInsn(ALOAD, 4); // groups
        BytecodeUtil.pushInt(mv, captureGroupNumber * 2);
        mv.visitVarInsn(ILOAD, 7); // quantifierStartPos
        // S: [A:groups, I:index, I:quantifierStartPos] -> []
        mv.visitInsn(IASTORE);

        // Set group end from current position (after all iterations)
        // S: [] -> [A:groups, I:index, I:currentPos]
        mv.visitVarInsn(ALOAD, 4); // groups
        BytecodeUtil.pushInt(mv, captureGroupNumber * 2 + 1);
        mv.visitVarInsn(ILOAD, 5); // currentPos (after quantifier matched)
        // S: [A:groups, I:index, I:currentPos] -> []
        mv.visitInsn(IASTORE);
      }

      // Now try remaining children
      // We need to try all of them, and if any fails, backtrack
      // IMPORTANT: Remaining children may also contain backtracking quantifiers
      // that need their own nested backtracking loops
      Label tryRemainingChildren = new Label();
      mv.visitLabel(tryRemainingChildren);

      // Check if remaining children need nested backtracking
      int nestedBacktrackIndex = -1;
      for (int i = backtrackChildIndex + 1; i < node.children.size() - 1; i++) {
        if (containsBacktrackingQuantifier(node.children.get(i))) {
          nestedBacktrackIndex = i;
          break;
        }
      }

      // Label used in the simple-case suffix-failure path: either shrink the last quantifier
      // iteration and retry the suffix, or fall back to the standard outer backtracking.
      Label doBacktrackOrShrink = new Label();

      if (nestedBacktrackIndex == -1) {
        // Simple case: no nested backtracking needed. A SubroutineNode with a variable-length
        // target not in last position gets a retry loop, so the subroutine can be retried with a
        // smaller end bound if the continuation fails; that loop matches the children after it.
        int n = node.children.size();
        int retryIndex = n;
        for (int i = backtrackChildIndex + 1; i + 1 < n; i++) {
          RegexNode child = node.children.get(i);
          if (child instanceof SubroutineNode && hasVariableLengthTarget((SubroutineNode) child)) {
            retryIndex = i;
            break;
          }
        }

        // A failed child attempts inner-iteration shrink or outer backtrack
        emitChildRun(node, backtrackChildIndex + 1, retryIndex, doBacktrackOrShrink);

        if (retryIndex < n) {
          SubroutineNode subroutineNode = (SubroutineNode) node.children.get(retryIndex);

          // Resolve the subroutine target method
          RegexNode targetNode = isRootCall(subroutineNode) ? ast : callTarget(subroutineNode);
          generateParserMethod(cw, className, targetNode);
          String targetMethod = getMethodNameForNode(targetNode);

          // Slots for retry loop (above the existing slot range 0-15):
          //   100: savedGroupsForRetry (int[])
          //   101: subResult (int)
          //   102: maxSubEnd (int)
          //   103: preSubroutinePos (int)

          // preSubroutinePos = currentPos (slot 5)
          mv.visitVarInsn(ILOAD, 5);
          mv.visitVarInsn(ISTORE, 103);
          // maxSubEnd = end (slot 3)
          mv.visitVarInsn(ILOAD, 3);
          mv.visitVarInsn(ISTORE, 102);
          // A target that cannot write groups needs no snapshot: the restores would be no-ops
          boolean saveForRetry = !isGroupsNeutral(targetNode);
          if (saveForRetry) {
            // savedGroupsForRetry = groups.clone()
            mv.visitVarInsn(ALOAD, 4);
            mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "[I");
            mv.visitVarInsn(ASTORE, 100);
          }

          Label retryLabel = new Label();
          Label subroutineExhaustedLabel = new Label();
          Label subroutineSuccessLabel = new Label();

          mv.visitLabel(retryLabel);

          // Guard: if maxSubEnd < preSubroutinePos, subroutine exhausted
          mv.visitVarInsn(ILOAD, 102);
          mv.visitVarInsn(ILOAD, 103);
          mv.visitJumpInsn(IF_ICMPLT, subroutineExhaustedLabel);

          // Restore groups to pre-subroutine state before each retry
          if (saveForRetry) {
            generateGroupArrayRestore(100, 4);
          }

          // Call subroutine: subResult = targetMethod(input, preSubroutinePos, maxSubEnd, groups,
          // depth+1)
          mv.visitVarInsn(ALOAD, 0);
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 103); // preSubroutinePos
          mv.visitVarInsn(ILOAD, 102); // maxSubEnd
          mv.visitVarInsn(ALOAD, 4);
          mv.visitVarInsn(ILOAD, depthSlot);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IADD);
          mv.visitMethodInsn(
              INVOKESPECIAL, className, targetMethod, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 101);

          // PCRE semantics: restore groups after subroutine call
          if (saveForRetry) {
            generateGroupArrayRestore(100, 4);
          }

          // If subroutine itself failed, exhaust
          mv.visitVarInsn(ILOAD, 101);
          mv.visitInsn(ICONST_M1);
          mv.visitJumpInsn(IF_ICMPEQ, subroutineExhaustedLabel);

          // Subroutine succeeded: update currentPos
          mv.visitVarInsn(ILOAD, 101);
          mv.visitVarInsn(ISTORE, 5);

          // Try remaining children; a failed one retries the subroutine with a smaller end
          Label childJFailed = new Label();
          emitChildRun(node, retryIndex + 1, n, childJFailed);

          // All j-children succeeded
          mv.visitJumpInsn(GOTO, subroutineSuccessLabel);

          mv.visitLabel(childJFailed);
          // maxSubEnd = subResult - 1
          mv.visitVarInsn(ILOAD, 101);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(ISUB);
          mv.visitVarInsn(ISTORE, 102);
          mv.visitJumpInsn(GOTO, retryLabel);

          mv.visitLabel(subroutineExhaustedLabel);
          // Subroutine exhausted: backtrack outer quantifier
          mv.visitIincInsn(9, quantNode.greedy ? -1 : 1);
          mv.visitJumpInsn(GOTO, backtrackLoop);

          mv.visitLabel(subroutineSuccessLabel);
        }

        // All suffix children succeeded: jump past the doBacktrackOrShrink block so we don't
        // accidentally enter it on the fall-through path from the last child-succeeded label.
        Label skipBacktrackOrShrink = new Label();
        mv.visitJumpInsn(GOTO, skipBacktrackOrShrink);

        // Inner last-iteration retry: when the suffix fails and tryMatchCount==1 and the last
        // quantifier iteration made forward progress, try calling quantChildMethod again with a
        // smaller end bound.  This lets patterns like (c+){0,}\1+ find the solution where the
        // group captures a shorter prefix (e.g. "c") rather than the greedy-maximum ("cc").
        //
        // We reuse slot 13 (lastIterationEnd) as the current shrink-end limit.  It already holds
        // the position where the last iteration ended; decrementing it narrows the bound for the
        // next retry.  When the retry produces a new (shorter) result we store it back into
        // slot 13, so the decrement loop always starts from the most recent result.
        //
        // Only applies to greedy quantifiers for now; non-greedy already iterates upward so
        // the outer tryMatchCount++ covers the equivalent exploration.
        mv.visitLabel(doBacktrackOrShrink);
        {
          Label doOuterBacktrack = new Label();

          // Only attempt inner shrink for greedy quantifiers with tryMatchCount == 1
          if (quantNode.greedy) {
            // Check tryMatchCount == 1
            mv.visitVarInsn(ILOAD, 9); // tryMatchCount
            mv.visitInsn(ICONST_1);
            mv.visitJumpInsn(IF_ICMPNE, doOuterBacktrack);

            // Check lastIterationEnd (slot 13) > lastIterationStart (slot 12): progress was made
            mv.visitVarInsn(ILOAD, 13); // lastIterationEnd (= current shrinkEnd)
            mv.visitVarInsn(ILOAD, 12); // lastIterationStart
            mv.visitJumpInsn(IF_ICMPLE, doOuterBacktrack);

            // Check slot-13 - 1 > lastIterationStart: room to shrink one more step
            mv.visitVarInsn(ILOAD, 13); // current shrinkEnd
            mv.visitInsn(ICONST_1);
            mv.visitInsn(ISUB); // shrinkEnd - 1
            mv.visitVarInsn(ILOAD, 12); // lastIterationStart
            mv.visitJumpInsn(IF_ICMPLE, doOuterBacktrack);

            // Decrement the shrink-end limit (slot 13)
            mv.visitIincInsn(13, -1);

            // Restore groups from savedGroups (same as the start of backtrackLoop)
            if (saveGroups) {
              generateGroupArrayRestore(6, 4);
            }

            // Reset currentPos to lastIterationStart
            mv.visitVarInsn(ILOAD, 12); // lastIterationStart
            mv.visitVarInsn(ISTORE, 5); // currentPos

            // Retry the single iteration with the new shrinkEnd (slot 13) as the end bound
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 5); // pos = lastIterationStart
            mv.visitVarInsn(ILOAD, 13); // shrinkEnd (decremented)
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, depthSlot);
            mv.visitMethodInsn(
                INVOKESPECIAL, className, quantChildMethod, "(Ljava/lang/String;II[II)I", false);
            mv.visitVarInsn(ISTORE, 11); // result

            // If the child can't match even at the shorter end, fall through to outer backtrack
            mv.visitVarInsn(ILOAD, 11);
            mv.visitInsn(ICONST_M1);
            mv.visitJumpInsn(IF_ICMPEQ, doOuterBacktrack);

            // Update currentPos; store the new result back into slot 13 so the next potential
            // shrink step starts from the correct (shorter) position.
            mv.visitVarInsn(ILOAD, 11);
            mv.visitVarInsn(ISTORE, 5); // currentPos = result
            mv.visitVarInsn(ILOAD, 11);
            mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = result (new shrinkEnd baseline)

            // Re-set captureGroupNumber boundaries if this quantifier sits directly inside a
            // capturing group (captureGroupNumber tracks that case).
            if (captureGroupNumber > 0) {
              mv.visitVarInsn(ALOAD, 4);
              BytecodeUtil.pushInt(mv, captureGroupNumber * 2);
              mv.visitVarInsn(ILOAD, 12); // lastIterationStart
              mv.visitInsn(IASTORE);
              mv.visitVarInsn(ALOAD, 4);
              BytecodeUtil.pushInt(mv, captureGroupNumber * 2 + 1);
              mv.visitVarInsn(ILOAD, 5); // currentPos after shrunk iteration
              mv.visitInsn(IASTORE);
            }

            // Jump back to retry the suffix children with the shorter last-iteration result
            mv.visitJumpInsn(GOTO, tryRemainingChildren);
          }

          // Standard outer backtrack: adjust tryMatchCount and restart
          mv.visitLabel(doOuterBacktrack);
          mv.visitIincInsn(9, quantNode.greedy ? -1 : 1);
          mv.visitJumpInsn(GOTO, backtrackLoop);
        }

        // Land here when all suffix children succeeded (the GOTO above skips the
        // doBacktrackOrShrink block on the success path).
        mv.visitLabel(skipBacktrackOrShrink);
      } else {
        // Complex case: nested backtracking needed
        // Process children before the nested backtrack point
        emitChildRunOrBacktrack(
            node,
            backtrackChildIndex + 1,
            nestedBacktrackIndex,
            9,
            quantNode.greedy ? -1 : 1,
            backtrackLoop);

        // Generate nested backtracking for the remaining children
        // Use localBase+1.. for nested backtracking (above this level's iterEnd, and clear of
        // the fixed slots 2-15); each nested level grows its base by 6.
        generateNestedBacktracking(
            node,
            nestedBacktrackIndex,
            backtrackLoop,
            quantNode.greedy ? -1 : 1,
            9,
            localBase + 1,
            1);
      }

      // All remaining children succeeded.
      // For a lazy quantifier in matches() mode: if the position hasn't reached the required
      // end yet, extend by one more iteration so the full input can be covered.
      if (!quantNode.greedy) {
        Label returnNow = new Label();
        // If lazyFindMode == true (find mode), return immediately on first success.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "lazyFindMode", "Z");
        mv.visitJumpInsn(IFNE, returnNow);
        // Matches mode: if pos < end, try one more quantifier iteration.
        mv.visitVarInsn(ILOAD, 5); // currentPos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitJumpInsn(IF_ICMPGE, returnNow); // pos >= end → done
        mv.visitIincInsn(9, 1); // tryMatchCount++ (lazy: increment toward max)
        mv.visitJumpInsn(GOTO, backtrackLoop);
        mv.visitLabel(returnNow);
      }
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IRETURN);

      mv.visitLabel(backtrackEnd);
      // Exhausted all backtrack attempts
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    /**
     * Generate nested backtracking for a quantifier at nestedIndex within remaining children. Uses
     * dynamically allocated slots to support multiple nesting levels.
     *
     * @param node The concat node containing all children
     * @param nestedIndex Index of the backtracking quantifier to handle
     * @param outerBacktrackLoop Label to jump to when this level exhausts backtracking
     * @param outerBacktrackDelta Amount to adjust outer tryMatchCount slot before jumping
     * @param outerTryMatchCountSlot Slot containing outer level's tryMatchCount (for adjustment);
     *     negative when there is none
     * @param slotBase Base slot for this nesting level; the next level starts at slotBase + 7
     * @param level Nesting levels generated in this method, this one included
     */
    private void generateNestedBacktracking(
        ConcatNode node,
        int nestedIndex,
        Label outerBacktrackLoop,
        int outerBacktrackDelta,
        int outerTryMatchCountSlot,
        int slotBase,
        int level) {
      // Slots for this level's nested backtracking (relative to slotBase):
      // slotBase + 0: nestedSavedGroups
      // slotBase + 1: nestedQuantifierStartPos
      // slotBase + 2: nestedMaxMatches
      // slotBase + 3: nestedTryMatchCount
      // slotBase + 4: nestedMatchCount
      // slotBase + 5: nestedResult
      // slotBase + 6: iterEnd (int[], or int when max == 1) or the int greedyClean flag, as in
      //               generateConcatWithBacktracking's localBase
      final int SAVED_GROUPS_SLOT = slotBase;
      final int QUANT_START_POS_SLOT = slotBase + 1;
      final int MAX_MATCHES_SLOT = slotBase + 2;
      final int TRY_MATCH_COUNT_SLOT = slotBase + 3;
      final int MATCH_COUNT_SLOT = slotBase + 4;
      final int RESULT_SLOT = slotBase + 5;
      final int ITER_SLOT = slotBase + 6;

      RegexNode nestedChild = node.children.get(nestedIndex);
      QuantifierNode nestedQuantNode = extractQuantifier(nestedChild);

      if (nestedQuantNode == null) {
        // Apply two-path backtracking when a non-quantified GroupNode has a trailing optional
        // quantifier. Reuse slotBase+0..1 (unused in this path) for pos/groups snapshot.
        if (nestedChild instanceof GroupNode) {
          GroupNode nestedGroup = (GroupNode) nestedChild;
          if (hasTrailingOptionalQuantifier(nestedGroup.child)) {
            int savedPosBefore = slotBase;
            int savedGroupsBefore = slotBase + 1;
            mv.visitVarInsn(ILOAD, 5);
            mv.visitVarInsn(ISTORE, savedPosBefore);
            generateGroupArraySave(4, savedGroupsBefore);
            Label localFail = new Label();
            Label successDone = new Label();
            generateGroupWithOptionalBacktracking(
                node,
                nestedIndex,
                nestedGroup,
                slotBase + 2,
                savedGroupsBefore,
                savedPosBefore,
                localFail);
            mv.visitJumpInsn(GOTO, successDone);
            mv.visitLabel(localFail);
            emitBacktrack(outerTryMatchCountSlot, outerBacktrackDelta, outerBacktrackLoop);
            mv.visitLabel(successDone);
            return;
          }
        }
        // Not a quantifier - process it and the rest normally
        emitChildRunOrBacktrack(
            node,
            nestedIndex,
            node.children.size(),
            outerTryMatchCountSlot,
            outerBacktrackDelta,
            outerBacktrackLoop);
        return;
      }

      // Check if nested child is a capturing group
      int nestedCaptureGroupNumber = -1;
      if (nestedChild instanceof GroupNode) {
        GroupNode groupNode = (GroupNode) nestedChild;
        if (groupNode.groupNumber > 0) {
          nestedCaptureGroupNumber = groupNode.groupNumber;
        }
      }

      // Save groups for nested backtracking (skipped when the snapshot cannot change)
      boolean saveGroups = !isGroupsNeutralFrom(node, nestedIndex);
      if (saveGroups) {
        generateGroupArraySave(4, SAVED_GROUPS_SLOT);
      }

      // Save position before nested quantifier
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ISTORE, QUANT_START_POS_SLOT);

      // Generate parser for nested quantifier's child
      generateParserMethod(cw, className, nestedQuantNode.child);
      String nestedQuantChildMethod = getMethodNameForNode(nestedQuantNode.child);

      // Same replay avoidance as generateConcatWithBacktracking: iterEnd lookup for a
      // groups-neutral body, otherwise reuse of a clean greedy pass for the first greedy step.
      boolean fastGiveBack = isGroupsNeutral(nestedQuantNode.child);
      boolean singleIter = fastGiveBack && nestedQuantNode.max == 1;
      boolean skipFirstReplay = nestedQuantNode.greedy && !fastGiveBack;
      if (singleIter) {
        mv.visitVarInsn(ILOAD, QUANT_START_POS_SLOT);
        mv.visitVarInsn(ISTORE, ITER_SLOT); // iterEnd1 = start
      } else if (fastGiveBack) {
        // iterEnd = new int[(end - start) + 2] (at most one empty iteration is counted)
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, QUANT_START_POS_SLOT);
        mv.visitInsn(ISUB);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(IADD);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, ITER_SLOT);
      } else if (skipFirstReplay) {
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, ITER_SLOT); // greedyClean = true
      }

      // Greedy match for nested quantifier
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, MATCH_COUNT_SLOT); // nestedMatchCount = 0

      Label nestedGreedyLoop = new Label();
      Label nestedGreedyEnd = new Label();

      mv.visitLabel(nestedGreedyLoop);

      // Check max limit
      if (nestedQuantNode.max != -1 && nestedQuantNode.max != Integer.MAX_VALUE) {
        mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
        BytecodeUtil.pushInt(mv, nestedQuantNode.max);
        mv.visitJumpInsn(IF_ICMPGE, nestedGreedyEnd);
      }

      // Try to match
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, nestedQuantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, RESULT_SLOT);

      Label nestedGreedyFailed = new Label();
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, skipFirstReplay ? nestedGreedyFailed : nestedGreedyEnd);

      // Check for empty match
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitVarInsn(ILOAD, 5);
      Label nestedMadeProgress = new Label();
      mv.visitJumpInsn(IF_ICMPNE, nestedMadeProgress);
      mv.visitIincInsn(MATCH_COUNT_SLOT, 1);
      emitIterEndStore(fastGiveBack, singleIter, ITER_SLOT, MATCH_COUNT_SLOT);
      mv.visitJumpInsn(GOTO, nestedGreedyEnd);

      mv.visitLabel(nestedMadeProgress);
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitIincInsn(MATCH_COUNT_SLOT, 1);
      emitIterEndStore(fastGiveBack, singleIter, ITER_SLOT, MATCH_COUNT_SLOT);
      mv.visitJumpInsn(GOTO, nestedGreedyLoop);

      mv.visitLabel(nestedGreedyFailed);
      if (skipFirstReplay) {
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, ITER_SLOT); // greedyClean = false
      }
      mv.visitLabel(nestedGreedyEnd);

      // Check minimum
      if (nestedQuantNode.min > 0) {
        Label nestedMinOk = new Label();
        mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
        BytecodeUtil.pushInt(mv, nestedQuantNode.min);
        mv.visitJumpInsn(IF_ICMPGE, nestedMinOk);
        // Failed minimum - backtrack outer
        emitBacktrack(outerTryMatchCountSlot, outerBacktrackDelta, outerBacktrackLoop);
        mv.visitLabel(nestedMinOk);
      }

      // Save maxMatches
      mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
      mv.visitVarInsn(ISTORE, MAX_MATCHES_SLOT);

      // Initialize tryMatchCount
      if (nestedQuantNode.greedy) {
        mv.visitVarInsn(ILOAD, MAX_MATCHES_SLOT);
      } else {
        BytecodeUtil.pushInt(mv, nestedQuantNode.min);
      }
      mv.visitVarInsn(ISTORE, TRY_MATCH_COUNT_SLOT);

      Label nestedBacktrackLoop = new Label();
      Label nestedBacktrackEnd = new Label();

      mv.visitLabel(nestedBacktrackLoop);

      // Check loop termination
      mv.visitVarInsn(ILOAD, TRY_MATCH_COUNT_SLOT);
      if (nestedQuantNode.greedy) {
        BytecodeUtil.pushInt(mv, nestedQuantNode.min);
        mv.visitJumpInsn(IF_ICMPLT, nestedBacktrackEnd);
      } else {
        mv.visitVarInsn(ILOAD, MAX_MATCHES_SLOT);
        mv.visitJumpInsn(IF_ICMPGT, nestedBacktrackEnd);
      }

      Label nestedMatchEnd = new Label();
      if (skipFirstReplay) {
        // First step after a clean greedy pass: slot 5 and the groups hold its state
        Label replay = new Label();
        mv.visitVarInsn(ILOAD, ITER_SLOT);
        mv.visitJumpInsn(IFEQ, replay);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, ITER_SLOT); // greedyClean = false
        mv.visitJumpInsn(GOTO, nestedMatchEnd);
        mv.visitLabel(replay);
      }

      // Restore groups
      if (saveGroups) {
        generateGroupArrayRestore(SAVED_GROUPS_SLOT, 4);
      }

      // Reset position
      mv.visitVarInsn(ILOAD, QUANT_START_POS_SLOT);
      mv.visitVarInsn(ISTORE, 5);

      if (fastGiveBack) {
        // currentPos = (tryMatchCount == 0) ? start : iterEnd[tryMatchCount]
        mv.visitVarInsn(ILOAD, TRY_MATCH_COUNT_SLOT);
        mv.visitJumpInsn(IFLE, nestedMatchEnd);
        if (singleIter) {
          mv.visitVarInsn(ILOAD, ITER_SLOT);
        } else {
          mv.visitVarInsn(ALOAD, ITER_SLOT);
          mv.visitVarInsn(ILOAD, TRY_MATCH_COUNT_SLOT);
          mv.visitInsn(IALOAD);
        }
        mv.visitVarInsn(ISTORE, 5);
        mv.visitJumpInsn(GOTO, nestedMatchEnd);
      }

      // Match exactly tryMatchCount times
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, MATCH_COUNT_SLOT);

      Label nestedMatchLoop = new Label();

      mv.visitLabel(nestedMatchLoop);
      mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
      mv.visitVarInsn(ILOAD, TRY_MATCH_COUNT_SLOT);
      mv.visitJumpInsn(IF_ICMPGE, nestedMatchEnd);

      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, nestedQuantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, RESULT_SLOT);

      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitInsn(ICONST_M1);
      Label nestedMatchSuccess = new Label();
      mv.visitJumpInsn(IF_ICMPNE, nestedMatchSuccess);
      mv.visitIincInsn(TRY_MATCH_COUNT_SLOT, nestedQuantNode.greedy ? -1 : 1);
      mv.visitJumpInsn(GOTO, nestedBacktrackLoop);

      mv.visitLabel(nestedMatchSuccess);
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitIincInsn(MATCH_COUNT_SLOT, 1);
      mv.visitJumpInsn(GOTO, nestedMatchLoop);

      mv.visitLabel(nestedMatchEnd);

      // Set group boundaries if capturing
      if (nestedCaptureGroupNumber > 0) {
        mv.visitVarInsn(ALOAD, 4);
        BytecodeUtil.pushInt(mv, nestedCaptureGroupNumber * 2);
        mv.visitVarInsn(ILOAD, QUANT_START_POS_SLOT);
        mv.visitInsn(IASTORE);

        mv.visitVarInsn(ALOAD, 4);
        BytecodeUtil.pushInt(mv, nestedCaptureGroupNumber * 2 + 1);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(IASTORE);
      }

      // Try remaining children after nested quantifier
      // Check if there's ANOTHER backtracking quantifier among remaining children
      int deeperNestedIndex = -1;
      for (int i = nestedIndex + 1; i < node.children.size() - 1; i++) {
        if (containsBacktrackingQuantifier(node.children.get(i))) {
          deeperNestedIndex = i;
          break;
        }
      }

      if (deeperNestedIndex == -1) {
        // No more nested backtracking needed - simple iteration; a failed child backtracks the
        // nested quantifier
        emitChildRunOrBacktrack(
            node,
            nestedIndex + 1,
            node.children.size(),
            TRY_MATCH_COUNT_SLOT,
            nestedQuantNode.greedy ? -1 : 1,
            nestedBacktrackLoop);
      } else {
        // MORE nested backtracking needed - process children before deeper nested, then recurse
        emitChildRunOrBacktrack(
            node,
            nestedIndex + 1,
            deeperNestedIndex,
            TRY_MATCH_COUNT_SLOT,
            nestedQuantNode.greedy ? -1 : 1,
            nestedBacktrackLoop);

        if (level < NESTED_LEVELS_PER_METHOD) {
          // Recursively generate deeper nested backtracking
          // Use slots slotBase + 7 for next level
          generateNestedBacktracking(
              node,
              deeperNestedIndex,
              nestedBacktrackLoop,
              nestedQuantNode.greedy ? -1 : 1,
              TRY_MATCH_COUNT_SLOT,
              slotBase + 7,
              level + 1);
        } else {
          // The deeper levels in their own method: its -1 (exhausted) backtracks this level
          Label deeperFailed = new Label();
          Label deeperDone = new Label();
          emitChildCall(generateNestedRestMethod(node, deeperNestedIndex), deeperFailed);
          mv.visitJumpInsn(GOTO, deeperDone);
          mv.visitLabel(deeperFailed);
          emitBacktrack(TRY_MATCH_COUNT_SLOT, nestedQuantNode.greedy ? -1 : 1, nestedBacktrackLoop);
          mv.visitLabel(deeperDone);
        }
      }

      // All children after nested quantifier succeeded - return success (handled by caller)
      // Jump past nestedBacktrackEnd since we succeeded
      Label nestedSuccess = new Label();
      mv.visitJumpInsn(GOTO, nestedSuccess);

      mv.visitLabel(nestedBacktrackEnd);
      // Nested backtracking exhausted - backtrack outer
      emitBacktrack(outerTryMatchCountSlot, outerBacktrackDelta, outerBacktrackLoop);

      mv.visitLabel(nestedSuccess);
      // Success path continues in caller
    }

    /**
     * Records {@code iterEnd[matchCount] = currentPos} for the fast give-back path: into the int
     * slot when {@code singleIter}, else into the int[] in {@code iterSlot}.
     */
    private void emitIterEndStore(
        boolean fastGiveBack, boolean singleIter, int iterSlot, int matchCountSlot) {
      if (singleIter) {
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ISTORE, iterSlot);
      } else if (fastGiveBack) {
        mv.visitVarInsn(ALOAD, iterSlot);
        mv.visitVarInsn(ILOAD, matchCountSlot);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(IASTORE);
      }
    }

    /** Extract QuantifierNode from a node, handling GroupNode wrappers. */
    private QuantifierNode extractQuantifier(RegexNode node) {
      if (node instanceof QuantifierNode) {
        return (QuantifierNode) node;
      }
      if (node instanceof GroupNode) {
        GroupNode groupNode = (GroupNode) node;
        if (groupNode.child instanceof QuantifierNode) {
          return (QuantifierNode) groupNode.child;
        }
      }
      return null;
    }

    /**
     * Generate backtracking for a GroupNode that has a trailing optional backref. Tries the full
     * group first (greedy); if the remaining concat children fail, retries with only the mandatory
     * prefix of the group (everything before the trailing optional backref). Recursively applies
     * the same logic for subsequent groups in the concat that also have trailing optional backrefs.
     *
     * <p>Uses slots already reserved by generateConcatWithBacktracking: 5=currentPos,
     * 6=savedGroups, 7=quantifierStartPos (position before the group). Allocates one extra slot via
     * {@code extraSlotBase} for the child result, and higher slots via {@code extraSlotBase+1} for
     * deeper recursion.
     *
     * <p>On overall failure, jumps to {@code overallFailLabel} (caller is responsible for emitting
     * any code at that label). On success, falls through with currentPos (slot 5) updated.
     *
     * @param node The outer ConcatNode
     * @param fromIndex Index of the GroupNode in node.children (first group to backtrack)
     * @param backtrackGroup The GroupNode at fromIndex
     * @param extraSlotBase Base for extra local variable slots (11 at the first level)
     * @param savedGroupsSlot Slot holding the saved groups snapshot to restore on backtrack
     * @param savedPosSlot Slot holding the saved position snapshot to restore on backtrack
     * @param overallFailLabel Label to jump to when both greedy and mandatory attempts fail
     */
    private void generateGroupWithOptionalBacktracking(
        ConcatNode node,
        int fromIndex,
        GroupNode backtrackGroup,
        int extraSlotBase,
        int savedGroupsSlot,
        int savedPosSlot,
        Label overallFailLabel) {

      int resultSlot = extraSlotBase; // temp result from group call

      // Build the "mandatory-only" parser node for this group.
      RegexNode mandatoryChild;
      if (backtrackGroup.child instanceof ConcatNode) {
        ConcatNode innerConcat = (ConcatNode) backtrackGroup.child;
        List<RegexNode> mandatoryChildren =
            innerConcat.children.subList(0, innerConcat.children.size() - 1);
        if (mandatoryChildren.isEmpty()) {
          mandatoryChild = EpsilonNode.INSTANCE; // epsilon
        } else if (mandatoryChildren.size() == 1) {
          mandatoryChild = mandatoryChildren.get(0);
        } else {
          mandatoryChild = new ConcatNode(new ArrayList<>(mandatoryChildren));
        }
      } else {
        mandatoryChild = EpsilonNode.INSTANCE; // epsilon
      }

      generateParserMethod(cw, className, backtrackGroup);
      String fullGroupMethod = getMethodNameForNode(backtrackGroup);
      generateParserMethod(cw, className, mandatoryChild);
      String mandatoryMethod = getMethodNameForNode(mandatoryChild);

      int captureGroupNumber = backtrackGroup.groupNumber;
      // Both attempts continue with the rest of the concat: when the rest has another such group,
      // it is generated once as its own method, so the code is linear in the number of groups
      String restMethod =
          nextOptionalBacktrackGroup(node, fromIndex + 1) < 0
              ? null
              : generateRemainingMethod(node, fromIndex + 1);

      Label tryMandatory = new Label();
      Label restFailed = new Label();

      // ── Attempt 1: full group (greedy) ──────────────────────────────────────
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, fullGroupMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, resultSlot);

      mv.visitVarInsn(ILOAD, resultSlot);
      mv.visitInsn(ICONST_M1);
      // If full group failed, skip to mandatory-only attempt
      mv.visitJumpInsn(IF_ICMPEQ, tryMandatory);

      // Full group succeeded: update currentPos
      mv.visitVarInsn(ILOAD, resultSlot);
      mv.visitVarInsn(ISTORE, 5);

      // Try remaining children (with recursive backtracking if needed)
      // On failure, jump to restFailed; on success, fall through
      emitRemaining(node, fromIndex + 1, restMethod, restFailed, extraSlotBase + 1);

      // All remaining children succeeded with full group result: fall through (success)
      // Jump past Attempt 2
      Label doneSuccess = new Label();
      mv.visitJumpInsn(GOTO, doneSuccess);

      // ── Attempt 2: mandatory-only group ─────────────────────────────────────
      // Reached when: (a) full group succeeded but remaining children failed, OR
      //               (b) full group itself failed
      mv.visitLabel(restFailed);
      mv.visitLabel(tryMandatory);

      // Restore position and groups to BEFORE this group
      generateGroupArrayRestore(savedGroupsSlot, 4);
      mv.visitVarInsn(ILOAD, savedPosSlot);
      mv.visitVarInsn(ISTORE, 5);

      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, depthSlot); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, mandatoryMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, resultSlot);

      mv.visitVarInsn(ILOAD, resultSlot);
      mv.visitInsn(ICONST_M1);
      Label mandatoryOk = new Label();
      mv.visitJumpInsn(IF_ICMPNE, mandatoryOk);
      // Mandatory also failed: jump to overall fail
      mv.visitJumpInsn(GOTO, overallFailLabel);
      mv.visitLabel(mandatoryOk);

      // Set group capture with mandatory result
      if (captureGroupNumber > 0) {
        mv.visitVarInsn(ALOAD, 4);
        BytecodeUtil.pushInt(mv, captureGroupNumber * 2);
        mv.visitVarInsn(ILOAD, savedPosSlot); // start = before this group
        mv.visitInsn(IASTORE);
        mv.visitVarInsn(ALOAD, 4);
        BytecodeUtil.pushInt(mv, captureGroupNumber * 2 + 1);
        mv.visitVarInsn(ILOAD, resultSlot); // end = after mandatory match
        mv.visitInsn(IASTORE);
      }

      mv.visitVarInsn(ILOAD, resultSlot);
      mv.visitVarInsn(ISTORE, 5);

      // Try remaining children after mandatory match (with backtracking if needed)
      // On failure, jump to overall fail; on success, fall through
      emitRemaining(node, fromIndex + 1, restMethod, overallFailLabel, extraSlotBase + 1);

      mv.visitLabel(doneSuccess);
      // Success: slot 5 (currentPos) is already updated; caller falls through here
    }

    /**
     * Generate code for the remaining concat children starting at {@code fromIndex}. If any
     * remaining child is a GroupNode with a trailing optional backref, applies {@link
     * #generateGroupWithOptionalBacktracking} recursively; otherwise falls through to simple
     * sequential matching.
     *
     * <p>On failure jumps to {@code failLabel}; success falls through.
     *
     * @param node The outer ConcatNode
     * @param fromIndex First child index to process
     * @param failLabel Label to jump to if the whole remaining section fails
     * @param extraSlotBase Slot base for this level's temporaries
     */
    private void generateRemainingWithBacktracking(
        ConcatNode node, int fromIndex, Label failLabel, int extraSlotBase) {

      int nextBacktrackIdx = nextOptionalBacktrackGroup(node, fromIndex);
      GroupNode nextBacktrackGroup =
          nextBacktrackIdx < 0 ? null : (GroupNode) node.children.get(nextBacktrackIdx);

      // Process simple sequential children before the next backtracking group
      int limit = (nextBacktrackIdx == -1) ? node.children.size() : nextBacktrackIdx;
      emitChildRun(node, fromIndex, limit, failLabel);

      if (nextBacktrackIdx == -1) {
        // No more backtracking groups; all sequential children done → fall through (success)
        return;
      }

      // Save pos and groups before the next backtracking group
      int savedPosSlot = extraSlotBase;
      int savedGroupsSlot = extraSlotBase + 1;
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ISTORE, savedPosSlot);
      generateGroupArraySave(4, savedGroupsSlot);

      // Recursively handle this group and the rest.
      // On failure, jump to failLabel; on success, fall through.
      generateGroupWithOptionalBacktracking(
          node,
          nextBacktrackIdx,
          nextBacktrackGroup,
          extraSlotBase + 2,
          savedGroupsSlot,
          savedPosSlot,
          failLabel);
      // generateGroupWithOptionalBacktracking falls through on success.
    }

    /**
     * Index of the first child at or after {@code fromIndex} that needs group-optional
     * backtracking, or -1.
     */
    private int nextOptionalBacktrackGroup(ConcatNode node, int fromIndex) {
      for (int i = fromIndex; i < node.children.size(); i++) {
        RegexNode child = node.children.get(i);
        if (child instanceof GroupNode) {
          GroupNode g = (GroupNode) child;
          if (g.groupNumber > 0
              && !PatternAnalyzer.hasSelfReferencingBackref(g)
              && (extractTrailingOptionalBackref(g.child) != null
                  || hasTrailingOptionalQuantifier(g.child))) {
            return i;
          }
        }
      }
      return -1;
    }

    /**
     * Matches the concat children from {@code fromIndex}: inline when {@code restMethod} is null,
     * else by calling it. On failure jumps to {@code failLabel}; success falls through with
     * currentPos (slot 5) updated.
     */
    private void emitRemaining(
        ConcatNode node, int fromIndex, String restMethod, Label failLabel, int extraSlotBase) {
      if (restMethod == null) {
        generateRemainingWithBacktracking(node, fromIndex, failLabel, extraSlotBase);
        return;
      }
      emitChildCall(restMethod, failLabel);
    }

    /**
     * Generates a method matching the concat children from {@code fromIndex} with group-optional
     * backtracking: returns the end position, or -1.
     */
    private String generateRemainingMethod(ConcatNode node, int fromIndex) {
      String name = "parse_ConcatRest_" + (methodCounter++);
      MethodVisitor m = cw.visitMethod(ACC_PRIVATE, name, "(Ljava/lang/String;II[II)I", null, null);
      m.visitCode();
      emitStepCheck(m, className);
      new ParserMethodGenerator(cw, m, className).emitRemainingBody(node, fromIndex);
      m.visitMaxs(0, 0);
      m.visitEnd();
      return name;
    }

    private void emitRemainingBody(ConcatNode node, int fromIndex) {
      // Slot layout of generateConcatWithBacktracking: depth in slot 2, currentPos in slot 5
      mv.visitVarInsn(ILOAD, 2);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ISTORE, 2);
      mv.visitVarInsn(ISTORE, 5);
      depthSlot = 2;
      Label fail = new Label();
      generateRemainingWithBacktracking(node, fromIndex, fail, 6);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IRETURN);
      mv.visitLabel(fail);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    /**
     * Generates a method running the nested backtracking from {@code nestedIndex} on: returns the
     * end position of its first success, or -1 when it is exhausted.
     */
    private String generateNestedRestMethod(ConcatNode node, int nestedIndex) {
      String name = "parse_ConcatNested_" + (methodCounter++);
      MethodVisitor m = cw.visitMethod(ACC_PRIVATE, name, "(Ljava/lang/String;II[II)I", null, null);
      m.visitCode();
      emitStepCheck(m, className);
      new ParserMethodGenerator(cw, m, className).emitNestedRestBody(node, nestedIndex);
      m.visitMaxs(0, 0);
      m.visitEnd();
      return name;
    }

    private void emitNestedRestBody(ConcatNode node, int nestedIndex) {
      // Slot layout of generateConcatWithBacktracking: depth in slot 2, currentPos in slot 5
      mv.visitVarInsn(ILOAD, 2);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ISTORE, 2);
      mv.visitVarInsn(ISTORE, 5);
      depthSlot = 2;
      Label exhausted = new Label();
      generateNestedBacktracking(node, nestedIndex, exhausted, 0, -1, 6, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IRETURN);
      mv.visitLabel(exhausted);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    /** Generate simple concat code when node is not a quantifier. */
    private void generateSimpleConcat(ConcatNode node, int startIndex) {
      emitChildRun(node, startIndex, node.children.size(), null);

      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IRETURN);
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      // Anchors: check position constraints
      if (node.type == AnchorNode.Type.START && node.multiline) {
        // ^ in multiline mode (JDK rules; the end bound is the real input end)
        // if (LineTerminators.isMultilineStart(input, pos, 0, input.length())) return pos;
        // return -1;
        Label atLineStart = new Label();
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        invokeMultilineStart(mv);
        mv.visitJumpInsn(IFNE, atLineStart);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(atLineStart);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(IRETURN);
      } else if (node.type == AnchorNode.Type.START || node.type == AnchorNode.Type.STRING_START) {
        // ^ (non-multiline) or \A: must be at start of input
        // if (pos != 0) return -1;
        // S: []
        mv.visitVarInsn(ILOAD, 2); // pos
        // S: [I]
        Label atStart = new Label();
        mv.visitJumpInsn(IFEQ, atStart);
        // S: []
        mv.visitInsn(ICONST_M1);
        // S: [I]
        mv.visitInsn(IRETURN);
        mv.visitLabel(atStart);
        // S: []
        // Return same position (anchor consumes no characters)
        mv.visitVarInsn(ILOAD, 2);
        // S: [I]
        mv.visitInsn(IRETURN);
      } else if (node.type == AnchorNode.Type.END && node.multiline) {
        // $ in multiline mode (JDK rules)
        // if (LineTerminators.isMultilineEnd(input, pos, end)) return pos; return -1;
        Label atLineEnd = new Label();
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        invokeMultilineEnd(mv);
        mv.visitJumpInsn(IFNE, atLineEnd);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(atLineEnd);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(IRETURN);
      } else if (node.type == AnchorNode.Type.STRING_END) {
        // \Z: matches at end of input, before terminal lone '\n' (CRLF guard), lone '\r', '\r\n',
        // NEL, LS, or PS
        Label atEnd = new Label();
        Label failLabel = new Label();
        Label checkCrlf = new Label();
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitJumpInsn(IF_ICMPEQ, atEnd); // if pos == end → pass
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitJumpInsn(IF_ICMPNE, checkCrlf); // if pos != end-1 → try CRLF at end-2
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\n');
        Label notNewlineZ = new Label();
        mv.visitJumpInsn(IF_ICMPNE, notNewlineZ);
        // '\n': CRLF guard — lone \n only; \r\n tail does not match \Z
        Label loneNewlineZ = new Label();
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitJumpInsn(IFEQ, loneNewlineZ); // pos == 0 → lone \n
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\r');
        mv.visitJumpInsn(IF_ICMPEQ, failLabel); // CRLF tail → \Z can't match here
        mv.visitLabel(loneNewlineZ);
        mv.visitJumpInsn(GOTO, atEnd);
        mv.visitLabel(notNewlineZ);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\r');
        mv.visitJumpInsn(IF_ICMPEQ, atEnd); // lone '\r' at end-1 → pass
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\u0085'); // NEL
        mv.visitJumpInsn(IF_ICMPEQ, atEnd);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\u2028'); // LS
        mv.visitJumpInsn(IF_ICMPEQ, atEnd);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\u2029'); // PS
        mv.visitJumpInsn(IF_ICMPEQ, atEnd);
        mv.visitJumpInsn(GOTO, failLabel); // end-1 but no recognized terminator: fail
        mv.visitLabel(checkCrlf);
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitInsn(ICONST_2);
        mv.visitInsn(ISUB);
        mv.visitJumpInsn(IF_ICMPNE, failLabel); // if pos != end-2 → fail
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\r');
        mv.visitJumpInsn(IF_ICMPNE, failLabel); // if charAt(pos) != '\r' → fail
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, atEnd); // '\r\n' at end-2..end-1 → pass
        mv.visitLabel(failLabel);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(atEnd);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(IRETURN);
      } else if (node.type == AnchorNode.Type.STRING_END_ABSOLUTE) {
        // \z: strict end of input only
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        Label atAbsEnd = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, atAbsEnd);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(atAbsEnd);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(IRETURN);
      } else if (node.type == AnchorNode.Type.END) {
        // $ (non-multiline): pos==end; pos==end-1 with lone '\n' (CRLF guard)/'\r'/NEL/LS/PS;
        // pos==end-2 with '\r\n'
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        Label dollarOk = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, dollarOk);
        Label dollarCheckCrlf = new Label();
        Label dollarFail = new Label();
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitJumpInsn(IF_ICMPNE, dollarCheckCrlf);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\n');
        Label dollarNotNewline = new Label();
        mv.visitJumpInsn(IF_ICMPNE, dollarNotNewline);
        // '\n': CRLF guard — lone \n only; \r\n tail does not match $
        Label dollarLoneNewline = new Label();
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitJumpInsn(IFEQ, dollarLoneNewline); // pos == 0 → lone \n
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\r');
        mv.visitJumpInsn(IF_ICMPEQ, dollarFail); // CRLF tail → $ not here
        mv.visitLabel(dollarLoneNewline);
        mv.visitJumpInsn(GOTO, dollarOk);
        mv.visitLabel(dollarNotNewline);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\r');
        mv.visitJumpInsn(IF_ICMPEQ, dollarOk); // lone '\r' at end-1 → pass
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\u0085'); // NEL
        mv.visitJumpInsn(IF_ICMPEQ, dollarOk);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\u2028'); // LS
        mv.visitJumpInsn(IF_ICMPEQ, dollarOk);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\u2029'); // PS
        mv.visitJumpInsn(IF_ICMPEQ, dollarOk);
        mv.visitJumpInsn(GOTO, dollarFail);
        mv.visitLabel(dollarCheckCrlf);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(ISUB);
        mv.visitJumpInsn(IF_ICMPNE, dollarFail);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\r');
        mv.visitJumpInsn(IF_ICMPNE, dollarFail);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitIntInsn(BIPUSH, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, dollarOk);
        mv.visitLabel(dollarFail);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(dollarOk);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(IRETURN);
      }

      // Unsupported anchor type (WORD_BOUNDARY)
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      return null;
    }

    @Override
    public Void visitAssertion(AssertionNode node) {
      // Assertions: zero-width checks (lookahead, lookbehind)
      // For simplicity, not fully implemented yet
      // Just fail for now
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      // Backreference: \1, \2, etc.
      // Check if the referenced group has been captured
      // If not, fail immediately

      // Implementation for backtracking-based backreference matching
      // This is a simplified version that handles the general case

      int groupNumber = node.groupNumber;
      int startIndex = groupNumber * 2;
      int endIndex = groupNumber * 2 + 1;

      // Defensive bounds checking: ensure startIndex and endIndex are valid
      // if (startIndex < 0 || startIndex >= groups.length) return -1;
      Label startIndexBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(IF_ICMPGE, startIndexBoundsOk); // if startIndex >= 0, continue checking
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(startIndexBoundsOk);

      Label startIndexUpperBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitInsn(ARRAYLENGTH);
      mv.visitJumpInsn(IF_ICMPLT, startIndexUpperBoundsOk); // if startIndex < groups.length, ok
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(startIndexUpperBoundsOk);

      // if (endIndex < 0 || endIndex >= groups.length) return -1;
      Label endIndexBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(IF_ICMPGE, endIndexBoundsOk); // if endIndex >= 0, continue checking
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(endIndexBoundsOk);

      Label endIndexUpperBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitInsn(ARRAYLENGTH);
      mv.visitJumpInsn(IF_ICMPLT, endIndexUpperBoundsOk); // if endIndex < groups.length, ok
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(endIndexUpperBoundsOk);

      // Check if group has been captured
      // JDK semantics: a backref to a group that never participated fails (returns -1).
      // C-03: Match empty string when group is in partial-open state
      // (groups[startIndex] >= 0 AND groups[endIndex] == -1), which means we are
      // currently inside that group's first iteration (self-referencing backref).
      Label groupCaptured = new Label();
      mv.visitVarInsn(ALOAD, 4); // groups
      BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(IALOAD);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPNE, groupCaptured);

      // groups[startIndex] == -1: group never captured — fail (JDK semantics)
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      mv.visitLabel(groupCaptured);
      // groups[startIndex] >= 0: check whether this is partial-open (endIndex == -1)
      {
        Label notPartialOpen = new Label();
        mv.visitVarInsn(ALOAD, 4); // groups
        BytecodeUtil.pushInt(mv, endIndex);
        mv.visitInsn(IALOAD);
        mv.visitInsn(ICONST_M1);
        mv.visitJumpInsn(IF_ICMPNE, notPartialOpen);
        // partial-open sentinel: return pos (zero-length match)
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitInsn(IRETURN);
        mv.visitLabel(notPartialOpen);
      }

      // Get group boundaries
      // int groupStart = groups[startIndex];
      mv.visitVarInsn(ALOAD, 4);
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(IALOAD);
      mv.visitVarInsn(ISTORE, 5); // groupStart

      // int groupEnd = groups[endIndex];
      mv.visitVarInsn(ALOAD, 4);
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
      mv.visitInsn(IALOAD);
      mv.visitVarInsn(ISTORE, 6); // groupEnd

      // int groupLen = groupEnd - groupStart;
      mv.visitVarInsn(ILOAD, 6);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(ISUB);
      mv.visitVarInsn(ISTORE, 7); // groupLen

      // Check if we have enough characters left: if (pos + groupLen > end) return -1;
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 7); // groupLen
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, 3); // end
      Label lengthOk = new Label();
      mv.visitJumpInsn(IF_ICMPLE, lengthOk);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(lengthOk);

      // Compare characters: for (int i = 0; i < groupLen; i++)
      //   if (input.charAt(pos + i) != input.charAt(groupStart + i)) return -1;
      Label loopStart = new Label();
      Label loopEnd = new Label();
      mv.visitInsn(ICONST_0); // i = 0
      mv.visitVarInsn(ISTORE, 8); // slot 8 for i

      mv.visitLabel(loopStart);
      mv.visitVarInsn(ILOAD, 8); // i
      mv.visitVarInsn(ILOAD, 7); // groupLen
      mv.visitJumpInsn(IF_ICMPGE, loopEnd); // if i >= groupLen, exit loop

      // char c1 = input.charAt(pos + i)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 8); // i
      mv.visitInsn(IADD);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // char c2 = input.charAt(groupStart + i)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 5); // groupStart
      mv.visitVarInsn(ILOAD, 8); // i
      mv.visitInsn(IADD);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // if (c1 != c2) return -1
      Label charsMatch = new Label();
      mv.visitJumpInsn(IF_ICMPEQ, charsMatch);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      mv.visitLabel(charsMatch);
      mv.visitIincInsn(8, 1); // i++
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(loopEnd);

      // Success: return pos + groupLen
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 7); // groupLen
      mv.visitInsn(IADD);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      // Subroutine call: (?R), (?1), (?&name)
      // Find the target node and call its parser

      // (?R)/(?0) calls the root pattern. A group call matches the group's body without capturing,
      // so the call does not overwrite the group's own capture.
      RegexNode targetNode = isRootCall(node) ? ast : callTarget(node);
      if (targetNode == null) {
        // No such group: fail
        // S: []
        mv.visitInsn(ICONST_M1);
        // S: [I]
        mv.visitInsn(IRETURN);
        return null;
      }

      // Check recursion depth limit
      // if (depth >= MAX_RECURSION_DEPTH) throw new StackOverflowError()
      mv.visitVarInsn(ILOAD, 5); // depth
      BytecodeUtil.pushInt(mv, MAX_RECURSION_DEPTH);
      Label depthOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, depthOk);

      // Throw StackOverflowError
      mv.visitTypeInsn(NEW, "java/lang/StackOverflowError");
      mv.visitInsn(DUP);
      mv.visitLdcInsn("Recursion depth limit exceeded: " + MAX_RECURSION_DEPTH);
      mv.visitMethodInsn(
          INVOKESPECIAL, "java/lang/StackOverflowError", "<init>", "(Ljava/lang/String;)V", false);
      mv.visitInsn(ATHROW);

      mv.visitLabel(depthOk);

      // Generate parser for target node and call it
      // Note: AST nodes no longer have parent references, so we cannot detect
      // if this subroutine is inside a quantifier at bytecode generation time.
      // The quantifier visitor will handle calling the subroutine parser if needed.
      generateParserMethod(cw, className, targetNode);
      String targetMethod = getMethodNameForNode(targetNode);

      // PCRE semantics: subroutine calls match the pattern but do NOT modify group captures.
      // Save groups before call and restore after to preserve original captures.
      // Local vars: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
      // Additional locals: 16=savedGroups, 17=result
      // NOTE: Using slots 16-17 to avoid conflicts with concat (uses slot 6) and backtracking (uses
      // slots 6-15)

      // A target that cannot write groups leaves them unchanged: skip the save and restore.
      boolean saveGroups = !isGroupsNeutral(targetNode);
      if (saveGroups) {
        // Save groups array: int[] savedGroups = groups.clone()
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, "[I");
        mv.visitVarInsn(ASTORE, 16); // savedGroups in slot 16
      }

      // Call target parser with depth+1: result = parse_target(input, pos, end, groups, depth+1)
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD); // depth + 1
      mv.visitMethodInsn(
          INVOKESPECIAL, className, targetMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 17); // result in slot 17

      // Restore groups array: System.arraycopy(savedGroups, 0, groups, 0, savedGroups.length)
      // This preserves the original group captures, implementing PCRE subroutine semantics
      if (saveGroups) {
        generateGroupArrayRestore(16, 4);
      }

      // Check if subroutine matched
      mv.visitVarInsn(ILOAD, 17);
      mv.visitInsn(ICONST_M1);
      Label subroutineMatched = new Label();
      mv.visitJumpInsn(IF_ICMPNE, subroutineMatched);

      // Subroutine failed: return -1
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      // Subroutine succeeded: return the result position
      mv.visitLabel(subroutineMatched);
      mv.visitVarInsn(ILOAD, 17);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      // Conditional: (?(condition)then|else)
      // Check if the condition group has been captured

      int conditionGroupNumber = node.condition;
      int startIndex = conditionGroupNumber * 2;
      int endIndex = conditionGroupNumber * 2 + 1;

      // Defensive bounds checking for conditional group reference
      // if (startIndex < 0 || startIndex >= groups.length) fail (use else branch or return -1)
      Label condStartIndexOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(IF_ICMPGE, condStartIndexOk);
      // Invalid startIndex, treat as group not captured (go to else)
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(condStartIndexOk);

      Label condStartIndexUpperOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitInsn(ARRAYLENGTH);
      mv.visitJumpInsn(IF_ICMPLT, condStartIndexUpperOk);
      // Invalid startIndex, treat as group not captured
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(condStartIndexUpperOk);

      // Generate parsers for then and else branches
      generateParserMethod(cw, className, node.thenBranch);
      String thenMethod = getMethodNameForNode(node.thenBranch);

      String elseMethod = null;
      if (node.elseBranch != null) {
        generateParserMethod(cw, className, node.elseBranch);
        elseMethod = getMethodNameForNode(node.elseBranch);
      }

      Label elseLabel = new Label();

      // Check if group was captured: if (groups[startIndex] == -1) goto else
      // S: []
      mv.visitVarInsn(ALOAD, 4); // groups
      // S: [A:[I]]
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      // S: [A:[I], I]
      mv.visitInsn(IALOAD); // groups[startIndex]
      // S: [I]

      // Compare with -1
      mv.visitInsn(ICONST_M1);
      // S: [I, I]
      mv.visitJumpInsn(IF_ICMPEQ, elseLabel); // if == -1, group didn't match, goto else
      // S: []

      // Then branch: group matched
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      // S: [A:this, A:String, I, I, A:[I], I]
      mv.visitMethodInsn(INVOKESPECIAL, className, thenMethod, "(Ljava/lang/String;II[II)I", false);
      // S: [I]
      mv.visitInsn(IRETURN);

      // Else branch: group didn't match
      mv.visitLabel(elseLabel);
      // S: []
      if (elseMethod != null) {
        // Call else branch parser
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        // S: [A:this, A:String, I, I, A:[I], I]
        mv.visitMethodInsn(
            INVOKESPECIAL, className, elseMethod, "(Ljava/lang/String;II[II)I", false);
        // S: [I]
        mv.visitInsn(IRETURN);
      } else {
        // No else branch: match succeeds with no advancement
        mv.visitVarInsn(ILOAD, 2); // pos
        // S: [I]
        mv.visitInsn(IRETURN);
      }

      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      // Branch reset: (?|alt1|alt2)
      // All alternatives share the same group numbers
      // Each alternative can set different groups, but they use the same indices
      // Local variables: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
      // Additional locals: 6=savedGroups, 7=result

      // Save groups array before trying alternatives
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, 6); // savedGroups in slot 6

      // Collect the group numbers that are actually inside this branch reset
      Set<Integer> branchResetGroups = collectBranchResetGroupNumbers(node);

      // Each alternative starts with the branch-reset groups cleared; groups outside the branch
      // reset are preserved; the reset adds at most groupResetBytes per alternative.
      int n = node.alternatives.size();
      int chunk = Math.max(1, RANGE_CHUNK * 40 / (40 + groupResetBytes(branchResetGroups)));
      if (n > chunk) {
        emitAlternationRanges(mv, node.alternatives, branchResetGroups, 0, n, chunk);
      } else {
        for (int i = 0; i < n; i++) {
          emitGroupReset(mv, branchResetGroups);
          emitAlternative(mv, node.alternatives.get(i), i < n - 1);
        }
      }

      // All alternatives failed
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      return null;
    }
  }
}
