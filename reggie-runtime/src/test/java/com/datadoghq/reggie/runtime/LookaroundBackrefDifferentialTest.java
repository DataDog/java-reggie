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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Lookaround and backreference patterns whose routes once gave wrong answers: captures under
 * lookarounds, alternations followed by lookarounds, lazy quantifiers before nullable alternations,
 * and quantified groups ending in a lookbehind. Every input over {@code {a,b,c,x}} up to length 4,
 * plus a few longer ones, is checked against JDK for matches, find, and the group spans of
 * findMatch, match, findMatchInto and matchInto.
 */
public class LookaroundBackrefDifferentialTest {

  private static final String[] PATTERNS = {
    "(?:((b[ab][ab])?(?<=a))+(?!b)[^a]|[^a].)",
    "(?:((b[ab][ab])?(?<=a))+(?!b)[^a])",
    "((b[ab][ab])?(?<=a))+(?!b)[^a]",
    "((b..)?(?<=a))+c",
    "((ba)?(?<=a))+c",
    "((ba)?(?=x))+c",
    "(?:((ba)?(?<=a))+c|xx)",
    "((?:ba)?(?<=a))+c",
    "(a|b)(?=c)c\\1",
    "(a|b)c\\1(?!x)",
    "(a|b)c\\1(?=a)",
    "(?<=c)(a|b)c\\1",
    "(a+)(?=c)c\\1",
    "(?=a)(a|ab)(b*)\\1",
    "(?=a)(a|ab)(b*)",
    "(?=a)(a|ab)(b*)x",
    "(a|ab)(b*)(?!x)",
    "(a|b)\\1(?<=bb|aa)",
    "((?=a)a|b)\\1",
    "(a|ab)(?!x)(c|bcd)\\1",
    "(a+)(?=b)",
    "(?=a)(a*)(a)",
    "(a|ab)(?=c)",
    "(?!x)(a|b)+",
    "(a*)(?=a)(a*)",
    "(?=[ab])([ab]*)(b)",
    "x(?=a)(a|ab)",
    "x(?=a)(?:a|ab)",
    "a*(?=a)a*",
    "(?:a|ab)(?=c)",
    "a+(?=b)",
    "(?=a)(?:a|ab)b*x",
    "(?:a|ab)b*(?!x)",
    "a(?=b)",
    "(?<=a)b+",
    "a(?!b)[a-c]",
    "a(?=b)[a-c]*",
    "[a-c]*(?=c)",
    "(?:a|ab)(?=b)",
    "a*(?!a)",
    "[ab]*(?<=a)b",
    "a(?=b)|ab",
    "(?=a)a|b",
    "x(?=a.*)(?:a|ab)",
    "a*(?=a.*)a*",
    "(?:a|ab)(?=.*c)",
    "[a-c]*(?=.*c)b",
    "a(?=b)b*",
    "b*(?<!a)c",
    "(?:ab|a)(?!b)",
    "[ab]+?(?:[ab]?|.*[^a])a?",
    "[ab]+?(?:[ab]?|.*[^a])",
    "a(?:[ab]?|.*[^a])a?",
    "a(?:[ab]?|.*[^a])",
    "[ab]+?(?:b|.*[^a])a?",
    "[ab]+?(?:[ab]?|c)a?",
    "[ab]+?(?:[ab]?|c)",
    "[ab]+?(?:b?|c)",
    "[ab]+?(?:b?|c)x?",
    "[ab]*?(?:[ab]?|.*[^a])a?",
    "a+?(?:b?|c)",
    "a+?(?:b|c)",
    "a+?(?:bd?|c)",
    "(a)b\\1?",
    "(a)(?=b)\\1?",
    "(a)(?=b\\1*)",
    "(a)(?=b\\1+)",
    "(a)(?=b(?:\\1)?)",
    "(a)(?=bc?)",
    "(a)(?=b\\1{0,1})",
    "(a)(?=b\\1?)",
    "(a)(?=b\\1?)b",
    "(a)(?=b\\1?)\\w",
    "(a)(?=b)",
    "(a)b?(?=b)",
    "(a)(?=b\\1?c)",
    "(a)(?=bx?)",
    "(a)(?=b\\1??)",
    "([^a]*)[^a](?=[ab]*)",
    "(.(.){1,2})(?=.*[ab]*)",
    "(?<=a|a).(?:(?:[ab]|[ab][^a])|[ab][ab](?=c+))",
    "(?:[ab]|(?=.*[ab]+)(?!c+a)(?:[^a]+|(?<!b|c)(?<!ab)a{1,2})).",
    "(?!ba)(?:(?:b|a?(?<=b)(?:.[ab]|a*.))+|(?:(?=b*c)[^a]?|(?:[ab]?a|.a)[ab]?(?<=c|c))b(?![ab]b))",
    "(?=.*\\d)(?=.*[a-z])(?:x|xy)",
    "(?=.*\\d)(?=.*[a-z])(?:x|xy)z?",
    "(?=.*a)(?=.*b)(?:x|xy)",
    "(?=.*a)(?=.*b)\\w+?",
    "(?=.*a)(?=.*b)(?:x|xy)\\w*?",
    "(?=.*foo)(?=.*bar)(?:q|qr)",
    "(?=\\w*\\d)(?=\\w*[a-z])(?:\\w|\\w\\w)",
    "x(?:a|ab)(?=b*)",
    "(?<=x)(?:a|ab)",
    "(?<=x)(?:a|ab)c?",
  };

