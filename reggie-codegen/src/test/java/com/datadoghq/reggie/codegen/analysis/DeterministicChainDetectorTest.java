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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.DeterministicChainInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.DeterministicChainInfo.ChainBranch;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.DeterministicChainInfo.ChainElem;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.DeterministicChainInfo.ChainSeq;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.DeterministicChainInfo.ElemKind;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins {@link PatternAnalyzer#detectDeterministicChain}'s admission rules (stage 1 of
 * doc/2026-09-01-deterministic-chain-bytecode-design.md): which patterns parse into the family and
 * which decline — every decline is a linearity or correctness requirement, so a regression here is
 * a ReDoS-safety regression, not just a coverage one.
 */
class DeterministicChainDetectorTest {

  private static DeterministicChainInfo detect(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, countGroups(pattern));
    return new PatternAnalyzer(ast, nfa).detectDeterministicChain(ast);
  }

  // ------------------------------------------------------------------------------------
  // Admitted patterns (structure assertions)
  // ------------------------------------------------------------------------------------

  @Test
  void urlAuthorityPatternParses() throws Exception {
    String p = "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";
    DeterministicChainInfo info = detect(p);
    assertNotNull(info, "the motivating UrlAuth pattern must be admitted");
    assertEquals(2, info.branches.size());

    ChainBranch b1 = info.branches.get(0);
    assertTrue(b1.startAnchored);
    assertFalse(b1.endAnchored);
    // ^ OPT([:]+ loop, ':') LITERAL '//' CAPTURE(1, [@]+ loop) LITERAL '@'
    assertEquals(ElemKind.OPT, b1.seq.elems.get(0).kind);
    ChainElem opt = b1.seq.elems.get(0);
    assertEquals(2, opt.nested.elems.size());
    assertEquals(ElemKind.GREEDY_LOOP, opt.nested.elems.get(0).kind);
    assertEquals(1, opt.nested.elems.get(0).min);
    assertEquals(ElemKind.LITERAL, opt.nested.elems.get(1).kind);
    assertEquals(":", opt.nested.elems.get(1).literal);
    assertEquals(ElemKind.LITERAL, b1.seq.elems.get(1).kind);
    assertEquals("//", b1.seq.elems.get(1).literal);
    ChainElem auth = b1.seq.elems.get(2);
    assertEquals(ElemKind.CAPTURE, auth.kind);
    assertEquals(1, auth.groupNumber);
    assertEquals(1, auth.nested.elems.size());
    assertEquals(ElemKind.GREEDY_LOOP, auth.nested.elems.get(0).kind);
    assertEquals("@", b1.seq.elems.get(3).literal);

    ChainBranch b2 = info.branches.get(1);
    assertFalse(b2.startAnchored);
    assertEquals(4, b2.seq.elems.size());
    // Unanchored branch: ASCII-only first-set gate, min-width >= 1
    assertTrue(b2.firstSetAscii['?']);
    assertTrue(b2.firstSetAscii['#']);
    assertTrue(b2.firstSetAscii['&']);
    assertFalse(b2.firstSetHasNonAscii);
    // {?#&} + [^=&;]+ + '=' + [^?#&]+ — minimum 4 consumed chars
    assertEquals(4, b2.minWidth);
    // LIT_ALT-free branch: CLASS1 {?#&} CAPTURE(2, loop) LITERAL '=' CAPTURE(3, loop)
    assertEquals(ElemKind.CLASS1, b2.seq.elems.get(0).kind);
    assertEquals(2, b2.seq.elems.get(1).groupNumber);
    assertEquals("=", b2.seq.elems.get(2).literal);
    assertEquals(3, b2.seq.elems.get(3).groupNumber);
  }

  @Test
  void ldapPatternParses() throws Exception {
    String p = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";
    DeterministicChainInfo info = detect(p);
    assertNotNull(info);
    assertEquals(1, info.branches.size());
    ChainSeq seq = info.branches.get(0).seq;
    // LITERAL '(' LAZY_LOOP(.) LIT_ALT(ops) CAPTURE(1, [)]+ loop) LITERAL ')'
    assertEquals(5, seq.elems.size());
    assertEquals(ElemKind.LITERAL, seq.elems.get(0).kind);
    assertEquals("(", seq.elems.get(0).literal);
    assertEquals(ElemKind.LAZY_LOOP, seq.elems.get(1).kind);
    ChainElem ops = seq.elems.get(2);
    assertEquals(ElemKind.LIT_ALT, ops.kind);
    assertEquals(4, ops.literals.size());
    assertEquals("=", ops.literals.get(1));
    ChainElem lit = seq.elems.get(3);
    assertEquals(ElemKind.CAPTURE, lit.kind);
    assertEquals(1, lit.groupNumber);
    assertEquals(ElemKind.GREEDY_LOOP, lit.nested.elems.get(0).kind);
    assertEquals(")", seq.elems.get(4).literal);
  }

  @Test
  void overlappingLiteralAlternationAdmittedAsSequentialTries() throws Exception {
    // (a|ab|abc)(1|12|123): overlapping alternatives are a bounded sequential retry — the
    // StateExplosionBenchmark.AlternationHeavy* shape.
    DeterministicChainInfo info = detect("(a|ab|abc)(1|12|123)");
    assertNotNull(info);
    // Both groups are capturing: CAPTURE(1, [LIT_ALT]) CAPTURE(2, [LIT_ALT])
    assertEquals(2, info.branches.get(0).seq.elems.size());
    assertEquals(ElemKind.CAPTURE, info.branches.get(0).seq.elems.get(0).kind);
    ChainElem alt1 = info.branches.get(0).seq.elems.get(0).nested.elems.get(0);
    assertEquals(ElemKind.LIT_ALT, alt1.kind);
    assertEquals(3, alt1.literals.size());
    assertEquals(ElemKind.CAPTURE, info.branches.get(0).seq.elems.get(1).kind);
  }

  @Test
  void xmlTagsPatternParses() throws Exception {
    DeterministicChainInfo info = detect("(<\\w+>).*?(</\\w+>)");
    assertNotNull(info);
    ChainSeq seq = info.branches.get(0).seq;
    // CAPTURE(1, [LITERAL "<", \w+ loop, LITERAL ">"]) LAZY_LOOP
    // CAPTURE(2, [LITERAL "</", \w+ loop, LITERAL ">"])
    assertEquals(3, seq.elems.size());
    assertEquals(ElemKind.CAPTURE, seq.elems.get(0).kind);
    assertEquals(3, seq.elems.get(0).nested.elems.size());
    assertEquals(ElemKind.LITERAL, seq.elems.get(0).nested.elems.get(0).kind);
    assertEquals("<", seq.elems.get(0).nested.elems.get(0).literal);
    assertEquals(ElemKind.GREEDY_LOOP, seq.elems.get(0).nested.elems.get(1).kind);
    assertEquals(">", seq.elems.get(0).nested.elems.get(2).literal);
    assertEquals(ElemKind.LAZY_LOOP, seq.elems.get(1).kind);
    assertEquals(ElemKind.CAPTURE, seq.elems.get(2).kind);
    // "<" and "/" merge into one literal: LITERAL("</") GREEDY_LOOP LITERAL(">")
    assertEquals(3, seq.elems.get(2).nested.elems.size());
  }

  @Test
  void v2AlphaWordBoundaryAdmitted() throws Exception {
    // \b parses as a WORDB element at any seq position; the first-set stays the next
    // element's (zero-width, prefix stays empty-matchable).
    DeterministicChainInfo info = detect("\\b\\d+x");
    assertNotNull(info);
    assertEquals(ElemKind.WORDB, info.branches.get(0).seq.elems.get(0).kind);
    assertEquals(2, info.branches.get(0).minWidth); // \\b (0) + \\d+ (1) + x (1)
    for (int c = 0; c < 128; c++) {
      boolean isDigit = c >= '0' && c <= '9';
      assertEquals(isDigit, info.branches.get(0).firstSetAscii[c], "first set char " + c);
    }
    assertNotNull(detect("a\\bb"));
    assertNotNull(detect("[0-9]+\\b"));
    assertNotNull(detect("\\bword\\b"));
  }

  @Test
  void v2AlphaGreedyGivebackFlagged() throws Exception {
    // A greedy loop whose class intersects the remainder first-set is admitted WITH the
    // giveBack flag (v2-alpha); a disjoint loop keeps the flag false.
    DeterministicChainInfo xmlTags = detect("(<\\w+>).*(</\\w+>)");
    assertNotNull(xmlTags);
    ChainSeq seq = xmlTags.branches.get(0).seq;
    assertEquals(ElemKind.GREEDY_LOOP, seq.elems.get(1).kind);
    assertTrue(seq.elems.get(1).giveBack, "the .* against </\\w+> needs give-back");
    assertFalse(seq.elems.get(0).nested.elems.get(1).giveBack, "\\w+ vs '>' stays disjoint");

    DeterministicChainInfo disjoint = detect("a[0-9]+x");
    assertNotNull(disjoint);
    assertFalse(disjoint.branches.get(0).seq.elems.get(1).giveBack);
  }

  @Test
  void v2AlphaMultipleStarsStillDeclines() throws Exception {
    // (a*b*c*d*e*) keeps min-width 0: the unanchored empty-match admission is unchanged.
    assertNull(detect("(a*b*c*d*e*)"));
  }

  @Test
  void v2AlphaTerminalAltChainAdmitted() throws Exception {
    DeterministicChainInfo info = detect("q(?:x[0-9]+[fd]|[0-9]+\\.[0-9]+)");
    assertNotNull(info);
    ChainSeq seq = info.branches.get(0).seq;
    assertEquals(2, seq.elems.size());
    assertEquals(ElemKind.LITERAL, seq.elems.get(0).kind);
    assertEquals(ElemKind.ALT_CHAIN, seq.elems.get(1).kind);
    assertEquals(2, seq.elems.get(1).alts.size());
    // alt0 = x [0-9]+ [fd]: LITERAL, GREEDY_LOOP, CLASS1
    assertEquals(3, seq.elems.get(1).alts.get(0).elems.size());
    assertEquals(ElemKind.GREEDY_LOOP, seq.elems.get(1).alts.get(0).elems.get(1).kind);
    assertEquals(ElemKind.CLASS1, seq.elems.get(1).alts.get(0).elems.get(2).kind);
    // alt1 = [0-9]+ \.[0-9]+: GREEDY_LOOP, LITERAL ".", GREEDY_LOOP
    assertEquals(3, seq.elems.get(1).alts.get(1).elems.size());
    assertEquals(ElemKind.GREEDY_LOOP, seq.elems.get(1).alts.get(1).elems.get(2).kind);
  }

  @Test
  void v2AlphaAltChainStructuralHashDistinct() throws Exception {
    // The structural cache keys on the alts: two patterns differing only in an alternative's
    // literal must not collide (the generated classes differ).
    String a = "q(?:x[0-9]+[f]|[0-9]+\\.[0-9]+)";
    String b = "q(?:x[0-9]+[d]|[0-9]+\\.[0-9]+)";
    assertNotEquals(detect(a).structuralHashCode(), detect(b).structuralHashCode());
  }

  @Test
  void optionalSingleClassParsesAsOpt() throws Exception {
    DeterministicChainInfo info = detect("[-+]?[0-9]+");
    assertNotNull(info);
    ChainSeq seq = info.branches.get(0).seq;
    assertEquals(ElemKind.OPT, seq.elems.get(0).kind);
    assertEquals(1, seq.elems.get(0).nested.elems.size());
    assertEquals(ElemKind.CLASS1, seq.elems.get(0).nested.elems.get(0).kind);
    assertEquals(ElemKind.GREEDY_LOOP, seq.elems.get(1).kind);
    assertTrue(info.branches.get(0).firstSetAscii['-']);
    assertTrue(info.branches.get(0).firstSetAscii['+']);
    assertTrue(info.branches.get(0).firstSetAscii['0']);
    assertEquals(1, info.branches.get(0).minWidth);
  }

  @Test
  void endAnchorBothModesParse() throws Exception {
    assertNotNull(detect("[a-z]+$"));
    DeterministicChainInfo multiline = detect("(?m)[a-z]+$");
    assertNotNull(multiline);
    assertTrue(multiline.branches.get(0).multilineEnd);
    assertTrue(multiline.branches.get(0).endAnchored);
  }

  // ------------------------------------------------------------------------------------
  // Declines — every one is a linearity or correctness requirement
  // ------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        // (v1 declined the greedy give-back loops a+a / \\d*\\d+ / (?i)[a-z]+z here; v2-alpha
        // admits them WITH the giveBack flag — see v2AlphaGreedyGivebackFlagged. They are not
        // in this decline list anymore.)
        // Unanchored branch can match empty: the find() scan cannot gate or bound it.
        "(a*b*c*d*e*)",
        // Quantified capturing group: capture re-binding across iterations (v1 scope).
        "(a)+",
        "(ab)?",
        // (v1 declined the bounded class loops x{2,4}/[0-9]{1,4} here — v2-beta admits them with
        // the min/max floor-and-cap machinery; see the V2Beta bounded-loop tests.)
        // Assertions / backreferences / boundaries: not this family.
        "(?=x)y",
        "(a)\\1",
        // \\B, \\A/\\z mid-chain: still outside the family (v2-alpha admits \\b only).
        "\\Bx",
        "\\Ax",
        "x\\z",
        // Anchors outside the two admissible positions: multiline ^, leading $.
        "(?m)^x",
        "$[^a-zA-Z0-9]|^[0-9]",
        // NOTE: v1 declined greedy give-back loops (a+a, \\d*\\d+, (?i)[a-z]+z); v2-alpha
        // admits them with the giveBack flag — see v2AlphaGreedyGivebackFlagged.
        // (v2-beta admitted the LOOP_ALT SQL quote shape '(?:''|[^'])*' that v2-alpha declined
        // here — see the V2Beta journal tests.)
        // (v2-alpha also declined non-terminal compound alternations and retryable bodies
        // here; v2-beta's slot pre-initialization lifted both — see the V2Alpha parity tests.)
        // Lazy min >= 1 (x+?) — not admitted.
        "a+?x",
        // Possessive/atomic — hard decline.
        "(?>a+)x",
        "a++x",
      })
  void declines(String pattern) throws Exception {
    assertNull(detect(pattern), "pattern must decline: " + pattern);
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
        char n2 = i + 2 < pattern.length() ? pattern.charAt(i + 2) : ' ';
        if (n2 == ':' || n2 == '=' || n2 == '!' || n2 == '>' || n2 == '#' || n2 == '|') continue;
        if (n2 == '<' && i + 3 < pattern.length()) {
          char n3 = pattern.charAt(i + 3);
          if (n3 == '=' || n3 == '!') continue;
        }
        count++;
      } else if (c == '(') {
        count++;
      }
    }
    return count;
  }
}
