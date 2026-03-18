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
package com.datadoghq.reggie.codegen.automaton;

import com.datadoghq.reggie.codegen.ast.*;
import java.util.*;

/**
 * Builds NFA from regex AST using Thompson's construction algorithm. Each regex construct is
 * converted to an NFA fragment with entry and exit states.
 */
public class ThompsonBuilder implements RegexVisitor<ThompsonBuilder.NFAFragment> {

  private int nextStateId = 0;
  private final List<NFA.NFAState> allStates = new ArrayList<>();

  /**
   * Temporary structure during NFA construction. Represents a partial NFA with entry state and
   * multiple exit states.
   */
  public static class NFAFragment {
    public final NFA.NFAState entry;
    public final Set<NFA.NFAState> exits;

    public NFAFragment(NFA.NFAState entry, Set<NFA.NFAState> exits) {
      this.entry = entry;
      this.exits = exits;
    }

    public NFAFragment(NFA.NFAState entry, NFA.NFAState exit) {
      this.entry = entry;
      this.exits = Set.of(exit);
    }
  }

  public NFA build(RegexNode ast, int groupCount) {
    NFAFragment fragment = ast.accept(this);

    // Create final accept state
    NFA.NFAState acceptState = createState();

    // Connect all exits to accept state via epsilon
    for (NFA.NFAState exit : fragment.exits) {
      exit.addEpsilonTransition(acceptState);
    }

    return new NFA(allStates, fragment.entry, Set.of(acceptState), groupCount);
  }

  private NFA.NFAState createState() {
    NFA.NFAState state = new NFA.NFAState(nextStateId++);
    allStates.add(state);
    return state;
  }

  @Override
  public NFAFragment visitLiteral(LiteralNode node) {
    NFA.NFAState entry = createState();
    NFA.NFAState exit = createState();

    // Check for epsilon (empty match) - represented as (char)0
    if (node.ch == 0) {
      // Epsilon transition: entry --ε--> exit (no character consumption)
      entry.addEpsilonTransition(exit);
    } else {
      // Simple transition: entry --[char]--> exit
      entry.addTransition(CharSet.of(node.ch), exit);
    }

    return new NFAFragment(entry, exit);
  }

  @Override
  public NFAFragment visitCharClass(CharClassNode node) {
    // Character class transition
    NFA.NFAState entry = createState();
    NFA.NFAState exit = createState();

    CharSet charset = node.negated ? node.chars.complement() : node.chars;
    entry.addTransition(charset, exit);

    return new NFAFragment(entry, exit);
  }

  @Override
  public NFAFragment visitConcat(ConcatNode node) {
    if (node.children.isEmpty()) {
      // Empty concat - epsilon transition
      NFA.NFAState state = createState();
      return new NFAFragment(state, state);
    }

    // Chain fragments: frag1 -> frag2 -> frag3
    NFAFragment result = node.children.get(0).accept(this);

    for (int i = 1; i < node.children.size(); i++) {
      NFAFragment next = node.children.get(i).accept(this);

      // Connect result exits to next entry via epsilon
      for (NFA.NFAState exit : result.exits) {
        exit.addEpsilonTransition(next.entry);
      }

      result = new NFAFragment(result.entry, next.exits);
    }

    return result;
  }

  @Override
  public NFAFragment visitAlternation(AlternationNode node) {
    // Create new entry with epsilon to each alternative
    NFA.NFAState entry = createState();
    Set<NFA.NFAState> allExits = new HashSet<>();

    for (RegexNode alt : node.alternatives) {
      NFAFragment altFrag = alt.accept(this);
      entry.addEpsilonTransition(altFrag.entry);
      allExits.addAll(altFrag.exits);
    }

    return new NFAFragment(entry, allExits);
  }