  private static final String[] EXTRA_INPUTS = {"xy1a", "xy ab", "xyab", "qr foo bar", "a1"};

  @Test
  void agreesWithJdk() {
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    for (int i = 0; inputs.get(i).length() < 4; i++) {
      for (char ch : new char[] {'a', 'b', 'c', 'x'}) inputs.add(inputs.get(i) + ch);
    }
    inputs.addAll(Arrays.asList(EXTRA_INPUTS));
    List<String> mismatches = new ArrayList<>();
    for (String pattern : PATTERNS) {
      ReggieMatcher r;
      try {
        r = Reggie.compile(pattern);
      } catch (UnsupportedPatternException e) {
        continue; // refused: no wrong answers to check
      }
      Pattern j = Pattern.compile(pattern);
      int groups = j.matcher("").groupCount();
      int[] starts = new int[groups + 1];
      int[] ends = new int[groups + 1];
      for (String in : inputs) {
        Matcher jm = j.matcher(in);
        String jFind = jm.find() ? spans(jm) : "none";
        jm = j.matcher(in);
        String jMatch = jm.matches() ? spans(jm) : "none";
        MatchResult rf = r.findMatch(in);
        MatchResult rm = r.match(in);
        String got =
            r.matches(in)
                + " "
                + r.find(in)
                + " "
                + (rf == null ? "none" : spans(rf))
                + " "
                + (r.findMatchInto(in, 0, starts, ends) ? spans(starts, ends) : "none")
                + " "
                + (rm == null ? "none" : spans(rm))
                + " "
                + (r.matchInto(in, starts, ends) ? spans(starts, ends) : "none");
        String want =
            !jMatch.equals("none")
                + " "
                + !jFind.equals("none")
                + " "
                + jFind
                + " "
                + jFind
                + " "
                + jMatch
                + " "
                + jMatch;
        if (!got.equals(want)) {
          mismatches.add(pattern + " on \"" + in + "\": want " + want + ", got " + got);
          break;
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static String spans(java.util.regex.MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append(',').append(m.end(g)).append(';');
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append(',').append(m.end(g)).append(';');
    return sb.toString();
  }

  private static String spans(int[] starts, int[] ends) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g < starts.length; g++)
      sb.append(starts[g]).append(',').append(ends[g]).append(';');
    return sb.toString();
  }
}