  @Override
  public NFAFragment visitQuantifier(QuantifierNode node) {
    NFAFragment child = node.child.accept(this);

    if (node.min == 0 && node.max == 1) {
      // ? : optional
      NFA.NFAState newEntry = createState();
      newEntry.addEpsilonTransition(child.entry);

      Set<NFA.NFAState> exits = new HashSet<>(child.exits);
      exits.add(newEntry); // can skip entirely

      return new NFAFragment(newEntry, exits);

    } else if (node.min == 0 && node.max == -1) {
      // * : zero or more
      NFA.NFAState newEntry = createState();
      newEntry.addEpsilonTransition(child.entry);

      // Loop back from exits to entry
      for (NFA.NFAState exit : child.exits) {
        exit.addEpsilonTransition(child.entry);
      }

      Set<NFA.NFAState> exits = new HashSet<>(child.exits);
      exits.add(newEntry); // can match zero times

      return new NFAFragment(newEntry, exits);

    } else if (node.min == 1 && node.max == -1) {
      // + : one or more
      // Loop back from exits to entry
      for (NFA.NFAState exit : child.exits) {
        exit.addEpsilonTransition(child.entry);
      }

      return child; // must match at least once

    } else {
      // {n}, {n,m} : counted quantifier
      return buildCountedQuantifier(node.child, node.min, node.max);
    }
  }

  private NFAFragment buildCountedQuantifier(RegexNode child, int min, int max) {
    // Build min required copies
    List<NFAFragment> fragments = new ArrayList<>();
    for (int i = 0; i < min; i++) {
      fragments.add(child.accept(this));
    }

    if (max == -1) {
      // {n,} : n or more
      // Add one more with loop back
      NFAFragment last = child.accept(this);
      for (NFA.NFAState exit : last.exits) {
        exit.addEpsilonTransition(last.entry); // loop
      }
      fragments.add(last);
    } else if (max > min) {
      // {n,m} : between n and m
      // Add (max - min) optional copies
      for (int i = min; i < max; i++) {
        fragments.add(child.accept(this));
      }
    }

    // Chain all fragments
    if (fragments.isEmpty()) {
      NFA.NFAState state = createState();
      return new NFAFragment(state, Set.of(state));
    }

    NFAFragment result = fragments.get(0);
    Set<NFA.NFAState> allExits = new HashSet<>();

    for (int i = 1; i < fragments.size(); i++) {
      NFAFragment next = fragments.get(i);

      boolean isOptional = i >= min;
      if (isOptional) {
        // Can skip this fragment
        allExits.addAll(result.exits);
      }

      // Connect result exits to next entry
      for (NFA.NFAState exit : result.exits) {
        exit.addEpsilonTransition(next.entry);
      }

      result = new NFAFragment(result.entry, next.exits);
    }

    allExits.addAll(result.exits);
    return new NFAFragment(result.entry, allExits);
  }

  @Override
  public NFAFragment visitGroup(GroupNode node) {
    NFAFragment child = node.child.accept(this);

    if (node.capturing) {
      // Create intermediate epsilon states to avoid overwriting nested group markers
      // Entry state marks the start of this group
      NFA.NFAState groupEntry = createState();
      groupEntry.enterGroup = node.groupNumber;
      groupEntry.addEpsilonTransition(child.entry);

      // Exit states mark the end of this group
      Set<NFA.NFAState> groupExits = new HashSet<>();
      for (NFA.NFAState childExit : child.exits) {
        NFA.NFAState groupExit = createState();
        groupExit.exitGroup = node.groupNumber;
        childExit.addEpsilonTransition(groupExit);
        groupExits.add(groupExit);
      }

      return new NFAFragment(groupEntry, groupExits);
    }

    return child;
  }

  @Override
  public NFAFragment visitAnchor(AnchorNode node) {
    // Anchors are zero-width assertions
    NFA.NFAState state = createState();

    switch (node.type) {
      case START:
        state.anchor = node.multiline ? NFA.AnchorType.START_MULTILINE : NFA.AnchorType.START;
        break;
      case END:
        state.anchor = node.multiline ? NFA.AnchorType.END_MULTILINE : NFA.AnchorType.END;
        break;
      case WORD_BOUNDARY:
        state.anchor = NFA.AnchorType.WORD_BOUNDARY;
        break;
      case STRING_START:
        state.anchor = NFA.AnchorType.STRING_START;
        break;
      case STRING_END:
        state.anchor = NFA.AnchorType.STRING_END;
        break;
      case STRING_END_ABSOLUTE:
        state.anchor = NFA.AnchorType.STRING_END_ABSOLUTE;
        break;
      case RESET_MATCH:
        state.anchor = NFA.AnchorType.RESET_MATCH;
        break;
    }

    return new NFAFragment(state, Set.of(state));
  }

  @Override
  public NFAFragment visitBackreference(BackreferenceNode node) {
    // Backreference requires special handling
    NFA.NFAState entry = createState();
    NFA.NFAState exit = createState();

    entry.backrefCheck = node.groupNumber;
    entry.addEpsilonTransition(exit);

    return new NFAFragment(entry, exit);
  }

  @Override
  public NFAFragment visitAssertion(AssertionNode node) {
    // Assertions are zero-width: they check a condition but don't consume input
    //
    // Strategy: Create a special NFA state with assertion metadata
    // Runtime will evaluate the sub-pattern at current position

    NFA.NFAState assertionState = createState();

    // Build NFA for sub-pattern
    NFAFragment subFrag = node.subPattern.accept(this);

    // Store assertion information in state metadata
    assertionState.assertionType = convertAssertionType(node.type);
    assertionState.assertionStartState = subFrag.entry;
    assertionState.assertionAcceptStates = subFrag.exits;
    assertionState.assertionWidth = node.fixedWidth;

    // Zero-width: entry and exit are the same state
    return new NFAFragment(assertionState, Set.of(assertionState));
  }

  private NFA.AssertionType convertAssertionType(AssertionNode.Type astType) {
    switch (astType) {
      case POSITIVE_LOOKAHEAD:
        return NFA.AssertionType.POSITIVE_LOOKAHEAD;
      case NEGATIVE_LOOKAHEAD:
        return NFA.AssertionType.NEGATIVE_LOOKAHEAD;
      case POSITIVE_LOOKBEHIND:
        return NFA.AssertionType.POSITIVE_LOOKBEHIND;
      case NEGATIVE_LOOKBEHIND:
        return NFA.AssertionType.NEGATIVE_LOOKBEHIND;
      default:
        throw new IllegalArgumentException("Unknown assertion type: " + astType);
    }
  }

  @Override
  public NFAFragment visitSubroutine(SubroutineNode node) {
    // Not yet implemented - Phase 4: Subroutine support
    throw new UnsupportedOperationException(
        "Subroutine patterns not yet supported: (?R), (?1), etc.");
  }

  @Override
  public NFAFragment visitConditional(ConditionalNode node) {
    // Conditional pattern: (?(n)then|else)
    // If group n has matched, execute then-branch, otherwise else-branch
    NFA.NFAState condState = createState();
    condState.conditionalGroup = node.condition;

    // Build then-branch
    NFAFragment thenFrag = node.thenBranch.accept(this);
    condState.thenBranch = thenFrag.entry;

    Set<NFA.NFAState> allExits = new HashSet<>(thenFrag.exits);

    // Build else-branch (if present)
    if (node.elseBranch != null) {
      NFAFragment elseFrag = node.elseBranch.accept(this);
      condState.elseBranch = elseFrag.entry;
      allExits.addAll(elseFrag.exits);
    } else {
      // No else branch - if condition fails, continue from conditional state
      allExits.add(condState);
    }

    return new NFAFragment(condState, allExits);
  }

  @Override
  public NFAFragment visitBranchReset(BranchResetNode node) {
    // Branch reset is like alternation, but group numbers are reset for each alternative
    // The parser has already handled group renumbering, so NFA construction is the same
    NFA.NFAState entry = createState();
    Set<NFA.NFAState> allExits = new HashSet<>();

    for (RegexNode alt : node.alternatives) {
      NFAFragment altFrag = alt.accept(this);
      entry.addEpsilonTransition(altFrag.entry);
      allExits.addAll(altFrag.exits);
    }

    return new NFAFragment(entry, allExits);
  }
}
